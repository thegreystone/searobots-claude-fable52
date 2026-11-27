package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.engine.WorldGenerator;

import java.util.Locale;
import java.util.SplittableRandom;

/** Lab-only: how much open water do generated maps have? */
public final class RoomStats {
	public static void main(String[] a) {
		var rng = new SplittableRandom(Long.parseUnsignedLong(a[0], 16));
		int maps = Integer.parseInt(a[1]);
		int[] histMax = new int[6];
		double fracSum = 0;
		for (int m = 0; m < maps; m++) {
			long seed = rng.nextLong();
			var config = MatchConfig.withDefaults(seed);
			var world = new WorldGenerator().generate(config);
			var chart = new SeaChart(world.terrain(), config.battleArea());
			double max = 0;
			int open = 0, water = 0;
			for (int r = 0; r < chart.n; r++) {
				for (int c = 0; c < chart.n; c++) {
					double x = chart.cellX(c), y = chart.cellY(r);
					if (config.battleArea().distanceToBoundary(x, y) < 0) {
						continue;
					}
					water++;
					double room = chart.roomAt(x, y);
					max = Math.max(max, room);
					if (room >= 1150) {
						open++;
					}
				}
			}
			histMax[(int) Math.min(5, max / 500)]++;
			fracSum += (double) open / water;
			if (m < 12) {
				var s0 = world.spawnPoints().get(0);
				var s1 = world.spawnPoints().get(1);
				System.out.printf(Locale.ROOT, "%016x maxRoom=%.0f open(>=1150m)=%.0f%% spawnRoom=%.0f/%.0f%n", seed, max,
						100.0 * open / water, chart.roomAt(s0.x(), s0.y()), chart.roomAt(s1.x(), s1.y()));
			}
		}
		System.out.printf(Locale.ROOT, "maps=%d  mean open fraction=%.0f%%  max room histogram (<500,<1000,<1500,<2000,<2500,>=2500): %d %d %d %d %d %d%n",
				maps, 100 * fracSum / maps, histMax[0], histMax[1], histMax[2], histMax[3], histMax[4], histMax[5]);
	}
}
