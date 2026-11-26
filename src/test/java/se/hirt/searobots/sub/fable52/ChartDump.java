package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.engine.WorldGenerator;

import java.util.Locale;

/** Lab-only: prints the chart and raw terrain around a point. */
public final class ChartDump {
	public static void main(String[] args) {
		long seed = Long.parseUnsignedLong(args[0], 16);
		double cx = Double.parseDouble(args[1]), cy = Double.parseDouble(args[2]);
		var config = MatchConfig.withDefaults(seed);
		var world = new WorldGenerator().generate(config);
		long t0 = System.nanoTime();
		var chart = new SeaChart(world.terrain(), config.battleArea());
		System.out.printf(Locale.ROOT, "chart build %.1f ms, n=%d%n", (System.nanoTime() - t0) / 1e6, chart.n);
		if (args.length >= 7) {
			double sx = Double.parseDouble(args[3]), sy = Double.parseDouble(args[4]);
			double gx = Double.parseDouble(args[5]), gy = Double.parseDouble(args[6]);
			var nav = new Navigator(chart);
			double h = nav.headingTo(0, sx, sy, gx, gy);
			System.out.printf(Locale.ROOT, "heading %.1f direct=%s aim=(%.0f,%.0f) costHere=%.2f costGoal=%.2f%n",
					Math.toDegrees(h), nav.direct, nav.aimX, nav.aimY, chart.costAt(sx, sy), chart.costAt(gx, gy));
			for (int i = 0; i < nav.remainingPoints(); i++) {
				System.out.printf(Locale.ROOT, "  route %d: (%.0f, %.0f)%n", i, nav.routeX(i), nav.routeY(i));
			}
			double dx = gx - sx, dy = gy - sy;
			for (double t = 0; t <= 1.0001; t += 0.02) {
				System.out.printf(Locale.ROOT, "  t=%.2f (%.0f,%.0f) cost=%.2f ceil=%.0f%n", t, sx + dx * t, sy + dy * t,
						chart.costAt(sx + dx * t, sy + dy * t), chart.ceilingAt(sx + dx * t, sy + dy * t));
			}
		}
		System.out.println("raw terrain (20 m grid), north up:");
		for (double y = cy + 200; y >= cy - 200; y -= 20) {
			var sb = new StringBuilder();
			for (double x = cx - 200; x <= cx + 200; x += 20) {
				sb.append(String.format(Locale.ROOT, "%5.0f", world.terrain().elevationAt(x, y)));
			}
			System.out.println(sb);
		}
		System.out.println("chart ceiling/cost (100 m cells), north up:");
		for (double y = cy + 400; y >= cy - 400; y -= 100) {
			var sb = new StringBuilder();
			for (double x = cx - 400; x <= cx + 400; x += 100) {
				sb.append(String.format(Locale.ROOT, " %5.0f/%3.1f", chart.ceilingAt(x, y), chart.costAt(x, y)));
			}
			System.out.println(sb);
		}
	}
}
