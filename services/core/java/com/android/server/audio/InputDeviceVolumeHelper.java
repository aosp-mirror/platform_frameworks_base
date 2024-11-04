/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.audio;

import static android.media.AudioManager.GET_DEVICES_INPUTS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.SparseIntArray;

import java.util.HashSet;
import java.util.Set;

/** Maintains the current state of input gains. */
/*package*/ class InputDeviceVolumeHelper {
    private static final String TAG = "InputDeviceVolumeHelper";

    // TODO(b/364923030): retrieve these constants from AudioPolicyManager.
    private final int INDEX_MIN = 0;
    private final int INDEX_MAX = 100;
    private final int INDEX_DEFAULT = 50;

    private final SettingsAdapter mSettings;
    private final ContentResolver mContentResolver;
    private final Object mSettingsLock;
    private final String mInputGainIndexSettingsName;

    // A map between device internal type (e.g. AudioSystem.DEVICE_IN_BUILTIN_MIC) to its input gain
    // index.
    private final SparseIntArray mInputGainIndexMap;
    private final Set<Integer> mSupportedDeviceTypes;

    InputDeviceVolumeHelper(
            SettingsAdapter settings,
            ContentResolver contentResolver,
            Object settingsLock,
            String settingsName) {
        mSettings = settings;
        mContentResolver = contentResolver;
        mSettingsLock = settingsLock;
        mInputGainIndexSettingsName = settingsName;

        IntArray internalDeviceTypes = new IntArray();
        int status = AudioSystem.getSupportedDeviceTypes(GET_DEVICES_INPUTS, internalDeviceTypes);
        mInputGainIndexMap =
                new SparseIntArray(
                        status == AudioManager.SUCCESS
                                ? internalDeviceTypes.size()
                                : AudioSystem.DEVICE_IN_ALL_SET.size());

        if (status == AudioManager.SUCCESS) {
            Set<Integer> supportedDeviceTypes = new HashSet<>();
            for (int i = 0; i < internalDeviceTypes.size(); i++) {
                supportedDeviceTypes.add(internalDeviceTypes.get(i));
            }
            mSupportedDeviceTypes = supportedDeviceTypes;
        } else {
            mSupportedDeviceTypes = AudioSystem.DEVICE_IN_ALL_SET;
        }

        readSettings();
    }

    public void readSettings() {
        synchronized (InputDeviceVolumeHelper.class) {
            for (int inputDeviceType : mSupportedDeviceTypes) {
                // Retrieve current input gain for device. If no input gain stored for current
                // device, use default input gain.
                int index;
                if (!hasValidSettingsName()) {
                    index = INDEX_DEFAULT;
                } else {
                    String name = getSettingNameForDevice(inputDeviceType);
                    index =
                            mSettings.getSystemIntForUser(
                                    mContentResolver, name, INDEX_DEFAULT, UserHandle.USER_CURRENT);
                }

                mInputGainIndexMap.put(inputDeviceType, getValidIndex(index));
            }
        }
    }

    public boolean hasValidSettingsName() {
        return mInputGainIndexSettingsName != null && !mInputGainIndexSettingsName.isEmpty();
    }

    public @Nullable String getSettingNameForDevice(int inputDeviceType) {
        if (!hasValidSettingsName()) {
            return null;
        }
        final String suffix = AudioSystem.getInputDeviceName(inputDeviceType);
        if (suffix.isEmpty()) {
            return mInputGainIndexSettingsName;
        }
        return mInputGainIndexSettingsName + "_" + suffix;
    }

    private int getValidIndex(int index) {
        if (index < INDEX_MIN) {
            return INDEX_MIN;
        }
        if (index > INDEX_MAX) {
            return INDEX_MAX;
        }
        return index;
    }

    public int getInputGainIndex(@NonNull AudioDeviceAttributes ada) {
        int inputDeviceType = AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(ada.getType());
        ensureValidInputDeviceType(inputDeviceType);

        synchronized (InputDeviceVolumeHelper.class) {
            return mInputGainIndexMap.get(inputDeviceType, INDEX_DEFAULT);
        }
    }

    public int getMaxInputGainIndex() {
        return INDEX_MAX;
    }

    public int getMinInputGainIndex() {
        return INDEX_MIN;
    }

    public boolean isInputGainFixed(@NonNull AudioDeviceAttributes ada) {
        int inputDeviceType = AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(ada.getType());
        ensureValidInputDeviceType(inputDeviceType);

        // For simplicity, all devices have non fixed input gain. This might change
        // when more input devices are supported and some do not support input gain control.
        return false;
    }

    public boolean setInputGainIndex(@NonNull AudioDeviceAttributes ada, int index) {
        int inputDeviceType = AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(ada.getType());
        ensureValidInputDeviceType(inputDeviceType);

        int oldIndex;
        synchronized (mSettingsLock) {
            synchronized (InputDeviceVolumeHelper.class) {
                oldIndex = getInputGainIndex(ada);
                index = getValidIndex(index);

                if (oldIndex == index) {
                    return false;
                }

                mInputGainIndexMap.put(inputDeviceType, index);
                return true;
            }
        }
    }

    public void persistInputGainIndex(@NonNull AudioDeviceAttributes ada, int index) {
        int inputDeviceType = AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(ada.getType());
        ensureValidInputDeviceType(inputDeviceType);

        if (hasValidSettingsName()) {
            mSettings.putSystemIntForUser(
                    mContentResolver,
                    getSettingNameForDevice(inputDeviceType),
                    index,
                    UserHandle.USER_CURRENT);
        }
    }

    public boolean isValidInputDeviceType(int inputDeviceType) {
        return mSupportedDeviceTypes.contains(inputDeviceType);
    }

    private void ensureValidInputDeviceType(int inputDeviceType) {
        if (!isValidInputDeviceType(inputDeviceType)) {
            throw new IllegalArgumentException("Bad input device type " + inputDeviceType);
        }
    }
}
