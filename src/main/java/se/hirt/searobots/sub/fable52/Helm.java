package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.BattleArea;
import se.hirt.searobots.api.SubmarineInput;
import se.hirt.searobots.api.SubmarineOutput;
import se.hirt.searobots.api.TerrainMap;
import se.hirt.searobots.api.Vec3;

/**
 * Turns "go this way, at this depth, at this speed" into actuator settings.
 * <p>
 * The hull answers the rudder and planes slowly: yaw and pitch rates creep toward their steady
 * values with a time constant of about half a minute. A plain proportional controller therefore
 * overshoots badly. The helm instead works with the hull's own response model: it decides which
 * turn rate it wants, taking into account how much heading the boat will still swing through while
 * the turn is being stopped, and then asks the rudder for the steady rate that gets there quickly.
 * Pitch is handled the same way, and buoyancy does the fine depth keeping (it is silent when the
 * tanks are not being worked hard).
 * <p>
 * The helm also owns the last line of defence against grounding and against leaving the battle
 * area. It runs the same turn model forward to see the path the boat is about to sweep, keeps the
 * boat shallow enough to clear the sea floor under that path, turns the other way round if that is
 * safer, slows down to tighten a turn, and as a last resort backs off.
 */
final class Helm {

	/** Yaw and pitch response time of the hull (seconds). */
	static final double TAU = 30.0;
	/** Lift coefficient at the stall angle; more deflection than this only loses lift. */
	static final double CL_MAX = 2.0 * Math.PI * Math.toRadians(25.0);
	private static final double CL_PER_UNIT = 2.0 * Math.PI * (Math.PI / 4.0);
	static final double MAX_SPEED = 15.0;

	/** Keep at least this much water between the boat's centre and the sea floor. */
	static final double CLEARANCE = 42.0;
	static final double SHALLOWEST = -22.0;
	static final double DEEPEST = -385.0;
	/** Sea floor shallower than this cannot be passed over at all. */
	private static final double IMPASSABLE_FLOOR = SHALLOWEST - 26.0;

	private final TerrainMap terrain;
	private final BattleArea area;
	private final SeaChart chart;
	private final double startingHp;

	// Sprint-and-drift state for quiet running
	private boolean drifting;
	private double ballastCmd = 0.5;

	// Path prediction cache
	private double cachedCeiling = SHALLOWEST;
	private double cachedHazardTime = Double.MAX_VALUE;
	private long predictedTick = -100;
	private double predictedFor = Double.NaN;
	private int forcedTurn; // +1 clockwise, -1 counter-clockwise, 0 shortest way
	private long forcedTurnUntil = -1;
	private double predCeiling;
	private double predHazardTime;

	// Backing off from an obstacle
	private boolean backingOff;
	private long backOffSince;
	private double backOffHeading = Double.NaN;

	/** True while the helm has taken over to get clear of terrain or the boundary. */
	boolean terrainAlarm;
	/** Shallowest depth the sea floor currently allows (for display and planning). */
	double floorCeilingZ;

	Helm(TerrainMap terrain, BattleArea area, SeaChart chart, double startingHp) {
		this.terrain = terrain;
		this.area = area;
		this.chart = chart;
		this.startingHp = startingHp;
	}

	/** Steady-state yaw rate (rad/s) the rudder can hold at this speed and hull health. */
	static double maxYawRate(double speed, double hpRatio) {
		double cf = 0.4 + 0.6 * hpRatio * hpRatio;
		return 92250.0 * speed * speed * cf / (5.625e7 * (1 + 0.05 * speed * speed)) * CL_MAX;
	}

	/** Top speed (m/s) at this hull health. */
	static double topSpeed(double hpRatio) {
		return MAX_SPEED * Math.sqrt(0.3 + 0.7 * hpRatio * hpRatio);
	}

	/**
	 * Turn rate to hold now so that the turn can still be stopped exactly on the wanted heading,
	 * given that the rate decays toward the opposite limit {@code -wDec} with time constant
	 * {@link #TAU}.
	 */
	static double rateForAngle(double angle, double wDec) {
		if (wDec < 1e-6) {
			return 0;
		}
		double c = angle / (TAU * wDec);
		double x = Math.sqrt(2 * c) + c / 3;
		for (int i = 0; i < 3; i++) {
			if (x < 1e-9) {
				break;
			}
			x -= (x - Math.log1p(x) - c) * (1 + x) / x;
		}
		return Math.max(0, x) * wDec;
	}

	/** Yaw rate the helm asks for at a given heading error (shared with the path prediction). */
	static double wantedRate(double err, double wMax) {
		double a = Math.abs(err);
		double w = Math.min(rateForAngle(a, wMax * 0.8), a / 6.0);
		return Math.min(w, wMax * 0.97) * Math.signum(err);
	}

	/** Heading error honouring a forced turn direction (go the long way round if told to). */
	private static double turnError(double heading, double wanted, int forced) {
		double err = Geo.diff(heading, wanted);
		if (forced > 0 && err < -0.35) {
			err += Geo.TWO_PI;
		} else if (forced < 0 && err > 0.35) {
			err -= Geo.TWO_PI;
		}
		return err;
	}

	/**
	 * @param quiet
	 *            sprint-and-drift with the clutch out when possible, and gentle tank work
	 */
	void drive(SubmarineInput in, SubmarineOutput out, double heading, double depth, double speed, boolean quiet) {
		var state = in.self();
		var pose = state.pose();
		Vec3 pos = pose.position();
		double v = state.surgeSpeed();
		double absV = Math.abs(v);
		double hpr = Geo.clamp(state.hp() / startingHp, 0, 1);
		double dmg = hpr * hpr;
		double thrustFactor = 0.3 + 0.7 * dmg;
		double controlFactor = 0.4 + 0.6 * dmg;
		double yawRate = state.velocity().angular().z();
		double pitchRate = state.velocity().angular().y();
		double pitch = pose.pitch();
		double vz = state.velocity().linear().z();
		long tick = in.tick();
		Vec3 ground = in.groundVelocity();
		double driftX = ground.x() - state.velocity().linear().x();
		double driftY = ground.y() - state.velocity().linear().y();

		if (area.distanceToBoundary(pos.x(), pos.y()) < 170) {
			// Last resort; the path prediction below normally keeps the boat well inside.
			heading = Geo.bearing(pos.x(), pos.y(), 0, 0);
			speed = Math.max(speed, 4.0);
		}

		// ── Look where the boat is about to go ──
		if (forcedTurn != 0 && (tick > forcedTurnUntil || Math.abs(Geo.diff(pose.heading(), heading)) < 0.3)) {
			forcedTurn = 0;
		}
		if (tick - predictedTick >= 4 || Double.isNaN(predictedFor)
				|| Math.abs(Geo.diff(predictedFor, heading)) > 0.1) {
			predictPath(pos, pose.heading(), yawRate, v, heading, speed, controlFactor, forcedTurn, driftX, driftY);
			if (predHazardTime < 45 && v > 1.0) {
				// The natural turn runs into trouble: would going the other way round be better?
				double ceilingA = predCeiling, hazardA = predHazardTime;
				int natural = forcedTurn != 0 ? forcedTurn : (Geo.diff(pose.heading(), heading) >= 0 ? 1 : -1);
				predictPath(pos, pose.heading(), yawRate, v, heading, speed, controlFactor, -natural, driftX,
						driftY);
				if (predHazardTime > hazardA + 10) {
					forcedTurn = -natural;
					forcedTurnUntil = tick + 50 * 90;
				} else {
					predCeiling = ceilingA;
					predHazardTime = hazardA;
				}
			}
			cachedCeiling = predCeiling;
			cachedHazardTime = predHazardTime;
			predictedTick = tick;
			predictedFor = heading;
		}
		double ceilingNow = cachedCeiling;
		floorCeilingZ = ceilingNow;

		// Trouble on the predicted path that no turn avoids: first slow down (a slower boat turns
		// tighter); if it is inside stopping distance, back off and swing the bow to open water.
		double commit = Math.max(12.0, absV * 2.3 + 7.0);
		if (cachedHazardTime < 50 && !backingOff) {
			speed = Math.min(speed, cachedHazardTime < commit + 12 ? 2.5 : 4.5);
		}
		if (cachedHazardTime < commit && v > -0.3 && !backingOff) {
			backingOff = true;
			backOffSince = tick;
			backOffHeading = openWaterHeading(pos, pose.heading());
		}
		double reverseTarget = Double.NaN;
		if (backingOff) {
			if ((tick - backOffSince) % 250 == 249) {
				backOffHeading = openWaterHeading(pos, pose.heading());
			}
			boolean facingOut = Math.abs(Geo.diff(pose.heading(), backOffHeading)) < Math.toRadians(30);
			boolean aheadClear = hazardFree(pos, pose.heading(), 280);
			if ((facingOut && aheadClear) || tick - backOffSince > 50 * 100) {
				backingOff = false;
				predictedFor = Double.NaN;
			} else {
				boolean asternClear = hazardFree(pos, pose.heading() + Math.PI, 150);
				reverseTarget = asternClear && !aheadClear ? -2.6 : (aheadClear ? 2.5 : 0.0);
				heading = backOffHeading;
			}
		}
		terrainAlarm = backingOff;
		double zCmd = Geo.clamp(Math.max(depth, ceilingNow), DEEPEST, SHALLOWEST);

		// ── Speed ──
		double throttle;
		boolean clutch = true;
		double steady = Geo.sq(speed / MAX_SPEED) / thrustFactor;
		if (!Double.isNaN(reverseTarget)) {
			drifting = false;
			if (reverseTarget < 0) {
				throttle = v > reverseTarget ? -1.0 : -0.5;
			} else if (reverseTarget > 0) {
				throttle = v < reverseTarget ? 0.35 : 0.0;
			} else {
				throttle = v > 0.3 ? -1.0 : (v < -0.3 ? 0.4 : 0.0);
			}
		} else if (quiet && speed > 0.5 && speed <= 7.5) {
			double low = speed * 0.78, high = speed * 1.18;
			if (drifting) {
				if (v < low) {
					drifting = false;
				}
			} else if (v > high) {
				drifting = true;
			}
			if (drifting) {
				throttle = 0;
				clutch = false;
			} else {
				throttle = Geo.clamp(steady * 2.2 + 0.25, 0, 1);
			}
		} else {
			drifting = false;
			throttle = Geo.clamp(steady + 0.4 * (speed - v), 0, 1);
			if (v > speed + 2.5 && cachedHazardTime < 50) {
				throttle = -0.6; // shed speed quickly when a turn has to be tightened
			}
		}

		// ── Heading ──
		double k = 92250.0 * v * absV * controlFactor / (5.625e7 * (1 + 0.05 * v * v));
		double wMax = Math.abs(k) * CL_MAX;
		double err = turnError(pose.heading(), heading, backingOff ? 0 : forcedTurn);
		double wDes = wantedRate(err, wMax);
		double wCmd = yawRate + (TAU / 5.0) * (wDes - yawRate);
		double cl;
		if (Math.abs(k) < 1e-5) {
			cl = Math.signum(err) * CL_MAX * (v >= 0 ? 1 : -1);
		} else {
			cl = Geo.clamp(wCmd / k, -CL_MAX, CL_MAX);
		}
		double rudder = cl / CL_PER_UNIT;

		// ── Depth: pitch for the bulk, buoyancy for the rest ──
		double ip = 8.4375e7 * (1 + 0.05 * v * v);
		double a = 76875.0 * v * absV * controlFactor / ip;
		double b = (2.4525e7 + 576562.0 * v * v) / ip;
		double pitchSinMax = Math.min(0.9, Math.abs(a) * CL_MAX / b);
		double pitchLim = Math.min(Math.toRadians(22), Math.asin(pitchSinMax) * 0.85);
		boolean urgent = ceilingNow > pos.z() - 3;
		boolean emergency = ceilingNow > pos.z() + 2 && zCmd > pos.z() + 2;
		double ez = zCmd - (pos.z() + vz * 5.0); // act on where the boat will be shortly
		double absEz = Math.abs(ez);
		double diveLim = Math.min(Math.toRadians(8), pitchLim);
		double climbLim = Math.min(urgent ? Math.toRadians(20) : Math.toRadians(12), pitchLim);
		double vzMax = 1.25 + absV * Math.sin(ez > 0 ? climbLim : diveLim);
		double vzDes = Math.signum(ez) * Math.min(vzMax, Math.min(absEz * 0.10, Math.sqrt(0.08 * absEz)));
		if (emergency) {
			// The sea floor is coming up: climb hard, but ease off before breaking the surface.
			vzDes = Math.min(vzMax, Math.max(0.3, 0.12 * (-10.0 - pos.z())));
		}
		double thetaDes = 0;
		if (v > 2.5) {
			thetaDes = Math.asin(Geo.clamp(vzDes * (emergency ? 1.0 : 0.8) / v, -Math.sin(diveLim), Math.sin(climbLim)));
		}
		double planes = 0;
		if (Math.abs(a) > 1e-5) {
			double wn = 0.22;
			double acc = wn * wn * (thetaDes - pitch) - 2 * wn * pitchRate;
			double qCmd = pitchRate + TAU * acc;
			double clP = Geo.clamp((qCmd + b * Math.sin(pitch)) / a, -CL_MAX, CL_MAX);
			planes = clP / CL_PER_UNIT;
		}
		double heave = vz - v * Math.sin(pitch);
		double heaveDes = Geo.clamp(vzDes - v * Math.sin(pitch), -1.3, 1.3);
		if (emergency && pos.z() < -45) {
			heaveDes = 1.3;
		}
		double force = 399750.0 * heaveDes * Math.abs(heaveDes) + 5.625e6 * 0.6 * (heaveDes - heave);
		double ballastWanted = Geo.clamp(0.5 + force / (2 * 735750.0), 0, 1);
		if (quiet && !urgent) {
			// Working the tanks is audible; creep instead.
			double maxStep = 0.015 * in.deltaTimeSeconds();
			ballastCmd += Geo.clamp(ballastWanted - ballastCmd, -maxStep, maxStep);
		} else {
			ballastCmd = ballastWanted;
		}

		out.setEngineClutch(clutch);
		out.setThrottle(throttle);
		out.setRudder(rudder);
		out.setSternPlanes(planes);
		out.setBallast(ballastCmd);
	}

	/**
	 * Runs the boat's own turn model forward for a minute and looks at the sea floor (and the
	 * battle area boundary) under the path it sweeps. Produces the shallowest depth the boat must
	 * already be at now ({@link #predCeiling}): everything it passes over soon has to be cleared at
	 * the present depth, because pitch answers too slowly to promise a climb; only farther out is a
	 * buoyancy-only climb credited. Also reports when the path first meets something that depth
	 * cannot solve ({@link #predHazardTime}).
	 */
	private void predictPath(Vec3 pos, double heading, double yawRate, double v, double wanted, double wantedSpeed,
			double controlFactor, int forced, double driftX, double driftY) {
		double x = pos.x(), y = pos.y();
		double h = heading, w = yawRate, speed = Math.max(v, 0.5);
		double ceiling = terrain.elevationAt(x, y) + CLEARANCE;
		// The stern swings wide in a turn; check under it too.
		ceiling = Math.max(ceiling, terrain.elevationAt(x - Math.sin(h) * 40, y - Math.cos(h) * 40) + CLEARANCE - 8);
		double hazard = Double.MAX_VALUE;
		double z0 = pos.z();
		double dt = 1.0;
		for (double t = dt; t <= 64.0; t += dt) {
			if (t > 40) {
				dt = 2.0;
			}
			double k = 92250.0 * speed * speed * controlFactor / (5.625e7 * (1 + 0.05 * speed * speed));
			double wMax = k * CL_MAX;
			double err = turnError(h, wanted, forced);
			if (Math.abs(Geo.diff(h, wanted)) < 0.3) {
				err = Geo.diff(h, wanted);
			}
			double wCmd = Geo.clamp(w + (TAU / 5.0) * (wantedRate(err, wMax) - w), -wMax, wMax);
			w += (wCmd - w) * (dt / TAU);
			h += w * dt;
			speed += Geo.clamp(wantedSpeed - speed, -0.15 * dt, 0.35 * dt);
			double sx = Math.sin(h), sy = Math.cos(h);
			x += (speed * sx + driftX) * dt;
			y += (speed * sy + driftY) * dt;
			// The bow leads the centre by half a hull; allow for some cross-track error as well.
			double bx = x + sx * 34, by = y + sy * 34;
			double raw = Math.max(terrain.elevationAt(bx, by),
					Math.max(terrain.elevationAt(x + sy * 35, y - sx * 35), terrain.elevationAt(x - sy * 35, y + sx * 35)));
			// What the boat can certainly climb by then (tanks plus a modest up-angle, after a lag).
			double reachable = z0 + Math.max(0, t - 7.0) * (1.1 + 0.12 * speed);
			if (hazard == Double.MAX_VALUE && (raw > IMPASSABLE_FLOOR || raw + 22.0 > Math.max(z0, reachable)
					|| area.distanceToBoundary(bx, by) < 90)) {
				hazard = t;
			}
			double need = raw + CLEARANCE - 0.8 * Math.max(0, t - 12.0);
			if (need > ceiling) {
				ceiling = need;
			}
		}
		predCeiling = ceiling;
		predHazardTime = hazard;
	}

	/** True if a straight run from the boat meets neither shoal water nor the boundary. */
	private boolean hazardFree(Vec3 pos, double heading, double distance) {
		double sx = Math.sin(heading), sy = Math.cos(heading);
		for (double d = 0; d <= distance; d += 25) {
			double px = pos.x() + sx * d, py = pos.y() + sy * d;
			if (terrain.elevationAt(px, py) > IMPASSABLE_FLOOR - 4 || area.distanceToBoundary(px, py) < 110) {
				return false;
			}
		}
		return true;
	}

	/** Picks the heading with the most open, deep water, preferring small turns. */
	private double openWaterHeading(Vec3 pos, double heading) {
		double best = heading + Math.PI;
		double bestScore = -1e18;
		for (int i = 0; i < 16; i++) {
			double h = heading + i * (Geo.TWO_PI / 16);
			double run = chart.freeRun(pos.x(), pos.y(), h, 900);
			double sx = Math.sin(h), sy = Math.cos(h);
			double worst = -1e9;
			for (double d = 40; d <= 400; d += 40) {
				worst = Math.max(worst, terrain.elevationAt(pos.x() + sx * d, pos.y() + sy * d));
			}
			double turn = Math.abs(Geo.wrap(h - heading));
			double score = run - worst * 2.0 - turn * 120;
			if (worst > IMPASSABLE_FLOOR - 10) {
				score -= 5000;
			}
			if (area.distanceToBoundary(pos.x() + sx * 400, pos.y() + sy * 400) < 150) {
				score -= 5000;
			}
			if (score > bestScore) {
				bestScore = score;
				best = h;
			}
		}
		return Geo.norm(best);
	}
}
