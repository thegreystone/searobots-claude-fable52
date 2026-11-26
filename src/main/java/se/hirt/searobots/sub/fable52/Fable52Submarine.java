package se.hirt.searobots.sub.fable52;

import se.hirt.searobots.api.SubmarineController;
import se.hirt.searobots.api.SubmarineInput;
import se.hirt.searobots.api.SubmarineOutput;

/** Placeholder while the observation lab is being built. */
public class Fable52Submarine implements SubmarineController {

	@Override
	public String name() {
		return "Fable 5.2";
	}

	@Override
	public String shortName() {
		return "F5";
	}

	@Override
	public void onTick(SubmarineInput input, SubmarineOutput output) {
		output.setThrottle(0.1);
		output.setBallast(0.5);
	}
}
