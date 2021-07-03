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

import android.os.PackageTagsList;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Version of SettingsHelper for testing. Settings are initialized to reasonable defaults (location
 * is enabled by default).
 */
public class FakeSettingsHelper extends SettingsHelper {

    private static class Setting {

        private final SparseArray<Object> mValues;
        private final Object mDefaultValue;

        private final CopyOnWriteArrayList<UserSettingChangedListener> mListeners;

        Setting(Object defaultValue) {
            mValues = new SparseArray<>();
            mDefaultValue = defaultValue;
            mListeners = new CopyOnWriteArrayList<>();
        }

        public void addListener(UserSettingChangedListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(UserSettingChangedListener listener) {
            mListeners.remove(listener);
        }

        public void setValue(Object newValue) {
            setValue(newValue, UserHandle.USER_NULL);
        }

        public void setValue(Object newValue, int userId) {
            Preconditions.checkState(userId == UserHandle.USER_NULL || userId >= 0);

            mValues.put(userId, newValue);

            for (UserSettingChangedListener listener : mListeners) {
                listener.onSettingChanged(userId);
            }
        }

        public <T> T getValue(Class<T> clazz) {
            return getValue(UserHandle.USER_NULL, clazz);
        }

        public <T> T getValue(int userId, Class<T> clazz) {
            return clazz.cast(mValues.get(userId, mDefaultValue));
        }
    }

    private final Setting mLocationEnabledSetting = new Setting(Boolean.TRUE);
    private final Setting mBackgroundThrottleIntervalSetting = new Setting(30 * 60 * 1000L);
    private final Setting mLocationPackageBlacklistSetting = new Setting(Collections.emptySet());
    private final Setting mBackgroundThrottlePackageWhitelistSetting = new Setting(
            Collections.emptySet());
    private final Setting mGnssMeasurementsFullTrackingSetting = new Setting(Boolean.FALSE);
    private final Setting mIgnoreSettingsAllowlist = new Setting(
            new PackageTagsList.Builder().build());
    private final Setting mBackgroundThrottleProximityAlertIntervalSetting = new Setting(
            30 * 60 * 1000L);
    private final Setting mCoarseLocationAccuracySetting = new Setting(2000.0f);

    @Override
    public boolean isLocationEnabled(int userId) {
        return mLocationEnabledSetting.getValue(userId, Boolean.class);
    }

    @Override
    public void setLocationEnabled(boolean enabled, int userId) {
        mLocationEnabledSetting.setValue(enabled, userId);
    }

    @Override
    public void addOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationEnabledSetting.addListener(listener);
    }

    @Override
    public void removeOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationEnabledSetting.removeListener(listener);
    }

    @Override
    public long getBackgroundThrottleIntervalMs() {
        return mBackgroundThrottleIntervalSetting.getValue(Long.class);
    }

    public void setBackgroundThrottleIntervalMs(long backgroundThrottleIntervalMs) {
        mBackgroundThrottleIntervalSetting.setValue(backgroundThrottleIntervalMs);
    }

    @Override
    public void addOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalSetting.addListener(listener);
    }

    @Override
    public void removeOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalSetting.removeListener(listener);
    }

    @Override
    public boolean isLocationPackageBlacklisted(int userId, String packageName) {
        return mLocationPackageBlacklistSetting.getValue(userId, Set.class).contains(packageName);
    }

    public void setLocationPackageBlacklisted(int userId, Set<String> newSet) {
        mLocationPackageBlacklistSetting.setValue(newSet, userId);
    }

    @Override
    public void addOnLocationPackageBlacklistChangedListener(UserSettingChangedListener listener) {
        mLocationPackageBlacklistSetting.addListener(listener);
    }

    @Override
    public void removeOnLocationPackageBlacklistChangedListener(
            UserSettingChangedListener listener) {
        mLocationPackageBlacklistSetting.removeListener(listener);
    }

    @Override
    public Set<String> getBackgroundThrottlePackageWhitelist() {
        return mBackgroundThrottlePackageWhitelistSetting.getValue(Set.class);
    }

    public void setBackgroundThrottlePackageWhitelist(Set<String> newSet) {
        mBackgroundThrottlePackageWhitelistSetting.setValue(newSet);
    }

    @Override
    public void addOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelistSetting.addListener(listener);
    }

    @Override
    public void removeOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelistSetting.removeListener(listener);
    }

    @Override
    public boolean isGnssMeasurementsFullTrackingEnabled() {
        return mGnssMeasurementsFullTrackingSetting.getValue(Boolean.class);
    }

    public void setGnssMeasurementsFullTrackingEnabled(boolean newValue) {
        mGnssMeasurementsFullTrackingSetting.setValue(newValue);
    }

    @Override
    public void addOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener) {
        mGnssMeasurementsFullTrackingSetting.addListener(listener);
    }

    @Override
    public void removeOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener) {
        mGnssMeasurementsFullTrackingSetting.removeListener(listener);
    }

    @Override
    public PackageTagsList getIgnoreSettingsAllowlist() {
        return mIgnoreSettingsAllowlist.getValue(PackageTagsList.class);
    }

    public void setIgnoreSettingsAllowlist(PackageTagsList newValue) {
        mIgnoreSettingsAllowlist.setValue(newValue);
    }

    @Override
    public void addIgnoreSettingsAllowlistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsAllowlist.addListener(listener);
    }

    @Override
    public void removeIgnoreSettingsAllowlistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsAllowlist.removeListener(listener);
    }

    @Override
    public long getBackgroundThrottleProximityAlertIntervalMs() {
        return mBackgroundThrottleProximityAlertIntervalSetting.getValue(Long.class);
    }

    public void getBackgroundThrottleProximityAlertIntervalMs(long newValue) {
        mBackgroundThrottleProximityAlertIntervalSetting.setValue(newValue);
    }

    @Override
    public float getCoarseLocationAccuracyM() {
        return mCoarseLocationAccuracySetting.getValue(Float.class);
    }

    public void setCoarseLocationAccuracyM(float newValue) {
        mCoarseLocationAccuracySetting.setValue(newValue);
    }

    @Override
    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {}
}
