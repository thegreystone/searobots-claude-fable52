package se.hirt.searobots.sub.fable52;

/**
 * Works out whether, and along which route, the boat can run out one or more torpedoes.
 * <p>
 * A torpedo can only go where its fuel takes it, and it can be nowhere sooner than a straight run
 * from where it is now. So for every chart cell there is an earliest time a weapon could be there
 * (or never, if the cell is beyond its reach), and for every cell there is the time the boat needs
 * to get there through navigable water. The boat is safe as long as it only passes through cells
 * it reaches before any weapon could, and it has escaped once it reaches a cell no weapon can get
 * to at all. A shortest-time search restricted to such cells either finds that route or proves
 * there is none - taking islands, shoals and the edge of the battle area into account, which a
 * simple "turn away and run" does not.
 * <p>
 * The same search answers the question the boat asks all the time while manoeuvring: <i>if the
 * enemy fired from where he is right now, could I still get away?</i>
 */
final class EscapePlanner {

	private static final int MAX_THREATS = 8;
	private static final int SEED_DIRECTIONS = 16;
	private static final double SEED_RADIUS = 300.0;

	private final SeaChart chart;
	private final int n;
	private final float[] time;
	private final int[] parent;
	private final int[] stamp;
	private final int[] heap;
	private final float[] heapKey;
	private int heapSize;
	private int searchId;

	private final double[] threatX = new double[MAX_THREATS];
	private final double[] threatY = new double[MAX_THREATS];
	private final double[] threatReach = new double[MAX_THREATS];
	private final double[] threatSpeed = new double[MAX_THREATS];
	private int threats;
	private double longestFuel;

	// Result of the last plan
	final double[] routeX = new double[48];
	final double[] routeY = new double[48];
	int routeLength;
	/** Seconds until the boat is beyond every weapon's reach (only meaningful if the plan succeeded). */
	double escapeSeconds;
	private final int[] chain;

	EscapePlanner(SeaChart chart) {
		this.chart = chart;
		this.n = chart.n;
		int total = n * n;
		time = new float[total];
		parent = new int[total];
		stamp = new int[total];
		heap = new int[total * 4 + 64];
		heapKey = new float[total * 4 + 64];
		chain = new int[total];
	}

	void clearThreats() {
		threats = 0;
	}

	/**
	 * @param fuelSeconds
	 *            how long the weapon can still run under power
	 * @param speed
	 *            how fast to assume it runs
	 */
	void addThreat(double x, double y, double fuelSeconds, double speed) {
		if (threats >= MAX_THREATS) {
			return;
		}
		threatX[threats] = x;
		threatY[threats] = y;
		threatSpeed[threats] = speed;
		threatReach[threats] = speed * Math.max(0, fuelSeconds) + 120.0;
		longestFuel = threats == 0 ? fuelSeconds : Math.max(longestFuel, fuelSeconds);
		threats++;
	}

	/** Earliest time any weapon could be at the point; infinity if none can reach it. */
	private double arrival(double px, double py) {
		double best = Double.POSITIVE_INFINITY;
		for (int i = 0; i < threats; i++) {
			double d = Math.hypot(px - threatX[i], py - threatY[i]);
			if (d <= threatReach[i]) {
				best = Math.min(best, d / threatSpeed[i]);
			}
		}
		return best;
	}

	/** True if the point lies beyond every weapon's reach with something to spare. */
	private boolean beyondReach(double px, double py) {
		for (int i = 0; i < threats; i++) {
			if (Math.hypot(px - threatX[i], py - threatY[i]) <= threatReach[i] + 250.0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Plans the run.
	 *
	 * @param delay
	 *            seconds before the boat starts to react
	 * @param margin
	 *            seconds by which the boat must beat a weapon to every point on its route
	 * @return true if a route to lasting safety exists; the route is then in
	 *         {@link #routeX}/{@link #routeY}. If false, the route (if any) is the one that keeps the
	 *         boat alive longest.
	 */
	boolean plan(double x, double y, double heading, double speed, double hpRatio, double delay, double margin) {
		routeLength = 0;
		escapeSeconds = Double.NaN;
		double run = Helm.topSpeed(hpRatio) * 0.95;
		double spoolUp = Geo.sq(Math.max(0, run - Math.max(speed, 0))) / (0.8 * run);
		searchId++;
		heapSize = 0;
		int start = chart.nearestPassable(x, y);
		if (start < 0) {
			return false;
		}
		stamp[start] = searchId;
		time[start] = (float) delay;
		parent[start] = -1;
		if (beyondReach(x, y)) {
			escapeSeconds = 0;
			return true;
		}
		// The first few hundred metres depend on which way the bow points: seed a ring of cells with
		// the time it takes to turn onto them and get there.
		boolean seeded = false;
		for (int k = 0; k < SEED_DIRECTIONS; k++) {
			double h = heading + k * (Geo.TWO_PI / SEED_DIRECTIONS);
			double px = x + Math.sin(h) * SEED_RADIUS, py = y + Math.cos(h) * SEED_RADIUS;
			if (!chart.clearLine(x, y, px, py, 100f)) {
				continue;
			}
			double turn = Math.abs(Geo.wrap(h - heading));
			double t = delay + spoolUp + 20.0 * Math.pow(turn / (Math.PI / 2), 1.55) + SEED_RADIUS / run;
			if (t + margin >= arrival(px, py)) {
				continue;
			}
			int cell = chart.row(py) * n + chart.col(px);
			if (stamp[cell] != searchId || t < time[cell]) {
				stamp[cell] = searchId;
				time[cell] = (float) t;
				parent[cell] = start;
				push(cell, (float) t);
				seeded = true;
			}
		}
		if (!seeded) {
			return false;
		}
		int last = -1;
		int goal = -1;
		while (heapSize > 0) {
			float key = heapKey[0];
			int cur = pop();
			if (key > time[cur] + 1e-3f) {
				continue;
			}
			last = cur;
			int cc = cur % n, cr = cur / n;
			double cx = chart.cellX(cc), cy = chart.cellY(cr);
			if (beyondReach(cx, cy)) {
				goal = cur;
				break;
			}
			float costHere = chart.costAt(cx, cy);
			for (int dr = -1; dr <= 1; dr++) {
				for (int dc = -1; dc <= 1; dc++) {
					if (dr == 0 && dc == 0) {
						continue;
					}
					int nc = cc + dc, nr = cr + dr;
					if (nc < 0 || nc >= n || nr < 0 || nr >= n) {
						continue;
					}
					double nx = chart.cellX(nc), ny = chart.cellY(nr);
					float k = chart.costAt(nx, ny);
					if (k == 0) {
						continue;
					}
					double len = (dr != 0 && dc != 0) ? 141.42 : 100.0;
					// Water near shoals is slower going: the helm has to be careful there.
					double slow = 1.0 + 0.3 * (0.5 * (k + costHere) - 1.0);
					if (k > 4.0 && costHere <= 4.0) {
						slow *= 2.5; // heading into tight water at speed is asking for trouble
					}
					float t = (float) (time[cur] + len / run * slow);
					int ni = nr * n + nc;
					if (stamp[ni] == searchId && t >= time[ni]) {
						continue;
					}
					if (t + margin >= arrival(nx, ny)) {
						continue; // a weapon could be waiting there
					}
					stamp[ni] = searchId;
					time[ni] = t;
					parent[ni] = cur;
					push(ni, t);
				}
			}
		}
		int end = goal >= 0 ? goal : last;
		if (end < 0) {
			return false;
		}
		int len = 0;
		for (int i = end; i != -1 && i != start; i = parent[i]) {
			chain[len++] = i;
		}
		// chain runs end..first step; string-pull it from the boat outwards
		double ax = x, ay = y;
		int anchor = len;
		int out = 0;
		while (anchor > 0 && out < routeX.length) {
			int next = anchor - 1;
			for (int cand = 0; cand < anchor - 1; cand++) {
				int idx = chain[cand];
				if (chart.clearLine(ax, ay, chart.cellX(idx % n), chart.cellY(idx / n), 3.0f)) {
					next = cand;
					break;
				}
			}
			int idx = chain[next];
			ax = chart.cellX(idx % n);
			ay = chart.cellY(idx / n);
			routeX[out] = ax;
			routeY[out] = ay;
			out++;
			anchor = next;
		}
		routeLength = out;
		if (goal >= 0) {
			escapeSeconds = time[goal];
			return true;
		}
		escapeSeconds = time[end];
		// Not beyond the weapons' reach, but ahead of them all the way until they run dry: that
		// is an escape too. In a cramped corner of the map it is the only kind there is.
		return time[end] >= longestFuel - margin - 12.0;
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
