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
}
