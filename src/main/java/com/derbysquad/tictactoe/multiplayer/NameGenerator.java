package com.derbysquad.tictactoe.multiplayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic "Adjective Noun" display names from a connection id. Port of the C#
 * {@code NameGenerator} ({@code ../legacy-csharp/web-multiplayer/Services/NameGenerator.cs}).
 *
 * <p>Faithfully reproduces the algorithm: SHA-256 the id, read two little-endian
 * {@code uint16}s from bytes [0..1] and [2..3], and index the word lists modulo length.
 */
public final class NameGenerator {

    private NameGenerator() {
    }

    private static final String[] ADJECTIVES = {
            "Quiet", "Bold", "Crimson", "Azure", "Golden", "Silver", "Distant",
            "Brave", "Swift", "Cosmic", "Nebula", "Stellar", "Lunar", "Solar",
            "Astral", "Ember", "Frost", "Twilight", "Dawn", "Ancient",
            "Bright", "Hidden", "Steady", "Restless", "Patient", "Wandering",
            "Daring", "Gentle", "Fierce", "Clever", "Watchful", "Eager",
            "Noble", "Quick", "Steep", "Vast", "Polar", "Tidal", "Velvet", "Iron",
    };

    private static final String[] NOUNS = {
            "Comet", "Wanderer", "Sigil", "Phoenix", "Voyager", "Nova", "Pulsar",
            "Sentinel", "Captain", "Navigator", "Pilot", "Outrider", "Drifter",
            "Falcon", "Beacon", "Herald", "Echo", "Spark", "Tide", "Glimmer",
            "Cinder", "Wisp", "Cipher", "Talon", "Veil", "Lantern", "Anchor",
            "Mirror", "Gale", "Vow", "Reaver", "Watcher", "Strider", "Sailor",
            "Rider", "Guardian", "Scout", "Ranger", "Skipper", "Dreamer",
    };

    public static String forConnection(String connectionId) {
        byte[] hash = sha256(connectionId);
        int adjIdx = uint16LittleEndian(hash, 0) % ADJECTIVES.length;
        int nounIdx = uint16LittleEndian(hash, 2) % NOUNS.length;
        return ADJECTIVES[adjIdx] + " " + NOUNS[nounIdx];
    }

    private static int uint16LittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
