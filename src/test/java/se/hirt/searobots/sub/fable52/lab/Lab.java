package se.hirt.searobots.sub.fable52.lab;

import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.api.SubmarineController;
import se.hirt.searobots.api.Vec3;
import se.hirt.searobots.api.VehicleConfig;
import se.hirt.searobots.engine.ControllerLoader;
import se.hirt.searobots.engine.ControllerLoader.ResolvedController;
import se.hirt.searobots.engine.GeneratedWorld;
import se.hirt.searobots.engine.HullGeometry;
import se.hirt.searobots.engine.SimulationListener;
import se.hirt.searobots.engine.SimulationLoop;
import se.hirt.searobots.engine.SimulationLoop.ControllerLimits;
import se.hirt.searobots.engine.SubmarineSnapshot;
import se.hirt.searobots.engine.TorpedoSnapshot;
import se.hirt.searobots.engine.WorldGenerator;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Headless observation lab. Runs league-rules matches between two controllers and reports ground
 * truth: who fired from where, what each torpedo did, why a submarine died. Two modes:
 *
 * <pre>
 *   Lab series --a fable --b claude --games 40 --seed 1 --threads 12
 *   Lab trace  --a fable --b claude --seed 1f3 [--swap] [--every 10] [--minutes 120]
 * </pre>
 *
 * Opponents are only ever observed from the outside: positions, published status strings and
 * weapon behaviour. Nothing here looks inside another controller.
 */
public final class Lab {

	private static final PrintStream OUT = System.out;
	private static final Map<Integer, Game> RUNNING = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long MAX_GAME_WALL_NANOS = 100_000_000_000L;
	private static boolean TORP_TRACE;
	private static boolean FLAT;
	/** Enemy torpedoes fired at A and how many hit, by how much sea room A had when they were fired. */
	private static final int[] ROOM_SHOTS = new int[5], ROOM_HITS = new int[5];
	private static final int[] ASPECT_SHOTS = new int[4], ASPECT_HITS = new int[4];
	private static final int[] SPEED_SHOTS = new int[3], SPEED_HITS = new int[3];
	private static final int[] RANGE_SHOTS = new int[6], RANGE_HITS = new int[6];
	private static final Map<String, int[]> MODE_STATS = new TreeMap<>();

	/** Distance from a point to the nearest shoal (floor above -78 m) or to the battle area boundary. */
	private static double seaRoom(GeneratedWorld world, double x, double y) {
		double best = world.config().battleArea().distanceToBoundary(x, y);
		var terrain = world.terrain();
		for (double r = 50; r < Math.min(best, 2000); r += 50) {
			int n = Math.max(8, (int) (2 * Math.PI * r / 50));
			for (int i = 0; i < n; i++) {
				double a = i * 2 * Math.PI / n;
				if (terrain.elevationAt(x + Math.sin(a) * r, y + Math.cos(a) * r) > -78) {
					return r;
				}
			}
		}
		return Math.min(best, 2000);
	}

	/**
	 * The engine's hull-on-seabed contact solver can lock up for minutes inside a single tick.
	 * Reports any game whose simulation has stopped advancing, with the last state seen, and
	 * returns how many are stuck.
	 */
	private static int reportStuck(java.util.Set<Integer> reported) {
		int stuck = 0;
		for (var g : RUNNING.values()) {
			if (g.finished || System.nanoTime() - g.lastWall < 8_000_000_000L) {
				continue;
			}
			stuck++;
			if (!reported.add(g.number)) {
				continue;
			}
			var sb = new StringBuilder();
			var subs = g.lastSubs;
			if (subs != null) {
				for (var s : subs) {
					var p = s.pose().position();
					sb.append(String.format(Locale.ROOT, " [%s hp=%d (%.0f,%.0f,%.1f) hdg=%.0f pitch=%.1f v=%.2f '%s']",
							s.name(), s.hp(), p.x(), p.y(), p.z(), Math.toDegrees(s.pose().heading()),
							Math.toDegrees(s.pose().pitch()), s.speed(), s.status()));
				}
			}
			OUT.printf(Locale.ROOT, "STUCK #%02d seed=%016x %s tick=%d%s%n", g.number, g.seed, g.swapped ? "swp" : "",
					g.lastTick, sb);
			OUT.flush();
		}
		return stuck;
	}

	private Lab() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			OUT.println("usage: Lab series|trace --a <spec> --b <spec> [--games N] [--seed hex] [--threads N]"
					+ " [--minutes N] [--swap] [--every seconds] [--quiet] [--no-limits]");
			return;
		}
		String mode = args[0];
		String a = "fable";
		String b = "claude";
		int games = 24;
		long seed = 1;
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
		int minutes = 120;
		boolean swap = false;
		int every = 10;
		boolean limits = true;
		boolean verbose = false;
		for (int i = 1; i < args.length; i++) {
			switch (args[i]) {
			case "--a" -> a = args[++i];
			case "--b" -> b = args[++i];
			case "--games" -> games = Integer.parseInt(args[++i]);
			case "--seed" -> seed = Long.parseUnsignedLong(args[++i], 16);
			case "--threads" -> threads = Integer.parseInt(args[++i]);
			case "--minutes" -> minutes = Integer.parseInt(args[++i]);
			case "--swap" -> swap = true;
			case "--every" -> every = Integer.parseInt(args[++i]);
			case "--no-limits" -> limits = false;
			case "--verbose" -> verbose = true;
			case "--torp" -> TORP_TRACE = true;
			case "--flat" -> FLAT = true;

			default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
			}
		}
		// The engine logs torpedo fates to stdout; keep our own report readable.
		System.setOut(new PrintStream(OutputStream.nullOutputStream()));
		var ca = resolve(a);
		var cb = resolve(b);
		if (mode.equals("nav")) {
			nav(games, seed, threads, minutes, every);
			OUT.flush();
			return;
		}
		if (mode.equals("trace")) {
			var g = play(1, seed, swap, ca, cb, minutes, limits, every, true);
			OUT.println(g.summaryLine());
		} else {
			series(ca, cb, games, seed, threads, minutes, limits, verbose);
		}
		OUT.flush();
	}

	static ResolvedController resolve(String spec) {
		if (spec.equalsIgnoreCase("fable")) {
			spec = "se.hirt.searobots.sub.fable52.Fable52Submarine";
		}
		return ControllerLoader.resolve(spec);
	}

	// ── Solo navigation test ─────────────────────────────────────────

	static void nav(int games, long masterSeed, int threads, int minutes, int everySeconds) throws Exception {
		var rng = new SplittableRandom(masterSeed);
		var lines = new ArrayList<Future<String>>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < games; i++) {
				final long seed = games == 1 ? masterSeed : rng.nextLong();
				final boolean trace = games == 1;
				lines.add(pool.submit(() -> navOne(seed, minutes, trace ? everySeconds : 0)));
			}
			for (var f : lines) {
				OUT.println(f.get());
			}
		}
	}

	static String navOne(long seed, int minutes, int everySeconds) {
		MatchConfig config = MatchConfig.withDefaults(seed).withMatchDurationTicks(minutes * 60 * 50);
		GeneratedWorld world = new WorldGenerator().generate(config);
		var sim = new SimulationLoop();
		sim.setSpeedMultiplier(1e9);
		sim.setControllerLimits(ControllerLimits.leagueDefaults());
		var probe = new se.hirt.searobots.sub.fable52.NavProbe();
		int[] hp = {config.startingHp()};
		boolean[] forfeited = {false};
		double[] minEdge = {1e9};
		sim.run(world, List.of(probe), List.of(VehicleConfig.submarine()), new SimulationListener() {
			@Override
			public void onTick(long tick, List<SubmarineSnapshot> subs, List<TorpedoSnapshot> torps) {
				var s = subs.getFirst();
				var p = s.pose().position();
				minEdge[0] = Math.min(minEdge[0], config.battleArea().distanceToBoundary(p.x(), p.y()));
				if (s.hp() < hp[0] && everySeconds > 0) {
					OUT.printf(Locale.ROOT, "[%5.0fs] DAMAGE %d -> hp %d at (%.0f,%.0f,%.0f) floor=%.0f v=%.1f '%s'%n",
							tick / 50.0, hp[0] - s.hp(), s.hp(), p.x(), p.y(), p.z(),
							world.terrain().elevationAt(p.x(), p.y()), s.speed(), s.status());
				}
				hp[0] = s.hp();
				forfeited[0] = s.forfeited();
				if (everySeconds > 0 && tick % (everySeconds * 50L) == 0) {
					OUT.printf(Locale.ROOT,
							"[%5.0fs] (%6.0f,%6.0f,%5.0f) fl=%5.0f h=%03.0f pitch=%+5.1f v=%4.1f SL=%3.0f thr=%+.2f rud=%+.2f pl=%+.2f '%s'%n",
							tick / 50.0, p.x(), p.y(), p.z(), world.terrain().elevationAt(p.x(), p.y()),
							Math.toDegrees(s.pose().heading()), Math.toDegrees(s.pose().pitch()), s.speed(),
							s.sourceLevelDb(), s.throttle(), s.rudder(), s.sternPlanes(), s.status());
				}
			}

			@Override
			public void onMatchEnd() {
			}
		});
		return String.format(Locale.ROOT,
				"NAV %016x hp=%4d%s goals=%2d worstClearance=%5.1f minEdge=%5.0f alarms=%d cpu=%dms %s", seed, hp[0],
				forfeited[0] ? " FORFEIT(" + sim.forfeitReason(0) + ")" : "", probe.goalsReached, probe.worstClearance,
				minEdge[0], probe.alarms, sim.controllerCpuNanos(0) / 1_000_000L,
				hp[0] < config.startingHp() || forfeited[0] ? "<<<<<" : "");
	}

	// ── Series ───────────────────────────────────────────────────────

	static void series(ResolvedController a, ResolvedController b, int games, long masterSeed, int threads,
			int minutes, boolean limits, boolean verbose) throws Exception {
		var rng = new SplittableRandom(masterSeed);
		long[] seeds = new long[games];
		for (int i = 0; i < games; i++) {
			seeds[i] = rng.nextLong();
		}
		long t0 = System.currentTimeMillis();
		var results = new ArrayList<Game>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			var futures = new ArrayList<Future<Game>>();
			for (int i = 0; i < games; i++) {
				final int n = i + 1;
				final long s = seeds[i];
				futures.add(pool.submit(() -> play(n, s, n % 2 == 0, a, b, minutes, limits, 0, false)));
			}
			var reported = new java.util.HashSet<Integer>();
			var pending = new ArrayList<>(futures);
			while (!pending.isEmpty()) {
				for (var it = pending.iterator(); it.hasNext();) {
					var f = it.next();
					if (f.isDone()) {
						it.remove();
						var g = f.get();
						results.add(g);
						if (verbose || g.winner != 1) {
							OUT.println(g.summaryLine());
							OUT.flush();
						}
					}
				}
				if (!pending.isEmpty()) {
					Thread.sleep(500);
					if (reportStuck(reported) >= pending.size()) {
						OUT.printf(Locale.ROOT, "%d game(s) stuck in the engine; abandoning them%n", pending.size());
						abandoned = pending.size();
						break;
					}
				}
			}
			if (abandoned > 0) {
				games -= abandoned;
				summarize(a, b, games, results, t0);
				OUT.flush();
				System.exit(3);
			}
		}
		summarize(a, b, games, results, t0);
	}

	private static int abandoned;

	private static void summarize(ResolvedController a, ResolvedController b, int games, List<Game> results, long t0) {
		int w = 0, l = 0, d = 0;
		int slow = 0;
		for (var g : results) {
			if (g.slow) {
				slow++;
			}
		}
		if (slow > 0) {
			results = results.stream().filter(g -> !g.slow).toList();
			games -= slow;
			OUT.printf(Locale.ROOT, "(%d game(s) dropped: engine too slow to finish)%n", slow);
		}
		for (var g : results) {
			if (g.winner == 1) {
				w++;
			} else if (g.winner == -1) {
				l++;
			} else {
				d++;
			}
		}
		var sa = new SideStats();
		var sb = new SideStats();
		long cpuMax = 0;
		double cpuSum = 0;
		double winTime = 0;
		for (var g : results) {
			sa.add(g.side[0]);
			sb.add(g.side[1]);
			cpuMax = Math.max(cpuMax, g.side[0].cpuMs);
			cpuSum += g.side[0].cpuMs;
			if (g.winner == 1) {
				winTime += g.ticks / 50.0;
			}
		}
		OUT.printf(Locale.ROOT, "RESULT %s vs %s: %d-%d-%d (W-L-D) of %d  win%%=%.1f  avgWinTime=%.0fs  wall=%.0fs%n",
				a.displayName(), b.displayName(), w, l, d, games, 100.0 * w / games, w > 0 ? winTime / w : 0,
				(System.currentTimeMillis() - t0) / 1000.0);
		OUT.printf(Locale.ROOT, "  %-10s %s  cpuAvg=%.0fms cpuMax=%dms%n", a.shortName(), sa, cpuSum / games, cpuMax);
		OUT.printf(Locale.ROOT, "  %-10s %s%n", b.shortName(), sb);
		String[] names = {"<300m", "300-600m", "600-1000m", "1000-1500m", ">1500m"};
		var room = new StringBuilder("  torpedoes fired at A by A's sea room at launch (hits/fired):");
		for (int i = 0; i < 5; i++) {
			room.append(String.format(Locale.ROOT, "  %s %d/%d", names[i], ROOM_HITS[i], ROOM_SHOTS[i]));
		}
		OUT.println(room);
		OUT.printf(Locale.ROOT,
				"  ... by where A's bow pointed (enemy off the bow):  0-45deg %d/%d  45-90deg %d/%d  90-135deg %d/%d  135-180deg %d/%d%n",
				ASPECT_HITS[0], ASPECT_SHOTS[0], ASPECT_HITS[1], ASPECT_SHOTS[1], ASPECT_HITS[2], ASPECT_SHOTS[2],
				ASPECT_HITS[3], ASPECT_SHOTS[3]);
		var modes = new StringBuilder("  ... by what A was doing:");
		for (var e : MODE_STATS.entrySet()) {
			modes.append(String.format(Locale.ROOT, "  %s %d/%d", e.getKey(), e.getValue()[1], e.getValue()[0]));
		}
		OUT.println(modes);
		OUT.printf(Locale.ROOT,
				"  ... by launch range:  <2500 %d/%d  2500-3000 %d/%d  3000-3500 %d/%d  3500-4000 %d/%d  4000-4500 %d/%d  >4500 %d/%d%n",
				RANGE_HITS[0], RANGE_SHOTS[0], RANGE_HITS[1], RANGE_SHOTS[1], RANGE_HITS[2], RANGE_SHOTS[2], RANGE_HITS[3],
				RANGE_SHOTS[3], RANGE_HITS[4], RANGE_SHOTS[4], RANGE_HITS[5], RANGE_SHOTS[5]);
		OUT.printf(Locale.ROOT, "  ... by A's speed:  <6 m/s %d/%d  6-11 m/s %d/%d  >11 m/s %d/%d%n", SPEED_HITS[0],
				SPEED_SHOTS[0], SPEED_HITS[1], SPEED_SHOTS[1], SPEED_HITS[2], SPEED_SHOTS[2]);
	}

	static final class SideStats {
		int fired, hits, deathsTorpedo, deathsOther, forfeits, otherDamage;
		double hitRangeSum, missRangeSum;
		int missCount;

		void add(Side s) {
			fired += s.fired;
			hits += s.hits;
			hitRangeSum += s.hitRangeSum;
			missRangeSum += s.missRangeSum;
			missCount += s.missCount;
			otherDamage += s.otherDamage;
			if (s.forfeit != null) {
				forfeits++;
			} else if (s.dead) {
				if (s.killedByTorpedo) {
					deathsTorpedo++;
				} else {
					deathsOther++;
				}
			}
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT,
					"fired=%d hits=%d (%.0f%%) hitRange=%.0f missRange=%.0f | died: torpedo=%d other=%d forfeit=%d"
							+ " | nonTorpedoDmg=%d",
					fired, hits, fired > 0 ? 100.0 * hits / fired : 0, hits > 0 ? hitRangeSum / hits : 0,
					missCount > 0 ? missRangeSum / missCount : 0, deathsTorpedo, deathsOther, forfeits, otherDamage);
		}
	}

	// ── One game ─────────────────────────────────────────────────────

	static final class Side {
		String name;
		int fired, hits, missCount, otherDamage, hp;
		double hitRangeSum, missRangeSum;
		boolean dead, killedByTorpedo;
		String forfeit;
		long cpuMs;
		String deathNote = "";
	}

	static final class Game {
		int number;
		long seed;
		boolean swapped;
		volatile List<SubmarineSnapshot> lastSubs;
		volatile long lastTick;
		volatile long lastWall = System.nanoTime();
		final long startWall = System.nanoTime();
		volatile boolean finished;
		final StringBuilder postMortem = new StringBuilder();
		boolean slow;
		int winner; // 1 = a, -1 = b, 0 = draw
		String reason;
		long ticks;
		Side[] side = {new Side(), new Side()};

		String summaryLine() {
			String outcome = slow ? "----" : winner > 0 ? "WIN " : winner < 0 ? "LOSS" : "DRAW";
			return String.format(Locale.ROOT,
					"#%02d %016x %s %s %-7s %5ds  hp %4d/%4d  fired %d/%d hits %d/%d  cpu %dms  %s%s", number, seed,
					swapped ? "swp" : "   ", outcome, reason, ticks / 50, side[0].hp, side[1].hp, side[0].fired,
					side[1].fired, side[0].hits, side[1].hits, side[0].cpuMs,
					side[0].forfeit != null ? "A-FORFEIT(" + side[0].forfeit + ") " : "",
					(side[0].deathNote + " " + side[1].deathNote).trim()) + (winner < 0 ? postMortem : "");
		}
	}

	static final class TorpTrack {
		int id, owner;
		long launchTick;
		double launchRange, minRange = Double.MAX_VALUE, speedSum, launchOffBow;
		long samples;
		int pings;
		long firstPingTick = -1;
		double targetSpeedAtLaunch;
		double victimSpeed, victimRel, victimX, victimY;
		String victimStatus;
		double victimRoom;
		boolean victimIsA;
		long evadeTick = -1, fastTick = -1;
	}

	static Game play(int number, long seed, boolean swap, ResolvedController a, ResolvedController b, int minutes,
			boolean limits, int everySeconds, boolean trace) {
		var slot0 = swap ? b : a;
		var slot1 = swap ? a : b;
		MatchConfig config = MatchConfig.withDefaults(seed).withMatchDurationTicks(minutes * 60 * 50);
		final GeneratedWorld world = FLAT ? flatten(new WorldGenerator().generate(config))
				: new WorldGenerator().generate(config);
		var sim = new SimulationLoop();
		sim.setSpeedMultiplier(1e9);
		if (limits) {
			sim.setControllerLimits(ControllerLimits.leagueDefaults());
		}
		List<SubmarineController> controllers = List.of(slot0.factory().get(), slot1.factory().get());
		List<VehicleConfig> vehicles = List.of(slot0.vehicleConfig(), slot1.vehicleConfig());

		var game = new Game();
		game.number = number;
		game.seed = seed;
		game.swapped = swap;
		RUNNING.put(number, game);
		// index 0 = controller a, index 1 = controller b
		int[] slotOf = swap ? new int[] {1, 0} : new int[] {0, 1};
		int[] sideOfSlot = swap ? new int[] {1, 0} : new int[] {0, 1};
		game.side[0].name = a.shortName();
		game.side[1].name = b.shortName();
		String[] tag = {swap ? "B" : "A", swap ? "A" : "B"};

		if (trace) {
			OUT.printf(Locale.ROOT, "TRACE seed=%016x  A=%s (slot %d)  B=%s (slot %d)%n", seed, a.displayName(),
					slotOf[0], b.displayName(), slotOf[1]);
			for (var layer : world.thermalLayers()) {
				OUT.printf(Locale.ROOT, "  thermal layer core=%.0f top=%.0f bottom=%.0f dT=%.1f%n", layer.depth(),
						layer.top(), layer.bottom(), layer.gradient());
			}
			for (int i = 0; i < world.spawnPoints().size(); i++) {
				Vec3 s = world.spawnPoints().get(i);
				OUT.printf(Locale.ROOT, "  spawn slot %d (%s): (%.0f, %.0f, %.0f) floor=%.0f%n", i, tag[i], s.x(), s.y(),
						s.z(), world.terrain().elevationAt(s.x(), s.y()));
			}
		}

		var listener = new SimulationListener() {
			final Map<Integer, TorpTrack> tracks = new HashMap<>();
			final int[] lastHp = {config.startingHp(), config.startingHp()};
			final Map<String, Integer>[] statusTicks = newMaps();
			final int[] pings = new int[2];
			final long[] lastPingTick = {-1, -1};

			@SuppressWarnings("unchecked")
			private Map<String, Integer>[] newMaps() {
				return new Map[] {new TreeMap<String, Integer>(), new TreeMap<String, Integer>()};
			}

			@Override
			public void onTick(long tick, List<SubmarineSnapshot> subs, List<TorpedoSnapshot> torps) {
				game.lastSubs = subs;
				game.lastTick = tick;
				game.lastWall = System.nanoTime();
				if (game.lastWall - game.startWall > MAX_GAME_WALL_NANOS) {
					sim.stop(); // the engine is crawling (usually a hull grinding on a beach)
				}
				for (var s : subs) {
					String st = s.status() == null ? "" : s.status().replaceAll("[-+]?[0-9]*\\.?[0-9]+", "#");
					statusTicks[s.id()].merge(st, 1, Integer::sum);
					if (s.pingRequested()) {
						pings[s.id()]++;
						if (trace && (lastPingTick[s.id()] < 0 || tick - lastPingTick[s.id()] > 1500)) {
							OUT.printf(Locale.ROOT, "[%5.0fs] %s PING (first in a while) range=%.0f%n", tick / 50.0,
									tag[s.id()], range(subs));
						}
						lastPingTick[s.id()] = tick;
					}
				}
				for (var t : torps) {
					var tr = tracks.get(t.id());
					var target = subs.get(1 - t.ownerId());
					var owner = subs.get(t.ownerId());
					double r = dist(t.pose().position(), target.pose().position());
					if (tr == null) {
						tr = new TorpTrack();
						tr.id = t.id();
						tr.owner = t.ownerId();
						tr.launchTick = tick;
						tr.launchRange = dist(owner.pose().position(), target.pose().position());
						double brg = Math.atan2(target.pose().position().x() - owner.pose().position().x(),
								target.pose().position().y() - owner.pose().position().y());
						tr.launchOffBow = Math.toDegrees(Math.abs(wrap(brg - owner.pose().heading())));
						tr.targetSpeedAtLaunch = target.speed();
						tracks.put(t.id(), tr);
						game.side[sideOfSlot[t.ownerId()]].fired++;
						tr.victimSpeed = target.speed();
						tr.victimRel = Math.toDegrees(wrap(Math.atan2(owner.pose().position().x() - target.pose().position().x(),
								owner.pose().position().y() - target.pose().position().y()) - target.pose().heading()));
						tr.victimStatus = target.status();
						tr.victimRoom = seaRoom(world, target.pose().position().x(), target.pose().position().y());
						tr.victimIsA = sideOfSlot[target.id()] == 0;
						tr.victimX = target.pose().position().x();
						tr.victimY = target.pose().position().y();
						if (trace) {
							OUT.printf(Locale.ROOT,
									"[%5.0fs] %s LAUNCH torp %d  range=%.0f offBow=%.0fdeg targetSpd=%.1f ownSpd=%.1f"
											+ " ownZ=%.0f tgtZ=%.0f status='%s'%n",
									tick / 50.0, tag[t.ownerId()], t.id(), tr.launchRange, tr.launchOffBow,
									target.speed(), owner.speed(), owner.pose().position().z(),
									target.pose().position().z(), owner.status());
						}
					}
					if (trace && TORP_TRACE && tick % 250 == 0 && t.alive()) {
						var tp = t.pose().position();
						var gp = target.pose().position();
						double estErr = Double.isNaN(t.diagEstX()) ? -1 : Math.hypot(t.diagEstX() - gp.x(), t.diagEstY() - gp.y());
						double ownRange = dist(tp, owner.pose().position());
						OUT.printf(Locale.ROOT,
								"         torp %d (%s) pos=(%.0f,%.0f,%.0f) fl=%.0f v=%.1f h=%03.0f pitch=%+.0f fuel=%.0f rTgt=%.0f rOwn=%.0f estErr=%.0f tgtZ=%.0f estZ=%.0f %s%n",
								t.id(), tag[t.ownerId()], tp.x(), tp.y(), tp.z(), world.terrain().elevationAt(tp.x(), tp.y()),
								t.speed(), Math.toDegrees(t.pose().heading()), Math.toDegrees(t.pose().pitch()),
								t.fuelRemaining(), r, ownRange, estErr, gp.z(), t.diagEstZ(), t.diagPhase());
					}
					if (tr.evadeTick < 0 && target.status() != null && target.status().startsWith("EVADE")) {
						tr.evadeTick = tick;
					}
					if (tr.fastTick < 0 && target.speed() > 13.5 && tick > tr.launchTick + 50) {
						tr.fastTick = tick;
					}
					tr.minRange = Math.min(tr.minRange, r);
					tr.speedSum += t.speed();
					tr.samples++;
					if (t.pingRequested()) {
						tr.pings++;
						if (tr.firstPingTick < 0) {
							tr.firstPingTick = tick;
						}
					}
					if (!t.alive()) {
						// Final snapshot of this torpedo.
						double hull = HullGeometry.bowDistanceToHull(t, target);
						double ownHull = HullGeometry.bowDistanceToHull(t, owner);
						int dmg = lastHp[target.id()] - target.hp();
						int ownDmg = lastHp[owner.id()] - owner.hp();
						var side = game.side[sideOfSlot[t.ownerId()]];
						boolean hit = t.detonated() && dmg > 0;
						if (tr.victimIsA) {
							int bucket = tr.victimRoom < 300 ? 0 : tr.victimRoom < 600 ? 1 : tr.victimRoom < 1000 ? 2 : tr.victimRoom < 1500 ? 3 : 4;
							int aspect = (int) Math.min(3, Math.abs(tr.victimRel) / 45.0);
							int fast = tr.victimSpeed < 6 ? 0 : tr.victimSpeed < 11 ? 1 : 2;
							String modeKey = tr.victimStatus == null || tr.victimStatus.isEmpty() ? "?" : tr.victimStatus.split(" ")[0];
							int rangeKey = (int) Math.min(5, Math.max(0, (tr.launchRange - 2000) / 500));
							synchronized (ROOM_SHOTS) {
								MODE_STATS.computeIfAbsent(modeKey, k -> new int[2])[0]++;
								RANGE_SHOTS[rangeKey]++;
								if (hit) {
									MODE_STATS.get(modeKey)[1]++;
									RANGE_HITS[rangeKey]++;
								}
								ROOM_SHOTS[bucket]++;
								ASPECT_SHOTS[aspect]++;
								SPEED_SHOTS[fast]++;
								if (hit) {
									ROOM_HITS[bucket]++;
									ASPECT_HITS[aspect]++;
									SPEED_HITS[fast]++;
								}
							}
						}
						if (hit && sideOfSlot[target.id()] == 0) {
							// Our boat was hit: how did the run go?
							double life = (tick - tr.launchTick) / 50.0;
							double moved = Math.hypot(target.pose().position().x() - tr.victimX,
									target.pose().position().y() - tr.victimY);
							game.postMortem.append(String.format(Locale.ROOT,
									"%n    HIT by torp %d: launched R=%.0f at t=%.0fs, room=%.0fm, we were v=%.1f rel=%+.0f '%s'; evade after %.0fs, flank after %.0fs; flight %.0fs fuelLeft=%.0f; we made good %.0fm (%.1f m/s) dmg=%d",
									t.id(), tr.launchRange, tr.launchTick / 50.0, tr.victimRoom, tr.victimSpeed, tr.victimRel, tr.victimStatus,
									tr.evadeTick < 0 ? -1 : (tr.evadeTick - tr.launchTick) / 50.0,
									tr.fastTick < 0 ? -1 : (tr.fastTick - tr.launchTick) / 50.0, life, t.fuelRemaining(), moved,
									moved / Math.max(life, 1), dmg));
						}
						if (hit) {
							side.hits++;
							side.hitRangeSum += tr.launchRange;
							if (target.hp() <= 0) {
								game.side[sideOfSlot[target.id()]].killedByTorpedo = true;
							}
						} else {
							side.missCount++;
							side.missRangeSum += tr.launchRange;
						}
						if (t.detonated() && ownDmg > 0 && owner.hp() <= 0) {
							game.side[sideOfSlot[owner.id()]].killedByTorpedo = true;
							game.side[sideOfSlot[owner.id()]].deathNote = "(own torpedo)";
						}
						if (trace) {
							OUT.printf(Locale.ROOT,
									"[%5.0fs] %s TORP %d END %s after %.0fs  launchRange=%.0f minRange=%.0f hullDist=%.1f"
											+ " dmg=%d ownDmg=%d(ownHull %.0f) avgSpd=%.1f pings=%d firstPing=+%.0fs fuel=%.0f"
											+ " z=%.0f floor=%.0f%n",
									tick / 50.0, tag[t.ownerId()], t.id(),
									t.detonated() ? (hit ? "HIT" : "DETONATED-MISS") : "LOST",
									(tick - tr.launchTick) / 50.0, tr.launchRange, tr.minRange, hull, dmg, ownDmg, ownHull,
									tr.speedSum / Math.max(1, tr.samples), tr.pings,
									tr.firstPingTick < 0 ? -1 : (tr.firstPingTick - tr.launchTick) / 50.0,
									t.fuelRemaining(), t.pose().position().z(),
									world.terrain().elevationAt(t.pose().position().x(), t.pose().position().y()));
						}
					}
				}
				// Damage not explained by a torpedo detonating this tick.
				boolean anyDetonation = false;
				for (var t : torps) {
					if (t.detonated()) {
						anyDetonation = true;
					}
				}
				for (var s : subs) {
					int lost = lastHp[s.id()] - s.hp();
					if (lost > 0 && !anyDetonation) {
						var side = game.side[sideOfSlot[s.id()]];
						side.otherDamage += lost;
						double floor = world.terrain().elevationAt(s.pose().position().x(), s.pose().position().y());
						if (trace && (lost >= 5 || s.hp() <= 0)) {
							OUT.printf(Locale.ROOT, "[%5.0fs] %s NON-TORPEDO DAMAGE %d (hp %d) z=%.0f floor=%.0f spd=%.1f%n",
									tick / 50.0, tag[s.id()], lost, s.hp(), s.pose().position().z(), floor, s.speed());
						}
						if (s.hp() <= 0) {
							side.deathNote = String.format(Locale.ROOT, "(%s died: z=%.0f floor=%.0f)", side.name,
									s.pose().position().z(), floor);
						}
					}
					lastHp[s.id()] = s.hp();
				}
				if (trace && everySeconds > 0 && tick % (everySeconds * 50L) == 0) {
					var sb = new StringBuilder();
					sb.append(String.format(Locale.ROOT, "[%5.0fs] R=%5.0f occ=%3.0f", tick / 50.0, range(subs),
							occlusion(world, subs.get(0).pose().position(), subs.get(1).pose().position())));
					for (int side = 0; side < 2; side++) {
						var s = subs.get(slotOf[side]);
						var p = s.pose().position();
						var other = subs.get(1 - s.id()).pose().position();
						double brg = Math.atan2(other.x() - p.x(), other.y() - p.y());
						String est = "";
						if (!s.contactEstimates().isEmpty()) {
							var ce = s.contactEstimates().getFirst();
							est = String.format(Locale.ROOT, " estErr=%.0f(n=%d)", Math.hypot(ce.x() - other.x(), ce.y() - other.y()),
									s.contactEstimates().size());
						}
						sb.append(String.format(Locale.ROOT,
								" | %s (%5.0f,%5.0f,%4.0f) fl=%4.0f h=%03.0f rel=%+04.0f v=%4.1f SL=%3.0f hp=%4d t=%d%s%s '%s'",
								side == 0 ? "A" : "B", p.x(), p.y(), p.z(), world.terrain().elevationAt(p.x(), p.y()),
								Math.toDegrees(s.pose().heading()), Math.toDegrees(wrap(brg - s.pose().heading())),
								s.speed(), s.sourceLevelDb(), s.hp(), s.torpedoesRemaining(),
								s.firingSolution() != null ? " FS" : "", est, s.status()));
					}
					OUT.println(sb);
				}
			}

			@Override
			public void onMatchEnd() {
				if (trace) {
					for (int side = 0; side < 2; side++) {
						int slot = slotOf[side];
						OUT.printf(Locale.ROOT, "STATUS HISTOGRAM %s (%s), pings=%d:%n", side == 0 ? "A" : "B",
								game.side[side].name, pings[slot]);
						statusTicks[slot].entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(25)
								.forEach(e -> OUT.printf(Locale.ROOT, "    %7.0fs  %s%n", e.getValue() / 50.0, e.getKey()));
					}
				}
			}
		};

		sim.run(world, controllers, vehicles, listener);
		game.finished = true;
		var result = sim.matchOutcome();
		if (result == null) {
			game.reason = "SLOW";
			game.slow = true;
			game.ticks = game.lastTick;
			return game;
		}
		game.ticks = result.tick() + 1;
		boolean[] alive = new boolean[2];
		for (int side = 0; side < 2; side++) {
			var r = result.controllers().get(slotOf[side]);
			alive[side] = r.hp() > 0 && !r.forfeited();
			game.side[side].hp = Math.max(0, r.hp());
			game.side[side].dead = r.hp() <= 0;
			game.side[side].cpuMs = r.cpuNanos() / 1_000_000L;
			if (r.forfeited()) {
				game.side[side].forfeit = r.forfeitReason().isEmpty() ? "forfeited" : r.forfeitReason();
			}
		}
		if (alive[0] && !alive[1]) {
			game.winner = 1;
			game.reason = game.side[1].forfeit != null ? "FORFEIT" : "KILL";
		} else if (alive[1] && !alive[0]) {
			game.winner = -1;
			game.reason = game.side[0].forfeit != null ? "FORFEIT" : "KILL";
		} else if (!alive[0] && !alive[1]) {
			game.reason = "MUTUAL";
		} else {
			game.reason = "TIMEOUT";
		}
		return game;
	}

	/** Control experiment: same spawn points and water, but no terrain at all. */
	private static GeneratedWorld flatten(GeneratedWorld generated) {
		var t = generated.terrain();
		double[] flat = new double[t.getCols() * t.getRows()];
		java.util.Arrays.fill(flat, -600.0);
		return new GeneratedWorld(generated.config(),
				new se.hirt.searobots.api.TerrainMap(flat, t.getCols(), t.getRows(), t.getOriginX(), t.getOriginY(),
						t.getCellSize()),
				generated.thermalLayers(), generated.currentField(), generated.spawnPoints());
	}

	/** One-way sound loss (dB) from terrain and thermal layers between two points, as the engine models it. */
	private static double occlusion(GeneratedWorld world, Vec3 a, Vec3 b) {
		var terrain = world.terrain();
		double dx = b.x() - a.x(), dy = b.y() - a.y();
		double dist = Math.hypot(dx, dy);
		double step = terrain.getCellSize() * 0.5;
		int samples = Math.max(2, (int) Math.ceil(dist / step));
		double total = 0;
		for (int i = 1; i < samples; i++) {
			double t = (double) i / samples;
			double floor = terrain.elevationAt(a.x() + dx * t, a.y() + dy * t);
			double line = a.z() + (b.z() - a.z()) * t;
			if (floor > line) {
				total += floor >= 0 ? 15.0 : 7.5 * Math.min((floor - line) / 50.0, 1.0);
			}
		}
		for (var layer : world.thermalLayers()) {
			boolean aAbove = a.z() > layer.top(), aBelow = a.z() < layer.bottom();
			boolean bAbove = b.z() > layer.top(), bBelow = b.z() < layer.bottom();
			double max = Math.clamp(3.0 + 0.5 * layer.gradient(), 5.0, 12.0);
			if ((aAbove && bAbove) || (aBelow && bBelow)) {
				total -= 2;
			} else if (aAbove != bAbove && aBelow != bBelow) {
				total += max;
			} else if (layer.thickness() >= 1) {
				total += max * Math.abs(Math.clamp(a.z(), layer.bottom(), layer.top())
						- Math.clamp(b.z(), layer.bottom(), layer.top())) / layer.thickness();
			} else {
				total += max;
			}
		}
		return total;
	}

	private static double range(List<SubmarineSnapshot> subs) {
		return dist(subs.get(0).pose().position(), subs.get(1).pose().position());
	}

	private static double dist(Vec3 a, Vec3 b) {
		return a.distanceTo(b);
	}

	private static double wrap(double a) {
		while (a > Math.PI) {
			a -= 2 * Math.PI;
		}
		while (a < -Math.PI) {
			a += 2 * Math.PI;
		}
		return a;
	}
}
