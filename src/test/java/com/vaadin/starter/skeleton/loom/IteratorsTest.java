package com.vaadin.starter.skeleton.loom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

import static com.vaadin.starter.skeleton.loom.Iterators.*;
import static org.junit.jupiter.api.Assertions.*;

public class IteratorsTest {
    @Test
    public void testIteratorsGenerate() {
        final List<Integer> expected = Arrays.asList(1, 2, 3, 4);
        final Deque<Integer> numbersToBeReturned = new LinkedList<>(expected);
        final List<Integer> actual = toStream(generate(numbersToBeReturned::poll))
                .toList();
        assertArrayEquals(expected.toArray(), actual.toArray());
    }

    @Test
    public void testIteratorsGenerateEmpty() {
        final List<Integer> actual = toStream(Iterators.<Integer>generate(() -> null))
                .toList();
        assertArrayEquals(new Integer[0], actual.toArray());
    }

    @Test
    public void testIteratorEmpty() {
        final List<Integer> actual = toStream(Iterators.<Integer>iterator(y -> {}))
                .toList();
        assertArrayEquals(new Integer[0], actual.toArray());
    }

    @Test
    public void testIteratorEmpty2() {
        final List<Integer> actual = toStream(Iterators.<Integer>iterator(y -> y.yield()))
                .toList();
        assertArrayEquals(new Integer[0], actual.toArray());
    }

    @Test
    public void testIteratorSimple() {
        final List<Integer> actual = toStream(Iterators.<Integer>iterator(y -> {
            y.yield(1);
            y.yield(2);
            y.yield(3);
            y.yield(4, 5, 6);
            y.yield(7);
        }))
                .toList();
        assertArrayEquals(new Integer[] { 1, 2, 3, 4, 5, 6, 7}, actual.toArray());
    }

    @Test
    public void testIteratorFibonacci() {
        final List<Integer> actual = toStream(fibonacci())
                .limit(10)
                .toList();
        assertArrayEquals(new Integer[] { 0, 1, 1, 2, 3, 5, 8, 13, 21, 34 }, actual.toArray());
    }

    @Test
    public void testIteratorYieldAll() {
        final List<Integer> actual = toStream(Iterators.<Integer>iterator(y -> {
            y.yieldAll(List.of(1, 2));
            y.yieldAll(List.of()); // yielding nothing must not produce a spurious item
            y.yieldAll(List.of(3));
        }))
                .toList();
        assertArrayEquals(new Integer[] { 1, 2, 3 }, actual.toArray());
    }

    @Test
    public void testGenerateNextThrowsWhenExhausted() {
        final Iterator<Integer> it = generate(() -> null);
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    public void testIteratorNextThrowsWhenExhausted() {
        final Iterator<Integer> it = Iterators.iterator(y -> y.yield(1));
        assertEquals(1, it.next());
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    public void testIteratorNextThrowsWhenGeneratorNeverYielded() {
        final Iterator<Integer> it = Iterators.iterator(y -> {});
        assertThrows(NoSuchElementException.class, it::next);
    }

    /**
     * KNOWN LIMITATION, documented here so that it doesn't change silently.
     * <p></p>
     * When the generator block throws, the exception is thrown on the generator's virtual thread
     * and is therefore handed to that thread's default uncaught exception handler (it gets printed
     * to stderr). It does NOT propagate out of {@link Iterator#next()}: the invoker merely observes
     * that the runnable terminated, so the iteration is silently truncated.
     * <p></p>
     * If this is ever fixed, this test will fail - flip it to assert that the exception propagates.
     */
    @Test
    public void testGeneratorFailureSilentlyTruncatesTheIteration() {
        final List<Integer> actual = toStream(Iterators.<Integer>iterator(y -> {
            y.yield(1);
            throw new RuntimeException("simulated");
        }))
                .toList();
        assertArrayEquals(new Integer[] { 1 }, actual.toArray(),
                "the generator failure is swallowed and the iteration just ends");
    }
}
