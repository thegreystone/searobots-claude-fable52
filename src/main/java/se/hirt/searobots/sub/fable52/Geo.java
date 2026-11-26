package se.hirt.searobots.sub.fable52;

/** Angle and vector helpers. Bearings are radians clockwise from north (+y), like the API. */
final class Geo {

	static final double TWO_PI = 2.0 * Math.PI;

	private Geo() {
	}

	/** Normalizes to [0, 2pi). */
	static double norm(double a) {
		double r = a % TWO_PI;
		return r < 0 ? r + TWO_PI : r;
	}

	/** Normalizes to (-pi, pi]. */
	static double wrap(double a) {
		double r = a % TWO_PI;
		if (r > Math.PI) {
			r -= TWO_PI;
		} else if (r <= -Math.PI) {
			r += TWO_PI;
		}
		return r;
	}

	/** Bearing from (x0, y0) to (x1, y1) in [0, 2pi). */
	static double bearing(double x0, double y0, double x1, double y1) {
		return norm(Math.atan2(x1 - x0, y1 - y0));
	}

	/** Signed shortest turn from heading {@code from} to heading {@code to}; positive = clockwise. */
	static double diff(double from, double to) {
		return wrap(to - from);
	}

	static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	static double sq(double v) {
		return v * v;
	}
}
