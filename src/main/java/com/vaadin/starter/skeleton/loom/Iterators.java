package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
     * Generator calls {@link #yield(Object)} on this object when it has generated an item.
     * @param <E> the type of the items produced.
     */
    public static final class Yielder<E> {
        private ContinuationInvoker continuationInvoker = null;
        /**
         * This will temporarily hold the item passed to {@link #yield(Object)}.
         */
        private final Deque<E> availableItems = new LinkedList<>();

        /**
         * Generate an item. The item is immediately returned via {@link Iterator#next()}.
         * @param item the item to return, may be null.
         */
        public void yield(@Nullable E item) {
            availableItems.add(item);
            continuationInvoker.suspend();
        }
    }

    @NotNull
    public static <E> Iterator<E> iterator(@NotNull Consumer<Yielder<E>> generator) {
        final Yielder<E> yielder = new Yielder<>();
        final ContinuationInvoker continuationInvoker = new ContinuationInvoker(() -> generator.accept(yielder));
        yielder.continuationInvoker = continuationInvoker;
        final Supplier<E> itemSupplier = () -> {
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
            while (true) {
                y.yield(t1);
                final int sum = t1 + t2;
                t1 = t2;
                t2 = sum;
            }
        });
    }
}
