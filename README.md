# Vaadin Loom

A prototype project which tries to implement Vaadin Blocking dialogs using the Java Project Loom.
Uses [Vaadin Boot](https://github.com/mvysny/vaadin-boot). Requires Java 24+
(compiles and mostly runs on Java 21, but deadlocks the moment anything blocks inside a
`synchronized` block; see [Java version requirements](#java-version-requirements) below).

Read [Vaadin and Blocking Dialogs](https://mvysny.github.io/vaadin-blocking-dialogs/) on why this
is such a hard thing to do.

Simply run `Main.java` `main()` method from your IDE. Make sure to have the following JVM
arguments when launching `Main.java` (in Intellij: edit launch configuration, modify options, add VM options):

```
--add-opens java.base/java.lang=ALL-UNNAMED
```

The reason is that we're hacking deep into virtual threads, and we need access to JVM internal stuff.

See the [live demo running on v-herd](https://v-herd.eu/vaadin-loom/).

# Documentation

Please see the [Vaadin Boot](https://github.com/mvysny/vaadin-boot#preparing-environment) documentation
on how you run, develop and package this Vaadin-Boot-based app.

## Main idea

First, read [Oracle article on virtual threads](https://blogs.oracle.com/javamagazine/post/java-loom-virtual-threads-platform-threads),
to familiarize yourself with the terminology: virtual thread, carrier thread, mounting and unmounting.

The main idea is to configure JVM to somehow make virtual threads mount to the Vaadin UI threads and run from
there. Of course there's no special UI thread per se, there are only threads currently holding the Vaadin session lock.
To run a `Runnable` in Vaadin UI 'thread' you call `UI.access()`.

Project Loom allows us to run code in a virtual thread. Virtual thread runs the code as a series
of continuations, each continuation running a piece of code until it blocks. Continuation is ultimately a `Runnable`.

We'll run Continuation `Runnables` via `UI.access()`. That's the whole idea of how this thing works.

## Java version requirements

In theory everything here needs only Java 21, where virtual threads went GA. In practice
**Java 24+ is required**, because of [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491).

On JDK 21–23 a virtual thread that parks while holding a monitor (i.e. inside a `synchronized`
block or method) cannot unmount. Instead it *pins*: `VirtualThread.park()` falls through to
`parkOnCarrierThread()` and blocks the carrier itself. Our carrier is the Vaadin UI thread, running
inside `UI.access()` with the session lock held, so the consequence is a deterministic deadlock rather
than a mere slowdown:

1. The request thread blocks inside `UI.accessSynchronously()`, session lock held.
2. The response never goes out, so the dialog never renders.
3. The click that would complete the future can never be processed - there is no dialog to click,
   and the session lock is held anyway.

The generator (`Iterators`) is affected in the same way: `Yielder.yield()` inside a `synchronized`
block pins the thread calling `Iterator.next()`, which then never returns.

The trigger is *any* monitor above the blocking call - your code, a third-party library, or the JDK
itself (e.g. `java.util.prefs.AbstractPreferences.put()` is `synchronized`). That's why this can't be
documented away as "don't synchronize around blocking calls": you don't control every frame on the
stack. JDK 24 (JEP 491) makes virtual threads unmount while holding monitors, which removes the problem
entirely; the `testParkingInsideSynchronizedUnmountsTheVirtualThread` and
`testYieldInsideSynchronizedSuspendsTheGenerator` tests verify exactly this scenario and are
skipped on older JDKs since they would hang forever there.

This was measured with Temurin 21.0.11 (hangs; the `jcmd <pid> Thread.dump_to_file` dump shows the
virtual thread parked in `parkOnCarrierThread` and its carrier stuck in `Continuation.run()` under
`UI.accessSynchronously()`) versus Temurin 24.0.2 and OpenJDK 25.0.4 (works). See
[issue #2](https://github.com/mvysny/vaadin-loom/issues/2) for the full analysis and thread dumps.

If you must run on JDK 21–23, start the JVM with `-Djdk.tracePinnedThreads=full` to get a stack
trace printed whenever a virtual thread pins - it shows you which monitor is to blame. The flag was
removed in JDK 24 since it's no longer needed there.

## Serving http requests in virtual threads

In `Main.java`, Vaadin Boot is configured to force Jetty to always use native threads to serve http requests.
The problem is that the Continations require native threads to run on, they can not run on virtual threads.

However, the main idea of virtual threads is to avoid having platform-native threads blocked by e.g. a database access.
And if we run all Vaadin code from `VaadinSuspendingExecutor` then the native http-request-serving threads will never block
since all they'll do is that they'll run Continuations which do not block by definition, since
Continuation execution ends when the virtual thread blocks.

# Generators

As it turns out, it's possible to also implement a so-called generator using this technique.
See the `Iterators.fibonacci()` for more details:

```java
public final class Iterators {
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
```

Please read [Java Generators](https://mvysny.github.io/java-generators/) on how this works.

Note: this makes a couple of assumptions on the implementation of the JVM virtual threads.
This may break. I'm testing on Oracle OpenJDK and Amazon Corretto and it seems to work,
but this might break on other JVMs.

# Pure Java Generators

[The Mug library](https://github.com/google/mug) offers a very interesting way of [implementing generators without loom](https://github.com/google/mug/wiki/Iteration-Explained).
Definitely worth a read.

# License

Licensed under the [MIT License](LICENSE).
