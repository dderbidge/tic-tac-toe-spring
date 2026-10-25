# Reproducible gameplay playtesters

`src/test/java/com/derbysquad/tictactoe/multiplayer/GameplayPlaytesterTest.java`
drives the actual local-game and multiplayer STOMP contract through a real
Spring Boot server on a random loopback port. Test agents remain separate from
production opponent AI and use an independent eight-line win oracle.

## Run

From the repository root with Java 21:

```powershell
.\gradlew.bat test --tests '*GameplayPlaytesterTest' --no-daemon
```

On Linux/macOS, use `./gradlew` with the same arguments. To run the full regression
suite and package the application, use `./gradlew build`. The tests create their
own server; do not start `bootRun` or target the live website. They use no external
accounts or paid APIs.

## Finite matrix

Seeds are **13, 2026, 104729**. Each failure includes the seed, ordered actions and
board before each move. Rerun that parameterized case/seed to reproduce it. Java
and .NET have different random generators, so matching seeds identify comparable
coverage rather than identical cross-language move sequences.

| Profile / path | Total rounds | Checks |
| --- | ---: | --- |
| Random and tactical hot-seat players over STOMP | 24 | Legal turn counts, one-cell mutation, out-of-range/repeated/terminal rejection, valid winner/draw, scores once per round, replay, reset, menu interruption, session isolation |
| Human agents versus production Warlord over STOMP, both seats | 6 | Random seed 13 and tactical seeds 2026/104729; Warlord never loses; no occupied-cell overwrite, bounded completion, valid scores |
| Random and tactical network peers over STOMP | 6 | Lobby/challenge/accept, legal game to result, invalid/repeated input, reconnect after two plies, board preservation, spectator isolation, match cleanup |

The tactical player takes an immediate win, blocks a one-move loss, takes the
center, then chooses a seeded legal cell. It does not reuse production AI. The
tests therefore check the AI through its actual gameplay interface.

Each round is bounded by nine legal moves; each awaited push has a five-second
timeout through `StompTestSupport`. Per-seed budgets are thirty seconds for the
local matrix, thirty seconds for the AI matrix, sixty seconds for the network
matrix, plus an outer 120-second JUnit limit. These are generous CI regression
budgets, not load-test or end-user latency guarantees.

## Evidence and boundaries

Gradle writes XML (including `PLAYTEST PASS` summaries or failure action traces)
under `build/test-results/test/` and an HTML report under
`build/reports/tests/test/`. Save those with the revision to reproduce failures.
The focused reconnect, scheduled timer and backend security suites cover expiry,
dual disconnects, bad tokens and adversarial cross-session access in greater
detail; this matrix adds longer user-like sequences around those checks.

The separate `tools/playtest/browser-smoke.mjs` runner uses tool-bound browser
tabs for rendered keyboard/control paths and screenshots. This JUnit suite is
**headless real transport gameplay**, not rendered browser acceptance. Bots do
not establish subjective fun, polish or novice comprehension. It is a finite
sample, not exhaustive proof or a production soak/load benchmark. Server state
is intentionally in memory, so a server process restart cannot resume old rounds.

## Rendered browser runner

The reusable browser runner takes an existing, authorized `cua_repl` tab; it does
not launch a browser or attach to the user's accounts. Start a local test server,
open its menu in a dedicated tool-controlled tab, then call:

```javascript
const runner = await import("file:///ABSOLUTE/REPOSITORY/tools/playtest/browser-smoke.mjs");
const result = await runner.runLocalSmoke(tab, { seed: 20261025 });
```

It verifies a keyboard-entered win, nine labeled cell buttons, disabled occupied
cells, a cleared replay board, and two seeded random rounds with legal turn counts.
Its ordered DOM-backed trace and elapsed times are returned for saving alongside
the tested revision. The 90-second cap is a regression budget. An optional
`capture(name, jpegBytes)` callback receives screenshots at checkpoints and on
failure. A thrown error carries the failure trace as `error.playtest`.

The October 2026 verification used this runner through the supported browser tool,
plus separate two-tab challenge, reconnect and forfeit checks. The existing
Playwright smoke suite remains a separate option; do not treat it as executed
merely because the tool-controlled runner passed.

