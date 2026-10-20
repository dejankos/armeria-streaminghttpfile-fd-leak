# StreamingHttpFile leaks the file of an aborted response

Reproducer for an Armeria issue: `HttpFile.of(path)` keeps the file open after its response is aborted, until the
channel is garbage collected.

`StreamingHttpFile.doRead()` schedules the next chunk with `res.whenConsumed().thenRun(...)`. An abort completes that
future exceptionally, so `thenRun` and the `close(in)` it would reach never run. Only files larger than one 8 KiB
chunk are affected.

## Run

```
./gradlew run
```

Requires JDK 21. A server serves a 32 MiB file with `HttpFile.of(path)`, a client cancels 100 requests on their first
data chunk, and the program counts the file's open descriptors (`/proc/self/fd` on Linux, `lsof` elsewhere) after every
server-side `RequestLog` has completed, then again after `System.gc()`.

Armeria 1.42.0, JDK 21, macOS:

```
open descriptors on the file before:            0
after 100 responses aborted and completed: 81
after System.gc():                              0
```

Across runs 63–81 of the 100 stay open, on macOS and on Linux (`eclipse-temurin:21-jdk`). The rest are closed through
the `tryWrite() == false` path, when the abort lands before the next chunk is written.
