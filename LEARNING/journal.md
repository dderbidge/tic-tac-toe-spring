# Learning Journal — Spring Boot for a C#/.NET Developer

> A running course built as a *byproduct* of porting a real .NET 9 app
> (`../legacy-csharp`, a themed tic-tac-toe) to **Java 21 + Spring Boot 3.x**.
> I already know C#, ASP.NET, EF Core, and the .NET tooling; these lessons map each
> new Spring/Java idea back to the .NET concept I'd recognize, call out where the
> analogy breaks, and explain *why* Spring/Java does it that way. Lessons are written
> in the order the migration tasks were done and are meant to be read top-to-bottom.
>
> This file lives only under `LEARNING/`. Nothing in `src/` imports or references it,
> and it never affects the build.

## Table of contents

1. [Setting up the Gradle build](#lesson-1--setting-up-the-gradle-build) — *project setup (enables Task 1)*
2. [Translating the domain to Java types](#lesson-2--translating-the-domain-to-java-types) — *Task 1: domain core*
3. [Designing out static state for dependency injection](#lesson-3--designing-out-static-state-for-dependency-injection) — *Task 1: domain core*
4. [Unit testing with JUnit 5](#lesson-4--unit-testing-with-junit-5) — *Task 1: domain core*
5. [Porting the console app & in-process I/O testing](#lesson-5--porting-the-console-app--in-process-io-testing) — *Task 3: console slice*
6. [Shared DTO records & the STOMP wire contract](#lesson-6--shared-dto-records--the-stomp-wire-contract) — *Task 4: hub DTOs*
7. [SignalR hub → Spring STOMP](#lesson-7--signalr-hub--spring-stomp) — *Task 5: STOMP config & handlers*
8. [Registries as singleton @Service beans](#lesson-8--registries-as-singleton-service-beans) — *Task 6: registry services*
9. [@Scheduled & application events replace timers](#lesson-9--scheduled--application-events-replace-timers) — *Task 8: scheduling & events*
10. [Session-keyed state & the @SpringBootTest STOMP test](#lesson-10--session-keyed-state--the-springboottest-stomp-test) — *Tasks 7 & 10*
11. [The thin JS client & serving static from Spring Boot](#lesson-11--the-thin-js-client--serving-static-from-spring-boot) — *Task 9: JS client*
12. [Testing the messaging backend & the session-id gotcha](#lesson-12--testing-the-messaging-backend--the-session-id-gotcha) — *backend e2e verification*
13. [Durable reconnect: capability tokens & per-tab storage](#lesson-13--durable-reconnect-capability-tokens--per-tab-storage) — *reconnect feature*

---

## Lesson 1 — Setting up the Gradle build

*Files: `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/`, `gradlew`.*

**Concepts introduced:** the Gradle build script, the Gradle wrapper, and Java toolchains.

- **`build.gradle.kts` ≈ your `.csproj`** — but it's a *program* (Kotlin DSL), not declarative XML. `plugins {}` is roughly `<Sdk>`; the `dependencies {}` block is your `<PackageReference>` list; `repositories { mavenCentral() }` is the NuGet feed. Coordinates are `group:artifact:version` (e.g. `org.junit.jupiter:junit-jupiter`) instead of a package id.
- **`settings.gradle.kts` ≈ your `.sln`** — it names the root project and (later) lists modules.
- **The wrapper (`gradlew`) has no .NET equivalent.** It pins the *exact Gradle version* per repo and self-downloads it, so every machine builds identically. .NET's closest cousin is `global.json` pinning the SDK — but that only constrains, it doesn't fetch.

**Why Spring/Gradle does it this way:** reproducible builds are a first-class goal — the build tool itself is versioned alongside the code.

> **Watch out:** the *Gradle Daemon* is a long-lived background JVM that caches build state between runs — there's no `dotnet build` analog. And `java { toolchain { languageVersion = 21 } }` can auto-provision a JDK over the network, unlike `<TargetFramework>` which only targets an already-installed SDK.

---

## Lesson 2 — Translating the domain to Java types

*Files: `domain/Player.java`, `Difficulty.java`, `Move.java`, `Board.java`, `ThemeText.java`.*

**Concepts introduced:** Java enums, `record`, and `final class`.

- **`enum` (Java) vs `enum` (C#):** Java enums are *full objects* (each constant is a singleton instance of a class), not thin integer aliases. You can't implicitly cast to `int`; you call `.ordinal()`. I kept `NONE, ASCENDANT, VANGUARD` in that order because the legacy wire format serializes `(int)player`, so ordinals `0/1/2` are load-bearing — `BoardTest` asserts this.
- **`record Move(int row, int col)` ≈ C# `record`:** value-based `equals`/`hashCode`/`toString` generated for you. It replaces C#'s `(int row, int col)` tuples. Note it's a *reference type*, not a value type — closer to a C# `record class` than a `struct`.
- **`final class Board` ≈ C# `sealed class`.** Board stays mutable (its `tryPlace` mutates), so it is deliberately *not* a record.

**Why Java does it this way:** enums-as-classes let constants carry behavior; records kill DTO boilerplate.

> **Watch out:** a Java array of enums defaults every slot to **`null`**, whereas C#'s `Player[,]` defaults to `Player.None`. `Board`'s constructor explicitly fills the grid with `NONE` — miss that and you get `NullPointerException`s instead of empty cells.

---

## Lesson 3 — Designing out static state for dependency injection

*File: `domain/Ai.java` (compare `../legacy-csharp/core/Ai.cs`).*

**Concepts introduced:** constructor injection, and why singletons/static state are a smell in a DI world.

- The C# `Ai` was a **`static class`** holding a **`static Random`** seeded from an environment variable in a static initializer. The Java port is an *ordinary object* that receives its `Random` through the constructor (`new Ai(rng)`).
- **Spring's IoC container ≈ `IServiceCollection` + `ServiceProvider`.** A Spring singleton bean is what `AddSingleton<T>()` gives you. The big idiom shift: you *never* `new` your collaborators or read globals — the container builds the object graph and hands dependencies to your constructor. Constructor injection is the default style (field injection exists but is discouraged).
- `java.util.Random` ≈ `System.Random`.

**Why Spring does it this way:** if an object declares its dependencies as constructor parameters, it's trivially testable (pass a seeded `Random`) and the container fully controls lifecycle. Static state is invisible to the container and fights both.

> **Watch out:** Spring will later create `Ai` via *component scanning / `@Bean` auto-wiring* — "magic" graph construction with no direct C# analog. Note the domain has **zero** Spring imports; that magic lives in the `config` package (Task 2). Also: one shared `Random` is **not thread-safe** — a flag for when `Ai` becomes a singleton bean serving concurrent requests.

---

## Lesson 4 — Unit testing with JUnit 5

*Files: `BoardTest.java`, `AiTest.java`, `ThemeTextTest.java`.*

**Concepts introduced:** JUnit 5 (Jupiter), parameterized tests, and the JUnit Platform.

- **`@Test` ≈ `[Fact]`** (xUnit) / `[Test]` (NUnit). `assertEquals(expected, actual)` puts **expected first** (same as xUnit's `Assert.Equal`).
- **`@ParameterizedTest` ≈ `[Theory]`.** `@ValueSource(ints = {-1,3,99})` ≈ `[InlineData]`; `@EnumSource(Difficulty.class)` is *nicer than .NET* — it auto-feeds every enum constant, which you'd hand-roll with `[MemberData]` in xUnit. I used these to sweep every row/column and every difficulty.
- **`useJUnitPlatform()` in the build ≈ the VSTest runner** behind `dotnet test`. The JUnit *Platform* is a launcher that separate test "engines" plug into.

**Why Java does it this way:** decoupling the platform (discovery/launch) from the engine (JUnit Jupiter, etc.) lets multiple test frameworks coexist under one runner.

> **Watch out — real gotcha:** JUnit reuses **one test-class instance for all its methods** by default. xUnit does the *opposite* — a fresh class instance per test for isolation. So shared mutable fields in a JUnit test class leak between methods. I sidestepped this by building a fresh `Board`/`Ai` inside each test (e.g. the `ai()` helper returns a new `Ai(new Random(13))`), rather than via fields.

---

## Lesson 5 — Porting the console app & in-process I/O testing

*Files: `console/ConsoleApplication.java`, `Renderer.java`, `GameConsole.java`, `console/SmokeParityTest.java`; build: a custom `runConsole` JavaExec task.*

**Concepts introduced:** running an executable main from Gradle, injecting I/O instead of using globals, and parameterized in-process integration tests.

- **Running a `main` ≈ `<OutputType>Exe</OutputType>` + `dotnet run`.** Run it with `./gradlew runConsole`. Gradle detaches stdin, so piping requires `standardInput = System.in` on the task — there's no .NET equivalent because `dotnet run` wires stdin automatically. (Originally this used the Gradle `application` plugin and `./gradlew run`, but once Spring Boot arrived in Task 5 the `application` plugin's `mainClass` clashed with Spring Boot's and made `bootRun` launch the *console* instead of the server — so the console moved to a dedicated `runConsole` JavaExec task. **Watch out:** mixing the `application` and `org.springframework.boot` plugins fights over the main class.)
- **Injected `PrintStream`/`BufferedReader` vs C#'s static `Console`.** The C# `Renderer` called the global `Console`; the Java port takes its streams via the constructor. That single change makes the *entire game* driveable from a test — exactly the dependency-injection principle Spring will later formalize with beans (depend on collaborators, never reach for a global).
- **`@ParameterizedTest` + `@MethodSource` feeding a `record Scenario` ≈ xUnit `[Theory]` + `[MemberData]`.** I replayed the 10 PowerShell smoke cases by piping each input via a `StringReader` and capturing stdout with a `ByteArrayOutputStream`.

**Why Java does it this way:** decoupling from process-global singletons (`Console`, `System.out`) is the precondition for fast, deterministic tests.

> **Watch out:** `java.util.Random(seed)` is *not* portable from .NET's `Random(seed)`, and its **first** value is badly biased for small seeds (every seed 0–40 gave the same draw). I had to search for seed `1536` to reproduce .NET's benign AI opener. No clean C# analog — just a hazard to know.

---

## Lesson 6 — Shared DTO records & the STOMP wire contract

*Files: the 20 records in `dto/` (compare `../legacy-csharp/web-multiplayer/Hubs/GameHub.cs`).*

**Concepts introduced:** records as serialization DTOs, STOMP destinations, and how SignalR's hub model maps onto a message broker.

- **Records + Jackson ≈ C# anonymous objects + System.Text.Json — but typed.** SignalR let the hub send `new { matchId, board, ... }` and take positional method args; nothing named the contract. Spring binds each JSON body to a declared record via Jackson, so the wire shape is a real type you can find, version, and test.
- **STOMP destinations replace "method name + recipient".** A SignalR hub method (`PlaceMove`) becomes a destination a `@MessageMapping` listens on (`/app/match/move`). For pushes: `Clients.Group("lobby")` → a broadcast **`/topic/lobby`**; `Clients.Client(connId)`/`Caller` → a per-user **`/user/queue/...`** (sent via `convertAndSendToUser`).
- **Prefix meanings:** `/app` = client→server, `/topic` = broadcast, `/user/queue` = one recipient.

**Why Spring does it this way:** decoupling *destination* from *recipient set* lets the broker fan out; a typed body keeps both ends honest.

> **Watch out (no clean C# analog):** (1) `Clients.OthersInGroup` doesn't exist in STOMP — you broadcast to everyone and filter yourself out client-side. (2) `/user/...` needs a per-session identity (Principal); the STOMP session id isn't the SignalR connection id and **changes on reconnect**, so rejoin needs a stable token. (3) A record with an array field (`int[][] board`) uses identity `equals` — fine for serialize-only, surprising otherwise.

---

## Lesson 7 — SignalR hub → Spring STOMP

*Files: `multiplayer/WebSocketConfig.java`, `GameMessageController.java`.*

**Concepts introduced:** `@EnableWebSocketMessageBroker`, `@MessageMapping`, and `SimpMessagingTemplate`.

- **`AddSignalR()` + `MapHub<GameHub>("/gamehub")` ≈ `@EnableWebSocketMessageBroker` + `registry.addEndpoint("/gamehub")`.** The broker config also declares the prefixes: `/app` (inbound), `/topic` (broadcast), `/user` (per-session).
- **A hub method ≈ a `@MessageMapping`.** SignalR's `PlaceMove(...)` becomes `@MessageMapping("/match/move")`. The single message body binds to a DTO via `@Payload`; `Context.ConnectionId` becomes `@Header("simpSessionId")`.
- **`Clients.*.SendAsync` ≈ `SimpMessagingTemplate`.** `Clients.Group("lobby")` → `convertAndSend("/topic/lobby", ...)`; `Clients.Client(connId)` → `convertAndSendToUser(sessionId, "/queue/...", ...)`.

**Why Spring does it this way:** SignalR is RPC-flavored (call a named method, target a named client). STOMP is pub/sub — senders publish to *destinations* and the broker fans out to whoever subscribed, which decouples the two ends.

> **Watch out (no clean C# analog):** to push to one session without authentication you must hand-build headers — `SimpMessageHeaderAccessor.create(MESSAGE)` + `setSessionId` + `setLeaveMutable(true)` — or the user-destination resolver won't target the session. And there is no `Clients.OthersInGroup`: a `/topic` subscriber receives **its own** broadcasts, so clients must ignore their own `connId`.

---

## Lesson 8 — Registries as singleton @Service beans

*Files: `multiplayer/PresenceTracker.java`, `MatchRegistry.java`, `ChallengeBroker.java`.*

**Concepts introduced:** `@Service` singletons, `ConcurrentHashMap`, and fine-grained locking.

- **`AddSingleton<T>()` ≈ `@Service`.** One instance per container, exactly like the C# singletons. Constructor injection supplies collaborators (e.g. `ApplicationEventPublisher`).
- **`Dictionary` + `lock (_gate)` ≈ `ConcurrentHashMap` (≈ `ConcurrentDictionary`).** Reads/single writes are lock-free. I kept the C# data model but dropped the global lock.
- **Compound invariants still need a lock.** `MatchRegistry.tryPlace` does read-validate-mutate, so I `synchronized` on the individual match — finer-grained than the C# registry-wide lock, same safety.

**Why Spring does it this way:** the container owns lifecycle, so "singleton service holding shared state" is the idiomatic home for what SignalR also kept in singletons — no statics, fully injectable/testable.

> **Watch out:** `ConcurrentHashMap` makes each *operation* atomic but does **not** make a read-then-write atomic — same trap as `ConcurrentDictionary`. And a Spring singleton is genuinely one instance for the whole app (not per-request), so every field it holds must be thread-safe.

---

## Lesson 9 — @Scheduled & application events replace timers

*Files: `ChallengeBroker.java`, `ChallengeExpiredAppEvent.java`, `GameMessageController.java`, `config/GameConfig.java`.*

**Concepts introduced:** `@Scheduled`, the `ApplicationEventPublisher`/`@EventListener` bus, and `TaskScheduler` for one-shots.

- **`System.Threading.Timer` (recurring) ≈ `@Scheduled(fixedRate = 5000)`** on a bean method, enabled by `@EnableScheduling`. The challenge sweep moved straight over.
- **A C# `event` + manual wiring ≈ `ApplicationEventPublisher` + `@EventListener`.** The C# `broker.Expired += …` bridge in `Program.cs` becomes `publishEvent(new ChallengeExpiredAppEvent(...))` with a listener that pushes the STOMP DTO. It's an in-process message bus (think MediatR).
- **A one-shot `Timer` ≈ `TaskScheduler.schedule(runnable, Instant)`** returning a `ScheduledFuture` I cancel on rejoin (the disconnect-grace timer).
- **SignalR `OnDisconnectedAsync` ≈ `@EventListener(SessionDisconnectEvent)`.**

**Why Spring does it this way:** declarative scheduling and decoupled events are container-managed — no manual thread or timer lifecycle to leak.

> **Watch out:** `@Scheduled` silently does nothing without `@EnableScheduling`. And application events are **synchronous by default** — the listener runs on the publisher's thread unless you mark it `@Async`.

---

## Lesson 10 — Session-keyed state & the @SpringBootTest STOMP test

*Files: `multiplayer/LocalGameService.java`, `LocalGame.java`, `multiplayer/StompIntegrationTest.java`.*

**Concepts introduced:** session-keyed state (vs a scoped bean) and `@SpringBootTest` with a real STOMP client.

- **Blazor `Scoped` (per-circuit) ≈ … an explicit map, by choice.** Spring offers `@Scope("websocket")`, but per our migration decision the per-connection local game lives in a `ConcurrentHashMap<sessionId, LocalGame>` inside a singleton `@Service` — explicit and predictable over framework "magic".
- **`@SpringBootTest(webEnvironment = RANDOM_PORT)` boots the whole app on a real port;** a `WebSocketStompClient` + `MappingJackson2MessageConverter` connects two clients and drives the full lobby→challenge→match flow, asserting on the DTOs. There's no .NET analog to spinning the real server in-process this cheaply.

**Why Spring does it this way:** a real broker + transport in the test catches wiring bugs a mock never would (it caught the self-broadcast behavior immediately).

> **Watch out:** `@SpringBootTest` starts the entire context (slower than a unit test — use sparingly). And two reconnect realities bit here: a `/topic` subscriber receives its **own** join echo (the test filters self), and the STOMP **session id changes on reconnect**, so durable rejoin needs a stable token, not the session id.

---

## Lesson 11 — The thin JS client & serving static from Spring Boot

*Files: `src/main/resources/static/{index.html,js/app.js,js/stomp.umd.min.js,css/*}`, `multiplayer/LocalGameController.java`, `e2e/`.*

**Concepts introduced:** Spring Boot static hosting, a browser STOMP client, and extending the wire contract.

- **`src/main/resources/static/` ≈ ASP.NET `wwwroot`.** Spring Boot serves it at `/` automatically — no controller, no route config. `index.html` is the default document. (The legacy Blazor Server rendered the UI server-side over the circuit; now the server is *pure messaging + static files* and rendering is client-side JS.)
- **`@stomp/stompjs` `Client` ≈ the SignalR JS client (`HubConnectionBuilder`).** `brokerURL: ws://host/gamehub` instead of `.withUrl()`. You **subscribe to destinations** (`/topic/lobby/joined`, `/user/queue/match-state`) rather than `connection.on("EventName", …)`. Sending is `client.publish({destination, body})` vs `connection.invoke("Method", …)`.
- **Extending the contract:** local play had no SignalR ancestor, so I added new `/app/local/*` destinations + a `LocalStateEvent` snapshot — cleanly separated from the ported hub DTOs.

**Why Spring does it this way:** decoupling static hosting from app logic means the same server is a message broker *and* a web host with zero glue.

> **Watch out:** two different messages on one `/topic` can't be told apart by a JS subscriber (they're just JSON) — I split lobby into `/topic/lobby/joined` and `/topic/lobby/left`. Vendoring `stomp.umd.min.js` (no npm build step) keeps the client thin but means *you* own updates/security — so the download was checksum-checked and virus-scanned. The Playwright `e2e/` suite is deliberately outside `./gradlew build` (needs Node + a browser download); the server flow stays covered by `StompIntegrationTest`.

---

## Lesson 12 — Testing the messaging backend & the session-id gotcha

*Files: `multiplayer/MultiplayerEndToEndTest.java`, `ScheduledTimersTest.java`, `StompTestSupport.java`, `LobbyClient.java`.*

**Concepts introduced:** property-overridden `@SpringBootTest` contexts, testing time-based logic without sleeps, and the client-vs-server STOMP session id.

- **`@SpringBootTest(properties = {…})` ≈ spinning the app with a test-only `appsettings`.** I shrank the timers (`tictactoe.challenge.lifetime-ms=300`, `disconnect-grace-seconds=1`) so the `@Scheduled` sweep and the `TaskScheduler` forfeit fire in ~1s instead of 30. (Externalizing those constants — Lesson 9's `@Value` — is what made this possible.)
- **Negative assertions:** "no cross-talk between two matches" is a *short-timeout poll that must return nothing* — proving a per-`/user/queue` message did **not** reach a third session.
- **The gotcha that failed my first test:** a STOMP client's own `session.getSessionId()` is **not** the server's `simpSessionId`. Identity is *server-assigned* (the analog of SignalR's `Context.ConnectionId`) and handed to the client as `yourConnId` — the client never independently knows it.

**Why Spring does it this way:** the server owns identity and routing; the client is told who it is. Tests assert that the server reports one consistent id across views and keys session state by it.

> **Watch out (no clean C# analog):** don't assume the client and server agree on the STOMP session id — they don't. Use the server-supplied `yourConnId`. And each distinct `properties` set creates a *separate* application context (extra startup cost; Spring caches one per unique config), so group same-config tests together.

---

## Lesson 13 — Durable reconnect: capability tokens & per-tab storage

*Files: `multiplayer/MultiplayerMatch.java`, `GameMessageController.java` (`rejoinMatch`), `dto/ChallengeAcceptedEvent.java`, `dto/RejoinMatchRequest.java`, `static/js/app.js`; tests: `ReconnectTest.java`.*

**Concepts introduced:** reconnect identity, capability tokens, and the browser-storage choice.

- **Why the session id can't be the rejoin identity:** the STOMP session id changes on every reconnect (Lesson 12). So the server issues a per-faction **token** at match start (in `ChallengeAcceptedEvent.yourToken`); the client presents it to `/app/match/rejoin`, and the server maps token → faction slot and swaps in the new session. This is a *capability token* — holding it proves you own the slot. (SignalR's connection id also rotates on reconnect; the legacy `RejoinMatch` swapped the id but took **no token**, so any client could hijack a disconnected slot — `ReconnectTest` now proves a bogus token is rejected.)
- **The browser-storage gotcha (found in a real browser, not a unit test):** I first stored the token in `localStorage` — which is **shared across all tabs of an origin**, so a second tab's match clobbered the first's token, and a reloaded tab rejoined as the *wrong faction*. Fix: **`sessionStorage`**, which is **per-tab**, survives a reload, and auto-clears on tab close — exactly the lifetime a reconnect token wants.

**Why it matters:** identity that must survive a transport reconnect has to be a secret the *client* holds, never something the transport assigns.

> **Watch out (no clean C# analog):** a rejoin endpoint without a token is a hijack vector — always authenticate the reclaim. And `localStorage` vs `sessionStorage` is a correctness decision, not a style one: shared-vs-per-tab scope changed the behavior.
