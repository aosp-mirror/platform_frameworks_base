/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A speed/rate limiting cache that's used to cache a value to be returned as long as period hasn't
 * elapsed and then fetches a new value after period has elapsed. Use this when AIDL calls are
 * expensive but the value returned by those APIs don't change often enough (or the recency doesn't
 * matter as much), to incur the cost every time. This class maintains the last fetch time and
 * fetches a new value when period has passed. Do not use this for API calls that have side-effects.
 * <p>
 * By passing in an optional <code>count</code> during creation, this can be used as a rate
 * limiter that allows up to <code>count</code> calls per period to be passed on to the query
 * and then the cached value is returned for the remainder of the period. It uses a simple fixed
 * window method to track rate. Use a window and count appropriate for bursts of calls and for
 * high latency/cost of the AIDL call.
 * <p>
 * This class is thread-safe. When multiple threads call get(), they will all fetch a new value
 * if the cached value is stale. This is to prevent a slow getting thread from blocking other
 * threads from getting a fresh value. In such circumsntaces it's possible to exceed
 * <code>count</code> calls in a given period by up to the number of threads that are concurrently
 * attempting to get a fresh value minus one.
 *
 * @param <Value> The type of the return value
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class RateLimitingCache<Value> {

    private final long mPeriodMillis; // window size
    private final int mLimit; // max per window
    // random offset to avoid batching of AIDL calls at window boundary
    private final long mRandomOffset;
    private final AtomicReference<CachedValue> mCachedValue = new AtomicReference();

    /**
     * The interface to fetch the actual value, if the cache is null or expired.
     * @hide
     * @param <V> The return value type
     */
    public interface ValueFetcher<V> {
        /** Called when the cache needs to be updated.
         * @return the latest value fetched from the source
         */
        V fetchValue();
    }

    class CachedValue {
        Value value;
        long timestamp;
        AtomicInteger count; // current count within window
    }

    /**
     * Create a speed limiting cache that returns the same value until periodMillis has passed
     * and then fetches a new value via the {@link ValueFetcher}.
     *
     * @param periodMillis time to wait before fetching a new value. Use a negative period to
     *                     indicate the value never changes and is fetched only once and
     *                     cached. A value of 0 will mean always fetch a new value.
     */
    public RateLimitingCache(long periodMillis) {
        this(periodMillis, 1);
    }

    /**
     * Create a rate-limiting cache that allows up to <code>count</code> number of AIDL calls per
     * period before it starts returning a cached value. The count resets when the next period
     * begins.
     *
     * @param periodMillis the window of time in which <code>count</code> calls will fetch the
     *                     newest value from the AIDL call.
     * @param count how many times during the period it's ok to forward the request to the fetcher
     *              in the {@link #get(ValueFetcher)} method.
     */
    public RateLimitingCache(long periodMillis, int count) {
        mPeriodMillis = periodMillis;
        mLimit = count;
        if (mLimit > 1 && periodMillis > 1) {
            mRandomOffset = (long) (Math.random() * (periodMillis / 2));
        } else {
            mRandomOffset = 0;
        }
    }

    /**
     * Returns the current time in <code>elapsedRealtime</code>. Can be overridden to use
     * a different timebase that is monotonically increasing; for example, uptimeMillis()
     * @return a monotonically increasing time in milliseconds
     */
    protected long getTime() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Returns either the cached value, if called more frequently than the specific rate, or
     * a new value is fetched and cached. Warning: if the caller is likely to mutate the returned
     * object, override this method and make a clone before returning it.
     * @return the cached or latest value
     */
    public Value get(ValueFetcher<Value> query) {
        CachedValue cached = mCachedValue.get();

        // If the value never changes and there is a previous cached value, return it
        if (mPeriodMillis < 0 && cached != null && cached.timestamp != 0) {
            return cached.value;
        }

        // Get the current time and add a random offset to avoid colliding with other
        // caches with similar harmonic window boundaries
        final long now = getTime() + mRandomOffset;
        final boolean newWindow = cached == null || now - cached.timestamp >= mPeriodMillis;
        if (newWindow || cached.count.getAndIncrement() < mLimit) {
            // Fetch a new value
            Value freshValue = query.fetchValue();
            long freshTimestamp = now;
            // If rate limiting, set timestamp to start of this window
            if (mLimit > 1) {
                freshTimestamp = now - (now % mPeriodMillis);
            }

            CachedValue freshCached = new CachedValue();
            freshCached.value = freshValue;
            freshCached.timestamp = freshTimestamp;
            if (newWindow) {
                freshCached.count = new AtomicInteger(1);
            } else {
                freshCached.count = cached.count;
            }

            // If we fail to CAS then it means that another thread beat us to it.
            // In this case we don't override their work.
            mCachedValue.compareAndSet(cached, freshCached);
        }
        return mCachedValue.get().value;
    }
}
