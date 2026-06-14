package com.derbysquad.tictactoe.domain;

/**
 * An immutable board coordinate.
 *
 * <p>Replaces the C# {@code (int row, int col)} value tuples used throughout
 * {@code core/}. A {@code record} is the idiomatic Java equivalent of an
 * immutable tuple.
 */
public record Move(int row, int col) {
}
