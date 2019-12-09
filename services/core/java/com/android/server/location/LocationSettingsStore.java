/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.location;

import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS;
import static android.provider.Settings.Global.LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST;
import static android.provider.Settings.Global.LOCATION_LAST_LOCATION_MAX_AGE_MILLIS;
import static android.provider.Settings.Secure.LOCATION_MODE;
import static android.provider.Settings.Secure.LOCATION_MODE_OFF;

import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemConfig;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Provides accessors and listeners for all location related settings.
 */
public class LocationSettingsStore {

    /**
     * Listener for user-specific settings changes.
     */
    public interface UserSettingChangedListener {
        /**
         * Called when setting changes.
         */
        void onSettingChanged(int userId);
    }

    /**
     * Listener for global settings changes.
     */
    public interface GlobalSettingChangedListener extends UserSettingChangedListener {
        /**
         * Called when setting changes.
         */
        void onSettingChanged();

        @Override
        default void onSettingChanged(int userId) {
            onSettingChanged();
        }
    }

    private static final String LOCATION_PACKAGE_BLACKLIST = "locationPackagePrefixBlacklist";
    private static final String LOCATION_PACKAGE_WHITELIST = "locationPackagePrefixWhitelist";

    private static final long DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;
    private static final long DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS =
            30 * 60 * 1000;
    private static final long DEFAULT_MAX_LAST_LOCATION_AGE_MS = 20 * 60 * 1000;

    private final Context mContext;

    private final IntegerSecureSetting mLocationMode;
    private final LongGlobalSetting mBackgroundThrottleIntervalMs;
    private final StringListCachedSecureSetting mLocationPackageBlacklist;
    private final StringListCachedSecureSetting mLocationPackageWhitelist;
    private final StringSetCachedGlobalSetting mBackgroundThrottlePackageWhitelist;
    private final StringSetCachedGlobalSetting mIgnoreSettingsPackageWhitelist;

    // TODO: get rid of handler
    public LocationSettingsStore(Context context, Handler handler) {
        mContext = context;

        mLocationMode = new IntegerSecureSetting(context, LOCATION_MODE, handler);
        mBackgroundThrottleIntervalMs = new LongGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS, handler);
        mLocationPackageBlacklist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_BLACKLIST, handler);
        mLocationPackageWhitelist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_WHITELIST, handler);
        mBackgroundThrottlePackageWhitelist = new StringSetCachedGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST,
                () -> SystemConfig.getInstance().getAllowUnthrottledLocation(), handler);
        mIgnoreSettingsPackageWhitelist = new StringSetCachedGlobalSetting(context,
                LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST,
                () -> SystemConfig.getInstance().getAllowIgnoreLocationSettings(), handler);
    }

    /**
     * Retrieve if location is enabled or not.
     */
    public boolean isLocationEnabled(int userId) {
        return mLocationMode.getValueForUser(LOCATION_MODE_OFF, userId) != LOCATION_MODE_OFF;
    }

    /**
     * Add a listener for changes to the location enabled setting.
     */
    public void addOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationMode.addListener(listener);
    }

    /**
     * Remove a listener for changes to the location enabled setting.
     */
    public void removeOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationMode.addListener(listener);
    }

    /**
     * Retrieve the background throttle interval.
     */
    public long getBackgroundThrottleIntervalMs() {
        return mBackgroundThrottleIntervalMs.getValue(DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS);
    }

    /**
     * Add a listener for changes to the background throttle interval.
     */
    public void addOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalMs.addListener(listener);
    }

    /**
     * Remove a listener for changes to the background throttle interval.
     */
    public void removeOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalMs.removeListener(listener);
    }

    /**
     * Check if the given package is blacklisted for location access.
     */
    public boolean isLocationPackageBlacklisted(int userId, String packageName) {
        List<String> locationPackageBlacklist = mLocationPackageBlacklist.getValueForUser(userId);
        if (locationPackageBlacklist.isEmpty()) {
            return false;
        }

        List<String> locationPackageWhitelist = mLocationPackageWhitelist.getValueForUser(userId);
        for (String locationWhitelistPackage : locationPackageWhitelist) {
            if (packageName.startsWith(locationWhitelistPackage)) {
                return false;
            }
        }

        for (String locationBlacklistPackage : locationPackageBlacklist) {
            if (packageName.startsWith(locationBlacklistPackage)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Retrieve the background throttle package whitelist.
     */
    public Set<String> getBackgroundThrottlePackageWhitelist() {
        return mBackgroundThrottlePackageWhitelist.getValue();
    }

    /**
     * Add a listener for changes to the background throttle package whitelist.
     */
    public void addOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelist.addListener(listener);
    }

    /**
     * Remove a listener for changes to the background throttle package whitelist.
     */
    public void removeOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelist.removeListener(listener);
    }

    /**
     * Retrieve the ignore settings package whitelist.
     */
    public Set<String> getIgnoreSettingsPackageWhitelist() {
        return mIgnoreSettingsPackageWhitelist.getValue();
    }

    /**
     * Add a listener for changes to the ignore settings package whitelist.
     */
    public void addOnIgnoreSettingsPackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsPackageWhitelist.addListener(listener);
    }

    /**
     * Remove a listener for changes to the ignore settings package whitelist.
     */
    public void removeOnIgnoreSettingsPackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsPackageWhitelist.removeListener(listener);
    }

    /**
     * Retrieve the background throttling proximity alert interval.
     */
    public long getBackgroundThrottleProximityAlertIntervalMs() {
        return Settings.Global.getLong(mContext.getContentResolver(),
                LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS,
                DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS);
    }

    public long getMaxLastLocationAgeMs() {
        return Settings.Global.getLong(
                mContext.getContentResolver(),
                LOCATION_LAST_LOCATION_MAX_AGE_MILLIS,
                DEFAULT_MAX_LAST_LOCATION_AGE_MS);
    }

    /**
     * Dump info for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        int userId = ActivityManager.getCurrentUser();

        ipw.print("Location Enabled: ");
        ipw.println(isLocationEnabled(userId));

        List<String> locationPackageBlacklist = mLocationPackageBlacklist.getValueForUser(userId);
        if (!locationPackageBlacklist.isEmpty()) {
            ipw.println("Location Blacklisted Packages:");
            ipw.increaseIndent();
            for (String packageName : locationPackageBlacklist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();

            List<String> locationPackageWhitelist = mLocationPackageWhitelist.getValueForUser(
                    userId);
            if (!locationPackageWhitelist.isEmpty()) {
                ipw.println("Location Whitelisted Packages:");
                ipw.increaseIndent();
                for (String packageName : locationPackageWhitelist) {
                    ipw.println(packageName);
                }
                ipw.decreaseIndent();
            }
        }

        Set<String> backgroundThrottlePackageWhitelist =
                mBackgroundThrottlePackageWhitelist.getValue();
        if (!backgroundThrottlePackageWhitelist.isEmpty()) {
            ipw.println("Throttling Whitelisted Packages:");
            ipw.increaseIndent();
            for (String packageName : backgroundThrottlePackageWhitelist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();
        }

        Set<String> ignoreSettingsPackageWhitelist = mIgnoreSettingsPackageWhitelist.getValue();
        if (!ignoreSettingsPackageWhitelist.isEmpty()) {
            ipw.println("Bypass Whitelisted Packages:");
            ipw.increaseIndent();
            for (String packageName : ignoreSettingsPackageWhitelist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();
        }
    }

    private abstract static class ObservingSetting extends ContentObserver {

        private final CopyOnWriteArrayList<UserSettingChangedListener> mListeners;

        private ObservingSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mListeners = new CopyOnWriteArrayList<>();

            context.getContentResolver().registerContentObserver(
                    getUriFor(settingName), false, this, UserHandle.USER_ALL);
        }

        public void addListener(UserSettingChangedListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(UserSettingChangedListener listener) {
            mListeners.remove(listener);
        }

        protected abstract Uri getUriFor(String settingName);

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            for (UserSettingChangedListener listener : mListeners) {
                listener.onSettingChanged(userId);
            }
        }
    }

    private static class IntegerSecureSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        private IntegerSecureSetting(Context context, String settingName, Handler handler) {
            super(context, settingName, handler);
            mContext = context;
            mSettingName = settingName;
        }

        public int getValueForUser(int defaultValue, int userId) {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(), mSettingName,
                    defaultValue, userId);
        }

        @Override
        protected Uri getUriFor(String settingName) {
            return Settings.Secure.getUriFor(settingName);
        }
    }

    private static class StringListCachedSecureSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        private int mCachedUserId = UserHandle.USER_NULL;
        private List<String> mCachedValue;

        private StringListCachedSecureSetting(Context context, String settingName,
                Handler handler) {
            super(context, settingName, handler);
            mContext = context;
            mSettingName = settingName;
        }

        public synchronized List<String> getValueForUser(int userId) {
            Preconditions.checkArgument(userId != UserHandle.USER_NULL);

            if (userId != mCachedUserId) {
                String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                        mSettingName, userId);
                if (TextUtils.isEmpty(setting)) {
                    mCachedValue = Collections.emptyList();
                } else {
                    mCachedValue = Arrays.asList(setting.split(","));
                }
                mCachedUserId = userId;
            }

            return mCachedValue;
        }

        public synchronized void invalidateForUser(int userId) {
            if (mCachedUserId == userId) {
                mCachedUserId = UserHandle.USER_NULL;
                mCachedValue = null;
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            invalidateForUser(userId);
            super.onChange(selfChange, uri, userId);
        }

        @Override
        protected Uri getUriFor(String settingName) {
            return Settings.Secure.getUriFor(settingName);
        }
    }

    private static class LongGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        private LongGlobalSetting(Context context, String settingName, Handler handler) {
            super(context, settingName, handler);
            mContext = context;
            mSettingName = settingName;
        }

        public long getValue(long defaultValue) {
            return Settings.Global.getLong(mContext.getContentResolver(), mSettingName,
                    defaultValue);
        }

        @Override
        protected Uri getUriFor(String settingName) {
            return Settings.Global.getUriFor(settingName);
        }
    }

    private static class StringSetCachedGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;
        private final Supplier<ArraySet<String>> mBaseValuesSupplier;

        private boolean mValid;
        private ArraySet<String> mCachedValue;

        private StringSetCachedGlobalSetting(Context context, String settingName,
                Supplier<ArraySet<String>> baseValuesSupplier, Handler handler) {
            super(context, settingName, handler);
            mContext = context;
            mSettingName = settingName;
            mBaseValuesSupplier = baseValuesSupplier;
        }

        public synchronized Set<String> getValue() {
            if (!mValid) {
                mCachedValue = new ArraySet<>(mBaseValuesSupplier.get());
                String setting = Settings.Global.getString(mContext.getContentResolver(),
                        mSettingName);
                if (!TextUtils.isEmpty(setting)) {
                    mCachedValue.addAll(Arrays.asList(setting.split(",")));
                }
                mValid = true;
            }

            return mCachedValue;
        }

        public synchronized void invalidate() {
            mValid = false;
            mCachedValue = null;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            invalidate();
            super.onChange(selfChange, uri, userId);
        }

        @Override
        protected Uri getUriFor(String settingName) {
            return Settings.Global.getUriFor(settingName);
        }
    }
}
