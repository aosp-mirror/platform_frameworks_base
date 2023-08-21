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

package com.android.server.location.injector;

import static android.location.LocationDeviceConfig.ADAS_SETTINGS_ALLOWLIST;
import static android.location.LocationDeviceConfig.IGNORE_SETTINGS_ALLOWLIST;
import static android.provider.Settings.Global.ENABLE_GNSS_RAW_MEAS_FULL_TRACKING;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS;
import static android.provider.Settings.Secure.LOCATION_COARSE_ACCURACY_M;
import static android.provider.Settings.Secure.LOCATION_MODE;
import static android.provider.Settings.Secure.LOCATION_MODE_OFF;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.PackageTagsList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.SystemConfig;

import java.io.FileDescriptor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Provides accessors and listeners for all location related settings.
 */
public class SystemSettingsHelper extends SettingsHelper {

    private static final String LOCATION_PACKAGE_DENYLIST = "locationPackagePrefixBlacklist";
    private static final String LOCATION_PACKAGE_ALLOWLIST = "locationPackagePrefixWhitelist";

    private static final long DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;
    private static final long DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS =
            30 * 60 * 1000;
    private static final float DEFAULT_COARSE_LOCATION_ACCURACY_M = 2000.0f;

    private final Context mContext;

    private final IntegerSecureSetting mLocationMode;
    private final LongGlobalSetting mBackgroundThrottleIntervalMs;
    private final BooleanGlobalSetting mGnssMeasurementFullTracking;
    private final StringListCachedSecureSetting mLocationPackageBlacklist;
    private final StringListCachedSecureSetting mLocationPackageWhitelist;
    private final StringSetCachedGlobalSetting mBackgroundThrottlePackageWhitelist;
    private final PackageTagsListSetting mAdasPackageAllowlist;
    private final PackageTagsListSetting mIgnoreSettingsPackageAllowlist;

    public SystemSettingsHelper(Context context) {
        mContext = context;

        mLocationMode = new IntegerSecureSetting(context, LOCATION_MODE, FgThread.getHandler());
        mBackgroundThrottleIntervalMs = new LongGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS, FgThread.getHandler());
        mGnssMeasurementFullTracking = new BooleanGlobalSetting(context,
                ENABLE_GNSS_RAW_MEAS_FULL_TRACKING, FgThread.getHandler());
        mLocationPackageBlacklist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_DENYLIST, FgThread.getHandler());
        mLocationPackageWhitelist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_ALLOWLIST, FgThread.getHandler());
        mBackgroundThrottlePackageWhitelist = new StringSetCachedGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST,
                () -> SystemConfig.getInstance().getAllowUnthrottledLocation(),
                FgThread.getHandler());
        mAdasPackageAllowlist = new PackageTagsListSetting(
                ADAS_SETTINGS_ALLOWLIST,
                () -> SystemConfig.getInstance().getAllowAdasLocationSettings());
        mIgnoreSettingsPackageAllowlist = new PackageTagsListSetting(
                IGNORE_SETTINGS_ALLOWLIST,
                () -> SystemConfig.getInstance().getAllowIgnoreLocationSettings());
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        mLocationMode.register();
        mBackgroundThrottleIntervalMs.register();
        mLocationPackageBlacklist.register();
        mLocationPackageWhitelist.register();
        mBackgroundThrottlePackageWhitelist.register();
        mIgnoreSettingsPackageAllowlist.register();
    }

    @Override
    public boolean isLocationEnabled(int userId) {
        return mLocationMode.getValueForUser(LOCATION_MODE_OFF, userId) != LOCATION_MODE_OFF;
    }

    @Override
    public void setLocationEnabled(boolean enabled, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCATION_MODE,
                    enabled
                            ? Settings.Secure.LOCATION_MODE_ON
                            : Settings.Secure.LOCATION_MODE_OFF,
                    userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void addOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationMode.addListener(listener);
    }

    @Override
    public void removeOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationMode.removeListener(listener);
    }

    @Override
    public long getBackgroundThrottleIntervalMs() {
        return mBackgroundThrottleIntervalMs.getValue(DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS);
    }

    @Override
    public void addOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalMs.addListener(listener);
    }

    @Override
    public void removeOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalMs.removeListener(listener);
    }

    @Override
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

    @Override
    public void addOnLocationPackageBlacklistChangedListener(
            UserSettingChangedListener listener) {
        mLocationPackageBlacklist.addListener(listener);
        mLocationPackageWhitelist.addListener(listener);
    }

    @Override
    public void removeOnLocationPackageBlacklistChangedListener(
            UserSettingChangedListener listener) {
        mLocationPackageBlacklist.removeListener(listener);
        mLocationPackageWhitelist.removeListener(listener);
    }

    @Override
    public Set<String> getBackgroundThrottlePackageWhitelist() {
        return mBackgroundThrottlePackageWhitelist.getValue();
    }

    @Override
    public void addOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelist.addListener(listener);
    }

    @Override
    public void removeOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelist.removeListener(listener);
    }

    @Override
    public boolean isGnssMeasurementsFullTrackingEnabled() {
        return mGnssMeasurementFullTracking.getValue(false);
    }

    @Override
    public void addOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener) {
        mGnssMeasurementFullTracking.addListener(listener);
    }

    @Override
    public void removeOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener) {
        mGnssMeasurementFullTracking.removeListener(listener);
    }

    @Override
    public PackageTagsList getAdasAllowlist() {
        return mAdasPackageAllowlist.getValue();
    }

    @Override
    public void addAdasAllowlistChangedListener(GlobalSettingChangedListener listener) {
        mAdasPackageAllowlist.addListener(listener);
    }

    @Override
    public void removeAdasAllowlistChangedListener(GlobalSettingChangedListener listener) {
        mAdasPackageAllowlist.removeListener(listener);
    }

    @Override
    public PackageTagsList getIgnoreSettingsAllowlist() {
        return mIgnoreSettingsPackageAllowlist.getValue();
    }

    @Override
    public void addIgnoreSettingsAllowlistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsPackageAllowlist.addListener(listener);
    }

    @Override
    public void removeIgnoreSettingsAllowlistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsPackageAllowlist.removeListener(listener);
    }

    @Override
    public long getBackgroundThrottleProximityAlertIntervalMs() {
        final long identity = Binder.clearCallingIdentity();
        try {
            return Settings.Global.getLong(mContext.getContentResolver(),
                    LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS,
                    DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public float getCoarseLocationAccuracyM() {
        final long identity = Binder.clearCallingIdentity();
        final ContentResolver cr = mContext.getContentResolver();
        try {
            return Settings.Secure.getFloatForUser(
                    cr,
                    LOCATION_COARSE_ACCURACY_M,
                    DEFAULT_COARSE_LOCATION_ACCURACY_M,
                    cr.getUserId());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        int[] userIds;
        try {
            userIds = ActivityManager.getService().getRunningUserIds();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        ipw.print("Location Setting: ");
        ipw.increaseIndent();
        if (userIds.length > 1) {
            ipw.println();
            for (int userId : userIds) {
                ipw.print("[u");
                ipw.print(userId);
                ipw.print("] ");
                ipw.println(isLocationEnabled(userId));
            }
        } else {
            ipw.println(isLocationEnabled(userIds[0]));
        }
        ipw.decreaseIndent();

        ipw.println("Location Allow/Deny Packages:");
        ipw.increaseIndent();
        if (userIds.length > 1) {
            for (int userId : userIds) {
                List<String> locationPackageBlacklist = mLocationPackageBlacklist.getValueForUser(
                        userId);
                if (locationPackageBlacklist.isEmpty()) {
                    continue;
                }

                ipw.print("user ");
                ipw.print(userId);
                ipw.println(":");
                ipw.increaseIndent();

                for (String packageName : locationPackageBlacklist) {
                    ipw.print("[deny] ");
                    ipw.println(packageName);
                }

                List<String> locationPackageWhitelist = mLocationPackageWhitelist.getValueForUser(
                        userId);
                for (String packageName : locationPackageWhitelist) {
                    ipw.print("[allow] ");
                    ipw.println(packageName);
                }

                ipw.decreaseIndent();
            }
        } else {
            List<String> locationPackageBlacklist = mLocationPackageBlacklist.getValueForUser(
                    userIds[0]);
            for (String packageName : locationPackageBlacklist) {
                ipw.print("[deny] ");
                ipw.println(packageName);
            }

            List<String> locationPackageWhitelist = mLocationPackageWhitelist.getValueForUser(
                    userIds[0]);
            for (String packageName : locationPackageWhitelist) {
                ipw.print("[allow] ");
                ipw.println(packageName);
            }
        }
        ipw.decreaseIndent();

        Set<String> backgroundThrottlePackageWhitelist =
                mBackgroundThrottlePackageWhitelist.getValue();
        if (!backgroundThrottlePackageWhitelist.isEmpty()) {
            ipw.println("Throttling Allow Packages:");
            ipw.increaseIndent();
            for (String packageName : backgroundThrottlePackageWhitelist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();
        }

        PackageTagsList ignoreSettingsAllowlist = mIgnoreSettingsPackageAllowlist.getValue();
        if (!ignoreSettingsAllowlist.isEmpty()) {
            ipw.println("Emergency Bypass Allow Packages:");
            ipw.increaseIndent();
            ignoreSettingsAllowlist.dump(ipw);
            ipw.decreaseIndent();
        }

        PackageTagsList adasPackageAllowlist = mAdasPackageAllowlist.getValue();
        if (!adasPackageAllowlist.isEmpty()) {
            ipw.println("ADAS Bypass Allow Packages:");
            ipw.increaseIndent();
            adasPackageAllowlist.dump(ipw);
            ipw.decreaseIndent();
        }
    }

    private abstract static class ObservingSetting extends ContentObserver {

        private final CopyOnWriteArrayList<UserSettingChangedListener> mListeners;

        @GuardedBy("this")
        private boolean mRegistered;

        ObservingSetting(Handler handler) {
            super(handler);
            mListeners = new CopyOnWriteArrayList<>();
        }

        protected synchronized boolean isRegistered() {
            return mRegistered;
        }

        protected synchronized void register(Context context, Uri uri) {
            if (mRegistered) {
                return;
            }

            context.getContentResolver().registerContentObserver(
                    uri, false, this, UserHandle.USER_ALL);
            mRegistered = true;
        }

        public void addListener(UserSettingChangedListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(UserSettingChangedListener listener) {
            mListeners.remove(listener);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (D) {
                Log.d(TAG, "location setting changed [u" + userId + "]: " + uri);
            }

            for (UserSettingChangedListener listener : mListeners) {
                listener.onSettingChanged(userId);
            }
        }
    }

    private static class IntegerSecureSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        IntegerSecureSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
        }

        void register() {
            register(mContext, Settings.Secure.getUriFor(mSettingName));
        }

        public int getValueForUser(int defaultValue, int userId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return Settings.Secure.getIntForUser(mContext.getContentResolver(), mSettingName,
                        defaultValue, userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class StringListCachedSecureSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        @GuardedBy("this")
        private int mCachedUserId;
        @GuardedBy("this")
        private List<String> mCachedValue;

        StringListCachedSecureSetting(Context context, String settingName,
                Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;

            mCachedUserId = UserHandle.USER_NULL;
        }

        public void register() {
            register(mContext, Settings.Secure.getUriFor(mSettingName));
        }

        public synchronized List<String> getValueForUser(int userId) {
            Preconditions.checkArgument(userId != UserHandle.USER_NULL);

            List<String> value = mCachedValue;
            if (userId != mCachedUserId) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                            mSettingName, userId);
                    if (TextUtils.isEmpty(setting)) {
                        value = Collections.emptyList();
                    } else {
                        value = Arrays.asList(setting.split(","));
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                if (isRegistered()) {
                    mCachedUserId = userId;
                    mCachedValue = value;
                }
            }

            return value;
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
    }

    private static class BooleanGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        BooleanGlobalSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
        }

        public void register() {
            register(mContext, Settings.Global.getUriFor(mSettingName));
        }

        public boolean getValue(boolean defaultValue) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return Settings.Global.getInt(mContext.getContentResolver(), mSettingName,
                        defaultValue ? 1 : 0) != 0;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class LongGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        LongGlobalSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
        }

        public void register() {
            register(mContext, Settings.Global.getUriFor(mSettingName));
        }

        public long getValue(long defaultValue) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return Settings.Global.getLong(mContext.getContentResolver(), mSettingName,
                        defaultValue);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class StringSetCachedGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;
        private final Supplier<ArraySet<String>> mBaseValuesSupplier;

        @GuardedBy("this")
        private boolean mValid;
        @GuardedBy("this")
        private ArraySet<String> mCachedValue;

        StringSetCachedGlobalSetting(Context context, String settingName,
                Supplier<ArraySet<String>> baseValuesSupplier, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
            mBaseValuesSupplier = baseValuesSupplier;

            mValid = false;
        }

        public void register() {
            register(mContext, Settings.Global.getUriFor(mSettingName));
        }

        public synchronized Set<String> getValue() {
            ArraySet<String> value = mCachedValue;
            if (!mValid) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    value = new ArraySet<>(mBaseValuesSupplier.get());
                    String setting = Settings.Global.getString(mContext.getContentResolver(),
                            mSettingName);
                    if (!TextUtils.isEmpty(setting)) {
                        value.addAll(Arrays.asList(setting.split(",")));
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                if (isRegistered()) {
                    mValid = true;
                    mCachedValue = value;
                }
            }

            return value;
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
    }

    private static class DeviceConfigSetting implements DeviceConfig.OnPropertiesChangedListener {

        protected final String mName;
        private final CopyOnWriteArrayList<GlobalSettingChangedListener> mListeners;

        @GuardedBy("this")
        private boolean mRegistered;

        DeviceConfigSetting(String name) {
            mName = name;
            mListeners = new CopyOnWriteArrayList<>();
        }

        protected synchronized boolean isRegistered() {
            return mRegistered;
        }

        protected synchronized void register() {
            if (mRegistered) {
                return;
            }

            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_LOCATION,
                    FgThread.getExecutor(), this);
            mRegistered = true;
        }

        public void addListener(GlobalSettingChangedListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(GlobalSettingChangedListener listener) {
            mListeners.remove(listener);
        }

        @Override
        public final void onPropertiesChanged(DeviceConfig.Properties properties) {
            if (!properties.getKeyset().contains(mName)) {
                return;
            }

            onPropertiesChanged();
        }

        public void onPropertiesChanged() {
            if (D) {
                Log.d(TAG, "location device config setting changed: " + mName);
            }

            for (UserSettingChangedListener listener : mListeners) {
                listener.onSettingChanged(UserHandle.USER_ALL);
            }
        }
    }

    private static class PackageTagsListSetting extends DeviceConfigSetting {

        private final Supplier<ArrayMap<String, ArraySet<String>>> mBaseValuesSupplier;

        @GuardedBy("this")
        private boolean mValid;
        @GuardedBy("this")
        private PackageTagsList mCachedValue;

        PackageTagsListSetting(String name,
                Supplier<ArrayMap<String, ArraySet<String>>> baseValuesSupplier) {
            super(name);
            mBaseValuesSupplier = baseValuesSupplier;
        }

        public synchronized PackageTagsList getValue() {
            PackageTagsList value = mCachedValue;
            if (!mValid) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    PackageTagsList.Builder builder = new PackageTagsList.Builder().add(
                            mBaseValuesSupplier.get());

                    String setting = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_LOCATION,
                            mName);
                    if (!TextUtils.isEmpty(setting)) {
                        for (String packageAndTags : setting.split(",")) {
                            if (TextUtils.isEmpty(packageAndTags)) {
                                continue;
                            }

                            String[] packageThenTags = packageAndTags.split(";");
                            String packageName = packageThenTags[0];
                            if (packageThenTags.length == 1) {
                                builder.add(packageName);
                            } else {
                                for (int i = 1; i < packageThenTags.length; i++) {
                                    String attributionTag = packageThenTags[i];
                                    if ("null".equals(attributionTag)) {
                                        attributionTag = null;
                                    }

                                    if ("*".equals(attributionTag)) {
                                        builder.add(packageName);
                                    } else {
                                        builder.add(packageName, attributionTag);
                                    }
                                }
                            }
                        }
                    }

                    value = builder.build();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                if (isRegistered()) {
                    mValid = true;
                    mCachedValue = value;
                }
            }

            return value;
        }

        public synchronized void invalidate() {
            mValid = false;
            mCachedValue = null;
        }

        @Override
        public void onPropertiesChanged() {
            invalidate();
            super.onPropertiesChanged();
        }
    }
}
