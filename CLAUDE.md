# CLAUDE.md — Tic-Tac-Toe Spring Boot Port

## Project goal

Port the **.NET 9** tic-tac-toe solution at **`../legacy-csharp`** to **Java 21 + Spring Boot 3.x (Gradle, Kotlin DSL)**.
Full source analysis: **`MIGRATION_ANALYSIS.md`** (read it before any task).

Work splits by layer, each with a different strategy:

| Layer | Strategy |
|---|---|
| Core domain (`Board`, minimax `Ai`, `ThemeText`) | **TRANSLATE** to framework-free POJOs/records |
| Console app | **PORT fully** — this is the first vertical slice |
| Multiplayer | **REDESIGN** — Spring STOMP/WebSocket backend + thin JS client; Blazor Server per-circuit model is dropped |
| Single-player Blazor WASM PWA | **OUT OF SCOPE for now** (future work) |

## Decisions (already made — do not re-litigate)

- **Persistence:** in-memory `ConcurrentHashMap` services. No DB. Redis is future-only.
- **Scaling:** single instance assumed.
- **Identity:** STOMP session ID, no auth — mirrors the C# connection-ID model.

## C# → Java / Spring mapping

| C# / .NET | Java / Spring Boot |
|---|---|
| Dependency-free domain lib (`core/`) | Plain Java package — **zero Spring imports** |
| Singleton registries (lock-guarded `Dictionary`) | Singleton `@Service` beans wrapping `ConcurrentHashMap` |
| Scoped per-circuit game state | **Session-keyed `ConcurrentHashMap`** (NOT `@Scope("websocket")` — keep lookup explicit & predictable) |
| SignalR hub `/gamehub` | Spring STOMP: `@MessageMapping` for the 9 callable methods |
| SignalR server pushes (~11 events) | `SimpMessagingTemplate` / `@SendTo` / `convertAndSendToUser` |
| Anonymous SignalR payloads | **Explicit shared DTO records** — one per method and per event |
| `TICTACTOE_SEED` static env read | `@ConfigurationProperties` + a seeded `Random` `@Bean` injected into the AI (no static global state) |
| `System.Threading.Timer` + `event` bridge | `@Scheduled` + `ApplicationEventPublisher` / `@EventListener` |
| `appsettings.json` / `appsettings.{Env}.json` | `application.yml` with profiles |
| `Dictionary` + `lock (_gate)` | `ConcurrentHashMap` (lock only for compound invariants) |
| `record` / `System.Text.Json` | Java `record` / Jackson |
| PowerShell smoke test | JUnit 5 unit tests for `domain` + a STOMP integration test |
| `.csproj` / `.sln` | `build.gradle.kts` / `settings.gradle.kts` |

## Conventions

**Base package:** `com.derbysquad.tictactoe`

```
com.derbysquad.tictactoe
├── domain         // core game logic — POJOs/records. ZERO Spring imports.
├── console        // ported console app (first vertical slice)
├── multiplayer    // STOMP config, @MessageMapping handlers, registry & session services
├── config         // @Configuration, @ConfigurationProperties, seeded Random bean
└── dto            // shared request/response/event records (the wire contract)
```

Rules:
- **`domain` must have zero Spring imports** — no `@Component`, no Jackson, no `@Autowired`.
- Constructor injection only. Prefer immutability (`record`, `final`).
- DTO records named per the method/event they carry (e.g. `PlaceMoveRequest`, `MatchStateEvent`).

## Build / test / run (Gradle, Kotlin DSL)

| Action | Command |
|---|---|
| Build | `./gradlew build` |
| Run app | `./gradlew bootRun` |
| All tests | `./gradlew test` |
| Single test | `./gradlew test --tests "*.BoardTest"` |
| Clean | `./gradlew clean` |

Use the wrapper (`gradlew` / `gradlew.bat`). Java 21 toolchain required.

## Migration rules

1. **Always read the corresponding C# file under `../legacy-csharp` before porting.** Cite it.
2. **Preserve game logic exactly** — win/draw detection, minimax scoring (`10 - depth`), turn validation,
   and timings (30s disconnect grace, 30s challenge lifetime, 5s sweep, 600ms AI delay).
3. **Flag anything that can't map cleanly** — stop and surface it; do not guess.
4. Lift the triplicated `ComputeWinningCells` into `domain` once.

## Migration checklist (dependency order — literal task list)

- [ ] **1. domain core** — translate `Board`, `Ai` (minimax), `ThemeText`, `Player`/`Difficulty` enums. Add JUnit 5 tests it never had.
- [ ] **2. seeded Random bean + config** — `application.yml`, profiles, `@ConfigurationProperties`, seeded `Random` `@Bean` for the AI.
- [ ] **3. console app** — port `console/Program.cs` end-to-end (the first working vertical slice).
- [ ] **4. shared DTO records** — one record per hub method (9) and per push event (~11), matching the wire shapes.
- [ ] **5. STOMP config + hub handlers** — `@EnableWebSocketMessageBroker`, `/gamehub` endpoint, `@MessageMapping` handlers.
- [ ] **6. registry services** — lobby (presence), match, challenge registries as singleton `@Service` + `ConcurrentHashMap`.
- [ ] **7. session-keyed game-state service** — per-connection local game state via a session-keyed `ConcurrentHashMap`.
- [ ] **8. scheduled timers + event publishing** — `@Scheduled` challenge sweep + disconnect-grace; `ApplicationEventPublisher` for expiry.
- [ ] **9. thin JS multiplayer client** — STOMP-over-WebSocket browser client driving the lobby/match UI.
- [ ] **10. STOMP integration test** — connect, enter lobby, challenge, play a match, assert pushed events.

<!-- voice: I rewrote the project guide to match your decisions. It now uses Maven, locks in the in-memory single-instance design, maps the SignalR hub to Spring's WebSocket messaging, sets the package layout with a Spring-free domain package, and lists the ten migration steps as a task list starting with the game engine and the console app as the first working slice. -->
