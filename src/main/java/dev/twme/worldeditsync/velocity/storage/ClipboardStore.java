package dev.twme.worldeditsync.velocity.storage;

import dev.twme.worldeditsync.common.storage.ProxyClipboardStore;

/** Velocity-facing type for the shared, memory-bounded proxy store. */
public class ClipboardStore extends ProxyClipboardStore {

    public ClipboardStore() {
        super();
    }

    public ClipboardStore(long maxMemoryBytes) {
        super(maxMemoryBytes);
    }
}
