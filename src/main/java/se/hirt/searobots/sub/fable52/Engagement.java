package se.hirt.searobots.sub.fable52;

/**
 * Quick kinematic what-if for one torpedo against one submarine: if a weapon were launched right
 * now, would it catch a target that does its best to get away?
 * <p>
 * A torpedo is half again as fast as a submarine but only burns for four minutes, and a submarine
 * needs the better part of a minute to turn around. Whether a shot connects is therefore decided
 * by range, by where the target's bow is pointing, and by how fast it is already going. The same
 * calculation, with the roles swapped, tells the boat whether it could still outrun a shot fired
 * at it from where the enemy is now.
 */
final class Engagement {

	static final double TORPEDO_FUEL = 240.0;
	private static final double DT = 0.5;

	/** Results of the last {@link #run}. */
	double hitTime;
	double closest;
	double endSeparation;

	/**
	 * @param sx,sy
	 *            torpedo start position
	 * @param sh
	 *            torpedo start heading (tubes point along the launcher's bow)
	 * @param sv
	 *            torpedo start speed
	 * @param tx,ty
	 *            target position
	 * @param th
	 *            target heading
	 * @param tv
	 *            target speed
	 * @param tw
	 *            target yaw rate
	 * @param hpRatio
	 *            target hull health 0..1 (a damaged boat is slower and turns worse)
	 * @param evade
	 *            true: the target turns directly away and runs flat out after {@code reaction}
	 *            seconds; false: it holds course and speed
	 * @param fuel
	 *            seconds of torpedo fuel left
	 * @return true if the torpedo reaches the target
	 */
	boolean run(double sx, double sy, double sh, double sv, double tx, double ty, double th, double tv, double tw,
			double hpRatio, boolean evade, double reaction, double fuel) {
		double mx = sx, my = sy, mh = sh, mv = Math.max(sv, 5.0), mw = 0;
		double dmg = hpRatio * hpRatio;
		double thrustFactor = 0.3 + 0.7 * dmg;
		double controlFactor = 0.4 + 0.6 * dmg;
		double throttle = Geo.sq(tv / 15.0) / thrustFactor;
		double best = Double.MAX_VALUE;
		hitTime = -1;
		for (double t = 0; t < fuel + 4.0; t += DT) {
			double rx = tx - mx, ry = ty - my;
			double dist = Math.hypot(rx, ry);
			if (dist < best) {
				best = dist;
			}
			double closing = mv + tv;
			if (dist < 20 + closing * DT * 0.5) {
				hitTime = t;
				closest = dist;
				endSeparation = dist;
				return true;
			}
			if (t >= fuel && mv < 16.0) {
				break; // spent and slower than a running submarine
			}
			// Torpedo: lead the target, turn with a ten second lag.
			double tvx = tv * Math.sin(th), tvy = tv * Math.cos(th);
			double tGo = Fable52Torpedo.interceptTime(rx, ry, tvx, tvy, Math.max(mv, 18.0));
			double aim = Math.atan2(rx + tvx * tGo, ry + tvy * tGo);
			double inertia = 3200.0 * (1 + 0.4 * mv * mv);
			double wMaxM = 51.25 * mv * mv / inertia * Math.PI;
			double errM = Geo.wrap(aim - mh);
			double wDesM = Math.min(Math.abs(errM) / 1.5, wMaxM) * Math.signum(errM);
			double wCmdM = Geo.clamp(mw + (10.0 / 1.2) * (wDesM - mw), -wMaxM, wMaxM);
			mw += (wCmdM - mw) * (DT / 10.0);
			mh += mw * DT;
			double thrust = t < fuel ? 1587.0 : 0.0;
			mv += (thrust - 3.0 * mv * mv) / 400.0 * DT;
			mx += mv * Math.sin(mh) * DT;
			my += mv * Math.cos(mh) * DT;

			// Target
			if (evade && t >= reaction) {
				double away = Math.atan2(tx - mx, ty - my);
				double k = 92250.0 * tv * tv * controlFactor / (5.625e7 * (1 + 0.05 * tv * tv));
				double wMax = k * Helm.CL_MAX;
				double err = Geo.wrap(away - th);
				double wCmd = Geo.clamp(tw + (Helm.TAU / 5.0) * (Helm.wantedRate(err, wMax) - tw), -wMax, wMax);
				tw += (wCmd - tw) * (DT / Helm.TAU);
				throttle = Math.min(1.0, throttle + 0.25 * DT);
			} else {
				tw -= tw * (DT / Helm.TAU);
			}
			th += tw * DT;
			tv += (2.7e6 * throttle * thrustFactor - 12000.0 * tv * tv) / 3.75e6 * DT;
			tx += tv * Math.sin(th) * DT;
			ty += tv * Math.cos(th) * DT;
		}
		closest = best;
		endSeparation = Math.hypot(tx - mx, ty - my);
		return false;
	}
}
