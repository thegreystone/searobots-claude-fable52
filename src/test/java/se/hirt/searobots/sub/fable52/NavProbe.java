package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.MatchContext;
import se.hirt.searobots.api.SubmarineController;
import se.hirt.searobots.api.SubmarineInput;
import se.hirt.searobots.api.SubmarineOutput;

import java.util.SplittableRandom;

/**
 * Lab-only controller that exercises the helm and navigator: it visits random reachable points at
 * random speeds and depths, with abrupt goal changes, so groundings and boundary excursions show up.
 */
public class NavProbe implements SubmarineController {

	public static volatile boolean quietMode;

	private SeaChart chart;
	private Helm helm;
	private Navigator nav;
	private SplittableRandom rng;
	private double goalX, goalY, goalDepth, goalSpeed;
	private long goalTick;
	public int goalsReached;
	public double worstClearance = 1e9;
	public double maxDepthError;
	public int alarms;

	@Override
	public String name() {
		return "NavProbe";
	}

	@Override
	public void onMatchStart(MatchContext context) {
		chart = new SeaChart(context.terrain(), context.config().battleArea());
		helm = new Helm(context.terrain(), context.config().battleArea(), chart, context.config().startingHp());
		nav = new Navigator(chart);
		rng = new SplittableRandom(context.config().worldSeed());
		goalTick = -1;
	}

	private void newGoal(long tick, double x, double y) {
		double extent = chart.area.extent();
		for (int i = 0; i < 200; i++) {
			double gx = (rng.nextDouble() * 2 - 1) * extent;
			double gy = (rng.nextDouble() * 2 - 1) * extent;
			if (chart.passable(gx, gy) && Math.hypot(gx - x, gy - y) > 1500) {
				goalX = gx;
				goalY = gy;
				break;
			}
		}
		goalDepth = -60 - rng.nextDouble() * 300;
		goalSpeed = 3 + rng.nextDouble() * 12;
		goalTick = tick;
	}

	@Override
	public void onTick(SubmarineInput input, SubmarineOutput output) {
		var pos = input.self().pose().position();
		long tick = input.tick();
		if (goalTick < 0 || Math.hypot(goalX - pos.x(), goalY - pos.y()) < 250) {
			if (goalTick >= 0) {
				goalsReached++;
			}
			newGoal(tick, pos.x(), pos.y());
		} else if (tick - goalTick > 50 * 500) {
			newGoal(tick, pos.x(), pos.y()); // abrupt change of mind
		}
		double heading = nav.headingTo(tick, pos.x(), pos.y(), goalX, goalY);
		if (Double.isNaN(heading)) {
			heading = input.self().pose().heading();
		}
		if (Math.hypot(nav.aimX - pos.x(), nav.aimY - pos.y()) < 150 && !nav.direct) {
			newGoal(tick, pos.x(), pos.y()); // as close as we can get
		}
		helm.drive(input, output, heading, goalDepth, goalSpeed, quietMode);
		double clearance = pos.z() - chart.terrain.elevationAt(pos.x(), pos.y());
		worstClearance = Math.min(worstClearance, clearance);
		if (helm.terrainAlarm) {
			alarms++;
		}
		output.setStatus(String.format("NAV g=%.0f,%.0f z=%.0f v=%.1f%s", goalX, goalY, goalDepth, goalSpeed,
				helm.terrainAlarm ? " ALARM" : ""));
	}
}
