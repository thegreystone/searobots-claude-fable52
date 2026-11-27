# SeaRobots Fable 5.2 Submarine

An autonomous submarine and torpedo controller for [SeaRobots](https://github.com/thegreystone/searobots),
written from scratch by Claude Fable 5.2.

It is a **clean-room implementation**. The author studied the public API and the simulation
engine (physics, sonar, damage and match rules), and learned about the built-in opponents only by
running headless matches against them and watching what they did. Their source code (everything
under the engine's `claude`, `codex` and `gemini` packages) was never read. The
[searobots-template](https://github.com/thegreystone/searobots-template) was used to learn how a
submarine plugs into the API and for the build files; none of its tactics code is used.

## Results

Head-to-head against the four built-in submarines, league rules (alternating spawns, CPU
guardrails, two-hour limit). Lab runs of 96 matches per opponent on maps the tuning never saw,
plus the official `mvn -Pmatch33` profile:

| Opponent | Lab, 96 matches (W–L–D) | `-Pmatch33 -Dseed=202612` (W–L–D) |
|---|---|---|
| Codex   | 92–0–4  | 33–0–0 |
| Claude  | 91–0–5  | 30–0–3 |
| Default | 92–0–3 (one match dropped, see below) | not run |
| Gemini  | between 62–15–16 and 72–15–7 depending on the map set | 26–5–2 |

Gemini is by far the strongest built-in (it beats Codex and Claude about 11–0 in a dozen games)
and is the one opponent that still wins a meaningful share. Controller CPU use is around 0.5–2 s
per match, peaking near 3 s against the league cap of 30 s.

One caveat about measuring: the engine's hull-on-seabed contact solver occasionally slows to a
crawl when a submarine runs itself onto a beach (the Default submarine does this now and then).
The lab gives up on such a match after 100 s of wall time and reports it as dropped;
`mvn -Pmatch33` has no such guard and can sit on one match for a long time.

## How it fights

Three facts of the simulation shape everything:

* **Active sonar sees everything in a straight line.** A ping returns range, bearing and depth
  of every submarine and torpedo not hidden behind rock, at any distance on the map. Passive
  listening cannot compete, so the boat pings every five seconds and accepts being heard.
* **A torpedo duel is decided before anyone fires.** A torpedo is half again as fast as a
  submarine but burns for only four minutes, and a submarine needs most of a minute to turn
  around. Whether a shot connects is a matter of range, of where the target's bow points, of how
  fast it is already going and of how much sea room it has.
* **The hull answers the helm slowly.** Turn and pitch rates build up over about thirty seconds.
  Steering and depth keeping are built on the hull's own response model instead of chasing an
  error signal.

From these the boat's behaviour follows:

1. **Ask two questions all the time.** *If the enemy fired now, could I still get away?*
   (`EscapePlanner`) and *if I fired now, could he?* (`Engagement`). The boat approaches while the
   first answer is a comfortable yes, opens the range when it is not, and fires when the second
   answer is no. Against an opponent that has not shown that it runs from torpedoes, a straight
   shot that will get there is enough.
2. **Run the moment a weapon is in the water.** Every ping places every torpedo; anything that
   is closing and has the fuel to arrive triggers a full-speed run along a route worked out on a
   chart of the sea floor: through cells the boat can reach before any weapon possibly could,
   until the weapons have burned out.
3. **Read the opponent.** One that pings constantly is a hunter and gets shot at long range. A
   silent one probably runs from torpedoes (and snipes): against it the boat holds fire for shots
   it cannot outrun, counts its launches, and once its magazine is empty walks up and finishes
   it. If it gets into a position from which the boat could not escape a shot, the boat fires a
   single torpedo first: a runner breaks off and runs, and the tables are turned.
4. **Two hits kill, so make hits count.** Blast damage falls off with distance from the hull.
   The proximity fuse is set to the largest radius at which two hits are still certain to sink
   (about 17 m), which forgives a near miss; once the enemy is hurt, the largest radius that
   finishes it with one. It is never set so large that the weapon would go off beside the boat's
   own bow when its launch safety lapses.
5. **Torpedoes cannot tell friend from foe.** Each weapon pings once a second and follows both
   hulls it can hear, the target and its own launcher, in separate tracks. An echo only counts as
   the target if it lies where the target could plausibly have gone and is not explained as well
   by the launcher; when in doubt it is ignored. The weapon runs straight out of the tube until
   it has room to turn, runs in shallow where sound carries over ridges, comes down onto the
   target late, and destroys itself when spent.
6. **Never touch the bottom, never leave the area.** The helm runs its own turn model a minute
   ahead, keeps the boat shallow enough to clear the sea floor under the path it is about to
   sweep, turns the other way round or slows down when that is safer, and backs off as a last
   resort. Near the edge of the battle area nothing is allowed to point the boat outward.

What was learned along the way, from the lab rather than from theory: on a flat, open ocean this
boat beats Gemini 96–0 and none of 760 Gemini torpedoes hit it. Every loss comes from terrain
getting in the way of the run, and almost all hits land when the boat has let itself be cornered
(no way out if the enemy fires *now*). Most of the tuning effort went into finding that out;
several plausible redesigns (holding station in open water, never showing the enemy the bow,
running straight away instead of along a planned route, wider safety margins) were built, measured
and thrown away because they lost matches.

## Code map

| File | What it does |
|---|---|
| `Fable52Submarine` | The controller: sensing, opponent model, posture, fire control |
| `Fable52Torpedo` | Torpedo guidance: two-track echo association, intercept steering, safety |
| `Helm` | Heading, depth and speed control on the hull's response model; grounding and boundary protection |
| `SeaChart` | 100 m navigation chart: passable water, sea room, connected regions, A* routes |
| `Navigator` | Follows chart routes toward a goal |
| `EscapePlanner` | Finds a route that stays ahead of every torpedo until it runs dry |
| `Engagement` | Kinematic what-if: does a torpedo fired now catch a target that runs? |
| `EnemyTrack` | Straight-line track of the enemy through active sonar fixes |
| `WeaponPlot` | Plot of torpedoes in the water and which of them are coming |
| `Lookout` | Remembers which water recent pings have covered, proposes where to look next |
| `Geo` | Angle helpers |

Everything under `src/main/java` is plain Java 25 against `searobots-api` only, as the league
requires (11 files, about 105 KB).

## Building and running

Requires JDK 25+ and Maven 3.9+.

| Command | What it does |
|---|---|
| `mvn test` | Unit tests, the bare-`javac` build check, a solo run and a recorded match against Default |
| `mvn -Pcheck` | League pre-flight (bare `javac`, bytecode rules, guarded smoke matches) |
| `mvn -Pmatch33 -Dopponent=gemini -Dseed=202612 -Dthreads=12` | Official 33-match sparring series |
| `mvn -Pwatch -Dopponent=gemini` | Watch a live match in the 3D viewer |
| `mvn -Preplay` | Replay the match recorded by `mvn test` |
| `mvn -Pmanifest` | Print a league registration manifest for the current commit |

## The lab

`lab.sh` is a fast loop around `src/test/java/.../lab/Lab.java`. It compiles the submarine the way
the league does and runs matches headless, many at a time, reporting ground truth: who fired from
where, what each torpedo did, why a submarine died.

```bash
./lab.sh series --a fable --b gemini --games 96 --seed 77 --threads 20   # a series with statistics
./lab.sh series --a fable --b gemini --games 96 --seed 77 --flat         # same, on an ocean without terrain
./lab.sh trace  --a fable --b gemini --seed 395142ca8efaf003 --swap --every 10 --torp   # one match in detail
./lab.sh nav    --games 48 --seed 7 --minutes 40                         # solo navigation: no grounding, no forfeits
```

A series ends with a breakdown of every enemy torpedo fired at the boat: by the sea room the boat
had, by where its bow pointed, by what it was doing and by launch range, each with how many hit.
Each lost match gets a post-mortem line per hit (how late the boat reacted, how fast it got up to
speed, how much ground it actually made good). Those tables are what the tactics were tuned on.
Other submarines are only ever observed from the outside: positions, published status strings and
weapon behaviour.

`ChartDump`, `EscapeDump` and `RoomStats` next to the tests are small command-line aids for looking
at the chart, replaying an escape plan for a hand-fed situation, and measuring how much open water
generated maps have.

## License

BSD-3-Clause, see [LICENSE](LICENSE).
