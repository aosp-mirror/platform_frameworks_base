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
import static android.provider.Settings.Secure.LOCATION_PROVIDERS_ALLOWED;

import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for all location related settings.
 */
public class LocationSettingsStore {

    private static final String LOCATION_PACKAGE_BLACKLIST = "locationPackagePrefixBlacklist";
    private static final String LOCATION_PACKAGE_WHITELIST = "locationPackagePrefixWhitelist";

    private static final long DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;
    private static final long DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS =
            30 * 60 * 1000;
    private static final long DEFAULT_MAX_LAST_LOCATION_AGE_MS = 20 * 60 * 1000;

    private final Context mContext;

    private final IntegerSecureSetting mLocationMode;
    private final StringListCachedSecureSetting mLocationProvidersAllowed;
    private final LongGlobalSetting mBackgroundThrottleIntervalMs;
    private final StringListCachedSecureSetting mLocationPackageBlacklist;
    private final StringListCachedSecureSetting mLocationPackageWhitelist;
    private final StringListCachedGlobalSetting mBackgroundThrottlePackageWhitelist;
    private final StringListCachedGlobalSetting mIgnoreSettingsPackageWhitelist;

    public LocationSettingsStore(Context context, Handler handler) {
        mContext = context;

        mLocationMode = new IntegerSecureSetting(context, LOCATION_MODE, handler);
        mLocationProvidersAllowed = new StringListCachedSecureSetting(context,
                LOCATION_PROVIDERS_ALLOWED, handler);
        mBackgroundThrottleIntervalMs = new LongGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS, handler);
        mLocationPackageBlacklist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_BLACKLIST, handler);
        mLocationPackageWhitelist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_WHITELIST, handler);
        mBackgroundThrottlePackageWhitelist = new StringListCachedGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST, handler);
        mIgnoreSettingsPackageWhitelist = new StringListCachedGlobalSetting(context,
                LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST, handler);
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
    public void addOnLocationEnabledChangedListener(Runnable listener) {
        mLocationMode.addListener(listener);
    }

    /**
     * Remove a listener for changes to the location enabled setting.
     */
    public void removeOnLocationEnabledChangedListener(Runnable listener) {
        mLocationMode.addListener(listener);
    }

    /**
     * Retrieve the currently allowed location providers.
     */
    public List<String> getLocationProvidersAllowed(int userId) {
        return mLocationProvidersAllowed.getValueForUser(userId);
    }

    /**
     * Add a listener for changes to the currently allowed location providers.
     */
    public void addOnLocationProvidersAllowedChangedListener(Runnable runnable) {
        mLocationProvidersAllowed.addListener(runnable);
    }

    /**
     * Remove a listener for changes to the currently allowed location providers.
     */
    public void removeOnLocationProvidersAllowedChangedListener(Runnable runnable) {
        mLocationProvidersAllowed.removeListener(runnable);
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
    public void addOnBackgroundThrottleIntervalChangedListener(Runnable listener) {
        mBackgroundThrottleIntervalMs.addListener(listener);
    }

    /**
     * Remove a listener for changes to the background throttle interval.
     */
    public void removeOnBackgroundThrottleIntervalChangedListener(Runnable listener) {
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
    public List<String> getBackgroundThrottlePackageWhitelist() {
        return mBackgroundThrottlePackageWhitelist.getValue();
    }

    /**
     * Add a listener for changes to the background throttle package whitelist.
     */
    public void addOnBackgroundThrottlePackageWhitelistChangedListener(Runnable listener) {
        mBackgroundThrottlePackageWhitelist.addListener(listener);
    }

    /**
     * Remove a listener for changes to the background throttle package whitelist.
     */
    public void removeOnBackgroundThrottlePackageWhitelistChangedListener(Runnable listener) {
        mBackgroundThrottlePackageWhitelist.removeListener(listener);
    }

    /**
     * Retrieve the ignore settings package whitelist.
     */
    public List<String> getIgnoreSettingsPackageWhitelist() {
        return mIgnoreSettingsPackageWhitelist.getValue();
    }

    /**
     * Add a listener for changes to the ignore settings package whitelist.
     */
    public void addOnIgnoreSettingsPackageWhitelistChangedListener(Runnable listener) {
        mIgnoreSettingsPackageWhitelist.addListener(listener);
    }

    /**
     * Remove a listener for changes to the ignore settings package whitelist.
     */
    public void removeOnIgnoreSettingsPackageWhitelistChangedListener(Runnable listener) {
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

        ipw.print("Location Providers Allowed: ");
        ipw.println(getLocationProvidersAllowed(userId));

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

        List<String> backgroundThrottlePackageWhitelist =
                mBackgroundThrottlePackageWhitelist.getValue();
        if (!backgroundThrottlePackageWhitelist.isEmpty()) {
            ipw.println("Throttling Whitelisted Packages:");
            ipw.increaseIndent();
            for (String packageName : backgroundThrottlePackageWhitelist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();
        }

        List<String> ignoreSettingsPackageWhitelist = mIgnoreSettingsPackageWhitelist.getValue();
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

        private final CopyOnWriteArrayList<Runnable> mListeners;

        private ObservingSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mListeners = new CopyOnWriteArrayList<>();

            context.getContentResolver().registerContentObserver(
                    getUriFor(settingName), false, this, UserHandle.USER_ALL);
        }

        public void addListener(Runnable listener) {
            mListeners.add(listener);
        }

        public void removeListener(Runnable listener) {
            mListeners.remove(listener);
        }

        protected abstract Uri getUriFor(String settingName);

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            for (Runnable listener : mListeners) {
                listener.run();
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

    private static class StringListCachedGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        private boolean mValid;
        private List<String> mCachedValue;

        private StringListCachedGlobalSetting(Context context, String settingName,
                Handler handler) {
            super(context, settingName, handler);
            mContext = context;
            mSettingName = settingName;
        }

        public synchronized List<String> getValue() {
            if (!mValid) {
                String setting = Settings.Global.getString(mContext.getContentResolver(),
                        mSettingName);
                if (TextUtils.isEmpty(setting)) {
                    mCachedValue = Collections.emptyList();
                } else {
                    mCachedValue = Arrays.asList(setting.split(","));
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
