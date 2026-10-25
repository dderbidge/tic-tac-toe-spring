package com.derbysquad.tictactoe.multiplayer;

import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.poll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.derbysquad.tictactoe.dto.ChallengePlayerRequest;
import com.derbysquad.tictactoe.dto.LocalMoveRequest;
import com.derbysquad.tictactoe.dto.LocalStateEvent;
import com.derbysquad.tictactoe.dto.MatchStateEvent;
import com.derbysquad.tictactoe.dto.PlaceMoveRequest;
import com.derbysquad.tictactoe.dto.RejoinMatchRequest;
import com.derbysquad.tictactoe.dto.RespondToChallengeRequest;
import com.derbysquad.tictactoe.dto.StartVsComputerRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Finite seeded agents driving the actual STOMP gameplay contract, without a browser. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "tictactoe.seed=13"})
@Timeout(120)
class GameplayPlaytesterTest {
    private static final int[][] LINES = {
            {0,1,2}, {3,4,5}, {6,7,8}, {0,3,6}, {1,4,7}, {2,5,8}, {0,4,8}, {2,4,6}};
    @LocalServerPort int port;
    @Autowired MatchRegistry matches;

    @ParameterizedTest
    @ValueSource(ints = {13, 2026, 104729})
    void localPlayersFinishReplayResetAndRecoverFromMenu(int seed) throws Exception {
        List<String> trace = new ArrayList<>();
        long started = System.nanoTime();
        LobbyClient player = LobbyClient.connectOnly(port);
        LobbyClient isolated = LobbyClient.connectOnly(port);
        try {
            local(isolated, "/app/local/hotseat", null);
            for (boolean tactical : new boolean[] {false, true}) {
                Random rng = new Random(seed);
                LocalStateEvent state = local(player, "/app/local/hotseat", null);
                state = local(player, "/app/local/move", new LocalMoveRequest(0, 0));
                local(player, "/app/local/main-menu", null);
                state = local(player, "/app/local/move", new LocalMoveRequest(1, 1));
                check(Arrays.stream(flat(state.board())).filter(v -> v != 0).count() == 1, "menu accepted a move");
                local(player, "/app/local/reset", null);
                state = local(player, "/app/local/hotseat", null);
                for (int round = 0; round < 4; round++) {
                    trace.add("local profile=" + (tactical ? "tactical" : "random") + " round=" + round);
                    int moves = 0;
                    while (state.phase().equals("InMatch") && moves < 9) {
                        int[] before = flat(state.board());
                        state = local(player, "/app/local/move", new LocalMoveRequest(-1, 3));
                        check(Arrays.equals(before, flat(state.board())), "out-of-range move changed board");
                        int move = choose(before, mark(state.active()), rng, tactical);
                        trace.add(state.active() + ":" + move + " board=" + Arrays.toString(before));
                        state = local(player, "/app/local/move", new LocalMoveRequest(move / 3, move % 3));
                        int[] after = flat(state.board());
                        check(changes(before, after) == 1, "move changed more than one cell");
                        // Repeated-input player submits the occupied cell twice more.
                        player.send("/app/local/move", new LocalMoveRequest(move / 3, move % 3));
                        player.send("/app/local/move", new LocalMoveRequest(move / 3, move % 3));
                        check(Arrays.equals(after, flat(poll(player.localState).board())), "first repeat changed board");
                        state = poll(player.localState);
                        check(Arrays.equals(after, flat(state.board())), "second repeat changed board");
                        validate(state);
                        check(isolated.localState.isEmpty(), "state leaked to a different local session");
                        moves++;
                    }
                    check(state.phase().equals("MatchOver"), "round stuck after nine legal moves");
                    check(state.ascendantWins() + state.vanguardWins() + state.draws() == round + 1, "round score counted incorrectly");
                    int[] terminal = flat(state.board());
                    state = local(player, "/app/local/move", new LocalMoveRequest(2, 2));
                    check(Arrays.equals(terminal, flat(state.board())), "terminal game accepted a move");
                    state = local(player, "/app/local/play-again", null);
                    check(Arrays.stream(flat(state.board())).allMatch(v -> v == 0) && state.winner() == null && !state.isDraw(), "replay kept stale state");
                }
                state = local(player, "/app/local/reset", null);
                check(state.ascendantWins() + state.vanguardWins() + state.draws() == 0, "score reset failed");
            }
            LocalStateEvent other = local(isolated, "/app/local/state", null);
            check(Arrays.stream(flat(other.board())).allMatch(v -> v == 0), "other session's board changed");
            summary(seed, "8 local STOMP rounds; random/tactical/invalid/repeated/menu/replay/reset/isolation", started, 30);
        } catch (Throwable error) { throw new AssertionError("seed=" + seed + "\n" + String.join("\n", trace), error); }
        finally { player.disconnect(); isolated.disconnect(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {13, 2026, 104729})
    void playersCannotBeatWarlordFromEitherSeat(int seed) throws Exception {
        List<String> trace = new ArrayList<>();
        long started = System.nanoTime();
        LobbyClient player = LobbyClient.connectOnly(port);
        try {
            for (String side : new String[] {"Ascendant", "Vanguard"}) {
                local(player, "/app/local/reset", null);
                Random rng = new Random(seed);
                LocalStateEvent state = local(player, "/app/local/vs-computer", new StartVsComputerRequest(side, "Warlord"));
                for (int turn = 0; state.phase().equals("InMatch") && turn < 5; turn++) {
                    int[] before = flat(state.board());
                    int move = choose(before, mark(side), rng, seed != 13);
                    trace.add("Warlord human=" + side + " move=" + move + " board=" + Arrays.toString(before));
                    state = local(player, "/app/local/move", new LocalMoveRequest(move / 3, move % 3));
                    int[] after = flat(state.board());
                    for (int i = 0; i < 9; i++) check(before[i] == 0 || before[i] == after[i], "AI overwrote occupied cell");
                    validate(state);
                }
                check(state.phase().equals("MatchOver"), "AI round stuck");
                check(!side.equals(state.winner()), "Warlord lost to the playtester");
                check(state.ascendantWins() + state.vanguardWins() + state.draws() == 1, "AI round score invalid");
            }
            summary(seed, "2 Warlord STOMP rounds; both seats/legal moves/terminal scores", started, 30);
        } catch (Throwable error) { throw new AssertionError("seed=" + seed + "\n" + String.join("\n", trace), error); }
        finally { player.disconnect(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {13, 2026, 104729})
    void networkPlayersReconnectAndCompleteIsolatedMatches(int seed) throws Exception {
        List<String> trace = new ArrayList<>();
        List<LobbyClient> clients = new ArrayList<>();
        long started = System.nanoTime();
        try {
            LobbyClient outsider = join(clients);
            for (boolean tactical : new boolean[] {false, true}) {
                LobbyClient a = join(clients), b = join(clients);
                a.send("/app/challenge/send", new ChallengePlayerRequest(b.connId));
                b.send("/app/challenge/respond", new RespondToChallengeRequest(poll(b.incoming).challengeId(), true));
                var accepted = poll(a.accepted); poll(b.accepted);
                String id = accepted.matchId(), token = accepted.yourToken();
                poll(a.matchState); poll(b.matchState);
                int[] board = new int[9];
                Random rng = new Random(seed);
                for (int ply = 0; ply < 9 && winner(board) == 0 && hasEmpty(board); ply++) {
                    if (ply == 2) {
                        a.disconnect(); poll(b.oppDisconnected);
                        a = join(clients);
                        a.send("/app/match/rejoin", new RejoinMatchRequest(id, token));
                        poll(a.accepted);
                        check(Arrays.equals(board, flat(poll(a.matchState).board())), "rejoin changed board");
                        poll(b.matchState); poll(b.oppReconnected);
                        trace.add("reconnect Ascendant after ply=2; board preserved");
                    }
                    LobbyClient actor = ply % 2 == 0 ? a : b;
                    // Invalid attempts use the same session as the valid move, preserving
                    // receive ordering. Other-session attacks are covered in backend security tests.
                    actor.send("/app/match/move", new PlaceMoveRequest(id, -1, 0));
                    check(Arrays.equals(board, flat(poll(actor.matchState).board())), "invalid-move acknowledgement changed board");
                    int move = choose(board, ply % 2 + 1, rng, tactical);
                    trace.add("network profile=" + (tactical ? "tactical" : "random") + " ply=" + ply + " move=" + move + " board=" + Arrays.toString(board));
                    actor.send("/app/match/move", new PlaceMoveRequest(id, move / 3, move % 3));
                    MatchStateEvent state = poll(a.matchState), other = poll(b.matchState);
                    int[] next = flat(state.board());
                    check(Arrays.equals(next, flat(other.board())), "players diverged");
                    check(changes(board, next) == 1, "network move changed multiple cells");
                    check(next[move] == ply % 2 + 1, "wrong side placed move");
                    board = next;
                    // Occupied-cell repetition remains invalid even on the next turn.
                    actor.send("/app/match/move", new PlaceMoveRequest(id, move / 3, move % 3));
                    if (winner(board) == 0 && hasEmpty(board)) {
                        check(Arrays.equals(board, flat(poll(actor.matchState).board())), "repeat acknowledgement changed board");
                    }
                }
                check(winner(board) != 0 || !hasEmpty(board), "network round stuck");
                var endA = poll(a.matchEnded); var endB = poll(b.matchEnded);
                int winning = winner(board);
                check(endA.result().equals(winning == 0 ? "draw" : winning == 1 ? "win" : "loss"), "Ascendant result disagrees with board");
                check(endB.result().equals(winning == 0 ? "draw" : winning == 2 ? "win" : "loss"), "Vanguard result disagrees with board");
                check(outsider.matchState.isEmpty() && outsider.matchEnded.isEmpty(), "match leaked to spectator");
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (matches.get(id) != null && System.nanoTime() < cleanupDeadline) Thread.sleep(10);
                check(matches.get(id) == null, "finished match leaked in registry");
            }
            summary(seed, "2 network STOMP rounds; random/tactical/reconnect/invalid/repeated/isolation", started, 60);
        } catch (Throwable error) { throw new AssertionError("seed=" + seed + "\n" + String.join("\n", trace), error); }
        finally { for (LobbyClient c : clients) if (c.session.isConnected()) c.disconnect(); }
    }

    private LobbyClient join(List<LobbyClient> clients) throws Exception {
        LobbyClient client = LobbyClient.enterLobby(port); clients.add(client); return client;
    }
    private static LocalStateEvent local(LobbyClient client, String path, Object body) throws Exception {
        client.send(path, body); return poll(client.localState);
    }
    private static int[] flat(int[][] board) { return Arrays.stream(board).flatMapToInt(Arrays::stream).toArray(); }
    private static int mark(String side) { return "Ascendant".equals(side) ? 1 : "Vanguard".equals(side) ? 2 : 0; }
    private static boolean hasEmpty(int[] board) { return Arrays.stream(board).anyMatch(v -> v == 0); }
    private static int winner(int[] board) {
        for (int[] line : LINES) if (board[line[0]] != 0 && board[line[0]] == board[line[1]] && board[line[1]] == board[line[2]]) return board[line[0]];
        return 0;
    }
    private static int changes(int[] before, int[] after) {
        int changed = 0; for (int i = 0; i < 9; i++) if (before[i] != after[i]) changed++; return changed;
    }
    private static int choose(int[] board, int side, Random rng, boolean tactical) {
        List<Integer> legal = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (board[i] == 0) legal.add(i);
        if (tactical) {
            for (int mark : new int[] {side, 3 - side}) for (int move : legal) {
                int[] copy = board.clone(); copy[move] = mark; if (winner(copy) == mark) return move;
            }
            if (board[4] == 0) return 4;
        }
        return legal.get(rng.nextInt(legal.size()));
    }
    private static void validate(LocalStateEvent state) {
        int[] board = flat(state.board());
        long a = Arrays.stream(board).filter(v -> v == 1).count(), b = Arrays.stream(board).filter(v -> v == 2).count();
        check(a == b || a == b + 1, "illegal turn count " + Arrays.toString(board));
        check(winner(board) == mark(state.winner()), "winner disagrees with independent line oracle");
        check(state.isDraw() == (winner(board) == 0 && !hasEmpty(board)), "draw disagrees with board");
    }
    private static void summary(int seed, String coverage, long started, int budgetSeconds) {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check(ms < budgetSeconds * 1000L, "matrix exceeded " + budgetSeconds + " second budget");
        System.out.println("PLAYTEST PASS seed=" + seed + "; " + coverage + "; elapsed=" + ms + "ms");
    }
    private static void check(boolean condition, String message) { assertTrue(condition, message); }
}
