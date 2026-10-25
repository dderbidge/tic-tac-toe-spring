const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// Exercise the shipped client through its DOM events and STOMP subscriptions.
// No test-only exports or browser dependency are needed for message-order tests.
function client(saved) {
    const subscriptions = new Map(), sent = [], timers = new Map(), storage = new Map();
    const handlers = {};
    let transport, nextTimer = 1;
    if (saved) storage.set("ttt.match", JSON.stringify(saved));
    const document = { activeElement: null, getElementById: () => main };
    const main = {
        innerHTML: "", addEventListener: (name, handler) => { handlers[name] = handler; },
        contains: () => false, querySelector: () => null, querySelectorAll: () => []
    };
    class Transport {
        constructor() { transport = this; this.connected = true; }
        activate() {}
        subscribe(destination, handler) { subscriptions.set(destination, handler); }
        publish(frame) { sent.push({ destination: frame.destination, payload: frame.body ? JSON.parse(frame.body) : null }); }
    }
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/js/app.js"), "utf8"), {
        document, location: { protocol: "http:", host: "localhost" }, StompJs: { Client: Transport },
        sessionStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
        setTimeout: callback => { const id = nextTimer++; timers.set(id, callback); return id; },
        clearTimeout: id => timers.delete(id)
    });
    transport.onConnect();
    return {
        get html() { return main.innerHTML; }, sent, timers, storage, transport,
        emit(destination, payload) {
            assert.ok(subscriptions.has(destination), "subscription exists: " + destination);
            subscriptions.get(destination)({ body: JSON.stringify(payload) });
        },
        click(action, attrs = {}) {
            const data = { "data-action": action, ...attrs };
            const el = { getAttribute: key => data[key] ?? null, disabled: false };
            handlers.click({ target: { closest: () => el } });
        },
        commands(destination) { return sent.filter(frame => frame.destination === destination); }
    };
}

function localState(overrides = {}) {
    return { phase: "InMatch", board: [[0, 0, 0], [0, 0, 0], [0, 0, 0]], active: "Ascendant",
        versusComputer: false, humanSide: "Ascendant", winner: null, isDraw: false,
        ascendantWins: 0, vanguardWins: 0, draws: 0, ...overrides };
}

function networkMatch(c) {
    c.emit("/user/queue/local-state", localState({ phase: "MainMenu" }));
    c.click("network");
    c.emit("/user/queue/lobby-state", { yourConnId: "self", yourName: "Test Pilot", players: [{ connId: "other", name: "Test Rival" }] });
    c.click("challenge", { "data-conn": "other" });
    c.emit("/user/queue/challenge-accepted", { matchId: "current", yourFaction: "Ascendant", yourToken: "sample-token", opponentName: "Test Rival" });
    c.emit("/user/queue/match-state", { matchId: "current", board: [[0, 0, 0], [0, 0, 0], [0, 0, 0]], activeFaction: "Ascendant" });
}

test("board cells are named native buttons and turn status is announced", () => {
    const c = client();
    c.emit("/user/queue/local-state", localState());
    assert.equal((c.html.match(/<button\b[^>]*class="cell/g) || []).length, 9);
    assert.match(c.html, /aria-label="Row 1, column 1: empty"/);
    assert.match(c.html, /role="status"/);
});

test("rapid local cell clicks send one move until the server responds", () => {
    const c = client();
    c.emit("/user/queue/local-state", localState());
    c.click("local-move", { "data-r": "0", "data-c": "0" });
    c.click("local-move", { "data-r": "0", "data-c": "1" });
    assert.equal(c.commands("/app/local/move").length, 1);
    c.emit("/user/queue/local-state", localState({ board: [[1, 0, 0], [0, 0, 0], [0, 0, 0]], active: "Vanguard" }));
    c.click("local-move", { "data-r": "0", "data-c": "1" });
    assert.equal(c.commands("/app/local/move").length, 2);
});

test("stale match events cannot replace the current game or clear its token", () => {
    const c = client(); networkMatch(c);
    const before = c.html;
    c.emit("/user/queue/match-state", { matchId: "old", board: [[2, 2, 2], [0, 0, 0], [0, 0, 0]], activeFaction: "Vanguard" });
    c.emit("/user/queue/match-ended", { matchId: "old", result: "loss", reason: "forfeit" });
    assert.equal(c.html, before);
    assert.equal(JSON.parse(c.storage.get("ttt.match")).matchId, "current");
});

test("a delayed nonterminal snapshot cannot reopen a completed match", () => {
    const c = client(); networkMatch(c);
    c.emit("/user/queue/match-ended", { matchId: "current", result: "win", reason: "forfeit" });
    c.emit("/user/queue/match-state", { matchId: "current", board: [[0, 0, 0], [0, 0, 0], [0, 0, 0]], activeFaction: "Ascendant" });
    assert.match(c.html, /forfeited . you win/);
    assert.doesNotMatch(c.html, /cell-empty/);
});

test("leaving a reconnect cancels its fallback and ignores late state", () => {
    const c = client({ matchId: "expired", token: "sample-token", faction: "Ascendant", opponentName: "Test Rival" });
    const callbacks = [...c.timers.values()];
    c.click("leave-network");
    c.emit("/user/queue/local-state", localState({ phase: "MainMenu" }));
    c.click("network");
    c.emit("/user/queue/lobby-state", { yourConnId: "new", yourName: "Test Pilot", players: [] });
    const before = c.html;
    callbacks.forEach(callback => callback()); // Even a queued, already-dequeued callback must be harmless.
    c.emit("/user/queue/match-state", { matchId: "expired", board: [[1, 0, 0], [0, 0, 0], [0, 0, 0]], activeFaction: "Vanguard" });
    assert.equal(c.html, before);
    assert.equal(c.storage.has("ttt.match"), false);
});

test("late opponent events from another match are ignored", () => {
    const c = client(); networkMatch(c);
    const before = c.html;
    c.emit("/user/queue/opponent-disconnected", { matchId: "old" });
    assert.equal(c.html, before);
});

test("cancel before challenge acknowledgement cancels its server challenge", () => {
    const c = client();
    c.emit("/user/queue/local-state", localState({ phase: "MainMenu" }));
    c.click("network");
    c.emit("/user/queue/lobby-state", { yourConnId: "self", yourName: "Test Pilot", players: [{ connId: "other", name: "Test Rival" }] });
    c.click("challenge", { "data-conn": "other" });
    c.click("cancel-pending");
    assert.equal(c.commands("/app/challenge/cancel").length, 0);
    c.emit("/user/queue/challenge-sent", { challengeId: "challenge-1", targetConnId: "other", targetName: "Test Rival" });
    assert.deepEqual(c.commands("/app/challenge/cancel")[0].payload, { challengeId: "challenge-1" });
    c.emit("/user/queue/challenge-expired", { challengeId: "challenge-1", role: "from" });
    assert.doesNotMatch(c.html, /lobby-pending/);
    assert.match(c.html, /data-action="challenge"/);
});

test("stale challenge expiry does not dismiss a newer invitation", () => {
    const c = client();
    c.emit("/user/queue/local-state", localState({ phase: "MainMenu" }));
    c.click("network");
    c.emit("/user/queue/lobby-state", { yourConnId: "self", yourName: "Test Pilot", players: [] });
    c.emit("/user/queue/challenge-incoming", { challengeId: "new", fromConnId: "other", fromName: "Test Rival" });
    c.emit("/user/queue/challenge-expired", { challengeId: "old", role: "to" });
    assert.match(c.html, /challenge-modal/);
});

test("network duplicate moves wait for matching state; unrelated state cannot unlock", () => {
    const c = client(); networkMatch(c);
    c.click("net-move", { "data-r": "0", "data-c": "0" });
    c.emit("/user/queue/match-state", { matchId: "old", board: [[0, 0, 0], [0, 0, 0], [0, 0, 0]], activeFaction: "Ascendant" });
    c.click("net-move", { "data-r": "0", "data-c": "1" });
    assert.equal(c.commands("/app/match/move").length, 1);
    c.emit("/user/queue/match-state", { matchId: "current", board: [[0, 0, 0], [0, 0, 0], [0, 0, 0]], activeFaction: "Ascendant" });
    c.click("net-move", { "data-r": "0", "data-c": "1" });
    assert.equal(c.commands("/app/match/move").length, 2);
});

test("replay and score reset commands cannot race an unacknowledged command", () => {
    const c = client();
    c.emit("/user/queue/local-state", localState({ phase: "MatchOver", winner: "Ascendant", ascendantWins: 1 }));
    c.click("play-again");
    c.click("play-again");
    c.click("reset-score");
    assert.equal(c.commands("/app/local/play-again").length, 1);
    assert.equal(c.commands("/app/local/reset").length, 0);
    c.emit("/user/queue/local-state", localState({ ascendantWins: 1 }));
    c.click("reset-score");
    assert.equal(c.commands("/app/local/reset").length, 1);
});

test("a local game offers a return to menu before the round ends", () => {
    const c = client(); c.emit("/user/queue/local-state", localState());
    assert.match(c.html, /data-action="main-menu">Main menu/);
    c.click("main-menu");
    assert.equal(c.commands("/app/local/main-menu").length, 1);
});
