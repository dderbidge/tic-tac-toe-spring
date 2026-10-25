package com.derbysquad.tictactoe.dto;

/** Sender acknowledgment, used to cancel exactly the pending challenge. */
public record ChallengeSentEvent(String challengeId, String targetConnId, String targetName) {}
