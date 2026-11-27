package se.hirt.searobots.sub.fable52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.api.SubmarineController;
import se.hirt.searobots.api.VehicleConfig;
import se.hirt.searobots.engine.SimulationListener;
import se.hirt.searobots.engine.SimulationListeners;
import se.hirt.searobots.engine.SimulationLoop;
import se.hirt.searobots.engine.SimulationLoop.ControllerLimits;
import se.hirt.searobots.engine.SubmarineSnapshot;
import se.hirt.searobots.engine.TorpedoSnapshot;
import se.hirt.searobots.engine.WorldGenerator;
import se.hirt.searobots.engine.replay.ReplayWriter;
import se.hirt.searobots.engine.ships.DefaultAttackSub;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quick checks that the submarine builds the way the league builds it, behaves under the league's
 * guardrails, and can fight. Strength is measured with the lab ({@code ./lab.sh}), not here.
 */
class Fable52SubmarineTest {

	/**
	 * The league compiles {@code src/main/java} with bare javac against the API jar and nothing
	 * else.
	 */
	@Test
	void compilesStandaloneWithOnlySeaRobotsApi() throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertTrue(compiler != null, "Tests must run on a JDK (not a JRE)");

		Path api = Path.of(SubmarineController.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		List<String> sources;
		try (var stream = Files.walk(Path.of("src", "main", "java"))) {
			sources = stream.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
		}
		assertFalse(sources.isEmpty(), "Expected Java sources under src/main/java");
		assertTrue(sources.size() <= 64, "League limit is 64 source files");

		Path out = Files.createTempDirectory("fable52-javac-check");
		var args = new ArrayList<>(List.of("--release", "25", "-proc:none", "-cp", api.toString(), "-d", out.toString()));
		args.addAll(sources);
		var err = new ByteArrayOutputStream();
		int exit = compiler.run(null, null, err, args.toArray(String[]::new));
		assertEquals(0, exit, "Standalone javac against searobots-api failed:\n" + err.toString(StandardCharsets.UTF_8));
	}

	/**
	 * Alone on the map for ten minutes: no grounding damage, no excursion out of the battle area,
	 * no guardrail trips, and a small CPU bill.
	 */
	@Test
	void sailsAloneWithoutDamageUnderLeagueLimits() {
		for (long seed : new long[] {0x2cL, 0x5eaL, 0xf00dL}) {
			var config = MatchConfig.withDefaults(seed).withMatchDurationTicks(10 * 60 * 50);
			var world = new WorldGenerator().generate(config);
			var sim = new SimulationLoop();
			sim.setSpeedMultiplier(1e9);
			sim.setControllerLimits(ControllerLimits.leagueDefaults());
			var hp = new AtomicInteger(config.startingHp());
			sim.run(world, List.of(new Fable52Submarine()), List.of(VehicleConfig.submarine()), new SimulationListener() {
				@Override
				public void onTick(long tick, List<SubmarineSnapshot> subs, List<TorpedoSnapshot> torps) {
					hp.set(subs.getFirst().hp());
				}

				@Override
				public void onMatchEnd() {
				}
			});
			assertEquals("", sim.forfeitReason(0), "No guardrail may trip (seed " + Long.toHexString(seed) + ")");
			assertEquals(config.startingHp(), hp.get(), "No damage when sailing alone (seed " + Long.toHexString(seed) + ")");
			assertTrue(sim.controllerCpuNanos(0) < 3_000_000_000L, "CPU use must stay far below the league budget");
		}
	}

	/**
	 * A full match against the engine's default submarine, recorded to
	 * {@code target/replays/fable52-vs-default.srl} for {@code mvn -Preplay}.
	 */
	@Test
	void fightsDefaultSubmarineAndRecordsReplay() throws Exception {
		var config = MatchConfig.withDefaults(0x13L).withMatchDurationTicks(40 * 60 * 50);
		var world = new WorldGenerator().generate(config);
		var sim = new SimulationLoop();
		sim.setSpeedMultiplier(1e9);
		sim.setControllerLimits(ControllerLimits.leagueDefaults());
		sim.setTicksAfterDecision(10 * 50);

		Path replay = Path.of("target", "replays", "fable52-vs-default.srl");
		Files.createDirectories(replay.getParent());
		var frames = new AtomicInteger();
		var counter = new SimulationListener() {
			@Override
			public void onTick(long tick, List<SubmarineSnapshot> subs, List<TorpedoSnapshot> torps) {
				frames.incrementAndGet();
			}

			@Override
			public void onMatchEnd() {
			}
		};
		try (var writer = new ReplayWriter(config, world.spawnPoints(), replay)) {
			sim.run(world, List.of(new Fable52Submarine(), new DefaultAttackSub()),
					List.of(VehicleConfig.submarine(), VehicleConfig.submarine()),
					SimulationListeners.composite(counter, writer));
		}
		assertEquals("", sim.forfeitReason(0), "Fable 5.2 must not trip a league guardrail");
		assertTrue(frames.get() > 100, "Expected the simulation to run");
		assertTrue(Files.isRegularFile(replay) && Files.size(replay) > 0, "Expected a replay at " + replay);
		var outcome = sim.matchOutcome();
		assertTrue(outcome != null && outcome.controllers().get(0).hp() > 0, "Fable 5.2 should survive this match");
	}

	@Test
	void helmStopsTurnsOnTheWantedHeading() {
		// The turn rate asked for must shrink to nothing as the heading error does, and never
		// exceed what the rudder can hold.
		double limit = Helm.maxYawRate(10.0, 1.0);
		assertTrue(limit > 0.05 && limit < 0.1, "about four degrees a second at ten metres a second");
		assertEquals(0.0, Helm.wantedRate(0.0, limit), 1e-12);
		assertTrue(Helm.wantedRate(Math.PI, limit) <= limit);
		assertTrue(Helm.wantedRate(-Math.PI, limit) >= -limit);
		assertTrue(Helm.wantedRate(0.02, limit) < Helm.wantedRate(0.5, limit));
		// From the rate it asks for at a given error, the boat can still stop within that error.
		for (double error : new double[] {0.05, 0.3, 1.0, 2.5}) {
			double rate = Helm.rateForAngle(error, limit);
			double x = rate / limit;
			double stoppingAngle = Helm.TAU * limit * (x - Math.log1p(x));
			assertEquals(error, stoppingAngle, 1e-6);
		}
	}

	@Test
	void anglesWrapAsExpected() {
		assertEquals(0.0, Geo.norm(Geo.TWO_PI), 1e-12);
		assertEquals(Math.PI / 2, Geo.bearing(0, 0, 10, 0), 1e-12);
		assertEquals(0.0, Geo.bearing(0, 0, 0, 10), 1e-12);
		assertEquals(-0.2, Geo.diff(0.1, Geo.TWO_PI - 0.1), 1e-12);
		assertEquals(Math.PI, Math.abs(Geo.wrap(Math.PI)), 1e-12);
	}

	@Test
	void torpedoInterceptTimeIsConsistent() {
		// Target dead ahead running away at 10 m/s, weapon at 20 m/s, 1000 m astern: 100 s.
		assertEquals(100.0, Fable52Torpedo.interceptTime(0, 1000, 0, 10, 20), 1e-6);
		// Stationary target.
		assertEquals(50.0, Fable52Torpedo.interceptTime(600, 800, 0, 0, 20), 1e-6);
	}
}
