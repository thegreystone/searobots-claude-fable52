package se.hirt.searobots.sub.fable52;

/**
 * Follows chart routes: give it a goal and it answers with the heading to steer right now, going
 * straight when the water in between is open and around obstacles when it is not.
 */
final class Navigator {

	private static final int REPLAN_TICKS = 750;

	private final SeaChart chart;
	private final double[] rx = new double[64];
	private final double[] ry = new double[64];
	private int length;
	private int index;
	private long plannedTick = Long.MIN_VALUE / 2;
	private double plannedGoalX = Double.NaN, plannedGoalY = Double.NaN;

	/** Point currently steered for (for display). */
	double aimX, aimY;
	boolean direct;
	/** True when the last request found no route at all. */
	boolean stuck;

	Navigator(SeaChart chart) {
		this.chart = chart;
	}

	void reset() {
		length = 0;
		index = 0;
		plannedTick = Long.MIN_VALUE / 2;
		plannedGoalX = Double.NaN;
	}

	/** Heading to steer from (x, y) to make progress toward the goal. */
	double headingTo(long tick, double x, double y, double goalX, double goalY) {
		float here = chart.costAt(x, y);
		// Going straight needs clearly open water; once on a planned route, demand a bit more
		// before abandoning it, so the boat does not dither between the two.
		float limit = Math.max(2.2f, Math.max(here, chart.costAt(goalX, goalY)) + 0.6f) - (direct ? 0f : 0.5f);
		if (here > 0 && chart.clearLine(x, y, goalX, goalY, limit)) {
			direct = true;
			stuck = false;
			length = 0;
			aimX = goalX;
			aimY = goalY;
			return Geo.bearing(x, y, goalX, goalY);
		}
		direct = false;
		boolean goalMoved = Double.isNaN(plannedGoalX) || Math.hypot(goalX - plannedGoalX, goalY - plannedGoalY) > 300;
		if (goalMoved || tick - plannedTick > (length == 0 ? 100 : REPLAN_TICKS)) {
			if (chart.plan(x, y, goalX, goalY)) {
				length = Math.min(chart.routeLength, rx.length);
				System.arraycopy(chart.routeX, 0, rx, 0, length);
				System.arraycopy(chart.routeY, 0, ry, 0, length);
			} else {
				length = 0;
			}
			index = 0;
			plannedTick = tick;
			plannedGoalX = goalX;
			plannedGoalY = goalY;
		}
		if (length == 0) {
			// Nowhere to go from here: hold the present position rather than steer blind.
			aimX = x;
			aimY = y;
			stuck = true;
			return Double.NaN;
		}
		stuck = false;
		while (index < length - 1 && Math.hypot(rx[index] - x, ry[index] - y) < 220) {
			index++;
		}
		// Cut the corner when the following point is already in plain view.
		if (index < length - 1 && here > 0) {
			float lim = Math.max(here, chart.costAt(rx[index + 1], ry[index + 1])) + 0.6f;
			if (chart.clearLine(x, y, rx[index + 1], ry[index + 1], lim)) {
				index++;
			}
		}
		aimX = rx[index];
		aimY = ry[index];
		return Geo.bearing(x, y, aimX, aimY);
	}

	int remainingPoints() {
		return length - index;
	}

	double routeX(int i) {
		return rx[index + i];
	}

	double routeY(int i) {
		return ry[index + i];
	}
}
