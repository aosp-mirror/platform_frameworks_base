/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkArgumentNonnegative;
import static com.android.internal.util.Preconditions.checkArgumentPositive;

/**
 * A class useful for rate-limiting or throttling that stores and distributes tokens.
 *
 * A TokenBucket starts with a fixed capacity of tokens, an initial amount of tokens, and
 * a fixed filling period (in milliseconds).
 *
 * For every filling period, the bucket gains one token, up to its maximum capacity from
 * which point tokens simply overflow and are lost. Tokens can be obtained one by one or n by n.
 *
 * The available amount of tokens is computed lazily when the bucket state is inspected.
 * Therefore it is purely synchronous and does not involve any asynchronous activity.
 * It is not synchronized in any way and not a thread-safe object.
 *
 * {@hide}
 */
public class TokenBucket {

    private final int mFillDelta; // Time in ms it takes to generate one token.
    private final int mCapacity;  // Maximum number of tokens that can be stored.
    private long mLastFill;       // Last time in ms the bucket generated tokens.
    private int mAvailable;       // Current number of available tokens.

    /**
     * Create a new TokenBucket.
     * @param deltaMs the time in milliseconds it takes to generate a new token.
     * Must be strictly positive.
     * @param capacity the maximum token capacity. Must be strictly positive.
     * @param tokens the starting amount of token. Must be positive or zero.
     */
    public TokenBucket(int deltaMs, int capacity, int tokens) {
        mFillDelta = checkArgumentPositive(deltaMs, "deltaMs must be strictly positive");
        mCapacity = checkArgumentPositive(capacity, "capacity must be strictly positive");
        mAvailable = Math.min(checkArgumentNonnegative(tokens), mCapacity);
        mLastFill = scaledTime();
    }

    /**
     * Create a new TokenBucket that starts completely filled.
     * @param deltaMs the time in milliseconds it takes to generate a new token.
     * Must be strictly positive.
     * @param capacity the maximum token capacity. Must be strictly positive.
     */
    public TokenBucket(int deltaMs, int capacity) {
        this(deltaMs, capacity, capacity);
    }

    /** Reset this TokenBucket and set its number of available tokens. */
    public void reset(int tokens) {
        checkArgumentNonnegative(tokens);
        mAvailable = Math.min(tokens, mCapacity);
        mLastFill = scaledTime();
    }

    /** Returns this TokenBucket maximum token capacity. */
    public int capacity() {
        return mCapacity;
    }

    /** Returns this TokenBucket currently number of available tokens. */
    public int available() {
        fill();
        return mAvailable;
    }

    /** Returns true if this TokenBucket as one or more tokens available. */
    public boolean has() {
        fill();
        return mAvailable > 0;
    }

    /** Consumes a token from this TokenBucket and returns true if a token is available. */
    public boolean get() {
        return (get(1) == 1);
    }

    /**
     * Try to consume many tokens from this TokenBucket.
     * @param n the number of tokens to consume.
     * @return the number of tokens that were actually consumed.
     */
    public int get(int n) {
        fill();
        if (n <= 0) {
            return 0;
        }
        if (n > mAvailable) {
            int got = mAvailable;
            mAvailable = 0;
            return got;
        }
        mAvailable -= n;
        return n;
    }

    private void fill() {
        final long now = scaledTime();
        final int diff = (int) (now - mLastFill);
        mAvailable = Math.min(mCapacity, mAvailable + diff);
        mLastFill = now;
    }

    private long scaledTime() {
        return SystemClock.elapsedRealtime() / mFillDelta;
    }
}
