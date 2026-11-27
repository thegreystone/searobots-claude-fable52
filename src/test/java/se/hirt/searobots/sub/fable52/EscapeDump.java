package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.engine.WorldGenerator;

import java.util.Locale;

/** Lab-only: runs the escape planner for a hand-fed situation and prints what it decides. */
public final class EscapeDump {
	public static void main(String[] a) {
		long seed = Long.parseUnsignedLong(a[0], 16);
		double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]);
		double heading = Math.toRadians(Double.parseDouble(a[3])), speed = Double.parseDouble(a[4]);
		var config = MatchConfig.withDefaults(seed);
		var world = new WorldGenerator().generate(config);
		var chart = new SeaChart(world.terrain(), config.battleArea());
		var planner = new EscapePlanner(chart);
		for (int i = 5; i + 2 < a.length; i += 3) {
			planner.addThreat(Double.parseDouble(a[i]), Double.parseDouble(a[i + 1]), Double.parseDouble(a[i + 2]), 22.5);
		}
		long t0 = System.nanoTime();
		boolean ok = planner.plan(x, y, heading, speed, 1.0, 0.5, 5.0);
		System.out.printf(Locale.ROOT, "ok=%s escapeSeconds=%.0f route points=%d (%.2f ms)%n", ok, planner.escapeSeconds,
				planner.routeLength, (System.nanoTime() - t0) / 1e6);
		double px = x, py = y;
		for (int i = 0; i < planner.routeLength; i++) {
			double rx = planner.routeX[i], ry = planner.routeY[i];
			System.out.printf(Locale.ROOT, "  -> (%.0f, %.0f) brg=%03.0f leg=%.0fm cost=%.1f edge=%.0f%n", rx, ry,
					Math.toDegrees(Geo.bearing(px, py, rx, ry)), Math.hypot(rx - px, ry - py), chart.costAt(rx, ry),
					config.battleArea().distanceToBoundary(rx, ry));
			px = rx;
			py = ry;
		}
		System.out.println("free run by heading (m, max 4000) and chart cost 1 km out:");
		for (int d = 0; d < 360; d += 30) {
			double h = Math.toRadians(d);
			System.out.printf(Locale.ROOT, "  %03d: %5.0f  cost=%.1f%n", d, chart.freeRun(x, y, h, 4000),
					chart.costAt(x + Math.sin(h) * 1000, y + Math.cos(h) * 1000));
		}
	}
}
