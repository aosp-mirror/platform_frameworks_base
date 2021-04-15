/*
 * Copyright 2021 The Android Open Source Project
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
package com.android.server.timedetector;

import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_TELEPHONY;
import static com.android.server.timedetector.TimeDetectorStrategy.stringToOrigin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.timezonedetector.ConfigurationChangeListener;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * A singleton that provides access to service configuration for time detection. This hides how
 * configuration is split between static, compile-time config and dynamic, server-pushed flags. It
 * provides a rudimentary mechanism to signal when values have changed.
 */
final class ServiceConfigAccessor {

    private static final int SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS_DEFAULT = 2 * 1000;

    /**
     * By default telephony and network only suggestions are accepted and telephony takes
     * precedence over network.
     */
    private static final @TimeDetectorStrategy.Origin int[]
            DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES = { ORIGIN_TELEPHONY, ORIGIN_NETWORK };

    /**
     * Time in the past. If an automatic time suggestion is before this point, it is sure to be
     * incorrect.
     */
    private static final Instant TIME_LOWER_BOUND_DEFAULT = Instant.ofEpochMilli(
            Long.max(android.os.Environment.getRootDirectory().lastModified(), Build.TIME));

    private static final Set<String> SERVER_FLAGS_KEYS_TO_WATCH = Collections.unmodifiableSet(
            new ArraySet<>(new String[] {
            }));

    private static final Object SLOCK = new Object();

    /** The singleton instance. Initialized once in {@link #getInstance(Context)}. */
    @GuardedBy("SLOCK")
    @Nullable
    private static ServiceConfigAccessor sInstance;

    @NonNull private final Context mContext;
    @NonNull private final ServerFlags mServerFlags;
    @NonNull private final int[] mOriginPriorities;

    /**
     * If a newly calculated system clock time and the current system clock time differs by this or
     * more the system clock will actually be updated. Used to prevent the system clock being set
     * for only minor differences.
     */
    private final int mSystemClockUpdateThresholdMillis;

    private ServiceConfigAccessor(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mServerFlags = ServerFlags.getInstance(mContext);
        mOriginPriorities = getOriginPrioritiesInternal();
        mSystemClockUpdateThresholdMillis =
                SystemProperties.getInt("ro.sys.time_detector_update_diff",
                        SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS_DEFAULT);
    }

    /** Returns the singleton instance. */
    static ServiceConfigAccessor getInstance(Context context) {
        synchronized (SLOCK) {
            if (sInstance == null) {
                sInstance = new ServiceConfigAccessor(context);
            }
            return sInstance;
        }
    }

    /**
     * Adds a listener that will be called when server flags related to this class change. The
     * callbacks are delivered on the main looper thread.
     *
     * <p>Note: Only for use by long-lived objects. There is deliberately no associated remove
     * method.
     */
    void addListener(@NonNull ConfigurationChangeListener listener) {
        mServerFlags.addListener(listener, SERVER_FLAGS_KEYS_TO_WATCH);
    }

    @NonNull
    int[] getOriginPriorities() {
        return mOriginPriorities;
    }

    int systemClockUpdateThresholdMillis() {
        return mSystemClockUpdateThresholdMillis;
    }

    Instant autoTimeLowerBound() {
        return TIME_LOWER_BOUND_DEFAULT;
    }

    private int[] getOriginPrioritiesInternal() {
        String[] originStrings =
                mContext.getResources().getStringArray(R.array.config_autoTimeSourcesPriority);
        if (originStrings.length == 0) {
            return DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES;
        } else {
            int[] origins = new int[originStrings.length];
            for (int i = 0; i < originStrings.length; i++) {
                int origin = stringToOrigin(originStrings[i]);
                origins[i] = origin;
            }

            return origins;
        }
    }
}
