package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.BattleArea;
import se.hirt.searobots.api.ContactEstimate;
import se.hirt.searobots.api.ExplosionEvent;
import se.hirt.searobots.api.FiringSolution;
import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.api.MatchContext;
import se.hirt.searobots.api.Purpose;
import se.hirt.searobots.api.SonarContact;
import se.hirt.searobots.api.StrategicWaypoint;
import se.hirt.searobots.api.SubmarineController;
import se.hirt.searobots.api.SubmarineInput;
import se.hirt.searobots.api.SubmarineOutput;
import se.hirt.searobots.api.TerrainMap;
import se.hirt.searobots.api.TorpedoController;
import se.hirt.searobots.api.TorpedoLaunchCommand;
import se.hirt.searobots.api.Vec3;
import se.hirt.searobots.api.Waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fable 5.2: a clean-room SeaRobots attack submarine.
 * <p>
 * The boat fights on one idea: <i>a torpedo duel is decided before anyone fires</i>. A torpedo
 * outruns a submarine only for four minutes, and a submarine needs most of a minute to turn around,
 * so whether a shot connects depends on range, on where the target's bow points and on how fast it
 * is already going. The boat therefore keeps asking two questions, answered by a small kinematic
 * simulation ({@link Engagement}): <i>if the enemy fired now, could I still run out his weapon?</i>
 * and <i>if I fired now, could he run out mine?</i> It manoeuvres to keep the first answer "yes",
 * fires when the second is "no", and turns away and sprints the moment a hostile weapon is seen.
 */
public class Fable52Submarine implements SubmarineController {

	private static final double CRUISE_DEPTH = -200.0;
	private static final int PING_COOLDOWN = 250;

	// ── Match ──
	private MatchConfig config;
	private TerrainMap terrain;
	private BattleArea area;
	private SeaChart chart;
	private Helm helm;
	private Navigator nav;
	private final EnemyTrack enemy = new EnemyTrack();
	private final WeaponPlot plot = new WeaponPlot();
	private final Engagement sim = new Engagement();
	private Lookout lookout;
	private final List<StrategicWaypoint> objectives = new ArrayList<>();
	private int objectiveIndex;

	// ── Own state, refreshed every tick ──
	private long tick;
	private double x, y, z, heading, speed, yawRate, hpRatio;
	private int torpedoes;

	// ── Weapons bookkeeping ──
	private int salvoLeft;
	private long nextShotTick;
	private long lastLaunchTick = -100000;
	private double enemyHp;
	private boolean enemyDead;
	private long enemyDeadTick;
	private int ownHits;
	private double lastFuse = 17.5;
	/** Ticks at which our weapons left, and how many of ours have since been heard to detonate. */
	private final long[] launchTicks = new long[16];
	private int launches, ownDetonations;

	// ── Tactical memory ──
	private int side = 1;
	private long sideUntil;
	private double fleeHeading = Double.NaN;
	private long fleeChosenTick;
	private long suspectUntil = -1;
	private double suspectBearing;
	private int passiveTorpedoes;
	private long lastThreatTick = -100000;
	private boolean safeNow = true, safeMargin = true, canKill, straightHit;
	private long assessedTick = -1000;
	private long searchUntil;
	private boolean pingedLastTick;
	private boolean gotEnemyEcho;
	private String mode = "INIT";

	@Override
	public String name() {
		return "Fable 5.2";
	}

	@Override
	public String shortName() {
		return "F5";
	}

	@Override
	public TorpedoController createTorpedoController() {
		return new Fable52Torpedo();
	}

	@Override
	public void onMatchStart(MatchContext context) {
		config = context.config();
		terrain = context.terrain();
		area = config.battleArea();
		chart = new SeaChart(terrain, area);
		helm = new Helm(terrain, area, chart, config.startingHp());
		nav = new Navigator(chart);
		lookout = new Lookout(chart);
		launches = ownDetonations = 0;
		enemyDead = false;
		enemy.reset();
		plot.reset();
		enemyHp = config.startingHp();
		ownHits = 0;
		salvoLeft = 0;
		lastLaunchTick = -100000;
		suspectUntil = -1;
		lastThreatTick = -100000;
		fleeHeading = Double.NaN;
		searchUntil = -1;
		assessedTick = -1000;
		safeNow = safeMargin = true;
		canKill = straightHit = false;
	}

	@Override
	public void setObjectives(List<StrategicWaypoint> mandatory) {
		objectives.clear();
		if (mandatory != null) {
			objectives.addAll(mandatory);
		}
		objectiveIndex = 0;
	}

	// ── Main loop ────────────────────────────────────────────────────

	@Override
	public void onTick(SubmarineInput in, SubmarineOutput out) {
		tick = in.tick();
		var self = in.self();
		Vec3 pos = self.pose().position();
		x = pos.x();
		y = pos.y();
		z = pos.z();
		heading = self.pose().heading();
		speed = self.surgeSpeed();
		yawRate = self.velocity().angular().z();
		hpRatio = Geo.clamp(self.hp() / (double) config.startingHp(), 0, 1);
		torpedoes = self.torpedoesRemaining();

		hearExplosions(in);
		gotEnemyEcho = false;
		readActiveReturns(in);
		if (pingedLastTick && !gotEnemyEcho) {
			lookout.swept(tick, x, y, z); // an empty ping still says where he is not
		}
		readPassive(in);
		plot.expire(tick);

		pingedLastTick = in.activeSonarCooldownTicks() == 0;
		if (pingedLastTick) {
			out.activeSonarPing();
		}

		double wantHeading, wantDepth = CRUISE_DEPTH, wantSpeed;
		boolean quiet = false;
		WeaponPlot.Weapon threat = plot.worstThreat(tick, x, y);
		if (threat != null) {
			lastThreatTick = tick;
		}
		boolean suspect = threat == null && tick < suspectUntil;

		if (threat != null || suspect) {
			// ── Evade: a weapon is in the water for us ──
			mode = "EVADE";
			double away;
			double need;
			if (threat != null) {
				double dt = (tick - threat.tick) * 0.02;
				away = Geo.bearing(threat.x + threat.vx * dt, threat.y + threat.vy * dt, x, y);
				need = Math.max(600, 15.0 * Math.max(threat.fuelLeft(tick), 0));
			} else {
				away = Geo.norm(suspectBearing + Math.PI);
				need = 3000;
			}
			wantHeading = chooseFlee(away, need);
			wantSpeed = Helm.MAX_SPEED;
			wantDepth = Geo.clamp(z, -260, -70);
		} else if (objectiveIndex < objectives.size()) {
			// ── Mandatory navigation objectives ──
			mode = "OBJECTIVE";
			StrategicWaypoint wp = objectives.get(objectiveIndex);
			if (Math.hypot(wp.x() - x, wp.y() - y) < Math.max(wp.arrivalRadius(), 150)) {
				objectiveIndex++;
			}
			wantHeading = navigate(wp.x(), wp.y());
			wantDepth = wp.preferredDepth();
			wantSpeed = wp.targetSpeed() > 0 ? wp.targetSpeed() : 9.0;
			fleeHeading = Double.NaN;
		} else if (enemyDead || !enemy.valid || enemy.fixAgeSeconds(tick) > 75) {
			// ── Search: nothing on the plot (or nothing left to shoot at) ──
			mode = enemyDead ? "DONE" : "SEARCH";
			if (tick > searchUntil || !lookout.haveGoal
					|| Math.hypot(lookout.goalX - x, lookout.goalY - y) < 350) {
				boolean hint = enemy.valid && !enemyDead;
				lookout.chooseGoal(tick, x, y, enemy.px(tick), enemy.py(tick), hint);
				searchUntil = tick + 50 * 150;
			}
			wantHeading = lookout.haveGoal ? navigate(lookout.goalX, lookout.goalY) : heading;
			wantSpeed = enemyDead ? 4.0 : 9.0;
			// Shallow water sees over ridges that would hide the enemy from a deep boat.
			wantDepth = -45.0;
			fleeHeading = Double.NaN;
		} else {
			// ── Engage ──
			fleeHeading = Double.NaN;
			double ex = enemy.px(tick), ey = enemy.py(tick);
			double range = Math.hypot(ex - x, ey - y);
			double bearing = Geo.bearing(x, y, ex, ey);
			if (tick - assessedTick >= 25) {
				assess(ex, ey, range, bearing);
				assessedTick = tick;
			}
			if (tick > sideUntil) {
				// Pass on the side with more sea room.
				double right = chart.freeRun(x, y, bearing + Math.toRadians(100), 2500);
				double left = chart.freeRun(x, y, bearing - Math.toRadians(100), 2500);
				if (Math.abs(right - left) > 300) {
					side = right > left ? 1 : -1;
				}
				sideUntil = tick + 50 * 20;
			}
			if (!safeNow) {
				mode = "OPEN";
				wantHeading = clearHeading(bearing + Math.PI, 700);
				wantSpeed = 13.5;
			} else if (!safeMargin) {
				mode = "HOLD";
				wantHeading = clearHeading(bearing + side * Math.toRadians(105), 600);
				wantSpeed = 10.5;
			} else if (range > 4800) {
				mode = "CLOSE";
				wantHeading = navigate(ex, ey);
				wantSpeed = 11.0;
			} else {
				mode = "STALK";
				wantHeading = clearHeading(bearing + side * Math.toRadians(40), 600);
				wantSpeed = 10.5;
			}
			considerFiring(out, ex, ey, range, bearing);
		}

		if (Double.isNaN(wantHeading)) {
			wantHeading = heading;
			wantSpeed = Math.min(wantSpeed, 3.0);
		}
		continueSalvo(out);
		helm.drive(in, out, wantHeading, wantDepth, wantSpeed, quiet);
		publish(out, wantHeading, wantDepth);
	}

	// ── Sensing ──────────────────────────────────────────────────────

	private void hearExplosions(SubmarineInput in) {
		for (ExplosionEvent e : in.explosionEvents()) {
			if (!e.ownTorpedo()) {
				continue;
			}
			ownDetonations++;
			if (e.submarineHit() && e.rangeMetres() > 90 && !enemyDead) {
				ownHits++;
				enemyHp -= 1200.0 * Geo.sq(1.0 - lastFuse / 50.0);
				if (enemyHp <= 0) {
					enemyDead = true;
					enemyDeadTick = tick;
				}
			}
		}
		if (enemyDead && enemy.valid && enemy.hasVelocity() && enemy.speed() > 4.0
				&& tick - enemyDeadTick > 50 * 60 && enemy.fixAgeSeconds(tick) < 6) {
			// A wreck does not make way: we miscounted.
			enemyDead = false;
			enemyHp = 300;
		}
	}

	/** Upper bound on how many of our own weapons can still be running. */
	private int ownWeaponsRunning() {
		int recent = 0;
		for (int i = 0; i < Math.min(launches, launchTicks.length); i++) {
			if ((tick - launchTicks[i]) * 0.02 < 300) {
				recent++;
			}
		}
		// Detonations are heard in launch order often enough; never report fewer than the plot shows.
		int old = Math.max(0, launches - recent);
		return Math.max(0, recent - Math.max(0, ownDetonations - old));
	}

	private void readActiveReturns(SubmarineInput in) {
		List<SonarContact> returns = in.activeSonarReturns();
		if (returns.isEmpty()) {
			return;
		}
		// Which echo is the submarine? The engine reports a mature solution only for a hull.
		SonarContact sub = null;
		for (SonarContact c : returns) {
			if (c.range() > 0 && c.solutionQuality() > 0.3) {
				if (sub == null || c.signalExcess() > sub.signalExcess()) {
					sub = c;
				}
			}
		}
		if (sub == null && enemy.valid) {
			// Fall back on position: the echo nearest to where the track says the enemy is.
			double bestD = 400 + 16 * enemy.fixAgeSeconds(tick);
			for (SonarContact c : returns) {
				if (!(c.range() > 0)) {
					continue;
				}
				double d = Math.hypot(echoX(c) - enemy.px(tick), echoY(c) - enemy.py(tick));
				if (d < bestD && c.estimatedSpeed() < 17.5) {
					bestD = d;
					sub = c;
				}
			}
		}
		if (sub != null) {
			enemy.addFix(tick, echoX(sub), echoY(sub), sub.estimatedDepth(), sub.range());
			gotEnemyEcho = true;
		}
		plot.beginPing();
		for (SonarContact c : returns) {
			if (c == sub || !(c.range() > 0)) {
				continue;
			}
			plot.observe(tick, x, y, echoX(c), echoY(c), c.estimatedDepth(), enemy.px(tick), enemy.py(tick),
					enemy.valid);
		}
	}

	private double groundRange(SonarContact c) {
		double dz = Double.isFinite(c.estimatedDepth()) ? c.estimatedDepth() - z : 0;
		return Math.sqrt(Math.max(0, c.range() * c.range() - dz * dz));
	}

	private double echoX(SonarContact c) {
		return x + Math.sin(c.bearing()) * groundRange(c);
	}

	private double echoY(SonarContact c) {
		return y + Math.cos(c.bearing()) * groundRange(c);
	}

	private void readPassive(SubmarineInput in) {
		int torps = 0;
		double loudest = -1;
		double loudestBearing = 0;
		for (SonarContact c : in.sonarContacts()) {
			// Torpedoes are heard without any range solution at all.
			boolean weapon = c.range() == 0 && c.rangeUncertainty() == 0;
			if (weapon) {
				torps++;
				if (c.signalExcess() > loudest) {
					loudest = c.signalExcess();
					loudestBearing = c.bearing();
				}
			}
		}
		passiveTorpedoes = torps;
		if (torps > ownWeaponsRunning() && plot.worstThreat(tick, x, y) == null) {
			// More weapons audible than we have running: one of them is not ours.
			suspectUntil = tick + 50 * 8;
			suspectBearing = enemy.valid ? Geo.bearing(x, y, enemy.px(tick), enemy.py(tick)) : loudestBearing;
		}
	}

	// ── Engagement assessment ────────────────────────────────────────

	private void assess(double ex, double ey, double range, double bearing) {
		boolean enemyDry = plot.hostileSeen >= config.torpedoCount();
		double fromEnemy = Geo.norm(bearing + Math.PI);
		if (enemyDry) {
			safeNow = safeMargin = true;
		} else {
			safeNow = !sim.run(ex, ey, fromEnemy, 18, x, y, heading, speed, yawRate, hpRatio, true, 5.0,
					Engagement.TORPEDO_FUEL);
			double mx = ex + Math.sin(fromEnemy) * 350, my = ey + Math.cos(fromEnemy) * 350;
			safeMargin = safeNow && !sim.run(mx, my, fromEnemy, 18, x, y, heading, speed, yawRate, hpRatio, true,
					6.0, Engagement.TORPEDO_FUEL);
		}
		canKill = false;
		straightHit = false;
		if (enemy.steadyFixes >= 2) {
			double eh = enemy.speed() > 1.0 ? enemy.heading() : fromEnemy;
			double ehp = Geo.clamp(enemyHp / config.startingHp(), 0.05, 1);
			canKill = sim.run(x, y, heading, speed + 3, ex, ey, eh, enemy.speed(), 0, ehp, true, 3.0,
					Engagement.TORPEDO_FUEL - 12);
			straightHit = canKill || (sim.run(x, y, heading, speed + 3, ex, ey, eh, enemy.speed(), 0, ehp, false, 0,
					Engagement.TORPEDO_FUEL - 12) && sim.hitTime < 170);
		}
	}

	private void considerFiring(SubmarineOutput out, double ex, double ey, double range, double bearing) {
		if (torpedoes <= 0 || salvoLeft > 0 || enemyDead || enemy.fixAgeSeconds(tick) > 6.0 || range < 450) {
			return;
		}
		if (ownWeaponsRunning() > 0 || (tick - lastLaunchTick) < 50 * 20) {
			return; // let the weapons already running do their work first
		}
		// Closing rate: positive when the enemy comes toward us.
		double closing = -(enemy.vx * Math.sin(bearing) + enemy.vy * Math.cos(bearing));
		boolean fire = canKill || (straightHit && closing > 2.5 && enemy.hasVelocity());
		if (!fire || !waterBetween(ex, ey)) {
			return;
		}
		out.publishFiringSolution(new FiringSolution(ex, ey, enemy.heading(), enemy.speed(), canKill ? 1.0 : 0.6));
		salvoLeft = Math.min(2, torpedoes);
		nextShotTick = tick;
	}

	private void continueSalvo(SubmarineOutput out) {
		if (salvoLeft <= 0 || tick < nextShotTick || !enemy.valid) {
			return;
		}
		if (torpedoes <= 0 || enemyDead) {
			salvoLeft = 0;
			return;
		}
		double ex = enemy.px(tick), ey = enemy.py(tick);
		double fuse = chooseFuse();
		double briefingError = 120 + 0.04 * enemy.lastFixRange + 14 * enemy.fixAgeSeconds(tick);
		String mission = String.format(Locale.ROOT, "F1,%.1f,%.1f,%.1f,%.2f,%.2f,%.1f,%.1f,%.2f,%.2f,%d,%.1f,%.0f",
				ex, ey, enemy.z, enemy.vx, enemy.vy, x, y, speed * Math.sin(heading), speed * Math.cos(heading), tick,
				fuse, briefingError);
		out.launchTorpedo(new TorpedoLaunchCommand(Geo.bearing(x, y, ex, ey), 0, fuse, mission));
		plot.noteOwnLaunch(tick, x, y);
		launchTicks[launches % launchTicks.length] = tick;
		launches++;
		lastFuse = fuse;
		lastLaunchTick = tick;
		salvoLeft--;
		nextShotTick = tick + 50 * 6;
	}

	/**
	 * Largest proximity fuse that still does the job: big enough to kill with this hit if the enemy
	 * is already hurt, otherwise big enough that two hits kill.
	 */
	private double chooseFuse() {
		double hp = Math.max(enemyHp, 30.0);
		double single = 50.0 * (1.0 - Math.sqrt(hp / 1200.0));
		double pair = 50.0 * (1.0 - Math.sqrt(hp / 2400.0));
		double fuse = single >= 12.0 ? single : pair;
		return Geo.clamp(fuse - 0.6, config.minFuseRadius(), config.maxFuseRadius());
	}

	/** True if no land stands between us and the point (a torpedo can swim over shoals). */
	private boolean waterBetween(double tx, double ty) {
		double dx = tx - x, dy = ty - y;
		double dist = Math.hypot(dx, dy);
		int steps = Math.max(1, (int) (dist / 40));
		for (int i = 1; i < steps; i++) {
			double t = (double) i / steps;
			if (terrain.elevationAt(x + dx * t, y + dy * t) > -18) {
				return false;
			}
		}
		return true;
	}

	// ── Movement helpers ─────────────────────────────────────────────

	private double navigate(double gx, double gy) {
		return nav.headingTo(tick, x, y, gx, gy);
	}

	/** The heading closest to {@code preferred} with at least {@code run} metres of open water. */
	private double clearHeading(double preferred, double run) {
		for (int i = 0; i <= 12; i++) {
			double off = Math.toRadians(15) * i;
			if (chart.freeRun(x, y, preferred + off * side, run) >= run) {
				return Geo.norm(preferred + off * side);
			}
			if (i > 0 && chart.freeRun(x, y, preferred - off * side, run) >= run) {
				return Geo.norm(preferred - off * side);
			}
		}
		return Geo.norm(preferred);
	}

	/** Escape heading: as directly away as the sea room allows. */
	private double chooseFlee(double away, double need) {
		if (!Double.isNaN(fleeHeading) && tick - fleeChosenTick < 100) {
			return fleeHeading;
		}
		need = Geo.clamp(need, 600, 3600);
		double best = away;
		double bestScore = -1e9;
		for (int i = -8; i <= 8; i++) {
			double h = away + Math.toRadians(12.5) * i;
			double run = chart.freeRun(x, y, h, need);
			double score = run / need - 0.55 * (1 - Math.cos(Math.toRadians(12.5) * i));
			// Do not throw away a turn already made.
			score -= 0.06 * Math.abs(Geo.wrap(h - heading));
			if (!Double.isNaN(fleeHeading)) {
				score -= 0.05 * Math.abs(Geo.wrap(h - fleeHeading));
			}
			if (score > bestScore) {
				bestScore = score;
				best = h;
			}
		}
		fleeHeading = Geo.norm(best);
		fleeChosenTick = tick;
		return fleeHeading;
	}

	// ── Telemetry ────────────────────────────────────────────────────

	private void publish(SubmarineOutput out, double wantHeading, double wantDepth) {
		if (enemy.valid) {
			double age = enemy.fixAgeSeconds(tick);
			double alive = Geo.clamp(1.0 - age / 120.0, 0.05, 1.0);
			out.publishContactEstimate(new ContactEstimate(enemy.px(tick), enemy.py(tick), alive * 0.9, alive,
					40 + 0.03 * enemy.lastFixRange + 15 * age, enemy.heading(), enemy.speed(), "track"));
		}
		for (WeaponPlot.Weapon w : plot.weapons) {
			if (w.live && !w.own) {
				out.publishContactEstimate(new ContactEstimate(w.x, w.y, 0.8, 1.0, 60, Double.NaN, 22, "torpedo"));
			}
		}
		out.publishWaypoint(new Waypoint(x + Math.sin(wantHeading) * 400, y + Math.cos(wantHeading) * 400,
				Math.max(wantDepth, helm.floorCeilingZ), true));
		if (enemy.valid) {
			out.publishStrategicWaypoint(new Waypoint(enemy.px(tick), enemy.py(tick), enemy.z, true),
					mode.equals("EVADE") ? Purpose.EVADE : Purpose.INTERCEPT);
		}
		if ((tick & 7) == 0) {
			double range = enemy.valid ? Math.hypot(enemy.px(tick) - x, enemy.py(tick) - y) : -1;
			out.setStatus(String.format(Locale.ROOT, "%s R%.0f %s%s%s e%d h%d%s", mode, range, safeNow ? "s" : "-",
					safeMargin ? "m" : "-", canKill ? "K" : (straightHit ? "k" : "-"), plot.hostileSeen, ownHits,
					helm.terrainAlarm ? " TERR" : ""));
		}
	}
}
