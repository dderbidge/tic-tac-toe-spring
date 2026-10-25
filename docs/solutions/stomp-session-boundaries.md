# STOMP session boundaries and reconnect leases

## Symptoms and evidence

The initial port permitted a nonparticipant to forfeit another match, allowed raw
broker subscriptions to another session's resolved private queue, and accepted
client publications that looked like server lobby events. Real STOMP regression
tests reproduced the broker cases before the fix. Independent disconnects also
shared one timer, so reconnecting one seat could erase the other's deadline.

## Governing constraints

`/user/queue` is a routing convention, not an authorization boundary. The simple
broker can otherwise accept raw `/queue` subscriptions and `/topic` publications.
Anonymous clients may send only the declared `/app` commands and subscribe only
to the declared logical user queues and lobby topics. Reconnect tokens stay in
per-tab session storage and must never appear on a public broker destination.

The controller serializes network lifecycle transitions. The registry validates
participant identity and owns an independent, uniquely identified disconnect
lease for each seat. A token may reclaim only that seat while its lease remains
valid. Delayed callbacks must still match the exact lease before ending a game.

## Resolution and prevention

- `ClientDestinationGuard` rejects broker injection and raw private subscriptions.
- `MatchRegistry` checks participation, live-seat ownership and lease deadlines.
- Challenge ownership is checked before consumption; cancellation notifies both
  clients and competing accepted challenges cannot reuse an occupied player.
- Ordered receive/publish processing keeps snapshots and terminal events ordered.
- Match/challenge IDs let the browser ignore delayed events from earlier rounds.
- Invalid participant moves return an unchanged snapshot to release the pending
  browser command; outsiders receive no match state.

## Verification and limits

The final Gradle build passed 93 tests, including real STOMP exploit regressions,
deterministic lease/timer tests and the seeded playtester matrix described in
`docs/testing/gameplay-playtesters.md`. Browser QA additionally exercised cancel,
keyboard moves, replay/reset, page-reload rejoin, completion and forfeit.

This design remains anonymous, in-memory and single-instance. A process restart
does not preserve matches. Add new application commands or subscription queues to
the explicit guard only with corresponding isolation tests.
