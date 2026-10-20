package repro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.file.HttpFile;

public final class Main {

    private static final int REQUESTS = 100;

    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("armeria-fd-leak", ".bin");
        Files.write(file, new byte[32 * 1024 * 1024]);
        List<CompletableFuture<?>> served = new CopyOnWriteArrayList<>();

        Server server = Server.builder()
                              .http(0)
                              .service("/file", (ctx, req) -> {
                                  served.add(ctx.log().whenComplete());
                                  return HttpFile.of(file).asService().serve(ctx, req);
                              })
                              .build();
        server.start().join();
        WebClient client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());

        System.out.println("open descriptors on the file before:            " + openDescriptors(file));
        for (int i = 0; i < REQUESTS; i++) {
            cancelOnFirstData(client);
        }
        for (CompletableFuture<?> f : served) {
            f.get(10, TimeUnit.SECONDS);
        }
        Thread.sleep(2000);
        System.out.println("after " + REQUESTS + " responses aborted and completed: " + openDescriptors(file));

        System.gc();
        Thread.sleep(2000);
        System.out.println("after System.gc():                              " + openDescriptors(file));

        server.stop().join();
        Files.delete(file);
    }

    private static void cancelOnFirstData(WebClient client) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        client.get("/file").subscribe(new Subscriber<HttpObject>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(HttpObject o) {
                if (o instanceof HttpData) {
                    subscription.cancel();
                    done.complete(null);
                } else {
                    subscription.request(1);
                }
            }

            @Override
            public void onError(Throwable t) {
                done.complete(null);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    private static long openDescriptors(Path file) throws IOException, InterruptedException {
        String target = file.toRealPath().toString();
        Path proc = Path.of("/proc/self/fd");
        if (Files.isDirectory(proc)) {
            try (var fds = Files.list(proc)) {
                return fds.filter(fd -> {
                    try {
                        return Files.readSymbolicLink(fd).toString().equals(target);
                    } catch (IOException e) {
                        return false;
                    }
                }).count();
            }
        }
        Process lsof = new ProcessBuilder("lsof", "-n", "-P", "-p",
                                          String.valueOf(ProcessHandle.current().pid())).start();
        String out = new String(lsof.getInputStream().readAllBytes());
        lsof.waitFor();
        return out.lines().filter(line -> line.endsWith(target)).count();
    }
}
