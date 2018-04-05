package com.android.server.location;

/**
 * A simple implementation of exponential backoff.
 */
class ExponentialBackOff {
    private static final int MULTIPLIER = 2;
    private final long mInitIntervalMillis;
    private final long mMaxIntervalMillis;
    private long mCurrentIntervalMillis;

    ExponentialBackOff(long initIntervalMillis, long maxIntervalMillis) {
        mInitIntervalMillis = initIntervalMillis;
        mMaxIntervalMillis = maxIntervalMillis;

        mCurrentIntervalMillis = mInitIntervalMillis / MULTIPLIER;
    }

    long nextBackoffMillis() {
        if (mCurrentIntervalMillis > mMaxIntervalMillis) {
            return mMaxIntervalMillis;
        }

        mCurrentIntervalMillis *= MULTIPLIER;
        return mCurrentIntervalMillis;
    }

    void reset() {
        mCurrentIntervalMillis = mInitIntervalMillis / MULTIPLIER;
    }
}

