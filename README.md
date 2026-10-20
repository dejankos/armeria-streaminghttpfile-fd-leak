# StreamingHttpFile leaks the file of an aborted response

Reproducer for an [Armeria](https://github.com/line/armeria) bug. When a response served by `HttpFile.of(path)` is
aborted mid-stream, the file stays open until a GC collects its channel.

## The problem

`StreamingHttpFile.doRead()` reads a file in 8 KiB chunks. After writing a chunk it schedules the next read with
`whenConsumed()`:

```java
res.whenConsumed().thenRun(() -> {
    try {
        fileReadExecutor.execute(() -> doRead(res, in, nextOffset, end, fileReadExecutor, alloc));
    } catch (Exception e) {
        close(res, in, e);
    }
});
```

[`StreamingHttpFile.java#L144-L150`](https://github.com/line/armeria/blob/main/core/src/main/java/com/linecorp/armeria/server/file/StreamingHttpFile.java#L144-L150)

When the response is aborted (the client cancels, resets the stream, or times out), `DefaultStreamMessage` completes the
pending `whenConsumed()` future exceptionally. `thenRun` only runs on normal completion, so the next `doRead` never gets
scheduled and `close(in)` never runs. A `whenConsumed()` on an already closed stream completes exceptionally too, so
nothing closes the file later either.

`doRead` closes the file only when `tryWrite()` returns false, when a read fails, or at end of stream. A file that fits
in one chunk always gets closed, but a larger one whose response is aborted between chunks keeps its descriptor until a
GC collects the `FileChannel`.

## Impact

We hit this in production with `HttpFile.of(path)` on a busy server. Clients cancelling downloads leaked descriptors
faster than major GCs released them, the process ran into its 65,535 open-file limit and stopped accepting connections.
The open-file count only dropped at major GC cycles.

## Reproduce

```
./gradlew run
```

Requires JDK 21. [`Main.java`](src/main/java/repro/Main.java) starts a server that serves a 32 MiB file with
`HttpFile.of(path)`. A client sends 100 requests and cancels each one on its first data chunk. Once every server-side
`RequestLog` has completed, the program counts the descriptors open on the file (`/proc/self/fd` on Linux, `lsof`
elsewhere), then counts again after `System.gc()`.

Armeria 1.42.0, JDK 21, macOS:

```
open descriptors on the file before:            0
after 100 responses aborted and completed: 81
after System.gc():                              0
```

Across runs 63 to 96 of the 100 stay open, on macOS and on Linux (`eclipse-temurin:21-jdk`). The rest get closed
through `tryWrite() == false`, when the abort lands before the next chunk is written. Expected is 0 once the responses
have completed, without a GC.

In our own tests on 1.40.0 the same happened over HTTP/1.1 and h2c, and when the client never read the body and a response timeout aborted it.

## Suggested fix

Close the file on the exceptional path as well:

```java
res.whenConsumed().handle((unused, cause) -> {
    if (cause != null) {
        close(in);
        return null;
    }
    try {
        fileReadExecutor.execute(() -> doRead(res, in, nextOffset, end, fileReadExecutor, alloc));
    } catch (Exception e) {
        close(res, in, e);
    }
    return null;
});
```

I haven't tested this against Armeria itself. Our workaround subclasses `StreamingHttpFile`, keeps the channel it
opens and closes it from the response's `whenComplete()`.
