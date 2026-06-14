# Browser smoke (Playwright)

Automated browser test for the thin multiplayer client — local vs-CPU and a full
two-client network match.

```bash
# from repo root, start the server in one terminal:
./gradlew bootRun

# in another terminal:
cd e2e
npm install
npm run install-browsers   # one-time: downloads Chromium
npm test
```

`playwright.config.js` has `reuseExistingServer: true`, so it attaches to a server you
already started (and otherwise tries to launch one via the Gradle wrapper).

These tests are intentionally **not** part of `./gradlew build` — they need Node + a
browser download. The server-side flow is covered automatically in the Gradle build by
`StompIntegrationTest`.
