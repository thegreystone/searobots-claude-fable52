package se.hirt.searobots.sub.fable52;

/**
 * What the boat believes about the enemy submarine: a straight-line track fitted through recent
 * active sonar fixes, coasted between pings.
 * <p>
 * A single fix is good to about two percent of range, almost all of it along the line of sight, so
 * a velocity needs several fixes spread over tens of seconds. When a new fix lands far from where
 * the track said it would, the target has manoeuvred and the older fixes are dropped.
 */
final class EnemyTrack {

	private static final int HISTORY = 32;
	private static final double WINDOW_SECONDS = 45.0;

	private final double[] hx = new double[HISTORY];
	private final double[] hy = new double[HISTORY];
	private final long[] ht = new long[HISTORY];
	private int count, head;

	boolean valid;
	double x, y, z = -150, vx, vy;
	/** Tick that (x, y) refers to. */
	long epoch;
	long lastFixTick = -1;
	int fixes;
	/** Fixes since the last detected manoeuvre; a velocity needs at least three. */
	int steadyFixes;
	double lastFixRange;

	void reset() {
		valid = false;
		count = head = fixes = steadyFixes = 0;
		lastFixTick = -1;
		vx = vy = 0;
	}

	double px(long tick) {
		return x + vx * (tick - epoch) * 0.02;
	}

	double py(long tick) {
		return y + vy * (tick - epoch) * 0.02;
	}

	double speed() {
		return Math.hypot(vx, vy);
	}

	double heading() {
		return Geo.norm(Math.atan2(vx, vy));
	}

	double fixAgeSeconds(long tick) {
		return lastFixTick < 0 ? Double.MAX_VALUE : (tick - lastFixTick) * 0.02;
	}

	/** True when the track has a velocity worth aiming with. */
	boolean hasVelocity() {
		return steadyFixes >= 3;
	}

	void addFix(long tick, double fx, double fy, double fz, double range) {
		if (valid) {
			double miss = Math.hypot(fx - px(tick), fy - py(tick));
			double expected = 0.03 * range + 25 + 3.0 * (tick - lastFixTick) * 0.02;
			if (miss > 3.0 * expected) {
				// Not where a straight track put it: start the fit over from the last fix.
				count = Math.min(count, 1);
				steadyFixes = Math.min(steadyFixes, 1);
			}
		}
		hx[head] = fx;
		hy[head] = fy;
		ht[head] = tick;
		head = (head + 1) % HISTORY;
		count = Math.min(count + 1, HISTORY);
		fixes++;
		steadyFixes++;
		lastFixTick = tick;
		lastFixRange = range;
		if (Double.isFinite(fz)) {
			z = fixes == 1 ? fz : z + 0.4 * (fz - z);
		}
		fit(tick, fx, fy);
		valid = true;
	}

	private void fit(long tick, double fx, double fy) {
		int n = 0;
		double st = 0, sx = 0, sy = 0, stt = 0, stx = 0, sty = 0;
		double oldest = 0;
		for (int i = 0; i < count; i++) {
			int idx = (head - 1 - i + HISTORY) % HISTORY;
			double t = (ht[idx] - tick) * 0.02;
			if (-t > WINDOW_SECONDS) {
				break;
			}
			n++;
			oldest = -t;
			st += t;
			sx += hx[idx];
			sy += hy[idx];
			stt += t * t;
			stx += t * hx[idx];
			sty += t * hy[idx];
		}
		double den = n * stt - st * st;
		if (n >= 3 && oldest >= 8.0 && den > 1e-6) {
			double fvx = (n * stx - st * sx) / den, fvy = (n * sty - st * sy) / den;
			double v = Math.hypot(fvx, fvy);
			if (v > 16.0) {
				fvx *= 16.0 / v;
				fvy *= 16.0 / v;
			}
			vx = fvx;
			vy = fvy;
			x = (sx - fvx * st) / n;
			y = (sy - fvy * st) / n;
		} else if (n == 2 && oldest >= 4.0) {
			// Two fixes: a rough velocity, heavily damped.
			int i0 = (head - 1 + HISTORY) % HISTORY, i1 = (head - 2 + HISTORY) % HISTORY;
			vx = 0.5 * (hx[i0] - hx[i1]) / oldest;
			vy = 0.5 * (hy[i0] - hy[i1]) / oldest;
			x = fx;
			y = fy;
		} else {
			x = fx;
			y = fy;
			if (n <= 1) {
				vx *= 0.5;
				vy *= 0.5;
			}
		}
		epoch = tick;
	}
}
