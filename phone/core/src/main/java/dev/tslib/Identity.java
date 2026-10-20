package dev.tslib;

/**
 * A TeamSpeak 3 identity based on ECDH.
 *
 * <p>Identities include a security level that must meet server requirements.
 * This class is not thread-safe.
 *
 * <pre>{@code
 * try (Identity id = new Identity()) {
 *     System.out.println("UID: " + id.getUniqueId());
 *     id.save("identity.ini");
 * }
 * }</pre>
 */
public class Identity implements AutoCloseable {
    private long nativePtr;

    /** Set once {@link #close()} runs, even while a client still borrows the handle. */
    private volatile boolean closed;

    /** Number of live {@link Client}s that called {@link #borrow()}. */
    private int borrowerCount;

    /**
     * Create a new random identity.
     *
     * @throws TsLibException if creation fails
     */
    public Identity() {
        this.nativePtr = nativeCreate();
        if (this.nativePtr == 0) {
            throw new TsLibException("Failed to create identity");
        }
    }

    private Identity(long ptr) {
        this.nativePtr = ptr;
    }

    /**
     * Load an identity from a file.
     *
     * @param path file path
     * @return the loaded identity
     * @throws TsLibException if loading fails
     */
    public static Identity load(String path) {
        long ptr = nativeLoad(path);
        if (ptr == 0) {
            throw new TsLibException("Failed to load identity from " + path);
        }
        return new Identity(ptr);
    }

    /**
     * Save the identity to a file.
     *
     * @param path file path
     * @throws TsLibException on error
     */
    public void save(String path) {
        checkNotClosed();
        nativeSave(nativePtr, path);
    }

    /**
     * Import an identity from a string.
     *
     * @param data serialized identity
     * @return the imported identity
     * @throws TsLibException if the format is invalid
     */
    public static Identity fromString(String data) {
        long ptr = nativeFromString(data);
        if (ptr == 0) {
            throw new TsLibException("Failed to parse identity string");
        }
        return new Identity(ptr);
    }

    /**
     * Export the identity as a string.
     *
     * @return serialized identity
     * @throws TsLibException on error
     */
    public String exportString() {
        checkNotClosed();
        return nativeExportString(nativePtr);
    }

    /**
     * Get the unique identifier (public key hash).
     *
     * @return the UID string
     */
    public String getUniqueId() {
        checkNotClosed();
        return nativeGetUniqueId(nativePtr);
    }

    /**
     * Get the current security level.
     *
     * @return security level (0+)
     */
    public int getSecurityLevel() {
        checkNotClosed();
        return nativeGetSecurityLevel(nativePtr);
    }

    /**
     * Get the nickname associated with this identity.
     *
     * @return the nickname, or {@code null} if not set
     */
    public String getNickname() {
        checkNotClosed();
        return nativeGetNickname(nativePtr);
    }

    /**
     * Set the nickname for this identity.
     *
     * @param name the new nickname
     */
    public void setNickname(String name) {
        checkNotClosed();
        nativeSetNickname(nativePtr, name);
    }

    /**
     * Improve the security level. This is CPU-intensive and may block.
     *
     * @param targetLevel desired security level
     * @throws TsLibException on error
     */
    public void improve(int targetLevel) {
        checkNotClosed();
        nativeImprove(nativePtr, targetLevel);
    }

    /**
     * Lend the native handle to a {@link Client}. The handle stays valid until the matching
     * {@link #release()} call, so a client is never left pointing at freed memory even if
     * this identity is closed first.
     *
     * @return the native pointer, valid until {@link #release()}
     * @throws IllegalStateException if this identity has already been closed
     */
    synchronized long borrow() {
        checkNotClosed();
        borrowerCount++;
        return nativePtr;
    }

    /** Give back a handle obtained from {@link #borrow()}. */
    synchronized void release() {
        if (borrowerCount > 0) {
            borrowerCount--;
        }
        if (closed && borrowerCount == 0) {
            destroyNative();
        }
    }

    /** @return true once {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // While a client still borrows the handle, destruction is deferred to release().
        if (borrowerCount == 0) {
            destroyNative();
        }
    }

    @Override
    public String toString() {
        if (closed) return "Identity(closed)";
        return "Identity(uid=" + getUniqueId() + ", level=" + getSecurityLevel() + ")";
    }

    private void destroyNative() {
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Identity has been closed");
        }
    }

    // Native methods
    private static native long nativeCreate();
    private static native void nativeDestroy(long ptr);
    private static native long nativeLoad(String path);
    private static native void nativeSave(long ptr, String path);
    private static native long nativeFromString(String data);
    private static native String nativeExportString(long ptr);
    private static native String nativeGetUniqueId(long ptr);
    private static native int nativeGetSecurityLevel(long ptr);
    private static native String nativeGetNickname(long ptr);
    private static native void nativeSetNickname(long ptr, String name);
    private static native void nativeImprove(long ptr, int targetLevel);
}
