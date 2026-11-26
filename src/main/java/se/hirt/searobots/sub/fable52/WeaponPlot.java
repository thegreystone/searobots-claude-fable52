package se.hirt.searobots.sub.fable52;

/**
 * Plot of torpedoes in the water, built from the boat's own active sonar returns. Each ping places
 * every weapon; following them from ping to ping tells which are closing.
 * <p>
 * Whose weapon it is matters less than where it is going: the boat's own torpedoes cannot tell
 * friend from foe either. Anything that closes fast and has the legs to arrive is a threat. A
 * weapon that first shows up right beside the enemy is counted against his magazine.
 */
final class WeaponPlot {

	static final int MAX = 24;

	static final class Weapon {
		boolean live;
		/** First seen leaving our own tubes. */
		boolean own;
		/** First seen next to the enemy submarine. */
		boolean hostile;
		double x, y, z, vx, vy;
		long tick, firstTick;
		/** Best guess of the tick it left its tube. */
		long launchTick;
		double range;
		/** Rate at which it is closing on us (m/s, positive = approaching); NaN until seen twice. */
		double closing = Double.NaN;
		int sightings;
		int misses;

		double fuelLeft(long now) {
			return Engagement.TORPEDO_FUEL - (now - launchTick) * 0.02;
		}

		double px(long now) {
			return x + vx * (now - tick) * 0.02;
		}

		double py(long now) {
			return y + vy * (now - tick) * 0.02;
		}
	}

	final Weapon[] weapons = new Weapon[MAX];
	private int pendingOwn;
	private long ownLaunchTick = -1;
	private double ownLaunchX, ownLaunchY;
	/** Hostile weapons ever seen (to count down the enemy's magazine). */
	int hostileSeen;

	WeaponPlot() {
		for (int i = 0; i < MAX; i++) {
			weapons[i] = new Weapon();
		}
	}

	void reset() {
		for (Weapon w : weapons) {
			w.live = false;
		}
		pendingOwn = 0;
		ownLaunchTick = -1;
		hostileSeen = 0;
	}

	void noteOwnLaunch(long tick, double x, double y) {
		pendingOwn++;
		ownLaunchTick = tick;
		ownLaunchX = x;
		ownLaunchY = y;
	}

	/** Call once before feeding the returns of a ping. */
	void beginPing() {
		for (Weapon w : weapons) {
			if (w.live) {
				w.misses++;
			}
		}
	}

	/** Feeds one torpedo echo from the current ping. */
	void observe(long tick, double selfX, double selfY, double x, double y, double z, double enemyX, double enemyY,
			boolean enemyKnown) {
		Weapon best = null;
		double bestD = Double.MAX_VALUE;
		for (Weapon w : weapons) {
			if (!w.live) {
				continue;
			}
			double dt = (tick - w.tick) * 0.02;
			double gate = 50 + 28 * dt;
			double d = Math.hypot(x - (w.x + w.vx * dt), y - (w.y + w.vy * dt));
			double plain = Math.hypot(x - w.x, y - w.y);
			if (Math.min(d, plain) < gate && d < bestD) {
				bestD = d;
				best = w;
			}
		}
		double range = Math.hypot(x - selfX, y - selfY);
		if (best != null) {
			double dt = (tick - best.tick) * 0.02;
			if (dt > 0.5) {
				best.vx = (x - best.x) / dt;
				best.vy = (y - best.y) / dt;
				best.closing = (best.range - range) / dt;
			}
			best.x = x;
			best.y = y;
			best.z = z;
			best.range = range;
			best.tick = tick;
			best.sightings++;
			best.misses = 0;
			return;
		}
		Weapon slot = null;
		for (Weapon w : weapons) {
			if (!w.live) {
				slot = w;
				break;
			}
		}
		if (slot == null) {
			return;
		}
		slot.live = true;
		slot.x = x;
		slot.y = y;
		slot.z = z;
		slot.vx = slot.vy = 0;
		slot.tick = slot.firstTick = tick;
		slot.range = range;
		slot.closing = Double.NaN;
		slot.sightings = 1;
		slot.misses = 0;
		double sinceLaunch = ownLaunchTick < 0 ? 1e9 : (tick - ownLaunchTick) * 0.02;
		slot.own = pendingOwn > 0 && sinceLaunch < 16
				&& Math.hypot(x - ownLaunchX, y - ownLaunchY) < 26 * sinceLaunch + 130;
		slot.hostile = false;
		slot.launchTick = tick;
		if (slot.own) {
			pendingOwn--;
			slot.launchTick = ownLaunchTick;
		} else if (enemyKnown && Math.hypot(x - enemyX, y - enemyY) < 420) {
			slot.hostile = true;
			hostileSeen++;
		}
	}

	/** Drops weapons that are spent or have not echoed for a while. */
	void expire(long tick) {
		for (Weapon w : weapons) {
			if (!w.live) {
				continue;
			}
			double unseen = (tick - w.tick) * 0.02;
			if ((w.misses >= 3 && unseen > 12) || unseen > 40 || w.fuelLeft(tick) < -12) {
				w.live = false;
			}
		}
		if (pendingOwn > 0 && ownLaunchTick >= 0 && (tick - ownLaunchTick) * 0.02 > 20) {
			pendingOwn = 0;
		}
	}

	/** True if the weapon is coming our way and has the legs to arrive. */
	boolean isThreat(Weapon w, long tick, double selfX, double selfY) {
		if (!w.live) {
			return false;
		}
		double fuel = w.fuelLeft(tick);
		if (fuel <= -3) {
			return false;
		}
		double range = Math.hypot(w.px(tick) - selfX, w.py(tick) - selfY);
		boolean coming;
		if (Double.isNaN(w.closing)) {
			coming = w.hostile; // just launched at us; assume the worst until the next ping
		} else if (w.own) {
			// Our own weapon: only if it has plainly turned back on us.
			coming = w.closing > 10 && range < 900 && (tick - w.firstTick) > 50 * 20;
		} else {
			coming = w.closing > 4 || (w.hostile && w.sightings < 3 && w.closing > -4);
			if (coming && !w.hostile && w.closing > 10 && range < 3000) {
				// Not seen leaving his tubes, but it is plainly his: count it against his magazine.
				w.hostile = true;
				hostileSeen++;
			}
		}
		return coming && range <= 23.0 * Math.max(fuel, 0) + 300;
	}
}
