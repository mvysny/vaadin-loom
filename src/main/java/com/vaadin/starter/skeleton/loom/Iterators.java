package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    public static final class Yielder<E> {
        private ContinuationInvoker continuationInvoker = null;
        private final Deque<E> availableItems = new LinkedList<>();

        public void yield(@NotNull E item) {
            availableItems.add(item);
            continuationInvoker.suspend();
        }
    }

    @NotNull
    public static <E> Iterator<E> iterator(@NotNull Consumer<Yielder<E>> block) {
        final Yielder<E> yielder = new Yielder<>();
        final ContinuationInvoker continuationInvoker = new ContinuationInvoker(() -> block.accept(yielder));
        yielder.continuationInvoker = continuationInvoker;
        final Supplier<E> itemSupplier = new Supplier<E>() {
            @Override
            public E get() {
                if (!continuationInvoker.next()) {
                    return null;
                }
                return yielder.availableItems.remove();
            }
        };
        return generate(itemSupplier);
    }
}
