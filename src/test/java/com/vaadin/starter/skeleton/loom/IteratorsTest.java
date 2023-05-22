package com.vaadin.starter.skeleton.loom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import static com.vaadin.starter.skeleton.loom.Iterators.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
