# Vaadin Loom

A prototype project which tries to implement Vaadin Blocking dialogs using the Java Project Loom.
Uses [Vaadin Boot](https://github.com/mvysny/vaadin-boot). Requires Java 20+.

Simply run `Main.java` `main()` method from your IDE. Make sure to have the following JVM
arguments when launching Main.java:

```
--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/jdk.internal.vm=ALL-UNNAMED
```

The reason is that we're hacking deep into virtual threads and we need access to JVM internal stuff.

# Warning

This is not even alpha quality. If it works, it works just by a random coincidence. I'm just hacking
deep into virtual threads, without really knowing what I'm really doing. Prototype quality.

# Documentation

Please see the [Vaadin Boot](https://github.com/mvysny/vaadin-boot#preparing-environment) documentation
on how you run, develop and package this Vaadin-Boot-based app.
