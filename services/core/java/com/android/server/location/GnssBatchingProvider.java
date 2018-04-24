package com.android.server.location;

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Manages GNSS Batching operations.
 *
 * <p>This class is not thread safe (It's client's responsibility to make sure calls happen on
 * the same thread).
 */
public class GnssBatchingProvider {

    private static final String TAG = "GnssBatchingProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final GnssBatchingProviderNative mNative;
    private boolean mEnabled;
    private boolean mStarted;
    private long mPeriodNanos;
    private boolean mWakeOnFifoFull;

    GnssBatchingProvider() {
        this(new GnssBatchingProviderNative());
    }

    @VisibleForTesting
    GnssBatchingProvider(GnssBatchingProviderNative gnssBatchingProviderNative) {
        mNative = gnssBatchingProviderNative;
    }

    /**
     * Returns the GNSS batching size
     */
    public int getBatchSize() {
        return mNative.getBatchSize();
    }

    /** Enable GNSS batching. */
    public void enable() {
        mEnabled = mNative.initBatching();
        if (!mEnabled) {
            Log.e(TAG, "Failed to initialize GNSS batching");
        }
    }

    /**
     * Starts the hardware batching operation
     */
    public boolean start(long periodNanos, boolean wakeOnFifoFull) {
        if (!mEnabled) {
            throw new IllegalStateException();
        }
        if (periodNanos <= 0) {
            Log.e(TAG, "Invalid periodNanos " + periodNanos +
                    " in batching request, not started");
            return false;
        }
        mStarted = mNative.startBatch(periodNanos, wakeOnFifoFull);
        if (mStarted) {
            mPeriodNanos = periodNanos;
            mWakeOnFifoFull = wakeOnFifoFull;
        }
        return mStarted;
    }

    /**
     * Forces a flush of existing locations from the hardware batching
     */
    public void flush() {
        if (!mStarted) {
            Log.w(TAG, "Cannot flush since GNSS batching has not started.");
            return;
        }
        mNative.flushBatch();
    }

    /**
     * Stops the batching operation
     */
    public boolean stop() {
        boolean stopped = mNative.stopBatch();
        if (stopped) {
            mStarted = false;
        }
        return stopped;
    }

    /** Disable GNSS batching. */
    public void disable() {
        stop();
        mNative.cleanupBatching();
        mEnabled = false;
    }

    // TODO(b/37460011): Use this with death recovery logic.
    void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        if (mStarted) {
            mNative.startBatch(mPeriodNanos, mWakeOnFifoFull);
        }
    }

    @VisibleForTesting
    static class GnssBatchingProviderNative {
        public int getBatchSize() {
            return native_get_batch_size();
        }

        public boolean startBatch(long periodNanos, boolean wakeOnFifoFull) {
            return native_start_batch(periodNanos, wakeOnFifoFull);
        }

        public void flushBatch() {
            native_flush_batch();
        }

        public boolean stopBatch() {
            return native_stop_batch();
        }

        public boolean initBatching() {
            return native_init_batching();
        }

        public void cleanupBatching() {
            native_cleanup_batching();
        }
    }

    private static native int native_get_batch_size();

    private static native boolean native_start_batch(long periodNanos, boolean wakeOnFifoFull);

    private static native void native_flush_batch();

    private static native boolean native_stop_batch();

    private static native boolean native_init_batching();

    private static native void native_cleanup_batching();
}
