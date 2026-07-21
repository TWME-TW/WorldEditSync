package dev.twme.worldeditsync.common.protocol;

import java.util.concurrent.atomic.AtomicLong;

/** Shared byte reservation for large in-flight backend transfers. */
public final class TransferMemoryBudget {

    private final long limitBytes;
    private final AtomicLong reservedBytes = new AtomicLong();

    public TransferMemoryBudget(long limitBytes) {
        if (limitBytes <= 0L) {
            throw new IllegalArgumentException("limitBytes must be positive");
        }
        this.limitBytes = limitBytes;
    }

    public boolean tryReserve(long bytes) {
        if (bytes <= 0L || bytes > limitBytes) {
            return false;
        }
        while (true) {
            long current = reservedBytes.get();
            if (current > limitBytes - bytes) {
                return false;
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    public void release(long bytes) {
        if (bytes <= 0L) {
            return;
        }
        while (true) {
            long current = reservedBytes.get();
            if (current < bytes) {
                throw new IllegalStateException("Released more transfer memory than was reserved");
            }
            if (reservedBytes.compareAndSet(current, current - bytes)) {
                return;
            }
        }
    }

    public long getReservedBytes() {
        return reservedBytes.get();
    }
}
