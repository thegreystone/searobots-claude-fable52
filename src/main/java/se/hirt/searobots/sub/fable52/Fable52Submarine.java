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
 * so whether a shot connects depends on range, on where the target's bow points, on how fast it is
 * already going and on how much sea room it has. The boat therefore keeps asking two questions:
 * <ul>
 * <li><i>If the enemy fired now, could I still run out his weapon?</i> ({@link EscapePlanner}) It
 * manoeuvres to keep the answer "yes", and the moment a weapon is actually in the water it runs the
 * route that answer was based on.</li>
 * <li><i>If I fired now, could he run out mine?</i> ({@link Engagement}) It fires when the answer
 * is "no" - or, against an opponent who has not shown that he runs, when a straight shot will get
 * there.</li>
 * </ul>
 * Everything is found and followed with active sonar: every ping gives position, depth and range
 * of the enemy and of every torpedo in line of sight.
 */
public class Fable52Submarine implements SubmarineController {

	private static final double COMBAT_DEPTH = -65.0;
	private static final double SEARCH_DEPTH = -45.0;
	private static final double TORPEDO_SPEED = 22.5;

	// ── Match ──
	private MatchConfig config;
	private TerrainMap terrain;
	private BattleArea area;
	private SeaChart chart;
	private Helm helm;
	private Navigator nav;
	private Lookout lookout;
	private EscapePlanner escape;
	private final EnemyTrack enemy = new EnemyTrack();
	private final WeaponPlot plot = new WeaponPlot();
	private final Engagement sim = new Engagement();
	private final List<StrategicWaypoint> objectives = new ArrayList<>();
	private int objectiveIndex;

	// ── Own state, refreshed every tick ──
	private long tick;
	private double x, y, z, heading, speed, yawRate, hpRatio;
	private int torpedoes;

	// ── Weapons bookkeeping ──
	private int salvoLeft;
	private long nextShotTick;
	private long lastLaunchTick;
	private double enemyHp;
	private boolean enemyDead;
	private long enemyDeadTick;
	private int ownHits;
	private double lastFuse;
	/** Ticks at which our weapons left, and how many of ours have since been heard to detonate. */
	private final long[] launchTicks = new long[16];
	private int launches, ownDetonations;
	/** What the enemy does when shot at: 0 unknown, 1 runs, -1 does not. */
	private int enemyRuns;
	private boolean enemyRunsObserved;
	/** Enemy weapons counted so far: never more than he has really fired. */
	private int enemyLaunched, salvoPeak;
	private long lastHostileEchoTick;
	/** Enemy active sonar pings heard, and since when we have been listening for them. */
	private int enemyPings;
	private long enemyPingTick, firstContactTick;
	private int hitsAtLastSalvo;
	private boolean salvoJudged = true;

	// ── Tactical memory ──
	private int side = 1;
	private long sideUntil;
	private long suspectUntil;
	private double suspectBearing;
	private boolean escapable = true, comfortable = true, canKill, straightHit, nearMiss;
	private long assessedTick, escapeCheckedTick, evadePlannedTick;
	private boolean evadeRouteValid;
	private final double[] runX = new double[48];
	private final double[] runY = new double[48];
	private int runLength, runIndex;
	private boolean runIsEscape;
	private long searchUntil;
	private boolean pingedLastTick;
	private boolean gotEnemyEcho;
	private boolean urgent;
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
		escape = new EscapePlanner(chart);
		enemy.reset();
		plot.reset();
		enemyHp = config.startingHp();
		enemyDead = false;
		ownHits = launches = ownDetonations = 0;
		enemyRuns = 0;
		enemyRunsObserved = false;
		enemyLaunched = salvoPeak = 0;
		lastHostileEchoTick = -100000;
		enemyPings = 0;
		enemyPingTick = -100000;
		firstContactTick = -1;
		salvoJudged = true;
		salvoLeft = 0;
		lastFuse = 17.0;
		lastLaunchTick = -100000;
		suspectUntil = -1;
		searchUntil = -1;
		assessedTick = escapeCheckedTick = evadePlannedTick = -100000;
		escapable = comfortable = true;
		canKill = straightHit = nearMiss = false;
		runLength = 0;
		evadeRouteValid = false;
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
		judgeLastSalvo();
		if (salvoPeak > 0 && (tick - lastHostileEchoTick) > 50 * 70) {
			enemyLaunched += salvoPeak;
			salvoPeak = 0;
		}
		if (firstContactTick < 0 && enemy.valid) {
			firstContactTick = tick;
		}
		if (!enemyRunsObserved && firstContactTick >= 0) {
			// A boat that pings all the time is a hunter, not a hider; one that stays silent is
			// more likely to slip away when shot at. Only a prior: what he actually does overrides it.
			double listened = (tick - firstContactTick) * 0.02;
			if (enemyPings >= 4) {
				enemyRuns = 0;
			} else if (listened > 60) {
				enemyRuns = 1;
			}
		}

		pingedLastTick = in.activeSonarCooldownTicks() == 0;
		if (pingedLastTick) {
			out.activeSonarPing();
		}

		double wantHeading, wantDepth = COMBAT_DEPTH, wantSpeed;
		urgent = false;
		boolean threatened = gatherThreats();
		boolean suspect = !threatened && tick < suspectUntil && enemy.valid;

		if (threatened || suspect) {
			// ── Evade: a weapon is in the water for us ──
			mode = "EVADE";
			if (suspect) {
				// Heard but not yet placed: assume it has just left the enemy's tubes.
				escape.clearThreats();
				escape.addThreat(enemy.px(tick), enemy.py(tick), Engagement.TORPEDO_FUEL, TORPEDO_SPEED);
			}
			if (tick - evadePlannedTick >= 100 || !evadeRouteValid) {
				boolean ok = escape.plan(x, y, heading, speed, hpRatio, 0.5, 5.0);
				adoptRoute(true);
				evadeRouteValid = true;
				evadePlannedTick = tick;
				if (!ok) {
					mode = "EVADE!";
				}
			}
			wantHeading = followRun();
			if (Double.isNaN(wantHeading)) {
				wantHeading = fallbackFlee();
			}
			wantSpeed = Helm.MAX_SPEED;
			wantDepth = Geo.clamp(z, -200, -55);
			urgent = true;
		} else if (objectiveIndex < objectives.size()) {
			// ── Mandatory navigation objectives ──
			mode = "OBJECTIVE";
			evadeRouteValid = false;
			StrategicWaypoint wp = objectives.get(objectiveIndex);
			if (Math.hypot(wp.x() - x, wp.y() - y) < Math.max(wp.arrivalRadius(), 150)) {
				objectiveIndex++;
			}
			wantHeading = navigate(wp.x(), wp.y());
			wantDepth = wp.preferredDepth();
			wantSpeed = wp.targetSpeed() > 0 ? wp.targetSpeed() : 9.0;
		} else if (enemyDead || !enemy.valid || enemy.fixAgeSeconds(tick) > 75) {
			// ── Search: nothing on the plot (or nothing left to shoot at) ──
			mode = enemyDead ? "DONE" : "SEARCH";
			evadeRouteValid = false;
			if (tick > searchUntil || !lookout.haveGoal
					|| Math.hypot(lookout.goalX - x, lookout.goalY - y) < 350) {
				boolean hint = enemy.valid && !enemyDead;
				boolean wary = hint && enemyLaunched < config.torpedoCount();
				lookout.chooseGoal(tick, x, y, enemy.x, enemy.y, hint, wary ? 4300 : 0);
				searchUntil = tick + 50 * 150;
			}
			wantHeading = lookout.haveGoal ? navigate(lookout.goalX, lookout.goalY) : heading;
			wantSpeed = enemyDead ? 4.0 : 9.0;
			// Shallow water sees over ridges that would hide the enemy from a deep boat.
			wantDepth = SEARCH_DEPTH;
		} else {
			// ── Engage ──
			evadeRouteValid = false;
			double ex = enemy.px(tick), ey = enemy.py(tick);
			double range = Math.hypot(ex - x, ey - y);
			double bearing = Geo.bearing(x, y, ex, ey);
			boolean enemyDry = enemyLaunched >= config.torpedoCount();
			boolean dry = torpedoes == 0;
			if (tick - assessedTick >= 50) {
				assessShot(ex, ey, bearing);
				assessedTick = tick;
			}
			// Out of sight is not out of danger: while he cannot be seen he may have closed, and he
			// may have fired. Assess as if he had come straight for us since the last echo.
			double blind = enemy.fixAgeSeconds(tick);
			boolean unseen = blind > 8.0;
			if (tick - escapeCheckedTick >= 150) {
				double creep = unseen ? Math.min(7.0 * blind, range * 0.5) : 0;
				assessEscape(ex - Math.sin(bearing) * creep, ey - Math.cos(bearing) * creep, bearing, range - creep,
						enemyDry, unseen ? 14.0 : 7.0);
				escapeCheckedTick = tick;
			}
			if (tick > sideUntil) {
				// Keep him on the side he is already on, unless the sea room says otherwise.
				double right = chart.freeRun(x, y, bearing + Math.toRadians(110), 2500);
				double left = chart.freeRun(x, y, bearing - Math.toRadians(110), 2500);
				int natural = Geo.diff(heading, bearing) < 0 ? 1 : -1;
				if (Math.abs(right - left) > 900) {
					side = right > left ? 1 : -1;
				} else {
					side = natural;
				}
				sideUntil = tick + 50 * 30;
			}
			if (!escapable) {
				// He could hit us from where he is: get out along the route that fails least.
				mode = "OPEN";
				wantHeading = followRun();
				if (Double.isNaN(wantHeading)) {
					wantHeading = clearHeading(bearing + Math.PI, 800);
				}
				wantSpeed = 13.5;
			} else if (dry) {
				// Nothing left to shoot with: stay alive, stay away.
				mode = "DRY";
				wantHeading = range < 6500 ? clearHeading(bearing + Math.PI, 900)
						: clearHeading(bearing + side * Math.toRadians(90), 900);
				wantSpeed = range < 5000 ? 11.0 : 6.0;
			} else if (enemyDry) {
				// He has nothing left: walk up and finish it.
				mode = "KILL";
				wantHeading = range > 1500 ? navigate(ex, ey) : clearHeading(bearing + side * Math.toRadians(70), 500);
				wantSpeed = 12.0;
			} else if (!comfortable) {
				mode = "HOLD";
				wantHeading = clearHeading(bearing + side * Math.toRadians(115), 900);
				wantSpeed = 12.0;
			} else if (range > 5200 && !unseen) {
				mode = "CLOSE";
				wantHeading = navigate(ex, ey);
				wantSpeed = 11.0;
			} else {
				mode = "STALK";
				wantHeading = clearHeading(bearing + side * Math.toRadians(unseen ? 90 : 62), 900);
				wantSpeed = 12.0;
			}
			considerFiring(out, ex, ey, range, bearing, enemyDry);
		}

		if (Double.isNaN(wantHeading)) {
			wantHeading = heading;
			wantSpeed = Math.min(wantSpeed, 3.0);
		}
		continueSalvo(out);
		helm.drive(in, out, wantHeading, wantDepth, wantSpeed, false, urgent);
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
		int weapons = 0;
		for (SonarContact c : returns) {
			if (c == sub || !(c.range() > 0)) {
				continue;
			}
			weapons++;
			plot.observe(tick, x, y, echoX(c), echoY(c), c.estimatedDepth(), enemy.px(tick), enemy.py(tick),
					enemy.valid);
		}
		// Count the enemy's magazine down. Only weapons that cannot be ours are counted, and each
		// salvo by the most seen in one ping, so the count can run low but never high.
		int hostile = Math.max(0, weapons - ownWeaponsRunning());
		if (hostile > 0) {
			salvoPeak = Math.max(salvoPeak, hostile);
			lastHostileEchoTick = tick;
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
			if (!weapon && c.signalExcess() > 85 && tick - enemyPingTick > 100) {
				enemyPings++; // nothing but an active ping is that loud
				enemyPingTick = tick;
			}
			if (weapon) {
				torps++;
				if (c.signalExcess() > loudest) {
					loudest = c.signalExcess();
					loudestBearing = c.bearing();
				}
			}
		}
		if (torps > ownWeaponsRunning()) {
			// More weapons audible than we have running: one of them is not ours.
			suspectUntil = tick + 50 * 8;
			suspectBearing = loudestBearing;
		}
	}

	/** Loads every weapon that is coming our way into the escape planner. */
	private boolean gatherThreats() {
		escape.clearThreats();
		boolean any = false;
		for (WeaponPlot.Weapon w : plot.weapons) {
			if (!plot.isThreat(w, tick, x, y)) {
				continue;
			}
			escape.addThreat(w.px(tick), w.py(tick), Math.max(w.fuelLeft(tick), 0), TORPEDO_SPEED);
			any = true;
		}
		return any;
	}

	// ── Assessment ───────────────────────────────────────────────────

	/** Could we still get away if he fired from where he is now? */
	private void assessEscape(double ex, double ey, double bearing, double range, boolean enemyDry, double delay) {
		if (enemyDry) {
			escapable = comfortable = true;
			return;
		}
		// With some margin: as if he were a few hundred metres closer and his weapon a little faster.
		double closer = Math.min(600, range * 0.25);
		escape.clearThreats();
		escape.addThreat(ex - Math.sin(bearing) * closer, ey - Math.cos(bearing) * closer, Engagement.TORPEDO_FUEL,
				23.6);
		comfortable = escape.plan(x, y, heading, speed, hpRatio, delay, 14.0);
		if (comfortable) {
			escapable = true;
			return;
		}
		escape.clearThreats();
		escape.addThreat(ex, ey, Engagement.TORPEDO_FUEL, 23.2);
		escapable = escape.plan(x, y, heading, speed, hpRatio, 5.0, 8.0);
		if (!escapable) {
			adoptRoute(false);
		}
	}

	/** Could he get away if we fired now? */
	private void assessShot(double ex, double ey, double bearing) {
		canKill = straightHit = nearMiss = false;
		if (enemy.steadyFixes < 2) {
			return;
		}
		double eh = enemy.speed() > 1.0 ? enemy.heading() : Geo.norm(bearing + Math.PI);
		double ehp = Geo.clamp(enemyHp / config.startingHp(), 0.05, 1);
		canKill = sim.run(x, y, heading, speed + 3, ex, ey, eh, enemy.speed(), 0, ehp, true, 2.5,
				Engagement.TORPEDO_FUEL - 14);
		nearMiss = !canKill && sim.closest < 450;
		straightHit = canKill || (sim.run(x, y, heading, speed + 3, ex, ey, eh, enemy.speed(), 0, ehp, false, 0,
				Engagement.TORPEDO_FUEL - 14) && sim.hitTime < 185);
	}

	/** Watches what the enemy does after we shoot, to learn whether he is the kind that runs. */
	private void judgeLastSalvo() {
		if (salvoJudged) {
			return;
		}
		double since = (tick - lastLaunchTick) * 0.02;
		if (enemy.valid && enemy.hasVelocity() && enemy.fixAgeSeconds(tick) < 6 && enemy.speed() > 12.0
				&& since > 15 && since < 200) {
			enemyRuns = 1;
			enemyRunsObserved = true;
			salvoJudged = true;
		} else if (ownHits > hitsAtLastSalvo) {
			if (!enemyRunsObserved) {
				enemyRuns = -1;
				enemyRunsObserved = true;
			}
			salvoJudged = true;
		} else if (since > 260) {
			salvoJudged = true;
		}
	}

	// ── Weapons ──────────────────────────────────────────────────────

	private void considerFiring(SubmarineOutput out, double ex, double ey, double range, double bearing,
			boolean enemyDry) {
		if (torpedoes <= 0 || salvoLeft > 0 || enemyDead || enemy.fixAgeSeconds(tick) > 6.0 || range < 450) {
			return;
		}
		if (ownWeaponsRunning() > 0 || (tick - lastLaunchTick) < 50 * 20) {
			return; // let the weapons already running do their work first
		}
		// Closing rate: positive when the enemy comes toward us.
		double closing = -(enemy.vx * Math.sin(bearing) + enemy.vy * Math.cos(bearing));
		boolean fire;
		if (canKill) {
			fire = true; // he cannot outrun it
		} else if (enemyDry) {
			fire = range < 1100; // no hurry: get close enough that it is certain
		} else if (enemyRuns == 1) {
			// He runs. Spend only what we can afford on shots that need him to make a mistake.
			fire = nearMiss && straightHit && torpedoes > 4;
		} else if (enemyRuns == -1) {
			fire = straightHit;
		} else {
			fire = straightHit && enemy.hasVelocity() && (closing > 2.5 || range < 3600);
		}
		if (!fire || !waterBetween(ex, ey)) {
			return;
		}
		out.publishFiringSolution(new FiringSolution(ex, ey, enemy.heading(), enemy.speed(), canKill ? 1.0 : 0.6));
		salvoLeft = Math.min(2, torpedoes);
		nextShotTick = tick;
		hitsAtLastSalvo = ownHits;
		salvoJudged = false;
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
	 * is already hurt, otherwise big enough that two hits kill. Never so big that the weapon would
	 * go off beside our own bow when its safety lapses five seconds after leaving the tube.
	 */
	private double chooseFuse() {
		double hp = Math.max(enemyHp, 30.0);
		double single = 50.0 * (1.0 - Math.sqrt(hp / 1200.0));
		double pair = 50.0 * (1.0 - Math.sqrt(hp / 2400.0));
		double fuse = (single >= 12.0 ? single : pair) - 0.6;
		// Distance the weapon opens on us in five seconds (it leaves 3 m/s faster and accelerates to 23).
		double v0 = Math.max(speed, 0) + 3.0;
		double a0 = 0.5 * Math.log((23.0 + v0) / Math.max(23.0 - v0, 0.01));
		double run = 133.3 * Math.log(Math.cosh(a0 + 0.8625) / Math.cosh(a0));
		double separation = run - 5.0 * Math.min(Helm.MAX_SPEED, Math.max(speed, 0) + 1.2);
		fuse = Math.min(fuse, separation - 8.5);
		return Geo.clamp(fuse, config.minFuseRadius(), config.maxFuseRadius());
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

	/** Takes over the escape planner's route as the one to run. */
	private void adoptRoute(boolean isEscape) {
		runLength = Math.min(escape.routeLength, runX.length);
		System.arraycopy(escape.routeX, 0, runX, 0, runLength);
		System.arraycopy(escape.routeY, 0, runY, 0, runLength);
		runIndex = 0;
		runIsEscape = isEscape;
	}

	/** Heading along the adopted route, or NaN if there is none (left). */
	private double followRun() {
		while (runIndex < runLength - 1 && Math.hypot(runX[runIndex] - x, runY[runIndex] - y) < 260) {
			runIndex++;
		}
		if (runIndex >= runLength) {
			return Double.NaN;
		}
		if (runIndex == runLength - 1 && Math.hypot(runX[runIndex] - x, runY[runIndex] - y) < 150) {
			return Double.NaN;
		}
		return Geo.bearing(x, y, runX[runIndex], runY[runIndex]);
	}

	/** No route: run directly away from the nearest weapon (or the enemy), sea room permitting. */
	private double fallbackFlee() {
		double away = heading;
		double best = Double.MAX_VALUE;
		for (WeaponPlot.Weapon w : plot.weapons) {
			if (!w.live) {
				continue;
			}
			double d = Math.hypot(w.px(tick) - x, w.py(tick) - y);
			if (d < best) {
				best = d;
				away = Geo.bearing(w.px(tick), w.py(tick), x, y);
			}
		}
		if (best == Double.MAX_VALUE && enemy.valid) {
			away = Geo.bearing(enemy.px(tick), enemy.py(tick), x, y);
		} else if (best == Double.MAX_VALUE) {
			away = Geo.norm(suspectBearing + Math.PI);
		}
		return clearHeading(away, 700);
	}

	// ── Telemetry ────────────────────────────────────────────────────

	private void publish(SubmarineOutput out, double wantHeading, double wantDepth) {
		if (enemy.valid) {
			double age = enemy.fixAgeSeconds(tick);
			double alive = enemyDead ? 0.05 : Geo.clamp(1.0 - age / 120.0, 0.05, 1.0);
			out.publishContactEstimate(new ContactEstimate(enemy.px(tick), enemy.py(tick), alive * 0.9, alive,
					40 + 0.03 * enemy.lastFixRange + 15 * age, enemy.heading(), enemy.speed(), "track"));
		}
		for (WeaponPlot.Weapon w : plot.weapons) {
			if (w.live && !w.own) {
				out.publishContactEstimate(
						new ContactEstimate(w.px(tick), w.py(tick), 0.8, 1.0, 60, Double.NaN, 22, "torpedo"));
			}
		}
		if (mode.startsWith("EVADE") || mode.equals("OPEN")) {
			for (int i = runIndex; i < runLength; i++) {
				out.publishWaypoint(new Waypoint(runX[i], runY[i], wantDepth, i == runIndex));
			}
		} else {
			out.publishWaypoint(new Waypoint(x + Math.sin(wantHeading) * 400, y + Math.cos(wantHeading) * 400,
					Math.max(wantDepth, helm.floorCeilingZ), true));
		}
		if (enemy.valid) {
			out.publishStrategicWaypoint(new Waypoint(enemy.px(tick), enemy.py(tick), enemy.z, true),
					mode.startsWith("EVADE") ? Purpose.EVADE : Purpose.INTERCEPT);
		}
		if ((tick & 7) == 0) {
			double range = enemy.valid ? Math.hypot(enemy.px(tick) - x, enemy.py(tick) - y) : -1;
			out.setStatus(String.format(Locale.ROOT, "%s R%.0f %s%s%s%s e%d h%d r%d%s", mode, range,
					escapable ? "s" : "-", comfortable ? "m" : "-", canKill ? "K" : (straightHit ? "k" : "-"),
					nearMiss ? "n" : "", enemyLaunched + salvoPeak, ownHits, enemyRuns,
					helm.terrainAlarm ? " TERR" : ""));
		}
	}
}
