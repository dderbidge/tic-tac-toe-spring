// Playwright config for the multiplayer client smoke.
// Start the server first (`./gradlew bootRun`), then: cd e2e && npm install && npm test
const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
    testDir: ".",
    testMatch: "**/*.spec.js", // Node message-order tests run separately via test:client.
    timeout: 30000,
    expect: { timeout: 10000 },
    use: {
        baseURL: "http://localhost:8080",
        trace: "on-first-retry"
    },
    // Reuses a server you already started; otherwise launches one (needs the Gradle wrapper on PATH-relative `..`).
    webServer: {
        command: process.platform === "win32" ? "..\\gradlew.bat bootRun" : "../gradlew bootRun",
        url: "http://localhost:8080/",
        reuseExistingServer: true,
        timeout: 120000
    }
});
