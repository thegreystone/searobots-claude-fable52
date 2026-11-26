package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.BattleArea;
import se.hirt.searobots.api.TerrainMap;

/**
 * A coarse navigation chart derived from the terrain heightmap: for every 100 m cell the shallowest
 * sea floor found in or next to it, whether a submarine can transit it, and how unattractive it is
 * to do so. Provides an allocation-free A* route planner and ray queries used for picking escape
 * headings.
 */
final class SeaChart {

	static final double CELL = 100.0;
	/** Cells whose (dilated) floor is shallower than this are never entered. */
	static final double BLOCK_FLOOR = -78.0;
	/** Floors deeper than this cost nothing extra. */
	static final double COMFORT_FLOOR = -190.0;
	/** Never plan closer than this to the battle area boundary. */
	static final double BOUNDARY_BLOCK = 320.0;
	static final double BOUNDARY_SOFT = 900.0;

	final TerrainMap terrain;
	final BattleArea area;
	final int n;
	final double origin;
	/** Shallowest floor in the cell and its eight neighbours. */
	private final float[] ceiling;
	/** Shallowest floor in the cell itself. */
	private final float[] ownCeiling;
	/** 0 = blocked, otherwise traversal cost multiplier >= 1. */
	private final float[] cost;
	/** 0 = blocked, otherwise the id of the connected body of passable water the cell belongs to. */
	private final int[] region;

	// A* scratch (reused; stamps avoid clearing)
	private final float[] g;
	private final int[] parent;
	private final int[] stamp;
	private final int[] heap;
	private final float[] heapKey;
	private int heapSize;
	private int searchId;

	// Last planned route
	final double[] routeX = new double[64];
	final double[] routeY = new double[64];
	int routeLength;

	private final int[] rawPath;

	SeaChart(TerrainMap terrain, BattleArea area) {
		this.terrain = terrain;
		this.area = area;
		double extent = area.extent();
		this.n = (int) Math.ceil(2 * extent / CELL) + 1;
		this.origin = -(n - 1) * CELL / 2.0;
		int total = n * n;
		ownCeiling = new float[total];
		ceiling = new float[total];
		cost = new float[total];
		region = new int[total];
		g = new float[total];
		parent = new int[total];
		stamp = new int[total];
		heap = new int[total * 4 + 16];
		heapKey = new float[total * 4 + 16];
		rawPath = new int[total];
		build();
	}

	private void build() {
		double half = CELL / 2.0;
		double step = 20.0;
		for (int r = 0; r < n; r++) {
			double cy = origin + r * CELL;
			for (int c = 0; c < n; c++) {
				double cx = origin + c * CELL;
				double max = -1e9;
				for (double oy = -half; oy <= half + 1e-6; oy += step) {
					for (double ox = -half; ox <= half + 1e-6; ox += step) {
						double e = terrain.elevationAt(cx + ox, cy + oy);
						if (e > max) {
							max = e;
						}
					}
				}
				ownCeiling[r * n + c] = (float) max;
			}
		}
		for (int r = 0; r < n; r++) {
			for (int c = 0; c < n; c++) {
				float max = -1e9f;
				for (int dr = -1; dr <= 1; dr++) {
					for (int dc = -1; dc <= 1; dc++) {
						int rr = r + dr, cc = c + dc;
						if (rr < 0 || rr >= n || cc < 0 || cc >= n) {
							continue;
						}
						float e = ownCeiling[rr * n + cc];
						if (e > max) {
							max = e;
						}
					}
				}
				ceiling[r * n + c] = max;
			}
		}
		for (int r = 0; r < n; r++) {
			double cy = origin + r * CELL;
			for (int c = 0; c < n; c++) {
				double cx = origin + c * CELL;
				int i = r * n + c;
				double edge = area.distanceToBoundary(cx, cy);
				float ceil = ceiling[i];
				if (edge < BOUNDARY_BLOCK || ceil > BLOCK_FLOOR) {
					cost[i] = 0;
					continue;
				}
				double k = 1.0;
				if (ceil > COMFORT_FLOOR) {
					double f = (ceil - COMFORT_FLOOR) / (BLOCK_FLOOR - COMFORT_FLOOR);
					k += 3.0 * f * f;
				}
				if (edge < BOUNDARY_SOFT) {
					double f = (BOUNDARY_SOFT - edge) / (BOUNDARY_SOFT - BOUNDARY_BLOCK);
					k += 2.0 * f;
				}
				cost[i] = (float) k;
			}
		}
		// Label connected bodies of passable water (8-connected, like the planner).
		int next = 0;
		for (int i = 0; i < n * n; i++) {
			if (cost[i] == 0 || region[i] != 0) {
				continue;
			}
			next++;
			int head = 0, tail = 0;
			rawPath[tail++] = i;
			region[i] = next;
			while (head < tail) {
				int cur = rawPath[head++];
				int cc = cur % n, cr = cur / n;
				for (int dr = -1; dr <= 1; dr++) {
					for (int dc = -1; dc <= 1; dc++) {
						int nc = cc + dc, nr = cr + dr;
						if (nc < 0 || nc >= n || nr < 0 || nr >= n) {
							continue;
						}
						int ni = nr * n + nc;
						if (cost[ni] > 0 && region[ni] == 0) {
							region[ni] = next;
							rawPath[tail++] = ni;
						}
					}
				}
			}
		}
	}

	/** Id of the body of water at the position, or 0 if it is not passable. */
	int regionAt(double x, double y) {
		int c = col(x), r = row(y);
		return inGrid(c, r) ? region[r * n + c] : 0;
	}

	/** True if a submarine can get from one position to the other. */
	boolean connected(double x0, double y0, double x1, double y1) {
		int a = regionAt(x0, y0);
		return a != 0 && a == regionAt(x1, y1);
	}

	// ── Queries ──────────────────────────────────────────────────────

	int col(double x) {
		return (int) Math.round((x - origin) / CELL);
	}

	int row(double y) {
		return (int) Math.round((y - origin) / CELL);
	}

	double cellX(int c) {
		return origin + c * CELL;
	}

	double cellY(int r) {
		return origin + r * CELL;
	}

	boolean inGrid(int c, int r) {
		return c >= 0 && c < n && r >= 0 && r < n;
	}

	/** True if a submarine may be at this position according to the chart. */
	boolean passable(double x, double y) {
		int c = col(x), r = row(y);
		return inGrid(c, r) && cost[r * n + c] > 0;
	}

	float costAt(double x, double y) {
		int c = col(x), r = row(y);
		return inGrid(c, r) ? cost[r * n + c] : 0;
	}

	/** Shallowest floor in the neighbourhood of the position (conservative). */
	double ceilingAt(double x, double y) {
		int c = col(x), r = row(y);
		return inGrid(c, r) ? ceiling[r * n + c] : 0;
	}

	/** Shallowest floor within the position's own 100 m cell. */
	double ownCeilingAt(double x, double y) {
		int c = col(x), r = row(y);
		return inGrid(c, r) ? ownCeiling[r * n + c] : 0;
	}

	/**
	 * Distance one can travel from (x, y) along {@code heading} before reaching an impassable cell
	 * or the boundary margin, capped at {@code maxDistance}.
	 */
	double freeRun(double x, double y, double heading, double maxDistance) {
		double sx = Math.sin(heading), sy = Math.cos(heading);
		double step = CELL * 0.5;
		for (double d = step; d <= maxDistance; d += step) {
			if (!passable(x + sx * d, y + sy * d)) {
				return d - step;
			}
		}
		return maxDistance;
	}

	/** True if the straight segment stays in passable cells with cost at most {@code maxCost}. */
	boolean clearLine(double x0, double y0, double x1, double y1, float maxCost) {
		double dx = x1 - x0, dy = y1 - y0;
		double dist = Math.hypot(dx, dy);
		int steps = Math.max(1, (int) Math.ceil(dist / (CELL * 0.5)));
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			float k = costAt(x0 + dx * t, y0 + dy * t);
			if (k == 0 || k > maxCost) {
				return false;
			}
		}
		return true;
	}

	/** Shallowest charted floor along a segment. */
	double ceilingAlong(double x0, double y0, double x1, double y1) {
		double dx = x1 - x0, dy = y1 - y0;
		double dist = Math.hypot(dx, dy);
		int steps = Math.max(1, (int) Math.ceil(dist / (CELL * 0.5)));
		double max = -1e9;
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			double e = ceilingAt(x0 + dx * t, y0 + dy * t);
			if (e > max) {
				max = e;
			}
		}
		return max;
	}

	/**
	 * Finds the nearest passable cell to the given position. Returns its index, or -1 if the chart
	 * has no passable cell at all.
	 */
	int nearestPassable(double x, double y) {
		return nearestPassable(x, y, 0);
	}

	/** As {@link #nearestPassable(double, double)}, restricted to one body of water (0 = any). */
	int nearestPassable(double x, double y, int wantedRegion) {
		int c0 = Math.clamp(col(x), 0, n - 1), r0 = Math.clamp(row(y), 0, n - 1);
		if (cost[r0 * n + c0] > 0 && (wantedRegion == 0 || region[r0 * n + c0] == wantedRegion)) {
			return r0 * n + c0;
		}
		for (int radius = 1; radius < n; radius++) {
			int best = -1;
			double bestD = Double.MAX_VALUE;
			for (int dr = -radius; dr <= radius; dr++) {
				for (int dc = -radius; dc <= radius; dc++) {
					if (Math.abs(dr) != radius && Math.abs(dc) != radius) {
						continue;
					}
					int r = r0 + dr, c = c0 + dc;
					if (!inGrid(c, r) || cost[r * n + c] == 0
							|| (wantedRegion != 0 && region[r * n + c] != wantedRegion)) {
						continue;
					}
					double d = Math.hypot(cellX(c) - x, cellY(r) - y) * cost[r * n + c];
					if (d < bestD) {
						bestD = d;
						best = r * n + c;
					}
				}
			}
			if (best >= 0) {
				return best;
			}
		}
		return -1;
	}

	// ── Route planning ───────────────────────────────────────────────

	/**
	 * Plans a route from start to goal through passable water. The result is stored in
	 * {@link #routeX}/{@link #routeY} ({@link #routeLength} points, start excluded, goal included).
	 * Returns false if no route exists.
	 */
	boolean plan(double startX, double startY, double goalX, double goalY) {
		routeLength = 0;
		int start = nearestPassable(startX, startY);
		if (start < 0) {
			return false;
		}
		// A goal in another body of water is replaced by the closest point we can actually reach.
		int goal = nearestPassable(goalX, goalY, region[start]);
		if (goal < 0) {
			return false;
		}
		if (start == goal) {
			routeX[0] = cellX(goal % n);
			routeY[0] = cellY(goal / n);
			routeLength = 1;
			return true;
		}
		searchId++;
		heapSize = 0;
		int gc = goal % n, gr = goal / n;
		g[start] = 0;
		parent[start] = -1;
		stamp[start] = searchId;
		push(start, heuristic(start % n, start / n, gc, gr));
		boolean found = false;
		while (heapSize > 0) {
			float key = heapKey[0];
			int cur = pop();
			int cc = cur % n, cr = cur / n;
			float gCur = g[cur];
			if (key > gCur + heuristic(cc, cr, gc, gr) + 1e-3f) {
				continue; // stale entry
			}
			if (cur == goal) {
				found = true;
				break;
			}
			for (int dr = -1; dr <= 1; dr++) {
				for (int dc = -1; dc <= 1; dc++) {
					if (dr == 0 && dc == 0) {
						continue;
					}
					int nc = cc + dc, nr = cr + dr;
					if (nc < 0 || nc >= n || nr < 0 || nr >= n) {
						continue;
					}
					int ni = nr * n + nc;
					float k = cost[ni];
					if (k == 0) {
						continue;
					}
					float stepLen = (dr != 0 && dc != 0) ? 1.4142f : 1f;
					float tentative = gCur + stepLen * (k + cost[cur]) * 0.5f;
					if (stamp[ni] != searchId || tentative < g[ni]) {
						stamp[ni] = searchId;
						g[ni] = tentative;
						parent[ni] = cur;
						push(ni, tentative + heuristic(nc, nr, gc, gr));
					}
				}
			}
		}
		if (!found) {
			return false;
		}
		int len = 0;
		for (int i = goal; i != -1; i = parent[i]) {
			rawPath[len++] = i;
		}
		// rawPath is goal..start; string-pull from the start
		int anchor = len - 1;
		double ax = startX, ay = startY;
		if (!passable(ax, ay)) {
			ax = cellX(rawPath[anchor] % n);
			ay = cellY(rawPath[anchor] / n);
		}
		int out = 0;
		while (anchor > 0 && out < routeX.length - 1) {
			int next = anchor - 1;
			// Extend as far as the line of sight allows.
			for (int cand = 0; cand < anchor - 1; cand++) {
				int idx = rawPath[cand];
				float limit = Math.max(cost[idx], costAt(ax, ay)) + 0.6f;
				if (clearLine(ax, ay, cellX(idx % n), cellY(idx / n), limit)) {
					next = cand;
					break;
				}
			}
			int idx = rawPath[next];
			ax = cellX(idx % n);
			ay = cellY(idx / n);
			routeX[out] = ax;
			routeY[out] = ay;
			out++;
			anchor = next;
		}
		if (out == 0) {
			routeX[0] = cellX(goal % n);
			routeY[0] = cellY(goal / n);
			out = 1;
		}
		routeLength = out;
		return true;
	}

	private float heuristic(int c, int r, int gc, int gr) {
		int dc = Math.abs(c - gc), dr = Math.abs(r - gr);
		return Math.max(dc, dr) + 0.4142f * Math.min(dc, dr);
	}

	private void push(int cell, float key) {
		if (heapSize >= heap.length) {
			return;
		}
		int i = heapSize++;
		while (i > 0) {
			int p = (i - 1) >> 1;
			if (heapKey[p] <= key) {
				break;
			}
			heap[i] = heap[p];
			heapKey[i] = heapKey[p];
			i = p;
		}
		heap[i] = cell;
		heapKey[i] = key;
	}

	private int pop() {
		int top = heap[0];
		heapSize--;
		if (heapSize > 0) {
			int cell = heap[heapSize];
			float key = heapKey[heapSize];
			int i = 0;
			while (true) {
				int l = 2 * i + 1;
				if (l >= heapSize) {
					break;
				}
				int r = l + 1;
				int m = (r < heapSize && heapKey[r] < heapKey[l]) ? r : l;
				if (heapKey[m] >= key) {
					break;
				}
				heap[i] = heap[m];
				heapKey[i] = heapKey[m];
				i = m;
			}
			heap[i] = cell;
			heapKey[i] = key;
		}
		return top;
	}
}
