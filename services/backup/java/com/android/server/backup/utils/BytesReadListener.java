package com.android.server.backup.utils;

/**
 * Listener for bytes reading.
 */
public interface BytesReadListener {
    /**
     * Will be called on each read operation.
     * @param bytesRead - number of bytes read with the most recent read operation.
     */
    void onBytesRead(long bytesRead);
}
