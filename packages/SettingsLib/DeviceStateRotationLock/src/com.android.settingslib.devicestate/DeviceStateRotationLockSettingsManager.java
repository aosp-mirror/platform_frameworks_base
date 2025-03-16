/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.devicestate;

import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_IGNORED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_LOCKED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages device-state based rotation lock settings. Handles reading, writing, and listening for
 * changes.
 */
public final class DeviceStateRotationLockSettingsManager {

    private static final String TAG = "DSRotLockSettingsMngr";
    private static final String SEPARATOR_REGEX = ":";

    private static DeviceStateRotationLockSettingsManager sSingleton;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Set<DeviceStateRotationLockSettingsListener> mListeners = new HashSet<>();
    private final SecureSettings mSecureSettings;
    private final PosturesHelper mPosturesHelper;
    private String[] mPostureRotationLockDefaults;
    private SparseIntArray mPostureRotationLockSettings;
    private SparseIntArray mPostureDefaultRotationLockSettings;
    private SparseIntArray mPostureRotationLockFallbackSettings;
    private List<SettableDeviceState> mSettableDeviceStates;

    @VisibleForTesting
    DeviceStateRotationLockSettingsManager(Context context, SecureSettings secureSettings) {
        mSecureSettings = secureSettings;

        mPosturesHelper = new PosturesHelper(context, getDeviceStateManager(context));
        mPostureRotationLockDefaults =
                context.getResources()
                        .getStringArray(R.array.config_perDeviceStateRotationLockDefaults);
        loadDefaults();
        initializeInMemoryMap();
        listenForSettingsChange();
    }

    @Nullable
    private DeviceStateManager getDeviceStateManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(DeviceStateManager.class);
        }
        return null;
    }

    /** Returns a singleton instance of this class */
    public static synchronized DeviceStateRotationLockSettingsManager getInstance(Context context) {
        if (sSingleton == null) {
            Context applicationContext = context.getApplicationContext();
            ContentResolver contentResolver = applicationContext.getContentResolver();
            SecureSettings secureSettings = new AndroidSecureSettings(contentResolver);
            sSingleton =
                    new DeviceStateRotationLockSettingsManager(applicationContext, secureSettings);
        }
        return sSingleton;
    }

    /** Resets the singleton instance of this class. Only used for testing. */
    @VisibleForTesting
    public static synchronized void resetInstance() {
        sSingleton = null;
    }

    /** Returns true if device-state based rotation lock settings are enabled. */
    public static boolean isDeviceStateRotationLockEnabled(Context context) {
        return context.getResources()
                .getStringArray(R.array.config_perDeviceStateRotationLockDefaults).length > 0;
    }

    private void listenForSettingsChange() {
        mSecureSettings
                .registerContentObserver(
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        /* notifyForDescendants= */ false,
                        new ContentObserver(mMainHandler) {
                            @Override
                            public void onChange(boolean selfChange) {
                                onPersistedSettingsChanged();
                            }
                        },
                        UserHandle.USER_CURRENT);
    }

    /**
     * Registers a {@link DeviceStateRotationLockSettingsListener} to be notified when the settings
     * change. Can be called multiple times with different listeners.
     */
    public void registerListener(DeviceStateRotationLockSettingsListener runnable) {
        mListeners.add(runnable);
    }

    /**
     * Unregisters a {@link DeviceStateRotationLockSettingsListener}. No-op if the given instance
     * was never registered.
     */
    public void unregisterListener(
            DeviceStateRotationLockSettingsListener deviceStateRotationLockSettingsListener) {
        if (!mListeners.remove(deviceStateRotationLockSettingsListener)) {
            Log.w(TAG, "Attempting to unregister a listener hadn't been registered");
        }
    }

    /** Updates the rotation lock setting for a specified device state. */
    public void updateSetting(int deviceState, boolean rotationLocked) {
        int posture = mPosturesHelper.deviceStateToPosture(deviceState);
        if (mPostureRotationLockFallbackSettings.indexOfKey(posture) >= 0) {
            // The setting for this device posture is IGNORED, and has a fallback posture.
            // The setting for that fallback posture should be the changed in this case.
            posture = mPostureRotationLockFallbackSettings.get(posture);
        }
        mPostureRotationLockSettings.put(
                posture,
                rotationLocked
                        ? DEVICE_STATE_ROTATION_LOCK_LOCKED
                        : DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        persistSettings();
    }

    /**
     * Returns the {@link Settings.Secure.DeviceStateRotationLockSetting} for the given device
     * state.
     *
     * <p>If the setting for this device state is {@link DEVICE_STATE_ROTATION_LOCK_IGNORED}, it
     * will return the setting for the fallback device state.
     *
     * <p>If no fallback is specified for this device state, it will return {@link
     * DEVICE_STATE_ROTATION_LOCK_IGNORED}.
     */
    @Settings.Secure.DeviceStateRotationLockSetting
    public int getRotationLockSetting(int deviceState) {
        int devicePosture = mPosturesHelper.deviceStateToPosture(deviceState);
        int rotationLockSetting = mPostureRotationLockSettings.get(
                devicePosture, /* valueIfKeyNotFound= */ DEVICE_STATE_ROTATION_LOCK_IGNORED);
        if (rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_IGNORED) {
            rotationLockSetting = getFallbackRotationLockSetting(devicePosture);
        }
        return rotationLockSetting;
    }

    private int getFallbackRotationLockSetting(int devicePosture) {
        int indexOfFallback = mPostureRotationLockFallbackSettings.indexOfKey(devicePosture);
        if (indexOfFallback < 0) {
            Log.w(TAG, "Setting is ignored, but no fallback was specified.");
            return DEVICE_STATE_ROTATION_LOCK_IGNORED;
        }
        int fallbackPosture = mPostureRotationLockFallbackSettings.valueAt(indexOfFallback);
        return mPostureRotationLockSettings.get(fallbackPosture,
                /* valueIfKeyNotFound= */ DEVICE_STATE_ROTATION_LOCK_IGNORED);
    }


    /** Returns true if the rotation is locked for the current device state */
    public boolean isRotationLocked(int deviceState) {
        return getRotationLockSetting(deviceState) == DEVICE_STATE_ROTATION_LOCK_LOCKED;
    }

    /**
     * Returns true if there is no device state for which the current setting is {@link
     * DEVICE_STATE_ROTATION_LOCK_UNLOCKED}.
     */
    public boolean isRotationLockedForAllStates() {
        for (int i = 0; i < mPostureRotationLockSettings.size(); i++) {
            if (mPostureRotationLockSettings.valueAt(i)
                    == DEVICE_STATE_ROTATION_LOCK_UNLOCKED) {
                return false;
            }
        }
        return true;
    }

    /** Returns a list of device states and their respective auto-rotation setting availability. */
    public List<SettableDeviceState> getSettableDeviceStates() {
        // Returning a copy to make sure that nothing outside can mutate our internal list.
        return new ArrayList<>(mSettableDeviceStates);
    }

    private void initializeInMemoryMap() {
        String serializedSetting = getPersistedSettingValue();
        if (TextUtils.isEmpty(serializedSetting)) {
            // No settings saved, we should load the defaults and persist them.
            fallbackOnDefaults();
            return;
        }
        String[] values = serializedSetting.split(SEPARATOR_REGEX);
        if (values.length % 2 != 0) {
            // Each entry should be a key/value pair, so this is corrupt.
            Log.wtf(TAG, "Can't deserialize saved settings, falling back on defaults");
            fallbackOnDefaults();
            return;
        }
        mPostureRotationLockSettings = new SparseIntArray(values.length / 2);
        int key;
        int value;

        for (int i = 0; i < values.length - 1; ) {
            try {
                key = Integer.parseInt(values[i++]);
                value = Integer.parseInt(values[i++]);
                boolean isPersistedValueIgnored = value == DEVICE_STATE_ROTATION_LOCK_IGNORED;
                boolean isDefaultValueIgnored = mPostureDefaultRotationLockSettings.get(key)
                        == DEVICE_STATE_ROTATION_LOCK_IGNORED;
                if (isPersistedValueIgnored != isDefaultValueIgnored) {
                    Log.w(TAG, "Conflict for ignored device state " + key
                            + ". Falling back on defaults");
                    fallbackOnDefaults();
                    return;
                }
                mPostureRotationLockSettings.put(key, value);
            } catch (NumberFormatException e) {
                Log.wtf(TAG, "Error deserializing one of the saved settings", e);
                fallbackOnDefaults();
                return;
            }
        }
    }

    /**
     * Resets the state of the class and saved settings back to the default values provided by the
     * resources config.
     */
    @VisibleForTesting
    public void resetStateForTesting(Resources resources) {
        mPostureRotationLockDefaults =
                resources.getStringArray(R.array.config_perDeviceStateRotationLockDefaults);
        fallbackOnDefaults();
    }

    private void fallbackOnDefaults() {
        loadDefaults();
        persistSettings();
    }

    private void persistSettings() {
        if (mPostureRotationLockSettings.size() == 0) {
            persistSettingIfChanged(/* newSettingValue= */ "");
            return;
        }

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder
                .append(mPostureRotationLockSettings.keyAt(0))
                .append(SEPARATOR_REGEX)
                .append(mPostureRotationLockSettings.valueAt(0));

        for (int i = 1; i < mPostureRotationLockSettings.size(); i++) {
            stringBuilder
                    .append(SEPARATOR_REGEX)
                    .append(mPostureRotationLockSettings.keyAt(i))
                    .append(SEPARATOR_REGEX)
                    .append(mPostureRotationLockSettings.valueAt(i));
        }
        persistSettingIfChanged(stringBuilder.toString());
    }

    private void persistSettingIfChanged(String newSettingValue) {
        String lastSettingValue = getPersistedSettingValue();
        Log.v(TAG, "persistSettingIfChanged: "
                + "last=" + lastSettingValue + ", "
                + "new=" + newSettingValue);
        if (TextUtils.equals(lastSettingValue, newSettingValue)) {
            return;
        }
        mSecureSettings.putStringForUser(
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                /* value= */ newSettingValue,
                UserHandle.USER_CURRENT);
    }

    private String getPersistedSettingValue() {
        return mSecureSettings.getStringForUser(
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                UserHandle.USER_CURRENT);
    }

    private void loadDefaults() {
        mSettableDeviceStates = new ArrayList<>(mPostureRotationLockDefaults.length);
        mPostureDefaultRotationLockSettings = new SparseIntArray(
                mPostureRotationLockDefaults.length);
        mPostureRotationLockSettings = new SparseIntArray(mPostureRotationLockDefaults.length);
        mPostureRotationLockFallbackSettings = new SparseIntArray(1);
        for (String entry : mPostureRotationLockDefaults) {
            String[] values = entry.split(SEPARATOR_REGEX);
            try {
                int posture = Integer.parseInt(values[0]);
                int rotationLockSetting = Integer.parseInt(values[1]);
                if (rotationLockSetting == DEVICE_STATE_ROTATION_LOCK_IGNORED) {
                    if (values.length == 3) {
                        int fallbackPosture = Integer.parseInt(values[2]);
                        mPostureRotationLockFallbackSettings.put(posture, fallbackPosture);
                    } else {
                        Log.w(TAG,
                                "Rotation lock setting is IGNORED, but values have unexpected "
                                        + "size of "
                                        + values.length);
                    }
                }
                boolean isSettable = rotationLockSetting != DEVICE_STATE_ROTATION_LOCK_IGNORED;
                Integer deviceState = mPosturesHelper.postureToDeviceState(posture);
                if (deviceState != null) {
                    mSettableDeviceStates.add(new SettableDeviceState(deviceState, isSettable));
                } else {
                    Log.wtf(TAG, "No matching device state for posture: " + posture);
                }
                mPostureRotationLockSettings.put(posture, rotationLockSetting);
                mPostureDefaultRotationLockSettings.put(posture, rotationLockSetting);
            } catch (NumberFormatException e) {
                Log.wtf(TAG, "Error parsing settings entry. Entry was: " + entry, e);
                return;
            }
        }
    }

    /** Dumps internal state. */
    public void dump(IndentingPrintWriter pw) {
        pw.println("DeviceStateRotationLockSettingsManager");
        pw.increaseIndent();
        pw.println("mPostureRotationLockDefaults: "
                + Arrays.toString(mPostureRotationLockDefaults));
        pw.println("mPostureDefaultRotationLockSettings: " + mPostureDefaultRotationLockSettings);
        pw.println("mDeviceStateRotationLockSettings: " + mPostureRotationLockSettings);
        pw.println("mPostureRotationLockFallbackSettings: " + mPostureRotationLockFallbackSettings);
        pw.println("mSettableDeviceStates: " + mSettableDeviceStates);
        pw.decreaseIndent();
    }

    /**
     * Called when the persisted settings have changed, requiring a reinitialization of the
     * in-memory map.
     */
    @VisibleForTesting
    public void onPersistedSettingsChanged() {
        initializeInMemoryMap();
        notifyListeners();
    }

    private void notifyListeners() {
        for (DeviceStateRotationLockSettingsListener r : mListeners) {
            r.onSettingsChanged();
        }
    }

    /** Listener for changes in device-state based rotation lock settings */
    public interface DeviceStateRotationLockSettingsListener {
        /** Called whenever the settings have changed. */
        void onSettingsChanged();
    }

    /** Represents a device state and whether it has an auto-rotation setting. */
    public static class SettableDeviceState {
        private final int mDeviceState;
        private final boolean mIsSettable;

        SettableDeviceState(int deviceState, boolean isSettable) {
            mDeviceState = deviceState;
            mIsSettable = isSettable;
        }

        /** Returns the device state associated with this object. */
        public int getDeviceState() {
            return mDeviceState;
        }

        /** Returns whether there is an auto-rotation setting for this device state. */
        public boolean isSettable() {
            return mIsSettable;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SettableDeviceState)) return false;
            SettableDeviceState that = (SettableDeviceState) o;
            return mDeviceState == that.mDeviceState && mIsSettable == that.mIsSettable;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDeviceState, mIsSettable);
        }

        @Override
        public String toString() {
            return "SettableDeviceState{"
                    + "mDeviceState=" + mDeviceState
                    + ", mIsSettable=" + mIsSettable
                    + '}';
        }
    }
}
