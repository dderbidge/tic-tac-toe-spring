# Migration Analysis — Legacy C# Tic-Tac-Toe → Spring Boot

**Source:** `../legacy-csharp` (`.NET 9`, C# 13, `TicTacToe.sln`)
**Target:** Spring Boot (Java/Kotlin)
**Date:** 2026-06-05
**Status:** Analysis only — no code written yet.

---

## 0. Executive summary

The legacy solution is a **space-opera-themed tic-tac-toe** ("The Ascendant ☼ vs The Crimson Vanguard ✦") delivered through three independent front-ends that all share **one game-logic library**:

| Project | Type | Role |
|---|---|---|
| `core` (`TicTacToe.Core`) | .NET class library | Single source of truth: `Board`, `Ai`, `ThemeText`, enums. No dependencies. |
| `console` (`TicTacToe.Console`) | Console `Exe` | ANSI terminal UI. |
| `web` (`TicTacToe.Web`) | Blazor **WebAssembly** PWA | Single-player, runs entirely in the browser, offline-capable, scores in `localStorage`. |
| `web-multiplayer` (`TicTacToe.WebMultiplayer`) | Blazor **Server** + SignalR | Hot-seat, vs-CPU, and networked multiplayer with a lobby/challenge system. |
| `tools/icon-gen` | Console `Exe` | Build-time utility that procedurally generates PWA icons via SkiaSharp. Not part of the runtime. |

**The single most important finding for migration planning:** this application has **no database, no ORM, no message queue, no cache, no authentication, and no REST API**. All state is held **in process memory** (multiplayer) or **in the browser** (single-player). The "backend" surface that maps to Spring Boot is almost entirely the `web-multiplayer` project, and its server contract is a **SignalR hub**, not HTTP endpoints.

This makes the migration conceptually simple on the data side but architecturally pointed on the **real-time messaging** side: the central translation problem is **SignalR → Spring WebSocket/STOMP**.

---

## 1. Overall architecture (layers, projects, entry points)

### Layering

The codebase follows a clean, shallow layering:

```
        ┌─────────────────────────────────────────────┐
        │  core  (Board, Ai, ThemeText, Player, …)     │  ← pure domain logic, zero deps
        └─────────────────────────────────────────────┘
            ▲              ▲                   ▲
            │              │                   │
   ┌────────────┐   ┌────────────┐   ┌────────────────────┐
   │  console   │   │    web     │   │   web-multiplayer   │
   │ (terminal) │   │ (WASM PWA) │   │ (Blazor Server+Hub) │
   └────────────┘   └────────────┘   └────────────────────┘
```

- **Domain layer** = `core`. Pure C#, no framework references, `ImplicitUsings` + `Nullable` enabled. Contains all rules (win/draw detection, move validation, minimax AI).
- **Presentation/application layer** = the three front-ends. Each owns its own session/state-management class and UI.
- There is **no service layer, no repository layer, no persistence layer** — because nothing is persisted server-side.

### Entry points

| Project | Entry point | Mechanism |
|---|---|---|
| `console` | `console/Program.cs` → `Program.Main` | Classic `static int Main(string[])`; menu loop, try/catch around `EndOfStreamException`. |
| `web` | `web/Program.cs` | Top-level statements; `WebAssemblyHostBuilder.CreateDefault` → mounts `<App>` to `#app`. Runs client-side. |
| `web-multiplayer` | `web-multiplayer/Program.cs` → `Program.Main` | `WebApplication.CreateBuilder` → builds the request pipeline, maps Razor components + the `/gamehub` SignalR hub. |

### Domain model details (`core`)

- `Player` enum: `None`, `Ascendant`, `Vanguard`.
- `Board`: a `Player[3,3]` grid (`sealed class`). Methods: `TryPlace`, `Winner`, `IsDraw`, `IsGameOver`, `EmptyCells`, `Clone`, static `Other`. Win detection is fully unrolled (rows, columns, two diagonals).
- `Difficulty` enum: `Recruit` (random), `Captain` (one-ply: win-then-block-then-random), `Warlord` (full **minimax**, depth-weighted to prefer faster wins / slower losses).
- `Ai`: `static class` with a single static `Random` seeded from the `TICTACTOE_SEED` env var (determinism for tests).
- `ThemeText`: all user-facing strings and symbols (names, sigils, message templates).

**Note:** `ComputeWinningCells` (the highlight-the-winning-line logic) is **duplicated** in `web/Services/GameSession.cs`, `web-multiplayer/Services/MatchRegistry.cs`, and `web-multiplayer/Services/LocalGameSession.cs`. It is *not* in `core`. A migration is a natural opportunity to lift this into the domain layer.

### Idiomatic Spring Boot equivalent

- **`core` → a plain domain module/package** (e.g. `com.derbysquad.tictactoe.domain`). Keep it framework-free POJOs — no `@Component` needed. In a Maven/Gradle multi-module build this is its own module that the web module depends on; in a single module it is just a package. This is the cleanest 1:1 port.
- **The three front-ends do *not* map 1:1.** Spring Boot is a server-side framework, so:
  - `console` → optional separate artifact using Spring Boot `CommandLineRunner` or, more idiomatically, a standalone `picocli` app. Likely **out of scope** for a web migration; flag for a decision.
  - `web` (WASM) → has **no direct Spring equivalent** (see §4) — it is a client-side runtime. The decision is whether to keep a JS/WASM/SPA frontend talking to a new REST API, or re-render server-side with Thymeleaf/HTMX.
  - `web-multiplayer` → the **primary migration target**: a Spring Boot app exposing WebSocket/STOMP messaging + a server-rendered or SPA frontend.
- **Layering target:** introduce the conventional Spring layering the C# app never needed — `domain` (game rules), `service` (`@Service` session/registry beans), `web`/`messaging` (controllers + WebSocket handlers), and optionally `config`. Persistence layer only if the team decides to add it (see §3).

---

## 2. Dependency injection setup and service lifetimes

### `web` (Blazor WebAssembly) — `web/Program.cs`

```
builder.Services.AddScoped(sp => new HttpClient { BaseAddress = ... });
builder.Services.AddScoped<GameSession>();
```

- DI container is the WASM client-side container. Lifetimes here are effectively **per-browser-tab singletons** (a WASM "scope" lasts the app's lifetime).
- `HttpClient` is registered but **the game never calls a server** — it's vestigial scaffolding from the template.
- `GameSession` holds all single-player state and talks to the browser via `IJSRuntime` (localStorage).

### `web-multiplayer` (Blazor Server) — `web-multiplayer/Program.cs`

```
builder.Services.AddRazorComponents().AddInteractiveServerComponents();
builder.Services.AddSignalR();

builder.Services.AddSingleton<PresenceTracker>();   // server-wide lobby roster
builder.Services.AddSingleton<MatchRegistry>();     // server-wide active matches
builder.Services.AddSingleton<ChallengeBroker>();   // server-wide pending challenges + sweep timer
builder.Services.AddScoped<LocalGameSession>();      // per-Blazor-circuit (per user) local game
```

| Service | Lifetime | Why |
|---|---|---|
| `PresenceTracker` | **Singleton** | Global lobby state shared by all connections. Internally thread-safe via a `lock (_gate)`. |
| `MatchRegistry` | **Singleton** | Global dictionary of in-flight matches. `lock`-guarded. |
| `ChallengeBroker` | **Singleton** | Global pending challenges + a `System.Threading.Timer` sweeping expirations every 5 s. Implements `IDisposable`. |
| `LocalGameSession` | **Scoped** | One per Blazor Server circuit ≈ one per connected user's hot-seat/vs-CPU session. |

There is also **manual event wiring in `Program.Main`**: after `Build()`, the code resolves `IHubContext<GameHub>` and `ChallengeBroker` and subscribes `broker.Expired += …` to push `OnChallengeExpired` to the two affected clients. This is an in-process pub/sub bridge from a background timer to the hub.

### Idiomatic Spring Boot equivalent

| C# concept | Spring Boot equivalent |
|---|---|
| `AddSingleton<T>` | Default bean scope. A `@Service`/`@Component` is a singleton — exactly `PresenceTracker`, `MatchRegistry`, `ChallengeBroker`. |
| `AddScoped<T>` (per circuit) | No exact match. For STOMP/WebSocket use `@Scope(scopeName = "websocket")` (Spring's WebSocket-session scope) for `LocalGameSession`-style per-connection state, **or** manage it in a `Map<sessionId, …>` inside a singleton. The Blazor "circuit" has no Spring twin; per-user state must be keyed by WebSocket session or HTTP session. |
| `lock (_gate)` over a `Dictionary` | `ConcurrentHashMap` (lock-free) or `synchronized`/`ReentrantLock`. Prefer `ConcurrentHashMap` + atomic ops; fall back to a lock for compound invariants (e.g. `MatchRegistry.TryPlace`'s read-validate-mutate sequence). |
| `IDisposable` + `System.Threading.Timer` | `@PreDestroy` for cleanup; replace the timer with `@Scheduled(fixedRate = 5000)` or a `TaskScheduler`/`ScheduledExecutorService` bean (see §5). |
| `broker.Expired += …` event wiring | Spring `ApplicationEventPublisher` + `@EventListener`, or inject `SimpMessagingTemplate` directly into the broker and push from there. |
| Manual `app.Services.GetRequiredService<T>()` post-build wiring | Constructor injection into a `@Configuration`/`@Service`; no manual service-locator step needed. |

**Watch-out:** the C# DI gives you a clean "global singleton vs per-circuit" split. In Spring the per-connection lifetime is the awkward part — budget design time for how `LocalGameSession` maps (websocket scope vs. a keyed map vs. moving that state to the client).

---

## 3. Data layer (EF Core? Dapper? raw SQL?), entities, and DB provider

### Finding: **there is no data layer at all.**

- **No Entity Framework Core, no Dapper, no ADO.NET, no SQL, no connection strings, no database provider.** Confirmed by inspecting every `.csproj` (only `Microsoft.AspNetCore.*` packages appear) and `appsettings.json` (no `ConnectionStrings` section).
- **"Entities" are transient, in-memory C# objects:**
  - `MultiplayerMatch` (in `MatchRegistry.cs`): `MatchId`, two connection IDs, two names, a `Board`, `Active`, `Winner`, `IsDraw`, `WinningCells`, `LastActivityAt`, `DisconnectedConnId`, `DisconnectTimer`. Stored in a `Dictionary<string, MultiplayerMatch>`.
  - `PlayerSession` (record, in `PresenceTracker.cs`): `ConnectionId`, `Name`, `ConnectedAt`. Stored in a `Dictionary`.
  - `Challenge` (record, in `ChallengeBroker.cs`): `ChallengeId`, from/to conn + names, `ExpiresAt`. Stored in a `Dictionary`.
- **Persistence that does exist:**
  - **Single-player scores** → browser `localStorage` key `tictactoe.scores.v1`, written via `IJSRuntime` and serialized with `System.Text.Json` (`ScoreDto`). Purely client-side.
  - **Multiplayer scores** → not persisted at all (`LocalGameSession.ResetScores` just zeroes in-memory counters; networked matches keep no score history).
- IDs are `Guid.NewGuid().ToString("N")[..10]` (10-char hex slugs), generated in process.

### Idiomatic Spring Boot equivalent

Because nothing is persisted server-side, **a database is optional** and should be a deliberate product decision, not an automatic part of the migration:

| Legacy approach | Spring Boot equivalent |
|---|---|
| In-memory `Dictionary<string,T>` in singletons | Keep as `ConcurrentHashMap` in `@Service` singletons — the **default, faithful port**. No JPA needed. |
| `localStorage` scores (client) | Keep client-side if you keep a JS/SPA frontend; or move to HTTP session / a `scores` table if scores should be server-authoritative and durable. |
| C# records as in-memory entities | Java `record`s / immutable POJOs (or Kotlin `data class`). `MultiplayerMatch` is mutable → a regular class. |
| `Guid.NewGuid().ToString("N")[..10]` | `UUID.randomUUID().toString().replace("-","").substring(0,10)`, or `java.security.SecureRandom`-based slug. |

**If/when durability is wanted** (match history, leaderboards, reconnect across server restarts):
- **Spring Data JPA** + an entity-per-table model (`MatchEntity`, `PlayerEntity`, …), with **H2** for dev/in-memory and **PostgreSQL** for production (the closest idiomatic default).
- Or **Spring Data Redis** if the goal is fast, shared, *ephemeral* state across multiple app instances — this is the more natural fit given the data is short-lived session/lobby/match state (and it would also solve the horizontal-scaling problem the current singletons can't).

**Recommendation to flag:** start with in-memory `ConcurrentHashMap` services (faithful, simplest), and only introduce Redis/JPA if multi-instance scaling or persistence becomes a requirement.

---

## 4. API surface (controllers, routes, DTOs, auth/middleware)

### Finding: **no REST controllers, no HTTP API endpoints.** The server contract is real-time messaging + component rendering.

#### `web` (WASM) — no server API
The single-player app is 100% client-side. It registers an `HttpClient` but never issues game calls. The server's only job is to serve static files + the PWA service worker. **There is no API to migrate here.**

#### `web-multiplayer` — SignalR hub is the API

The entire server "API" is the **`GameHub`** (`/gamehub`), a SignalR hub. Its **callable methods (client → server)** are the de-facto endpoints:

| Hub method (client→server) | Purpose |
|---|---|
| `EnterLobby()` | Join lobby, register presence, get roster. |
| `LeaveLobby()` | Leave lobby. |
| `ChallengePlayer(targetConnectionId)` | Send a challenge. |
| `RespondToChallenge(challengeId, accept)` | Accept/decline; on accept creates a match. |
| `CancelChallenge(challengeId)` | Withdraw a pending challenge. |
| `PlaceMove(matchId, row, col)` | Make a move (server validates turn/cell). |
| `ForfeitMatch(matchId)` | Concede. |
| `RequestRematch(matchId)` | No-op stub (forward-compat). |
| `RejoinMatch(matchId)` | Reconnect into a match within the grace window. |

**Server → client events** (the "response" contract), dispatched by name:
`OnLobbyState`, `OnPlayerJoined`, `OnPlayerLeft`, `OnIncomingChallenge`, `OnChallengeAccepted`, `OnChallengeDeclined`, `OnChallengeExpired`, `OnMatchState`, `OnMatchEnded`, `OnOpponentDisconnected`, `OnOpponentReconnected`.

#### DTOs
- **Server side:** payloads are **anonymous objects** built inline in `GameHub` (e.g. `new { matchId, board, activeFaction, winner, isDraw, winningCells }`). The board is serialized to `int[3][]` via `SerializeBoard`.
- **Client side:** matching `record` types are declared inside `Game.razor` (`LobbyState`, `PlayerInfo`, `IncomingChallenge`, `ChallengeResolution`, `MatchStateMsg`, `MatchEnded`, `CellRef`, …). There are **no shared DTO classes** — the contract is duplicated by name/shape on each side.

#### Routing & component surface
- `MapRazorComponents<App>().AddInteractiveServerRenderMode()` — the UI is **server-rendered Blazor components over a WebSocket circuit**, not pages returned by controllers.
- One route only: `Index.razor` → `@page "/"` → renders `<Game />`. `Error.razor` for the error page. `Routes.razor` defines the `<Router>` with `MainLayout`.

#### Auth & middleware
- **No authentication, no authorization.** Players are anonymous; identity = SignalR connection ID; display names are auto-generated (`NameGenerator`, SHA-256 of conn ID → adjective+noun).
- Middleware pipeline (`Program.cs`): `UseExceptionHandler("/Error")` + `UseHsts()` (non-dev only), `UseHttpsRedirection()`, `UseAntiforgery()`, `MapStaticAssets()`. That's the whole pipeline.

### Idiomatic Spring Boot equivalent

| Legacy | Spring Boot equivalent |
|---|---|
| **SignalR `GameHub`** (the core contract) | **Spring WebSocket + STOMP.** A `@Controller` with `@MessageMapping("/placeMove")` methods for client→server, and `SimpMessagingTemplate` / `@SendTo` / `@SendToUser` for server→client pushes. Register via `@EnableWebSocketMessageBroker` + a `WebSocketMessageBrokerConfigurer` mapping the `/gamehub`-equivalent endpoint. SignalR's "call a method by name" maps to STOMP destinations; SignalR's `Clients.Client(connId).SendAsync("EventName", payload)` maps to `messagingTemplate.convertAndSendToUser(sessionId, "/queue/eventName", payload)`. |
| SignalR groups (`"lobby"`) | A STOMP topic (`/topic/lobby`) for broadcast, or a server-side set of session IDs you fan out to. |
| Connection ID as identity | WebSocket/STOMP session ID (`SimpMessageHeaderAccessor.getSessionId()`). For reconnect/rejoin, you'll need a stable token since the session ID changes on reconnect (the C# code already handles this by swapping `connId` in `RejoinMatch`). |
| Anonymous inline payloads | **Define explicit shared DTOs** (Java records / Kotlin data classes) — e.g. `MatchStateMessage`, `LobbyStateMessage`. This *removes* the by-shape duplication and is strictly better than the legacy design. Jackson handles (de)serialization. |
| `SerializeBoard` → `int[][]` | A `BoardDto` (e.g. `int[][]` or `List<List<Integer>>`); Jackson serializes automatically. |
| Blazor Server interactive components | **No equivalent.** Choose a frontend strategy: (a) **Thymeleaf + HTMX/WebSocket** server-rendered, (b) a **JS SPA** (React/Vue/Svelte) talking to the STOMP API, or (c) keep game rendering client-side and use Spring purely as the messaging backend. This is the biggest UI-architecture decision. |
| `UseExceptionHandler`/`UseHsts`/`UseHttpsRedirection` | `@ControllerAdvice`/`ErrorController`; HSTS + HTTPS redirect via Spring Security or `server.ssl.*` + a redirect config. |
| `UseAntiforgery` | Spring Security CSRF protection (enabled by default for state-changing HTTP; configure CSRF handling for WebSocket/STOMP as needed). |
| No auth | No auth required initially; Spring Security can be added later for accounts. Keep anonymous auto-naming as-is. |
| Single REST API (if `web` logic moves server-side) | If single-player logic is moved off the client, add a small `@RestController` (`POST /api/move`, etc.). Only needed if you abandon the client-side game engine. |

**Key migration insight:** the contract is **message-shaped, not resource-shaped**, so the natural Spring target is **STOMP-over-WebSocket**, *not* a REST controller layer. Resist the urge to REST-ify the hub — the turn-by-turn, server-push nature maps cleanly to messaging.

---

## 5. External integrations (message queues, caches, third-party SDKs)

### Finding: **essentially none in the runtime.**

- **Message queues:** none (no RabbitMQ, Kafka, Azure Service Bus, etc.).
- **Caches:** none (no Redis, no `IMemoryCache`). The in-memory dictionaries *are* the store.
- **Third-party SDKs:**
  - The only non-Microsoft package anywhere is **SkiaSharp**, and it's confined to `tools/icon-gen` — a **build/design-time** utility for generating PWA icons. It is **not referenced by any runtime project**.
  - Runtime packages are all first-party ASP.NET/Blazor: `Microsoft.AspNetCore.Components.WebAssembly` (+ DevServer) in `web`, and `Microsoft.AspNetCore.SignalR.Client` in `web-multiplayer` (the *client* SignalR library, used by the Blazor component to talk to its own hub).
- **In-process "integrations"** worth noting (they replace what would otherwise be external infra):
  - **Background timers:** `ChallengeBroker` runs a 5-second sweep `Timer`; each match uses a per-match disconnect-grace `Timer` (30 s) in `GameHub.OnDisconnectedAsync` / `OnGraceExpired`.
  - **In-process event bus:** `ChallengeBroker.Expired` event bridged to the hub in `Program.Main`.
  - **External web assets (front-end only):** Google Fonts via `<link>` in `App.razor` (`Spectral`, `Inter`). Not a backend integration.

### Idiomatic Spring Boot equivalent

| Legacy | Spring Boot equivalent |
|---|---|
| `System.Threading.Timer` sweep (challenge expiry) | `@Scheduled(fixedRate = 5000)` on a `@Service` method, with `@EnableScheduling`. |
| Per-match disconnect-grace `Timer` | A `TaskScheduler`/`ScheduledExecutorService` bean scheduling a one-shot task per match; cancel the `ScheduledFuture` on reconnect (mirrors `DisconnectTimer?.Dispose()`). |
| `broker.Expired` in-process event | `ApplicationEventPublisher.publishEvent(...)` + `@EventListener`. |
| In-memory dictionaries acting as cache/store | `ConcurrentHashMap` in singleton `@Service`s (faithful), **or** Spring Data Redis if you need shared/scalable ephemeral state. |
| SkiaSharp icon tool | Leave as a standalone build-time tool (could stay C#, or port to a Gradle/Maven task or a small Java `BufferedImage`/`Graphics2D` utility). Not part of the Spring app. |
| Google Fonts `<link>` | Unchanged — a frontend concern, independent of Spring. |
| SignalR client lib | Drops away on the server; the *browser* uses a STOMP JS client (`@stomp/stompjs`) or SockJS instead. |

**Scaling caveat to surface:** every piece of shared state lives in singleton process memory and is coordinated with in-process timers/events. This works for a single instance only. If the Spring deployment needs to run **multiple instances** behind a load balancer, the lobby/match/challenge state and the WebSocket fan-out must move to shared infrastructure — **Redis** (state) + a **STOMP relay / message broker** (e.g. an external broker, or Redis pub/sub) for cross-instance message delivery. The legacy app never had to solve this; the migration is the moment to decide.

---

## 6. Configuration and environment handling

### Legacy

- **Config files:** only `web-multiplayer` has them — `appsettings.json` (Logging levels, `AllowedHosts: "*"`) and `appsettings.Development.json` (Logging only). `web` and `console` have no config files; `core` has none.
- **No custom config sections, no `ConnectionStrings`, no secrets, no `IOptions<T>` binding.** Configuration is essentially just default ASP.NET logging.
- **Environment selection:** `ASPNETCORE_ENVIRONMENT` (set to `Development` in `launchSettings.json` profiles). Used by `app.Environment.IsDevelopment()` to toggle HSTS/exception handler.
- **`launchSettings.json`:** dev-only `http`/`https` profiles with fixed ports — `web` on `5010`/`7291`, `web-multiplayer` on `5216`/`7196`. (README mentions `5089`/`5050`; the launch profiles are the authoritative dev ports.)
- **Runtime environment variable:** `TICTACTOE_SEED` — read in `core/Ai.cs` to seed the RNG deterministically (used by the smoke test for reproducible AI moves). This is the one piece of genuinely app-specific configuration.
- **Hard-coded constants** (not externalized) worth cataloguing: `AiDeliberationMs = 600` (artificial AI "thinking" delay), `DisconnectGraceSeconds = 30`, `ChallengeBroker.Lifetime = 30s`, sweep interval `5s`, lobby group name `"lobby"`, localStorage key `tictactoe.scores.v1`, match/challenge ID length `10`.

### Idiomatic Spring Boot equivalent

| Legacy | Spring Boot equivalent |
|---|---|
| `appsettings.json` / `appsettings.{Env}.json` | `application.yml` / `application-{profile}.yml`. |
| `ASPNETCORE_ENVIRONMENT=Development` | Spring **profiles** (`SPRING_PROFILES_ACTIVE=dev`); `@Profile("dev")` and `Environment#acceptsProfiles`. |
| `app.Environment.IsDevelopment()` toggles | Profile-conditional beans/config, or `@ConditionalOnProperty`. |
| `Logging:LogLevel` section | `logging.level.*` in `application.yml`. |
| `AllowedHosts: "*"` | `server.forward-headers-strategy` / CORS config (`WebMvcConfigurer`/`CorsConfigurationSource`) as appropriate. |
| Fixed `launchSettings.json` ports | `server.port` in `application.yml` (+ per-profile overrides). `launchSettings.json` has no equivalent — it's a VS dev-runner file. |
| `TICTACTOE_SEED` env var | `@Value("${tictactoe.seed:}")` or `@ConfigurationProperties(prefix="tictactoe")`; Spring relaxed binding maps `TICTACTOE_SEED` automatically. Inject into the AI bean instead of reading `Environment.GetEnvironmentVariable` statically. |
| Hard-coded gameplay constants | Externalize into a typed `@ConfigurationProperties` record (e.g. `GameProperties` with `aiDeliberation`, `disconnectGrace`, `challengeLifetime`, …) — an improvement over the scattered `const`s. |

**Migration note:** the static, env-var-driven `Ai.Rng` is the one config-coupling that needs care. In Spring, prefer injecting a seeded `Random`/`RandomGenerator` bean (configurable via properties) rather than reading the environment from a static initializer — this makes the AI testable and removes static global state.

---

## 7. Test coverage and frameworks used

### Legacy

- **No unit-test framework anywhere.** Confirmed: no xUnit, NUnit, MSTest, or `Microsoft.NET.Test.Sdk` references in any `.csproj`; no test projects in the solution.
- **The only automated tests** are a **PowerShell smoke/regression script**: `console/tests/smoke.ps1` — **10 end-to-end scenarios** driven by piping canned stdin files (`tests/inputs/test-input-*.txt`) into the console app and asserting on **output substring counts** via regex. Coverage:
  1. row win, 2. column win, 3. diagonal win, 4. draw, 5. invalid-input recovery, 6. Captain blocks both threats, 7–9. Warlord never loses (column/center/corner attacks), 10. quit-from-menu (no board, exit 0).
  - Uses `TICTACTOE_SEED=13` (and `2` per comments) for deterministic AI.
  - It is a **black-box CLI integration test of the console front-end**, exercising `core` indirectly. It does **not** test `web` or `web-multiplayer`.
- **Web/multiplayer testing is entirely manual** ("open it in a browser and play," per the README).
- The `docs/superpowers/` folder contains design specs and implementation plans (not tests).

### Idiomatic Spring Boot equivalent

| Legacy | Spring Boot equivalent |
|---|---|
| (No unit tests) | **JUnit 5 + AssertJ** for the domain (`Board`, `Ai` minimax, win/draw detection) — the `core` port is highly unit-testable and should get real coverage it never had. |
| PowerShell substring assertions on CLI | Proper assertions on return values; the determinism trick (seed) becomes an injected seeded `Random` bean. |
| Manual hub testing | **`spring-boot-starter-test`** (`@SpringBootTest`) + a **STOMP `WebSocketStompClient`** integration test that connects, enters the lobby, challenges, plays moves, and asserts on pushed messages — automating what is currently manual. |
| Mocking (none today) | **Mockito** (bundled with `spring-boot-starter-test`) for service-layer tests (`MatchRegistry`, `ChallengeBroker`, `PresenceTracker`). |
| Manual browser play | Optional **end-to-end** with Testcontainers/Playwright if a server-rendered or SPA frontend is built. |

**Opportunity:** the migration should *raise* test quality. The domain logic (especially minimax correctness and the thread-safe registries) is the highest-value, easiest-to-cover target and currently has only indirect, CLI-level coverage.

---

## 8. Migration summary & risk map

| Area | Legacy reality | Spring difficulty | Notes |
|---|---|---|---|
| Domain (`core`) | Pure C#, no deps | **Trivial** | Direct port to POJOs; add the deduplicated `WinningCells` helper here. |
| Data layer | None (in-memory + localStorage) | **Trivial / decision** | `ConcurrentHashMap` services; DB only if newly required. |
| DI & lifetimes | Singletons + per-circuit scoped | **Moderate** | Per-connection ("scoped") state is the awkward mapping. |
| Real-time API | **SignalR hub** | **Moderate–High** | Core effort: SignalR → STOMP/WebSocket; define explicit DTOs. |
| Single-player web | Blazor **WASM** (client-only) | **High / decision** | No server equivalent; pick a frontend strategy. |
| Background work | `Timer` + in-proc event | **Low** | `@Scheduled` + `ApplicationEventPublisher`. |
| Config/env | Minimal + `TICTACTOE_SEED` | **Low** | `application.yml` + profiles + `@ConfigurationProperties`. |
| Auth/security | None | **Low** | Add later if needed; CSRF/HSTS via Spring Security. |
| Tests | PowerShell smoke only | **Low / opportunity** | Add JUnit + STOMP integration tests. |
| Scaling | Single-instance in-memory | **Decision** | Multi-instance needs Redis + external broker. |
| External SDKs | Only SkiaSharp (build tool) | **N/A** | Leave the icon tool out of the Spring app. |

**Three decisions to surface before any code is written:**
1. **Frontend strategy** for the (currently client-side) UI — Thymeleaf/HTMX vs. JS SPA vs. keep client game engine + Spring-as-messaging-backend.
2. **Scope of migration** — port only `web-multiplayer`, or also reproduce the single-player WASM PWA and the console app?
3. **Persistence & scaling** — stay single-instance in-memory (faithful), or introduce Redis/JPA for durability and horizontal scaling?

<!-- voice: I analyzed the C# tic-tac-toe codebase and wrote the migration report. The big takeaways: there's no database, no message queue, no cache, and no REST API — everything lives in memory or in the browser. The shared game-logic library ports over trivially. The real work is translating the real-time multiplayer layer from Microsoft's SignalR to Spring's WebSocket messaging, and deciding what to do about the browser-based single-player app, which has no direct server-side equivalent. I saved the full report as a markdown file in the Spring Boot project folder. -->
