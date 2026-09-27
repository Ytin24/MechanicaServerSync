package ru.mechanica.sync.ipc;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class PipeChannel implements AutoCloseable {
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Mechanica bridge timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final RandomAccessFile pipe;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledFuture<?> timeout;

    private PipeChannel(RandomAccessFile pipe, Duration maximumDuration) {
        this.pipe = pipe;
        timeout = TIMEOUTS.schedule(this::close, maximumDuration.toMillis(), TimeUnit.MILLISECONDS);
    }

    public static PipeChannel connect(BridgeConfig config, BooleanSupplier cancelled, Duration maximumDuration) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!cancelled.getAsBoolean()) {
            try {
                return new PipeChannel(new RandomAccessFile("\\\\.\\pipe\\" + config.pipe(), "rw"), maximumDuration);
            } catch (IOException ex) {
                if (System.nanoTime() >= deadline) {
                    throw new IOException("bridge_unavailable");
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled");
                }
            }
        }
        throw new IOException("cancelled");
    }

    public JsonObject exchange(JsonObject message) throws IOException {
        if (closed.get()) {
            throw new IOException("bridge_closed");
        }
        FrameCodec.write(pipe, message);
        return FrameCodec.read(pipe);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (timeout != null) {
                timeout.cancel(false);
            }
            try {
                pipe.close();
            } catch (IOException ignored) { }
        }
    }
}
