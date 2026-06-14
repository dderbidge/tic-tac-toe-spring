# Order vs Rebellion — Spring Boot edition

A space-opera tic-tac-toe (**The Ascendant ☼ vs The Crimson Vanguard ✦**), ported from a
.NET 9 solution to **Java 21 + Spring Boot 3.3**. It ships three things from one shared game
engine:

- a colourful **terminal** game,
- a **multiplayer web** app (lobby → challenge → match) over STOMP/WebSocket,
- **local** play (hot-seat and vs-CPU) in the same browser client.

Three AI tiers: **Recruit** (random) / **Captain** (one-ply tactical) / **Warlord** (perfect
minimax — never loses).

## Quick start

Needs **JDK 21**. The Gradle wrapper handles the rest.

```bash
# multiplayer + local web app  → http://localhost:8080
./gradlew bootRun

# terminal game
./gradlew runConsole

# tests (domain, console parity, STOMP integration, reconnect)
./gradlew test
```

Open `http://localhost:8080` in two windows to try multiplayer. A dropped/reloaded tab
rejoins its match automatically (durable reconnect via a server-issued token).

## Layout

```
src/main/java/com/derbysquad/tictactoe/
├── domain/        game engine (Board, minimax Ai, ThemeText) — framework-free
├── console/       terminal app (byte-identical output parity with the C# original)
├── multiplayer/   STOMP config, @MessageMapping handlers, registry/session services
├── config/        @Configuration, seeded Random, scheduler
└── dto/           shared wire-contract records (the client ↔ server messages)
src/main/resources/static/   thin vanilla-JS client + ported theme
LEARNING/journal.md          a C#→Spring learning course written alongside the port
e2e/                         optional Playwright browser smoke (Node; not part of the build)
```

## Notes

- This is a faithful port of a .NET 9 app. Migration analysis is in
  [`MIGRATION_ANALYSIS.md`](MIGRATION_ANALYSIS.md); a play-by-play learning log is in
  [`LEARNING/journal.md`](LEARNING/journal.md).
- The browser client vendors a minified copy of **[@stomp/stompjs](https://stomp-js.github.io/)**
  (Apache-2.0) at `src/main/resources/static/js/stomp.umd.min.js`.
- State is in-memory and single-instance by design (no database).

## License

Currently proprietary — all rights reserved. See [LICENSE](LICENSE).
