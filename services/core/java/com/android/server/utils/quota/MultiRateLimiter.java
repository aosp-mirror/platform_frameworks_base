/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.utils.quota;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Can be used to rate limit events per app based on multiple rates at the same time. For example,
 * it can limit an event to happen only:
 *
 * <li>5 times in 20 seconds</li>
 * and
 * <li>6 times in 40 seconds</li>
 * and
 * <li>10 times in 1 hour</li>
 *
 * <p><br>
 * All listed rates apply at the same time, and the UPTC will be out of quota if it doesn't satisfy
 * all the given rates. The underlying mechanism used is
 * {@link com.android.server.utils.quota.CountQuotaTracker}, so all its conditions apply, as well
 * as an additional constraint: all the user-package-tag combinations (UPTC) are considered to be in
 * the same {@link com.android.server.utils.quota.Category}.
 * </p>
 *
 * @hide
 */
public class MultiRateLimiter {
    private static final String TAG = "MultiRateLimiter";

    private static final CountQuotaTracker[] EMPTY_TRACKER_ARRAY = {};

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final CountQuotaTracker[] mQuotaTrackers;

    private MultiRateLimiter(List<CountQuotaTracker> quotaTrackers) {
        mQuotaTrackers = quotaTrackers.toArray(EMPTY_TRACKER_ARRAY);
    }

    /** Record that an event happened and count it towards the given quota. */
    public void noteEvent(int userId, @NonNull String packageName, @Nullable String tag) {
        synchronized (mLock) {
            noteEventLocked(userId, packageName, tag);
        }
    }

    /** Check whether the given UPTC is allowed to trigger an event. */
    public boolean isWithinQuota(int userId, @NonNull String packageName, @Nullable String tag) {
        synchronized (mLock) {
            return isWithinQuotaLocked(userId, packageName, tag);
        }
    }

    /** Remove all saved events from the rate limiter for the given app (reset it). */
    public void clear(int userId, @NonNull String packageName) {
        synchronized (mLock) {
            clearLocked(userId, packageName);
        }
    }

    @GuardedBy("mLock")
    private void noteEventLocked(int userId, @NonNull String packageName, @Nullable String tag) {
        for (CountQuotaTracker quotaTracker : mQuotaTrackers) {
            quotaTracker.noteEvent(userId, packageName, tag);
        }
    }

    @GuardedBy("mLock")
    private boolean isWithinQuotaLocked(int userId, @NonNull String packageName,
            @Nullable String tag) {
        for (CountQuotaTracker quotaTracker : mQuotaTrackers) {
            if (!quotaTracker.isWithinQuota(userId, packageName, tag)) {
                return false;
            }
        }
        return true;
    }

    @GuardedBy("mLock")
    private void clearLocked(int userId, @NonNull String packageName) {
        for (CountQuotaTracker quotaTracker : mQuotaTrackers) {
            // This method behaves as if the package has been removed from the device, which
            // isn't the case here, but it does similar clean-up to what we are aiming for here,
            // so it works for this use case.
            quotaTracker.onAppRemovedLocked(userId, packageName);
        }
    }

    /** Can create a new {@link MultiRateLimiter}. */
    public static class Builder {

        private final List<CountQuotaTracker> mQuotaTrackers;
        private final Context mContext;
        private final Categorizer mCategorizer;
        private final Category mCategory;
        @Nullable
        private final QuotaTracker.Injector mInjector;

        /**
         * Creates a new builder and allows to inject an object that can be used
         * to manipulate elapsed time in tests.
         */
        @VisibleForTesting
        Builder(Context context, QuotaTracker.Injector injector) {
            this.mQuotaTrackers = new ArrayList<>();
            this.mContext = context;
            this.mInjector = injector;
            this.mCategorizer = Categorizer.SINGLE_CATEGORIZER;
            this.mCategory = Category.SINGLE_CATEGORY;
        }

        /** Creates a new builder for {@link MultiRateLimiter}. */
        public Builder(Context context) {
            this(context, null);
        }

        /**
         * Adds another rate limit to be used in {@link MultiRateLimiter}.
         *
         * @param limit      The maximum event count an app can have in the rolling time window.
         * @param windowSize The rolling time window to use when checking quota usage.
         */
        public Builder addRateLimit(int limit, Duration windowSize) {
            CountQuotaTracker countQuotaTracker;
            if (mInjector != null) {
                countQuotaTracker = new CountQuotaTracker(mContext, mCategorizer, mInjector);
            } else {
                countQuotaTracker = new CountQuotaTracker(mContext, mCategorizer);
            }
            countQuotaTracker.setCountLimit(mCategory, limit, windowSize.toMillis());
            mQuotaTrackers.add(countQuotaTracker);
            return this;
        }

        /** Adds another rate limit to be used in {@link MultiRateLimiter}. */
        public Builder addRateLimit(@NonNull RateLimit rateLimit) {
            return addRateLimit(rateLimit.mLimit, rateLimit.mWindowSize);
        }

        /** Adds all given rate limits that will be used in {@link MultiRateLimiter}. */
        public Builder addRateLimits(@NonNull RateLimit[] rateLimits) {
            for (RateLimit rateLimit : rateLimits) {
                addRateLimit(rateLimit);
            }
            return this;
        }

        /**
         * Return a new {@link com.android.server.utils.quota.MultiRateLimiter} using set rate
         * limit.
         */
        public MultiRateLimiter build() {
            return new MultiRateLimiter(mQuotaTrackers);
        }
    }

    /** Helper class that describes a rate limit. */
    public static class RateLimit {
        public final int mLimit;
        public final Duration mWindowSize;

        /**
         * @param limit      The maximum count of some occurrence in the rolling time window.
         * @param windowSize The rolling time window to use when checking quota usage.
         */
        private RateLimit(int limit, Duration windowSize) {
            this.mLimit = limit;
            this.mWindowSize = windowSize;
        }

        /**
         * @param limit      The maximum count of some occurrence in the rolling time window.
         * @param windowSize The rolling time window to use when checking quota usage.
         */
        public static RateLimit create(int limit, Duration windowSize) {
            return new RateLimit(limit, windowSize);
        }
    }
}
