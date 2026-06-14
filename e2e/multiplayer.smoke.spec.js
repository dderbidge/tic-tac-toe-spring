// Browser smoke for the thin JS client (migration Task 9 / checklist verification).
// Mirrors the flow verified manually: local vs-CPU + a full two-client network match.
const { test, expect } = require("@playwright/test");

// Stable selectors via the client's data-action attributes.
const cell = (action, r, c) => `[data-action="${action}"][data-r="${r}"][data-c="${c}"]`;

test("local vs Warlord — AI responds to a human move", async ({ page }) => {
    await page.goto("/");
    await page.click('[data-action="vs-setup"]');
    await page.click('[data-action="sel-difficulty"][data-difficulty="Warlord"]');
    await page.click('[data-action="begin-duel"]');

    // Human (Ascendant) takes the center; the server's Warlord replies.
    await page.click(cell("local-move", 1, 1));

    await expect(page.locator(".sigil-ascendant:not(.sigil-ghost)")).toHaveCount(1);
    await expect(page.locator(".sigil-vanguard:not(.sigil-ghost)")).toHaveCount(1);
    await expect(page.locator(".status-text")).toContainText("claim a sector");
});

test("network — two clients play a full match to a win", async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const p1 = await ctx1.newPage(); // will accept -> Vanguard
    const p2 = await ctx2.newPage(); // will challenge -> Ascendant

    await p1.goto("/");
    await p1.click('[data-action="network"]');
    await expect(p1.locator(".your-name")).toBeVisible();

    await p2.goto("/");
    await p2.click('[data-action="network"]');
    await expect(p2.locator(".lobby-row")).toBeVisible(); // p2 sees p1

    await p2.click('[data-action="challenge"]');
    await expect(p1.locator(".challenge-modal")).toBeVisible();
    await p1.click('[data-action="accept"]');

    // p2 is the challenger (Ascendant, moves first).
    await expect(p2.locator(".status-text")).toContainText("Your move");

    // Ascendant (p2) wins the top row; Vanguard (p1) plays elsewhere. Playwright
    // auto-waits for each cell to become interactive (i.e. for the player's turn).
    await p2.click(cell("net-move", 0, 0));
    await p1.click(cell("net-move", 1, 0));
    await p2.click(cell("net-move", 0, 1));
    await p1.click(cell("net-move", 1, 1));
    await p2.click(cell("net-move", 0, 2)); // winning move

    await expect(p2.locator(".status-text")).toContainText("Victory");
    await expect(p1.locator(".status-text")).toContainText("holds the line");

    await ctx1.close();
    await ctx2.close();
});
