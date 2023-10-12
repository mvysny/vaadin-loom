# Vaadin Loom

A prototype project which tries to implement Vaadin Blocking dialogs using the Java Project Loom.
Uses [Vaadin Boot](https://github.com/mvysny/vaadin-boot). Requires Java 21+.

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
