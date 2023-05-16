# Vaadin Loom

A prototype project which tries to implement Vaadin Blocking dialogs using the Java Project Loom.
Uses [Vaadin Boot](https://github.com/mvysny/vaadin-boot). Requires Java 20+.

Read [Vaadin and Blocking Dialogs](https://mvysny.github.io/vaadin-blocking-dialogs/) on why this
is such a hard thing to do.

Simply run `Main.java` `main()` method from your IDE. Make sure to have the following JVM
arguments when launching `Main.java` (in Intellij: edit launch configuration, modify options, add VM options):

```
--enable-preview --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/jdk.internal.vm=ALL-UNNAMED
```

The reason is that we're hacking deep into virtual threads and we need access to JVM internal stuff.

# Warning

This is not even alpha quality. If it works, it works just by a random coincidence. I'm just hacking
deep into virtual threads, without really knowing what I'm really doing. Prototype quality.

This is obviously bloody dark magic.

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

We'll run Continuation `Runnables` via `UI.access()`.

I repeat. This is obviously bloody dark magic.
