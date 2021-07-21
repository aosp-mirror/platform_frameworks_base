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

import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE;
import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_TELEPHONY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.timedetector.TimeDetectorStrategy.Origin;
import com.android.server.timezonedetector.ConfigurationChangeListener;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
    private static final @Origin int[]
            DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES = { ORIGIN_TELEPHONY, ORIGIN_NETWORK };

    /**
     * Time in the past. If an automatic time suggestion is before this point, it is sure to be
     * incorrect.
     */
    private static final Instant TIME_LOWER_BOUND_DEFAULT = Instant.ofEpochMilli(
            Long.max(android.os.Environment.getRootDirectory().lastModified(), Build.TIME));

    /** Device config keys that affect the {@link TimeDetectorService}. */
    private static final Set<String> SERVER_FLAGS_KEYS_TO_WATCH = Collections.unmodifiableSet(
            new ArraySet<>(new String[] {
                    KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE,
                    KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE,
            }));

    private static final Object SLOCK = new Object();

    /** The singleton instance. Initialized once in {@link #getInstance(Context)}. */
    @GuardedBy("SLOCK")
    @Nullable
    private static ServiceConfigAccessor sInstance;

    @NonNull private final Context mContext;
    @NonNull private final ConfigOriginPrioritiesSupplier mConfigOriginPrioritiesSupplier;
    @NonNull private final ServerFlagsOriginPrioritiesSupplier mServerFlagsOriginPrioritiesSupplier;
    @NonNull private final ServerFlags mServerFlags;

    /**
     * If a newly calculated system clock time and the current system clock time differs by this or
     * more the system clock will actually be updated. Used to prevent the system clock being set
     * for only minor differences.
     */
    private final int mSystemClockUpdateThresholdMillis;

    private ServiceConfigAccessor(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mServerFlags = ServerFlags.getInstance(mContext);
        mConfigOriginPrioritiesSupplier = new ConfigOriginPrioritiesSupplier(context);
        mServerFlagsOriginPrioritiesSupplier =
                new ServerFlagsOriginPrioritiesSupplier(mServerFlags);
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
    @Origin int[] getOriginPriorities() {
        int[] serverFlagsValue = mServerFlagsOriginPrioritiesSupplier.get();
        if (serverFlagsValue != null) {
            return serverFlagsValue;
        }

        int[] configValue = mConfigOriginPrioritiesSupplier.get();
        if (configValue != null) {
            return configValue;
        }
        return DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES;
    }

    int systemClockUpdateThresholdMillis() {
        return mSystemClockUpdateThresholdMillis;
    }

    @NonNull
    Instant autoTimeLowerBound() {
        return mServerFlags.getOptionalInstant(KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE)
                .orElse(TIME_LOWER_BOUND_DEFAULT);
    }

    /**
     * A base supplier of an array of time origin integers in priority order.
     * It handles memoization of the result to avoid repeated string parsing when nothing has
     * changed.
     */
    private abstract static class BaseOriginPrioritiesSupplier implements Supplier<@Origin int[]> {
        @GuardedBy("this") @Nullable private String[] mLastPriorityStrings;
        @GuardedBy("this") @Nullable private int[] mLastPriorityInts;

        /** Returns an array of {@code ORIGIN_*} values, or {@code null}. */
        @Override
        @Nullable
        public @Origin int[] get() {
            String[] priorityStrings = lookupPriorityStrings();
            synchronized (this) {
                if (Arrays.equals(mLastPriorityStrings, priorityStrings)) {
                    return mLastPriorityInts;
                }

                int[] priorityInts = null;
                if (priorityStrings != null && priorityStrings.length > 0) {
                    priorityInts = new int[priorityStrings.length];
                    try {
                        for (int i = 0; i < priorityInts.length; i++) {
                            String priorityString = priorityStrings[i];
                            Preconditions.checkArgument(priorityString != null);

                            priorityString = priorityString.trim();
                            priorityInts[i] = TimeDetectorStrategy.stringToOrigin(priorityString);
                        }
                    } catch (IllegalArgumentException e) {
                        // If any strings were bad and they were ignored then the semantics of the
                        // whole list could change, so return null.
                        priorityInts = null;
                    }
                }
                mLastPriorityStrings = priorityStrings;
                mLastPriorityInts = priorityInts;
                return priorityInts;
            }
        }

        @Nullable
        protected abstract String[] lookupPriorityStrings();
    }

    /** Supplies origin priorities from config_autoTimeSourcesPriority. */
    private static class ConfigOriginPrioritiesSupplier extends BaseOriginPrioritiesSupplier {

        @NonNull private final Context mContext;

        private ConfigOriginPrioritiesSupplier(Context context) {
            mContext = Objects.requireNonNull(context);
        }

        @Override
        @Nullable
        protected String[] lookupPriorityStrings() {
            return mContext.getResources().getStringArray(R.array.config_autoTimeSourcesPriority);
        }
    }

    /**
     * Supplies origin priorities from device_config (server flags), see
     * {@link ServerFlags#KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE}.
     */
    private static class ServerFlagsOriginPrioritiesSupplier extends BaseOriginPrioritiesSupplier {

        @NonNull private final ServerFlags mServerFlags;

        private ServerFlagsOriginPrioritiesSupplier(ServerFlags serverFlags) {
            mServerFlags = Objects.requireNonNull(serverFlags);
        }

        @Override
        @Nullable
        protected String[] lookupPriorityStrings() {
            Optional<String[]> priorityStrings = mServerFlags.getOptionalStringArray(
                    KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE);
            return priorityStrings.orElse(null);
        }
    }
}
