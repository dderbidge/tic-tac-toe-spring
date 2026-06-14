/*
 * Thin multiplayer client for Order vs Rebellion (migration Task 9).
 *
 * Vanilla JS + @stomp/stompjs over native WebSocket. Mirrors the legacy Blazor
 * Game.razor state machine. Two transports share one STOMP connection:
 *   - Local play  -> /app/local/*  (server pushes /user/queue/local-state)
 *   - Network play -> /app/lobby|challenge|match/*  (the ported SignalR hub)
 *
 * Identity is the STOMP session id, assigned by the server; the client never sets it.
 */
(function () {
    "use strict";

    var GLYPH = { Ascendant: "☼", Vanguard: "✦" }; // ☼ ✦
    var NAME = { Ascendant: "The Ascendant", Vanguard: "The Crimson Vanguard" };
    var DIFFICULTIES = [
        { id: "Recruit", note: "Random — beatable warm-up." },
        { id: "Captain", note: "Tactical — wins or blocks one move ahead." },
        { id: "Warlord", note: "Unbeatable — perfect play." }
    ];

    var main = document.getElementById("app-main");

    // ── State ────────────────────────────────────────────────────────────────
    var stomp = null;
    var mode = "local";                 // "local" | "network"
    var local = null;                   // latest LocalStateEvent
    var setup = { faction: "Ascendant", difficulty: "Captain" };
    var net = blankNet();
    var incoming = null;                // {challengeId, fromConnId, fromName}
    var pending = null;                 // {targetName}
    var connecting = true;
    var rejoinPending = false;          // awaiting state after a reconnect rejoin

    // Durable-reconnect storage: the server-issued match token survives a page reload /
    // dropped socket, letting us reclaim our faction slot (the STOMP session id can't —
    // it changes on reconnect). Uses sessionStorage, NOT localStorage: it is per-tab (two
    // tabs in one browser don't clobber each other's token) and auto-clears on tab close.
    var MATCH_KEY = "ttt.match";
    function saveMatch(m) { try { sessionStorage.setItem(MATCH_KEY, JSON.stringify(m)); } catch (e) {} }
    function loadMatch() { try { return JSON.parse(sessionStorage.getItem(MATCH_KEY)); } catch (e) { return null; } }
    function clearMatch() { try { sessionStorage.removeItem(MATCH_KEY); } catch (e) {} }

    // If a rejoin gets no response in time (match expired during the outage), drop the
    // stale token and fall back to the main menu.
    function startRejoinFallback() {
        setTimeout(function () {
            if (rejoinPending) {
                rejoinPending = false;
                clearMatch();
                mode = "local";
                send("/app/local/state");
                render();
            }
        }, 3000);
    }

    function blankNet() {
        return {
            phase: "connecting",        // connecting|lobby|inMatch|matchOver
            connId: null, name: null,
            players: [],                // [{connId,name}]
            matchId: null, opponentName: null, yourFaction: null,
            board: emptyBoard(), active: "Ascendant",
            winner: null, isDraw: false, winningCells: null,
            endMessage: null, resultClass: "none",
            opponentDisconnected: false
        };
    }
    function emptyBoard() { return [[0, 0, 0], [0, 0, 0], [0, 0, 0]]; }

    // ── Connection ─────────────────────────────────────────────────────────────
    function connect() {
        var url = (location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/gamehub";
        stomp = new StompJs.Client({ brokerURL: url, reconnectDelay: 3000 });

        stomp.onConnect = function () {
            connecting = false;
            // Local play
            sub("/user/queue/local-state", onLocalState);
            // Network: lobby
            sub("/user/queue/lobby-state", onLobbyState);
            sub("/topic/lobby/joined", onPlayerJoined);
            sub("/topic/lobby/left", onPlayerLeft);
            // Network: challenges
            sub("/user/queue/challenge-incoming", onIncomingChallenge);
            sub("/user/queue/challenge-accepted", onChallengeAccepted);
            sub("/user/queue/challenge-declined", onChallengeDeclined);
            sub("/user/queue/challenge-expired", onChallengeExpired);
            // Network: match
            sub("/user/queue/match-state", onMatchState);
            sub("/user/queue/match-ended", onMatchEnded);
            sub("/user/queue/opponent-disconnected", onOpponentDisconnected);
            sub("/user/queue/opponent-reconnected", onOpponentReconnected);

            // Durable reconnect: if we have a saved match token, reclaim our slot;
            // otherwise fetch the initial local MainMenu snapshot.
            var saved = loadMatch();
            if (saved && saved.matchId && saved.token) {
                mode = "network";
                net = blankNet();
                net.phase = "connecting";
                net.matchId = saved.matchId;
                net.yourFaction = saved.faction;
                net.opponentName = saved.opponentName;
                rejoinPending = true;
                send("/app/match/rejoin", { matchId: saved.matchId, token: saved.token });
                startRejoinFallback();
                render();
            } else {
                send("/app/local/state");
            }
        };
        stomp.onWebSocketClose = function () { connecting = true; render(); };
        stomp.activate();
    }

    function sub(dest, handler) {
        stomp.subscribe(dest, function (frame) {
            handler(frame.body ? JSON.parse(frame.body) : {});
        });
    }
    function send(dest, payload) {
        stomp.publish({ destination: dest, body: payload ? JSON.stringify(payload) : "" });
    }

    // ── Local-play handlers ────────────────────────────────────────────────────
    function onLocalState(s) {
        local = s;
        if (mode === "local") render();
    }

    // ── Network handlers ────────────────────────────────────────────────────────
    function onLobbyState(s) {
        net.connId = s.yourConnId;
        net.name = s.yourName;
        net.players = (s.players || []).filter(function (p) { return p.connId !== s.yourConnId; });
        net.phase = "lobby";
        if (mode === "network") render();
    }
    function onPlayerJoined(p) {
        if (p.connId === net.connId) return; // ignore our own echo (no "others in group")
        if (!net.players.some(function (x) { return x.connId === p.connId; })) {
            net.players.push({ connId: p.connId, name: p.name });
        }
        if (mode === "network") render();
    }
    function onPlayerLeft(p) {
        net.players = net.players.filter(function (x) { return x.connId !== p.connId; });
        if (mode === "network") render();
    }
    function onIncomingChallenge(c) {
        incoming = c;
        render();
    }
    function onChallengeAccepted(r) {
        // On a fresh accept this is a new match; on a reconnect-rejoin it re-confirms the
        // current one, so don't wipe a board that match-state may have already restored.
        var isNewMatch = net.matchId !== r.matchId;
        net.matchId = r.matchId;
        net.opponentName = r.opponentName;
        net.yourFaction = r.yourFaction;
        if (isNewMatch) {
            net.board = emptyBoard();
            net.active = "Ascendant";
            net.winner = null; net.isDraw = false; net.winningCells = null;
        }
        net.endMessage = null; net.opponentDisconnected = false;
        net.phase = (net.winner || net.isDraw) ? "matchOver" : "inMatch";
        pending = null; incoming = null;
        rejoinPending = false;
        saveMatch({ matchId: r.matchId, token: r.yourToken, faction: r.yourFaction, opponentName: r.opponentName });
        if (mode === "network") render();
    }
    function onChallengeDeclined() { pending = null; if (mode === "network") render(); }
    function onChallengeExpired(e) {
        if (e.role === "from") pending = null;
        else if (e.role === "to") incoming = null;
        render();
    }
    function onMatchState(m) {
        rejoinPending = false;
        net.matchId = m.matchId;
        net.board = m.board;
        net.active = m.activeFaction;
        net.winningCells = m.winningCells || null;
        net.phase = (m.winner || m.isDraw) ? "matchOver" : "inMatch";
        if (mode === "network") render();
    }
    function onMatchEnded(e) {
        clearMatch(); // match is over — don't try to rejoin it later
        net.phase = "matchOver";
        net.winningCells = e.winningCells || net.winningCells;
        var opp = net.opponentName;
        if (e.result === "win" && e.reason === "forfeit") net.endMessage = opp + " forfeited — you win.";
        else if (e.result === "loss" && e.reason === "forfeit") net.endMessage = "You forfeited — " + opp + " wins.";
        else if (e.result === "win") net.endMessage = "You hold the line. Victory.";
        else if (e.result === "loss") net.endMessage = opp + " holds the line.";
        else net.endMessage = "Stalemate. The galaxy holds its breath.";
        net.resultClass = e.result === "win" ? lc(net.yourFaction)
            : e.result === "loss" ? lc(other(net.yourFaction)) : "none";
        if (mode === "network") render();
    }
    function onOpponentDisconnected() { net.opponentDisconnected = true; if (mode === "network") render(); }
    function onOpponentReconnected() { net.opponentDisconnected = false; if (mode === "network") render(); }

    // ── Helpers ─────────────────────────────────────────────────────────────────
    function lc(f) { return f === "Ascendant" ? "ascendant" : f === "Vanguard" ? "vanguard" : "none"; }
    function other(f) { return f === "Ascendant" ? "Vanguard" : "Ascendant"; }
    function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
    function factionOf(ord) { return ord === 1 ? "Ascendant" : ord === 2 ? "Vanguard" : null; }
    function isWinning(cells, r, c) {
        return !!cells && cells.some(function (w) { return w.row === r && w.col === c; });
    }

    // ── Board rendering ──────────────────────────────────────────────────────────
    // opts: { board, interactive(r,c), hoverFaction, winningCells, action }
    function boardHtml(opts) {
        var html = '<div class="board-grid">';
        for (var r = 0; r < 3; r++) {
            for (var c = 0; c < 3; c++) {
                var ord = opts.board[r][c];
                var faction = factionOf(ord);
                var interactive = !faction && opts.interactive(r, c);
                var won = isWinning(opts.winningCells, r, c);
                var cls = "cell" + (interactive ? " cell-empty" : "") + (won ? " cell-won" : "");
                var attrs = interactive
                    ? ' data-action="' + opts.action + '" data-r="' + r + '" data-c="' + c + '"' : "";
                var inner = "";
                if (faction) {
                    inner = '<div class="sigil sigil-' + lc(faction) + (won ? " won" : "") + '">'
                        + '<span class="sigil-glyph">' + GLYPH[faction] + "</span></div>";
                } else if (interactive && opts.hoverFaction) {
                    inner = '<div class="sigil sigil-' + lc(opts.hoverFaction) + ' sigil-ghost">'
                        + '<span class="sigil-glyph">' + GLYPH[opts.hoverFaction] + "</span></div>";
                }
                html += '<div class="' + cls + '"' + attrs + ">" + inner + "</div>";
            }
        }
        return html + "</div>";
    }

    function scoreHtml(asc, van, draws, showReset) {
        return '<div class="score-board">'
            + '<div class="score score-ascendant"><span class="score-sigil">' + GLYPH.Ascendant + "</span>"
            + '<span class="score-name">' + NAME.Ascendant + '</span><span class="score-value">' + asc + "</span></div>"
            + '<div class="score score-draws"><span class="score-name">Stalemates</span>'
            + '<span class="score-value">' + draws + "</span></div>"
            + '<div class="score score-vanguard"><span class="score-sigil">' + GLYPH.Vanguard + "</span>"
            + '<span class="score-name">' + NAME.Vanguard + '</span><span class="score-value">' + van + "</span></div>"
            + (showReset ? '<button class="btn btn-link" data-action="reset-score">Reset score</button>' : "")
            + "</div>";
    }

    // ── Screens ──────────────────────────────────────────────────────────────────
    function render() {
        if (connecting) { main.innerHTML = panel("<h2 class='subtitle'>Connecting…</h2>"); return; }
        var html;
        if (mode === "network") html = renderNetwork();
        else html = renderLocal();
        if (incoming) html += challengeModalHtml();
        main.innerHTML = html;
    }

    function panel(inner) { return '<div class="menu-panel">' + inner + "</div>"; }

    function renderLocal() {
        var phase = local ? local.phase : "MainMenu";
        if (phase === "MainMenu") {
            return panel(
                '<h1 class="title">Order vs Rebellion</h1>'
                + '<p class="tagline">A duel of cosmic sigils.</p>'
                + '<div class="menu-buttons">'
                + '<button class="btn btn-primary" data-action="hotseat">Hot-seat (two humans)</button>'
                + '<button class="btn btn-primary" data-action="vs-setup">Versus the computer</button>'
                + '<button class="btn btn-primary" data-action="network">Network multiplayer</button>'
                + "</div>");
        }
        if (phase === "ModeSetup") return renderModeSetup();
        return renderLocalMatch();
    }

    function renderModeSetup() {
        var fac = ["Ascendant", "Vanguard"].map(function (f) {
            var sel = setup.faction === f ? " selected" : "";
            return '<button class="faction-card faction-' + lc(f) + sel + '" data-action="sel-faction" data-faction="' + f + '">'
                + '<div class="sigil sigil-' + lc(f) + '"><span class="sigil-glyph">' + GLYPH[f] + "</span></div>"
                + '<div class="faction-name">' + NAME[f] + "</div>"
                + '<div class="faction-note">' + (f === "Ascendant" ? "Moves first" : "Moves second") + "</div></button>";
        }).join("");
        var diff = DIFFICULTIES.map(function (d) {
            var sel = setup.difficulty === d.id ? " selected" : "";
            return '<button class="difficulty-card' + sel + '" data-action="sel-difficulty" data-difficulty="' + d.id + '">'
                + '<div class="difficulty-name">' + d.id + "</div>"
                + '<div class="difficulty-note">' + d.note + "</div></button>";
        }).join("");
        return panel(
            '<h2 class="subtitle">Choose your faction</h2><div class="faction-row">' + fac + "</div>"
            + '<h2 class="subtitle">Choose opponent difficulty</h2><div class="difficulty-row">' + diff + "</div>"
            + '<div class="menu-buttons">'
            + '<button class="btn btn-secondary" data-action="main-menu">Back</button>'
            + '<button class="btn btn-primary" data-action="begin-duel">Begin duel</button></div>');
    }

    function renderLocalMatch() {
        var g = local;
        var over = g.phase === "MatchOver";
        var status;
        if (over && g.winner) status = '<span class="status-text status-' + lc(g.winner) + '">' + NAME[g.winner] + " holds the line — three sectors in a row.</span>";
        else if (over && g.isDraw) status = '<span class="status-text">Stalemate. The galaxy holds its breath.</span>';
        else status = '<span class="status-text status-' + lc(g.active) + '">' + NAME[g.active] + " — claim a sector</span>";

        var hover = g.versusComputer ? g.humanSide : g.active;
        var board = boardHtml({
            board: g.board, hoverFaction: hover, winningCells: g.winningCells, action: "local-move",
            interactive: function () {
                return g.phase === "InMatch" && (!g.versusComputer || g.active === g.humanSide);
            }
        });
        var buttons = over ? '<div class="menu-buttons">'
            + '<button class="btn btn-primary" data-action="play-again">Play again</button>'
            + '<button class="btn btn-secondary" data-action="main-menu">Main menu</button></div>' : "";
        return '<div class="match-panel"><div class="status-banner">' + status + "</div>"
            + board + scoreHtml(g.ascendantWins, g.vanguardWins, g.draws, true) + buttons + "</div>";
    }

    function renderNetwork() {
        if (net.phase === "connecting") return panel("<h2 class='subtitle'>Connecting…</h2>"
            + '<div class="menu-buttons"><button class="btn btn-secondary" data-action="leave-network">Cancel</button></div>');
        if (net.phase === "lobby") return renderLobby();
        return renderNetworkMatch();
    }

    function renderLobby() {
        var body;
        if (pending) {
            body = '<div class="lobby-pending">Waiting for ' + esc(pending.targetName) + " to respond…"
                + '<button class="btn btn-link" data-action="cancel-pending">Cancel</button></div>';
        } else if (net.players.length === 0) {
            body = '<div class="lobby-empty">No other players right now. Open this URL in another window to play.</div>';
        } else {
            body = '<div class="lobby-list">' + net.players.map(function (p) {
                return '<div class="lobby-row"><span class="name">' + esc(p.name) + "</span>"
                    + '<button class="btn btn-primary btn-challenge" data-action="challenge" data-conn="' + p.connId + '">⚔ Challenge</button></div>';
            }).join("") + "</div>";
        }
        return '<div class="lobby-panel"><div class="lobby-self"><div class="label">You are</div>'
            + '<div class="your-name">' + esc(net.name || "") + "</div></div>" + body
            + '<div class="menu-buttons"><button class="btn btn-secondary" data-action="leave-network">Back to menu</button></div></div>';
    }

    function renderNetworkMatch() {
        var status;
        if (net.phase === "matchOver") status = '<span class="status-text status-' + net.resultClass + '">' + esc(net.endMessage || "Match ended.") + "</span>";
        else status = '<span class="status-text status-' + lc(net.active) + '">'
            + (net.active === net.yourFaction ? "Your move" : esc(net.opponentName) + "'s move…") + "</span>";

        var banner = net.opponentDisconnected
            ? '<div class="disconnect-banner"><span>' + esc(net.opponentName) + " has disconnected.</span></div>" : "";
        var board = boardHtml({
            board: net.board, hoverFaction: net.yourFaction, winningCells: net.winningCells, action: "net-move",
            interactive: function () {
                return net.phase === "inMatch" && net.active === net.yourFaction && !net.opponentDisconnected;
            }
        });
        var buttons = net.phase === "matchOver"
            ? '<div class="menu-buttons"><button class="btn btn-primary" data-action="back-lobby">Back to lobby</button>'
            + '<button class="btn btn-secondary" data-action="leave-network">Main menu</button></div>'
            : '<div class="menu-buttons"><button class="btn btn-link" data-action="forfeit">Forfeit</button></div>';
        return '<div class="match-panel"><div class="status-banner">' + status + "</div>" + banner + board + buttons + "</div>";
    }

    function challengeModalHtml() {
        return '<div class="challenge-modal-backdrop"><div class="challenge-modal">'
            + "<h3>" + esc(incoming.fromName) + " wants to duel</h3><p>The Ascendant moves first.</p>"
            + '<div class="menu-buttons"><button class="btn btn-primary" data-action="accept">Accept</button>'
            + '<button class="btn btn-secondary" data-action="decline">Decline</button></div></div></div>';
    }

    // ── Event delegation ─────────────────────────────────────────────────────────
    main.addEventListener("click", function (ev) {
        var el = ev.target.closest("[data-action]");
        if (!el) return;
        var a = el.getAttribute("data-action");
        switch (a) {
            // local
            case "hotseat": send("/app/local/hotseat"); break;
            case "vs-setup": send("/app/local/setup"); break;
            case "begin-duel": send("/app/local/vs-computer", { faction: setup.faction, difficulty: setup.difficulty }); break;
            case "sel-faction": setup.faction = el.getAttribute("data-faction"); render(); break;
            case "sel-difficulty": setup.difficulty = el.getAttribute("data-difficulty"); render(); break;
            case "local-move": send("/app/local/move", { row: +el.getAttribute("data-r"), col: +el.getAttribute("data-c") }); break;
            case "play-again": send("/app/local/play-again"); break;
            case "main-menu": send("/app/local/main-menu"); break;
            case "reset-score": send("/app/local/reset"); break;
            // mode switch
            case "network": mode = "network"; net = blankNet(); send("/app/lobby/enter"); render(); break;
            case "leave-network": clearMatch(); send("/app/lobby/leave"); mode = "local"; pending = null; incoming = null; send("/app/local/state"); render(); break;
            // network challenges
            case "challenge": pending = { targetName: nameOf(el.getAttribute("data-conn")) }; send("/app/challenge/send", { targetConnId: el.getAttribute("data-conn") }); render(); break;
            case "cancel-pending": pending = null; render(); break; // challenge lapses server-side via expiry
            case "accept": var id = incoming.challengeId; incoming = null; send("/app/challenge/respond", { challengeId: id, accept: true }); render(); break;
            case "decline": var d = incoming.challengeId; incoming = null; send("/app/challenge/respond", { challengeId: d, accept: false }); render(); break;
            // network match
            case "net-move": send("/app/match/move", { matchId: net.matchId, row: +el.getAttribute("data-r"), col: +el.getAttribute("data-c") }); break;
            case "forfeit": send("/app/match/forfeit", { matchId: net.matchId }); break;
            case "back-lobby": clearMatch(); net.phase = "lobby"; net.matchId = null; net.winningCells = null; net.endMessage = null; net.opponentDisconnected = false; send("/app/lobby/enter"); render(); break;
        }
    });

    function nameOf(connId) {
        var p = net.players.filter(function (x) { return x.connId === connId; })[0];
        return p ? p.name : "your opponent";
    }

    render();
    connect();
})();
