package se.hirt.searobots.sub.fable52;

/**
 * Search bookkeeping: which parts of the battle area a ping has recently looked into.
 * <p>
 * Sound does not pass through rock. A ping that comes back empty therefore only says the enemy is
 * not anywhere the ping could reach in a straight line; behind an island or a ridge he may still be
 * sitting a kilometre away. The lookout remembers when each chart cell was last covered and
 * proposes the reachable spot from which the most stale water can be looked at.
 */
final class Lookout {

	private static final int RAYS = 120;
	/** One-way loss (dB) the active sonar can afford on top of spreading and still get an echo. */
	private static final double BUDGET_DB = 40.0;
	/** Depth at which the enemy is assumed to be when working out what a ping can see. */
	private static final double TARGET_DEPTH = -70.0;

	private final SeaChart chart;
	private final long[] seen;
	double goalX, goalY;
	boolean haveGoal;

	Lookout(SeaChart chart) {
		this.chart = chart;
		this.seen = new long[chart.n * chart.n];
		java.util.Arrays.fill(seen, Long.MIN_VALUE / 4);
	}

	/** Records an empty ping made at (x, y, z). */
	void swept(long tick, double x, double y, double z) {
		int n = chart.n;
		double maxRange = chart.area.extent() * 2.0;
		for (int ray = 0; ray < RAYS; ray++) {
			double h = ray * (Geo.TWO_PI / RAYS);
			double sx = Math.sin(h), sy = Math.cos(h);
			double loss = 0;
			for (double d = 0; d <= maxRange; d += SeaChart.CELL) {
				double px = x + sx * d, py = y + sy * d;
				int c = chart.col(px), r = chart.row(py);
				if (!chart.inGrid(c, r)) {
					break;
				}
				// Sound line from us down (or up) to a boat at the assumed depth in this cell.
				double floor = chart.ownCeilingAt(px, py);
				double line = Math.max(z, TARGET_DEPTH) - 15;
				if (floor > line) {
					loss += floor >= 0 ? 300 : 150.0 * Math.min((floor - line) / 50.0, 1.0);
					if (loss > BUDGET_DB) {
						break;
					}
				}
				seen[r * n + c] = tick;
			}
		}
	}

	/** Forget everything: the enemy has been seen and lost again, he can be anywhere near there. */
	void forget() {
		java.util.Arrays.fill(seen, Long.MIN_VALUE / 4);
	}

	/**
	 * Picks the reachable place from which the most stale water can be seen, preferring ones close
	 * by and (if given) close to where the enemy was last known to be - but, if {@code keepOut} is
	 * set, no closer to that spot than that.
	 */
	void chooseGoal(long tick, double x, double y, double hintX, double hintY, boolean hint, double keepOut) {
		int n = chart.n;
		int home = chart.regionAt(x, y);
		double best = -1;
		long staleAfter = 50L * 240;
		for (int r = 2; r < n - 2; r += 5) {
			for (int c = 2; c < n - 2; c += 5) {
				double cx = chart.cellX(c), cy = chart.cellY(r);
				if (!chart.passable(cx, cy) || (home != 0 && chart.regionAt(cx, cy) != home)) {
					continue;
				}
				double dist = Math.hypot(cx - x, cy - y);
				if (dist < 500) {
					continue;
				}
				if (hint && keepOut > 0 && Math.hypot(cx - hintX, cy - hintY) < keepOut) {
					continue; // do not go looking for him inside his own firing range
				}
				int stale = 0;
				for (int dr = -15; dr <= 15; dr += 5) {
					for (int dc = -15; dc <= 15; dc += 5) {
						int rr = r + dr, cc = c + dc;
						if (rr < 0 || rr >= n || cc < 0 || cc >= n) {
							continue;
						}
						double wx = chart.cellX(cc), wy = chart.cellY(rr);
						if (chart.ownCeilingAt(wx, wy) > -25 || chart.area.distanceToBoundary(wx, wy) < 0) {
							continue; // land or outside: nobody there
						}
						if (tick - seen[rr * n + cc] > staleAfter) {
							stale++;
						}
					}
				}
				if (stale == 0) {
					continue;
				}
				double score = stale / (dist + 1200.0);
				if (hint) {
					score *= 1.0 + 2500.0 / (Math.hypot(cx - hintX, cy - hintY) + 1200.0);
				}
				if (score > best) {
					best = score;
					goalX = cx;
					goalY = cy;
				}
			}
		}
		haveGoal = best > 0;
		if (!haveGoal) {
			// Everything looked at recently: start over.
			forget();
		}
	}
}
