# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Prototype that implements blocking dialogs (and generator iterators) in Vaadin Flow using Project Loom virtual threads. Vaadin Boot runs embedded Jetty from `Main.main()`; no Spring. Requires Java 21+.

Background reading before modifying the loom code: [Oracle's virtual-threads article](https://blogs.oracle.com/javamagazine/post/java-loom-virtual-threads-platform-threads) and [Vaadin and Blocking Dialogs](https://mvysny.github.io/vaadin-blocking-dialogs/).

## Commands

- Run the app: `./gradlew run` (or run `Main.main()` from the IDE).
- Build + tests: `./gradlew build` (default task is `clean build`).
- Run a single test: `./gradlew test --tests com.vaadin.starter.skeleton.MainViewTest.testBlockingDialogYes`.
- Production build: `./gradlew clean build -Pvaadin.productionMode`. The distributable lands in `build/distributions/app.tar`.
- Docker image: `docker build -t test/vaadin-loom:latest .` then `docker run --rm -ti -p8080:8080 test/vaadin-loom`.

### Required JVM args

`--add-opens java.base/java.lang=ALL-UNNAMED` is mandatory for both running and testing because `LoomUtils.newVirtualBuilder` reflects into `java.lang.ThreadBuilders$VirtualThreadBuilder` (JDK-8308541). `build.gradle.kts` already wires this into `Test`, `JavaExec`, and `application.applicationDefaultJvmArgs`; if you launch `Main` from an IDE you must add it manually to the run configuration.

## Architecture

The whole trick of the project is to mount Loom virtual threads onto Vaadin's UI "thread" (whichever platform thread currently holds the `VaadinSession` lock via `UI.access`). A blocking call on the virtual thread unmounts it, lets the carrier finish its `UI.access` callback (which flushes UI changes to the browser via `@Push`), and the virtual thread later remounts as another `UI.access` callback. Key layers, bottom-up:

- `LoomUtils.newVirtualBuilder(Executor)` — reflection hack that returns a `Thread.Builder.OfVirtual` whose scheduler is **our** executor. This is the only way to redirect continuations away from the default ForkJoinPool.
- `SuspendingExecutor` — wraps a carrier `Executor` in a virtual-thread-per-task executor built via the builder above. Close uses `shutdownNow()` (not `close()`/`awaitTermination`) because suspended virtual threads may never terminate on their own (they could be parked waiting for a user to click a dialog).
- `VaadinSuspendingExecutor` — plugs `UI.access` in as the carrier executor so every continuation runs under the Vaadin session lock. On each continuation it re-seeds `UI.setCurrent` / `VaadinSession.setCurrent` because virtual threads don't inherit these from the carrier. It also swallows the `InterruptedException` that `shutdownNow()` fires into parked virtual threads during `close()`.
- `MainView` — demo: creates/destroys one `VaadinSuspendingExecutor` per attach/detach, and `confirmDialog()` blocks on a `CompletableFuture.get()` that only works because we're on a virtual thread.

Two cross-cutting subtleties:

1. **Servlet threads must be platform threads.** Continuations cannot run on a virtual carrier — you'd hit `WrongThreadException` at `VirtualThread.runContinuation`. `Main` disables Vaadin Boot's virtual-thread request serving (`useVirtualThreadsIfAvailable(false)`), and `UIExecutor.execute` additionally throws if it ever sees a virtual carrier. If you touch request-serving config, preserve this.
2. **`VaadinSession.hasLock()` lies for virtual threads.** The `ReentrantLock` is held by the carrier platform thread, so `isHeldByCurrentThread()` returns `false` on the virtual thread even though we effectively hold the lock. `MyServlet` installs a `VirtualThreadAwareVaadinSession` that short-circuits `hasLock()` by checking `VaadinSession.getCurrent() == this`. Tests mirror this via `MockVirtualThreadAwareServlet`. Any new servlet/session subclass must preserve this override or Vaadin internals will reject our UI updates.

## Generators (Iterators.java)

`Iterators.iterator(Consumer<Yielder<E>>)` reuses the same continuation machinery but with a **synchronous** one-shot executor: `ContinuationInvoker` starts a virtual thread whose "scheduler" runs each continuation inline on the caller thread. `Yielder.yield()` parks on a single-slot `BlockingQueue`; `ContinuationInvoker.next()` unparks it by offering an item. That turns the virtual thread into a coroutine — the iterator's `next()` synchronously drives exactly one continuation of the generator. Do not call `suspend()` from a non-virtual thread, and do not let the generator block on anything other than `Yielder.yield()` (the `continuationUnpark` empty-queue assertion in `next()` will fire).

## Testing

Karibu-Testing (browserless) via `MockVaadin.setup(...)`; tests drive the UI through `_get`/`_click`/`_fireConfirm`. Because the virtual-thread trick requires the custom session override, tests must use `MockVirtualThreadAwareServlet` — do not swap it for `MockVaadinServlet`. `Routes` are discovered once in `@BeforeAll` to keep tests fast.

## Version management

Versions live in `gradle/libs.versions.toml`. CI (`.github/workflows/gradle.yml`) matrix-tests JDK 21/25 across Oracle, Corretto, and Temurin — the reflection hack in `LoomUtils` is JVM-implementation-sensitive, so changes there should be validated against all three.
