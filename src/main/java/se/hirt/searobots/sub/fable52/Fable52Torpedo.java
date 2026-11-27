package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.SonarContact;
import se.hirt.searobots.api.TerrainMap;
import se.hirt.searobots.api.TorpedoController;
import se.hirt.searobots.api.TorpedoInput;
import se.hirt.searobots.api.TorpedoLaunchContext;
import se.hirt.searobots.api.TorpedoOutput;
import se.hirt.searobots.api.Vec3;

import java.util.List;

/**
 * Torpedo guidance.
 * <p>
 * The weapon is told where the target was and how it was moving at launch. From then on it is on
 * its own: it pings once a second, keeps a short history of the echoes it believes are the target,
 * fits a straight track through them and steers for the point where that track and its own run
 * meet.
 * <p>
 * The launching submarine echoes too, and a torpedo cannot tell friend from foe by sound. The
 * weapon therefore follows <i>both</i> hulls, each in its own track, and an echo only counts as the
 * target if it lies where the target could plausibly have gone and is not better explained by the
 * launcher. When in doubt the echo is ignored: a wasted torpedo is cheap, one that turns on its own
 * boat is not. For the same reason it first runs straight out of the tube until it is well clear.
 * <p>
 * Like the submarine, the weapon turns sluggishly (its turn rate takes about ten seconds to build),
 * so steering uses the same response-model approach as the helm rather than chasing the bearing.
 */
public class Fable52Torpedo implements TorpedoController {

	private static final double YAW_TAU = 10.0;
	/** Lift coefficient at the fin stall angle (30 degrees). */
	private static final double CL_PEAK = Math.PI;
	private static final int HISTORY = 40;
	private static final double SUB_TOP_SPEED = 15.5;

	private TerrainMap terrain;
	private long launchTick = -1;
	private long missionTick;
	private double launchHeading;
	private double fuse = 18.0;

	// Target track
	private boolean haveTarget;
	private double tx, ty, tz, tvx, tvy;
	private long targetTick; // tick the estimate (tx, ty) refers to
	private long lastFixTick = -1;
	private double lastFixX, lastFixY;
	private int fixes;
	private final double[] hx = new double[HISTORY];
	private final double[] hy = new double[HISTORY];
	private final long[] ht = new long[HISTORY];
	private int hCount, hHead;
	/** How well the briefed position can be trusted before the first own fix (metres). */
	private double briefingError = 250.0;

	// Launcher track
	private double ox, oy, ovx, ovy;
	private long ownerTick;
	private long ownerFixTick = -1;

	private boolean clear;

	@Override
	public void onLaunch(TorpedoLaunchContext context) {
		terrain = context.terrain();
		Vec3 p = context.launchPosition();
		launchHeading = context.launchHeading();
		ox = p.x();
		oy = p.y();
		String data = context.missionData();
		if (data == null) {
			return;
		}
		String[] f = data.split(",");
		try {
			if (f.length >= 6 && f[0].equals("F1")) {
				tx = Double.parseDouble(f[1]);
				ty = Double.parseDouble(f[2]);
				tz = Double.parseDouble(f[3]);
				tvx = Double.parseDouble(f[4]);
				tvy = Double.parseDouble(f[5]);
				haveTarget = Double.isFinite(tx) && Double.isFinite(ty) && Double.isFinite(tz) && Double.isFinite(tvx)
						&& Double.isFinite(tvy);
				if (f.length >= 11) {
					ox = Double.parseDouble(f[6]);
					oy = Double.parseDouble(f[7]);
					ovx = Double.parseDouble(f[8]);
					ovy = Double.parseDouble(f[9]);
					missionTick = Long.parseLong(f[10]);
				}
				if (f.length >= 13) {
					fuse = Double.parseDouble(f[11]);
					briefingError = Double.parseDouble(f[12]);
				}
			}
		} catch (NumberFormatException e) {
			haveTarget = false;
		}
		lastFixX = tx;
		lastFixY = ty;
	}

	@Override
	public void onTick(TorpedoInput in, TorpedoOutput out) {
		long tick = in.tick();
		if (launchTick < 0) {
			launchTick = tick;
			if (missionTick <= 0 || missionTick > tick) {
				missionTick = tick;
			}
			targetTick = missionTick;
			ownerTick = missionTick;
		}
		var pose = in.self();
		Vec3 pos = pose.position();
		double speed = Math.max(in.speed(), 1.0);
		double dt = in.deltaTimeSeconds();
		double sinceLaunch = (tick - launchTick) * dt;
		out.setThrottle(1.0);

		// ── Sense ──
		if (in.activeSonarCooldownTicks() == 0 && tick - launchTick > 25) {
			out.activeSonarPing();
		}
		if (!in.activeSonarReturns().isEmpty()) {
			absorbReturns(in.activeSonarReturns(), pos, tick, dt);
		}

		double oAge = (tick - ownerTick) * dt;
		double opx = ox + ovx * oAge, opy = oy + ovy * oAge;
		double ownerRange = Math.hypot(opx - pos.x(), opy - pos.y());

		if (!haveTarget) {
			out.setRudder(0);
			out.setSternPlanes(0);
			return;
		}

		// ── Where will the target be when we get there? ──
		double age = (tick - targetTick) * dt;
		double px = tx + tvx * age, py = ty + tvy * age;
		double rx = px - pos.x(), ry = py - pos.y();
		double range = Math.hypot(rx, ry);
		double tGo = interceptTime(rx, ry, tvx, tvy, Math.max(speed, 18.0));
		double ax = px + tvx * tGo, ay = py + tvy * tGo;
		double aimZ = tz;

		// A spent weapon that cannot coast onto the target is only a hazard: get rid of it.
		if (in.fuelRemaining() <= 0 && speed < 14.0 && range > 120 && ownerRange > 90) {
			out.detonate();
			return;
		}

		double wantHeading = Geo.bearing(pos.x(), pos.y(), ax, ay);

		// ── Keep clear of the launcher: its hull sets off the fuse just as well ──
		double safe = fuse + 55.0;
		if (!clear) {
			// Run straight out of the tube until there is room to turn.
			if (ownerRange > safe + 40 || sinceLaunch > 25) {
				clear = true;
			} else {
				wantHeading = launchHeading;
			}
		}
		if (clear && ownerRange < safe + 120 && (ownerFixTick >= 0 || sinceLaunch < 60)) {
			double toOwner = Geo.bearing(pos.x(), pos.y(), opx, opy);
			double off = Geo.diff(toOwner, wantHeading);
			double keepOff = Math.toRadians(ownerRange < safe ? 100 : 60);
			if (Math.abs(off) < keepOff) {
				wantHeading = Geo.norm(toOwner + (off >= 0 ? keepOff : -keepOff));
			}
		}

		// ── Depth ──
		double floorHere = terrain.elevationAt(pos.x(), pos.y());
		double sinH = Math.sin(pose.heading()), cosH = Math.cos(pose.heading());
		double floorAhead = floorHere;
		for (double d = 30; d <= Math.min(260, range + 30); d += 46) {
			floorAhead = Math.max(floorAhead, terrain.elevationAt(pos.x() + sinH * d, pos.y() + cosH * d));
		}
		double wantZ = aimZ;
		if (range > 700) {
			// Run in shallow and come down onto the target late: sound carries over the ridges up
			// there, and there is less sea floor to hit.
			wantZ = Math.max(aimZ, -38);
		}
		double minZ = floorAhead + (range < 150 ? 4 : 14);
		wantZ = Math.min(Math.max(wantZ, minZ), -4);
		double wantPitch = Math.atan2(wantZ - pos.z(), Math.min(Math.max(range, 30.0), 260));
		if (pos.z() < minZ) {
			wantPitch = Math.max(wantPitch, Math.toRadians(25));
		}
		wantPitch = Geo.clamp(wantPitch, Math.toRadians(-40), Math.toRadians(40));

		// ── Steer ──
		double yawRate = in.velocity().angular().z();
		double pitchRate = in.velocity().angular().y();
		double inertia = 3200.0 * (1 + 0.4 * speed * speed);
		double kYaw = 51.25 * speed * speed / inertia; // steady yaw rate per unit lift coefficient
		double wMax = kYaw * CL_PEAK;
		double err = Geo.diff(pose.heading(), wantHeading);
		double wDes = Math.min(stopRate(Math.abs(err), wMax * 0.85, YAW_TAU), Math.abs(err) / 1.5);
		wDes = Math.min(wDes, wMax) * Math.signum(err);
		double wCmd = yawRate + (YAW_TAU / 1.2) * (wDes - yawRate);
		out.setRudder(finFor(Geo.clamp(wCmd / kYaw, -CL_PEAK, CL_PEAK)));

		double pitchInertia = 4800.0 + 1280.0 * speed * speed;
		double kPitch = 51.25 * speed * speed / pitchInertia;
		double pitchTau = Geo.clamp(pitchInertia / (15.0 * speed), 1.2, 6.0);
		double pErr = wantPitch - pose.pitch();
		double qDes = Geo.clamp(pErr / 1.2, -kPitch * CL_PEAK, kPitch * CL_PEAK);
		double qCmd = pitchRate + (pitchTau / 0.8) * (qDes - pitchRate);
		out.setSternPlanes(finFor(Geo.clamp(qCmd / kPitch, -CL_PEAK, CL_PEAK)));

		out.publishTarget(ax, ay, aimZ);
		out.publishDiagnostics(px, py, tz, Geo.norm(Math.atan2(tvx, tvy)), Math.hypot(tvx, tvy), ax, ay, aimZ,
				!clear ? "RUNOUT" : (fixes == 0 ? "BLIND" : (range < 400 ? "TERMINAL" : "HOMING")));
	}

	/** Fin command (-1..1) that produces the wanted lift coefficient. */
	private static double finFor(double cl) {
		return Math.asin(Geo.clamp(cl / (2 * Math.PI), -0.5, 0.5)) / (Math.PI / 4);
	}

	/** Turn rate from which the turn can still be stopped within {@code angle}. */
	private static double stopRate(double angle, double wDec, double tau) {
		if (wDec < 1e-6) {
			return 0;
		}
		double c = angle / (tau * wDec);
		double x = Math.sqrt(2 * c) + c / 3;
		for (int i = 0; i < 3; i++) {
			if (x < 1e-9) {
				break;
			}
			x -= (x - Math.log1p(x) - c) * (1 + x) / x;
		}
		return Math.max(0, x) * wDec;
	}

	/** Time for a pursuer at {@code speed} to meet a target at relative position r moving at v. */
	static double interceptTime(double rx, double ry, double vx, double vy, double speed) {
		double a = vx * vx + vy * vy - speed * speed;
		double b = 2 * (rx * vx + ry * vy);
		double c = rx * rx + ry * ry;
		if (Math.abs(a) < 1e-6) {
			return b < -1e-9 ? -c / b : Math.sqrt(c) / speed;
		}
		double disc = b * b - 4 * a * c;
		if (disc < 0) {
			return Math.sqrt(c) / speed;
		}
		double sq = Math.sqrt(disc);
		double t1 = (-b - sq) / (2 * a), t2 = (-b + sq) / (2 * a);
		double t = Math.min(t1, t2);
		if (t < 0) {
			t = Math.max(t1, t2);
		}
		return t < 0 ? Math.sqrt(c) / speed : Math.min(t, 400);
	}

	// ── Echo handling ────────────────────────────────────────────────

	private double echoX, echoY;

	private void locate(SonarContact c, Vec3 pos) {
		double dz = Double.isFinite(c.estimatedDepth()) ? c.estimatedDepth() - pos.z() : 0;
		double ground = Math.sqrt(Math.max(0, c.range() * c.range() - dz * dz));
		echoX = pos.x() + Math.sin(c.bearing()) * ground;
		echoY = pos.y() + Math.cos(c.bearing()) * ground;
	}

	private void absorbReturns(List<SonarContact> returns, Vec3 pos, long tick, double dt) {
		double tAge = (tick - targetTick) * dt;
		double ptx = tx + tvx * tAge, pty = ty + tvy * tAge;
		double oAge = (tick - ownerTick) * dt;
		double pox = ox + ovx * oAge, poy = oy + ovy * oAge;
		double sinceTarget = (tick - (lastFixTick < 0 ? missionTick : lastFixTick)) * dt;
		double sinceOwner = (tick - (ownerFixTick < 0 ? missionTick : ownerFixTick)) * dt;

		// Pick, for each hull, the echo that fits it best.
		SonarContact tBest = null, oBest = null;
		double tCost = Double.MAX_VALUE, oCost = Double.MAX_VALUE;
		double tBestX = 0, tBestY = 0, oBestX = 0, oBestY = 0;
		double tAsOwner = Double.MAX_VALUE;
		for (SonarContact c : returns) {
			if (!(c.range() > 0)) {
				continue;
			}
			locate(c, pos);
			double noise = 25 + 0.04 * c.range();
			// A straight-running hull stays near its track; allow for a turn, bounded by top speed.
			double tGate = noise + (lastFixTick < 0 ? briefingError : 0) + 7.0 * sinceTarget;
			double oGate = noise + 7.0 * sinceOwner;
			double dTrack = Math.hypot(echoX - ptx, echoY - pty);
			double dLast = Math.hypot(echoX - lastFixX, echoY - lastFixY);
			double reach = noise + (lastFixTick < 0 ? briefingError : 0) + SUB_TOP_SPEED * sinceTarget;
			double asT = (dTrack < tGate && dLast < reach) ? dTrack / tGate : Double.MAX_VALUE;
			double dOwner = Math.hypot(echoX - pox, echoY - poy);
			double asO = dOwner < oGate ? dOwner / oGate : Double.MAX_VALUE;
			if (asT < tCost) {
				tCost = asT;
				tBest = c;
				tBestX = echoX;
				tBestY = echoY;
				tAsOwner = asO;
			}
			if (asO < oCost) {
				oCost = asO;
				oBest = c;
				oBestX = echoX;
				oBestY = echoY;
			}
		}
		if (tBest != null && tBest == oBest) {
			// One echo wanted by both tracks. Give it to the launcher unless it is clearly the target.
			if (tCost < 0.5 * oCost) {
				oBest = null;
			} else {
				tBest = null;
			}
		}
		if (oBest != null && oCost < Double.MAX_VALUE) {
			double span = (tick - ownerTick) * dt;
			if (ownerFixTick < 0 || span >= 2.0) {
				if (ownerFixTick >= 0 && span > 0.5) {
					double nvx = (oBestX - ox) / span, nvy = (oBestY - oy) / span;
					ovx += 0.5 * (nvx - ovx);
					ovy += 0.5 * (nvy - ovy);
				}
				ox = oBestX;
				oy = oBestY;
				ownerTick = tick;
			}
			ownerFixTick = tick;
		}
		if (tBest == null || tCost == Double.MAX_VALUE) {
			return;
		}
		// The launcher must not be an equally good explanation of the echo we are about to chase.
		if (tAsOwner < Double.MAX_VALUE && tAsOwner < 2.0 * tCost) {
			return;
		}
		hx[hHead] = tBestX;
		hy[hHead] = tBestY;
		ht[hHead] = tick;
		hHead = (hHead + 1) % HISTORY;
		hCount = Math.min(hCount + 1, HISTORY);
		fixes++;
		lastFixTick = tick;
		lastFixX = tBestX;
		lastFixY = tBestY;
		if (Double.isFinite(tBest.estimatedDepth())) {
			double w = fixes == 1 ? 1.0 : Geo.clamp(60.0 / Math.max(tBest.range(), 1), 0.08, 0.7);
			tz += w * (tBest.estimatedDepth() - tz);
		}
		fitTrack(tick, tBest.range(), dt, tBestX, tBestY);
	}

	/** Least-squares straight track through the recent fixes; longer memory at long range. */
	private void fitTrack(long tick, double range, double dt, double fixX, double fixY) {
		double window = Geo.clamp(range / 60.0, 5.0, 30.0);
		int n = 0;
		double st = 0, sx = 0, sy = 0, stt = 0, stx = 0, sty = 0;
		double oldest = 0;
		for (int i = 0; i < hCount; i++) {
			int idx = (hHead - 1 - i + HISTORY) % HISTORY;
			double t = (ht[idx] - tick) * dt;
			if (-t > window) {
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
		if (n >= 4 && den > 1e-6 && oldest >= 3.0) {
			double vx = (n * stx - st * sx) / den, vy = (n * sty - st * sy) / den;
			double v = Math.hypot(vx, vy);
			if (v > 16.0) {
				vx *= 16.0 / v;
				vy *= 16.0 / v;
			}
			tvx = vx;
			tvy = vy;
			tx = (sx - vx * st) / n;
			ty = (sy - vy * st) / n;
		} else {
			// Too little history for a velocity: keep the briefed one, take the position.
			tx = fixX;
			ty = fixY;
		}
		targetTick = tick;
	}
}
