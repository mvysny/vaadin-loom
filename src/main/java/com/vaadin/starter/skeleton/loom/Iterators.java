package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Utility class which simplifies writing generators and converting them to plain
 * {@link Iterator}s. See {@link #fibonacci()} source code for an example.
 */
public final class Iterators  {
    /**
     * Returns an iterator which invokes the function to calculate the next value on each iteration until the function returns `null`.
     * @param nextFunction supplies next values. If the function returns null the iteration stops and the function is no longer called.
     * @return the iterator
     * @param <E> the type of the item returned by the iterator
     */
    @NotNull
    public static <E> Iterator<E> generate(@NotNull Supplier<E> nextFunction) {
        return new Iterator<E>() {
            /**
             * If true the iteration is done and there will be no more items.
             */
            private boolean done = false;
            /**
             * Next item returned by {@link #next()}. If null then the item hasn't been
             * calculated yet.
             */
            private E nextItem = null;

            /**
             * Polls nextFunction and calculates {@link #nextItem}.
             */
            private void peekNext() {
                if (!done && nextItem == null) {
                    nextItem = nextFunction.get();
                    if (nextItem == null) {
                        done = true;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                peekNext();
                return !done;
            }

            @Override
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                final E item = nextItem;
                nextItem = null;
                return item;
            }
        };
    }

    /**
     * The generator block calls {@link #yield(Object)} on this object when it has generated an item.
     * <br/>
     * Utility class, not intended to be instantiated by users. Expects
     * to be used from a {@link Supplier}; see {@link #iterator(Consumer)}
     * for exact details.
     * @param <E> the type of the items produced.
     */
    public static final class Yielder<E> {
        private Yielder() {}

        /**
         * Runs the block which calls this yielder.
         */
        private ContinuationInvoker continuationInvoker = null;
        /**
         * This will temporarily hold the item passed to {@link #yield(Object)}.
         */
        private final Deque<E> availableItems = new LinkedList<>();

        /**
         * Generate an item. The item is immediately returned via {@link Iterator#next()}.
         * @param item the item to be returned by the iterator, may be null.
         */
        public void yield(@Nullable E item) {
            availableItems.add(item);
            // suspend the generator block, which allows owner Supplier to continue
            // execution. The Supplier is specifically crafted in a way that it reads
            // the item from the "availableItems" list of this Yielder.
            continuationInvoker.suspend();
        }

        /**
         * Generate a couple of items at the same time. The first item is immediately returned via {@link Iterator#next()}.
         * @param items the items to be returned by the iterator.
         */
        public void yield(@Nullable E... items) {
            yieldAll(Arrays.asList(items));
        }

        /**
         * Generate an item. The item is immediately returned via {@link Iterator#next()}.
         * @param items the items to be returned by the iterator.
         */
        public void yieldAll(@NotNull Collection<E> items) {
            if (!items.isEmpty()) {
                availableItems.addAll(items);
                continuationInvoker.suspend();
            }
        }
    }

    /**
     * Allows you to build the iterator contents by calling {@link Yielder#yield(Object)}:
     * <pre>
     * val iterator = Iterators.iterator(y -> {
     *     y.yield(1);
     *     y.yield(2);
     *     y.yield(3);
     * });
     * </pre>
     * The call to {@link Yielder#yield(Object) yield()} suspends the generator until the item is consumed by a call to {@link Iterator#next()}.
     * The iterator starts lazy, upon call to {@link Iterator#hasNext()} or {@link Iterator#next()}.
     * <p></p>
     * The generator may never terminate, thus producing infinite sequence of items. See {@link #fibonacci()}
     * for an example.
     * @param generator calls {@link Yielder#yield(Object)} to pass generated values to whoever called {@link Iterator#next()}.
     * @return the iterator
     * @param <E> the item type
     */
    @NotNull
    public static <E> Iterator<E> iterator(@NotNull Consumer<Yielder<E>> generator) {
        final Yielder<E> yielder = new Yielder<>();
        final ContinuationInvoker continuationInvoker = new ContinuationInvoker(() -> generator.accept(yielder));
        yielder.continuationInvoker = continuationInvoker;

        // when this supplier is called, it calls the generator block. The generator
        // block calls Yielder.yield() with an item, which causes the generator
        // to suspend. This supplier can then simply read the yielded item from Yielder
        // and return it.
        final Supplier<E> itemSupplier = () -> {
            if (!yielder.availableItems.isEmpty()) {
                // multiple items have been yielded previously. Return them one-by-one.
                return yielder.availableItems.remove();
            }

            // run the next continuation. The continuation stops when called yield(),
            // therefore Yielder.availableItems will have at most 1 item.
            final boolean hasMoreContinuations = continuationInvoker.next();
            if (!hasMoreContinuations) {
                // the generator finished and there won't be more items. Terminate the iteration.
                return null;
            }
            // The continuation stopped by called yield(), therefore we have an item.
            return yielder.availableItems.remove();
        };
        return generate(itemSupplier);
    }

    @NotNull
    public static <E> Stream<E> toStream(@NotNull Iterator<E> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }

    /**
     * An endless iterator of Fibonacci numbers. Demoes the generating {@link #iterator(Consumer)}.
     * @return the iterator
     */
    @NotNull
    public static Iterator<Integer> fibonacci() {
        return iterator(y -> {
            int t1 = 0;
            int t2 = 1;

            // NOTE: This while() block shows the InfiniteLoopStatement warning.
            // While the warning is correct when using regular threads,
            // IDEA doesn't know that the block is suspended via Loom and can be
            // "killed" at any time, simply by not continuing the iteration.
            // Therefore, we'll silence the warning.

            //noinspection InfiniteLoopStatement
            while (true) {
                y.yield(t1);
                final int sum = t1 + t2;
                t1 = t2;
                t2 = sum;
            }
        });
    }
}
