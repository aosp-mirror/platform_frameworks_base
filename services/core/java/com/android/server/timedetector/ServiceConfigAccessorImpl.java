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

import static android.content.Intent.ACTION_USER_SWITCHED;

import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE;
import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_EXTERNAL;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_GNSS;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_TELEPHONY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.timedetector.TimeDetectorHelper;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.IUserRestrictionsListener;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.timedetector.TimeDetectorStrategy.Origin;
import com.android.server.timezonedetector.StateChangeListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A singleton implementation of {@link ServiceConfigAccessor}.
 */
final class ServiceConfigAccessorImpl implements ServiceConfigAccessor {

    /**
     * An absolute threshold at/below which the system clock confidence can be upgraded. i.e. if the
     * detector receives a high-confidence time and the current system clock is +/- this value from
     * that time and the confidence in the time is low, then the device's confidence in the current
     * system clock time can be upgraded. This needs to be an amount users would consider
     * "close enough".
     */
    private static final int SYSTEM_CLOCK_CONFIRMATION_THRESHOLD_MILLIS = 1000;

    /**
     * By default telephony and network only suggestions are accepted and telephony takes
     * precedence over network.
     */
    private static final @Origin int[]
            DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES = { ORIGIN_TELEPHONY, ORIGIN_NETWORK };

    /** Device config keys that affect the {@link TimeDetectorService}. */
    private static final Set<String> SERVER_FLAGS_KEYS_TO_WATCH = Set.of(
            KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE,
            KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE
    );

    private static final Object SLOCK = new Object();

    /** The singleton instance. Initialized once in {@link #getInstance(Context)}. */
    @GuardedBy("SLOCK")
    @Nullable
    private static ServiceConfigAccessor sInstance;

    @NonNull private final Context mContext;
    @NonNull private final ServerFlags mServerFlags;
    @NonNull private final ContentResolver mCr;
    @NonNull private final UserManager mUserManager;
    @NonNull private final ConfigOriginPrioritiesSupplier mConfigOriginPrioritiesSupplier;
    @NonNull private final ServerFlagsOriginPrioritiesSupplier mServerFlagsOriginPrioritiesSupplier;

    @GuardedBy("this")
    @NonNull
    private final List<StateChangeListener> mConfigurationInternalListeners = new ArrayList<>();

    /**
     * If a newly calculated system clock time and the current system clock time differs by this or
     * more the system clock will actually be updated. Used to prevent the system clock being set
     * for only minor differences.
     */
    private final int mSystemClockUpdateThresholdMillis;

    private ServiceConfigAccessorImpl(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mCr = context.getContentResolver();
        mUserManager = context.getSystemService(UserManager.class);
        mServerFlags = ServerFlags.getInstance(mContext);
        mConfigOriginPrioritiesSupplier = new ConfigOriginPrioritiesSupplier(context);
        mServerFlagsOriginPrioritiesSupplier =
                new ServerFlagsOriginPrioritiesSupplier(mServerFlags);
        mSystemClockUpdateThresholdMillis = context.getResources().getInteger(
                R.integer.config_timeDetectorAutoUpdateDiffMillis);

        // Wire up the config change listeners for anything that could affect ConfigurationInternal.
        // Use the main thread for event delivery, listeners can post to their chosen thread.

        // Listen for the user changing / the user's location mode changing. Report on the main
        // thread.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USER_SWITCHED);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleConfigurationInternalChangeOnMainThread();
            }
        }, filter, null, null /* main thread */);

        Handler mainThreadHandler = mContext.getMainThreadHandler();

        // Add async callbacks for global settings being changed.
        ContentResolver contentResolver = mContext.getContentResolver();
        ContentObserver contentObserver = new ContentObserver(mainThreadHandler) {
            @Override
            public void onChange(boolean selfChange) {
                handleConfigurationInternalChangeOnMainThread();
            }
        };
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true, contentObserver);

        // Watch server flags.
        mServerFlags.addListener(this::handleConfigurationInternalChangeOnMainThread,
                SERVER_FLAGS_KEYS_TO_WATCH);

        // Watch for policy changes that affect what the user is permitted to do.
        mUserManager.addUserRestrictionsListener(
                new IUserRestrictionsListener.Stub() {
                    @Override
                    public void onUserRestrictionsChanged(
                            int userId, Bundle newRestrictions, Bundle prevRestrictions) {
                        // This callback currently delivered on main thread, but this post() is
                        // defensive and doesn't rely on that in case it changes.
                        mainThreadHandler.post(
                                () -> handleUserRestrictionsChangeOnMainThread(
                                        userId, newRestrictions, prevRestrictions));
                    }
                });
    }

    /** Returns the singleton instance. */
    static ServiceConfigAccessor getInstance(Context context) {
        synchronized (SLOCK) {
            if (sInstance == null) {
                sInstance = new ServiceConfigAccessorImpl(context);
            }
            return sInstance;
        }
    }

    private void handleConfigurationInternalChangeOnMainThread() {
        // Copy the listeners holding the "this" lock but don't hold the lock while delivering the
        // notifications to avoid deadlocks.
        List<StateChangeListener> configurationInternalListeners;
        synchronized (this) {
            configurationInternalListeners = new ArrayList<>(this.mConfigurationInternalListeners);
        }
        for (StateChangeListener changeListener : configurationInternalListeners) {
            changeListener.onChange();
        }
    }

    private void handleUserRestrictionsChangeOnMainThread(
            int userId, Bundle newRestrictions, Bundle prevRestrictions) {
        // No attempt at optimisation here. If the policy changes in any way for any user, just
        // notify.
        handleConfigurationInternalChangeOnMainThread();
    }

    @Override
    public synchronized void addConfigurationInternalChangeListener(
            @NonNull StateChangeListener listener) {
        mConfigurationInternalListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public synchronized void removeConfigurationInternalChangeListener(
            @NonNull StateChangeListener listener) {
        mConfigurationInternalListeners.remove(Objects.requireNonNull(listener));
    }

    @Override
    @NonNull
    public synchronized ConfigurationInternal getCurrentUserConfigurationInternal() {
        int currentUserId =
                LocalServices.getService(ActivityManagerInternal.class).getCurrentUserId();
        return getConfigurationInternal(currentUserId);
    }

    @Override
    public synchronized boolean updateConfiguration(@UserIdInt int userId,
            @NonNull TimeConfiguration requestedConfiguration, boolean bypassUserPolicyChecks) {
        Objects.requireNonNull(requestedConfiguration);

        TimeCapabilitiesAndConfig capabilitiesAndConfig = getConfigurationInternal(userId)
                .createCapabilitiesAndConfig(bypassUserPolicyChecks);
        TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
        TimeConfiguration oldConfiguration = capabilitiesAndConfig.getConfiguration();

        final TimeConfiguration newConfiguration =
                capabilities.tryApplyConfigChanges(oldConfiguration, requestedConfiguration);
        if (newConfiguration == null) {
            // The changes could not be made because the user's capabilities do not allow it.
            return false;
        }

        // Store the configuration / notify as needed. This will cause the mEnvironment to invoke
        // handleConfigChanged() asynchronously.
        storeConfiguration(userId, newConfiguration);

        return true;
    }

    /**
     * Stores the configuration properties contained in {@code newConfiguration}.
     * All checks about user capabilities must be done by the caller and
     * {@link TimeConfiguration#isComplete()} must be {@code true}.
     */
    @GuardedBy("this")
    private void storeConfiguration(
            @UserIdInt int userId, @NonNull TimeConfiguration configuration) {
        Objects.requireNonNull(configuration);

        // Avoid writing the auto detection enabled setting for devices that do not support auto
        // time detection: if we wrote it down then we'd set the value explicitly, which would
        // prevent detecting "default" later. That might influence what happens on later releases
        // that support new types of auto detection on the same hardware.
        if (isAutoDetectionSupported()) {
            final boolean autoDetectionEnabled = configuration.isAutoDetectionEnabled();
            setAutoDetectionEnabledIfRequired(autoDetectionEnabled);
        }
    }

    @Override
    @NonNull
    public synchronized ConfigurationInternal getConfigurationInternal(@UserIdInt int userId) {
        TimeDetectorHelper timeDetectorHelper = TimeDetectorHelper.INSTANCE;
        return new ConfigurationInternal.Builder(userId)
                .setUserConfigAllowed(isUserConfigAllowed(userId))
                .setAutoDetectionSupported(isAutoDetectionSupported())
                .setAutoDetectionEnabledSetting(getAutoDetectionEnabledSetting())
                .setSystemClockUpdateThresholdMillis(getSystemClockUpdateThresholdMillis())
                .setSystemClockConfidenceThresholdMillis(
                        getSystemClockConfidenceUpgradeThresholdMillis())
                .setAutoSuggestionLowerBound(getAutoSuggestionLowerBound())
                .setManualSuggestionLowerBound(timeDetectorHelper.getManualSuggestionLowerBound())
                .setSuggestionUpperBound(timeDetectorHelper.getSuggestionUpperBound())
                .setOriginPriorities(getOriginPriorities())
                .build();
    }

    private void setAutoDetectionEnabledIfRequired(boolean enabled) {
        // This check is racey, but the whole settings update process is racey. This check prevents
        // a ConfigurationChangeListener callback triggering due to ContentObserver's still
        // triggering *sometimes* for no-op updates. Because callbacks are async this is necessary
        // for stable behavior during tests.
        if (getAutoDetectionEnabledSetting() != enabled) {
            Settings.Global.putInt(mCr, Settings.Global.AUTO_TIME, enabled ? 1 : 0);
        }
    }

    private boolean isUserConfigAllowed(@UserIdInt int userId) {
        UserHandle userHandle = UserHandle.of(userId);
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, userHandle);
    }

    private boolean getAutoDetectionEnabledSetting() {
        return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME, 1 /* default */) > 0;
    }

    /** Returns {@code true} if any form of automatic time detection is supported. */
    private boolean isAutoDetectionSupported() {
        @Origin int[] originsSupported = getOriginPriorities();
        for (@Origin int originSupported : originsSupported) {
            if (originSupported == ORIGIN_NETWORK
                    || originSupported == ORIGIN_EXTERNAL
                    || originSupported == ORIGIN_GNSS) {
                return true;
            } else if (originSupported == ORIGIN_TELEPHONY) {
                boolean deviceHasTelephony = mContext.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
                if (deviceHasTelephony) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getSystemClockUpdateThresholdMillis() {
        return mSystemClockUpdateThresholdMillis;
    }

    private int getSystemClockConfidenceUpgradeThresholdMillis() {
        return SYSTEM_CLOCK_CONFIRMATION_THRESHOLD_MILLIS;
    }

    @NonNull
    private Instant getAutoSuggestionLowerBound() {
        return mServerFlags.getOptionalInstant(KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE)
                .orElse(TimeDetectorHelper.INSTANCE.getAutoSuggestionLowerBoundDefault());
    }

    @NonNull
    private @Origin int[] getOriginPriorities() {
        @Origin int[] serverFlagsValue = mServerFlagsOriginPrioritiesSupplier.get();
        if (serverFlagsValue != null) {
            return serverFlagsValue;
        }

        @Origin int[] configValue = mConfigOriginPrioritiesSupplier.get();
        if (configValue != null) {
            return configValue;
        }
        return DEFAULT_AUTOMATIC_TIME_ORIGIN_PRIORITIES;
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
                if (priorityStrings != null) {
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
