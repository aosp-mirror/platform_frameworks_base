/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.media.AudioSystem.DEVICE_NONE;
import static android.media.AudioSystem.isBluetoothDevice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.util.Objects;

/**
 * Class representing all devices that were previously or are currently connected. Data is
 * persisted in {@link android.provider.Settings.Secure}
 */
/*package*/ final class AdiDeviceState {
    private static final String TAG = "AS.AdiDeviceState";

    private static final String SETTING_FIELD_SEPARATOR = ",";

    @AudioDeviceInfo.AudioDeviceType
    private final int mDeviceType;

    private final int mInternalDeviceType;
    @NonNull
    private final String mDeviceAddress;
    /** Unique device id from internal device type and address. */
    private final Pair<Integer, String> mDeviceId;
    private boolean mSAEnabled;
    private boolean mHasHeadTracker = false;
    private boolean mHeadTrackerEnabled;

    /**
     * Constructor
     *
     * @param deviceType external audio device type
     * @param internalDeviceType if not set pass {@link DEVICE_NONE}, in this case the
     *                           default conversion of the external type will be used
     * @param address must be non-null for wireless devices
     * @throws NullPointerException if a null address is passed for a wireless device
     */
    AdiDeviceState(@AudioDeviceInfo.AudioDeviceType int deviceType,
                        int internalDeviceType,
                        @Nullable String address) {
        mDeviceType = deviceType;
        if (internalDeviceType != DEVICE_NONE) {
            mInternalDeviceType = internalDeviceType;
        } else {
            mInternalDeviceType = AudioDeviceInfo.convertDeviceTypeToInternalDevice(deviceType);

        }
        mDeviceAddress = isBluetoothDevice(mInternalDeviceType) ? Objects.requireNonNull(
                address) : "";
        mDeviceId = new Pair<>(mInternalDeviceType, mDeviceAddress);
    }

    public Pair<Integer, String> getDeviceId() {
        return mDeviceId;
    }

    @AudioDeviceInfo.AudioDeviceType
    public int getDeviceType() {
        return mDeviceType;
    }

    public int getInternalDeviceType() {
        return mInternalDeviceType;
    }

    @NonNull
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    public void setSAEnabled(boolean sAEnabled) {
        mSAEnabled = sAEnabled;
    }

    public boolean isSAEnabled() {
        return mSAEnabled;
    }

    public void setHeadTrackerEnabled(boolean headTrackerEnabled) {
        mHeadTrackerEnabled = headTrackerEnabled;
    }

    public boolean isHeadTrackerEnabled() {
        return mHeadTrackerEnabled;
    }

    public void setHasHeadTracker(boolean hasHeadTracker) {
        mHasHeadTracker = hasHeadTracker;
    }


    public boolean hasHeadTracker() {
        return mHasHeadTracker;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        // type check and cast
        if (getClass() != obj.getClass()) {
            return false;
        }
        final AdiDeviceState sads = (AdiDeviceState) obj;
        return mDeviceType == sads.mDeviceType
                && mInternalDeviceType == sads.mInternalDeviceType
                && mDeviceAddress.equals(sads.mDeviceAddress)  // NonNull
                && mSAEnabled == sads.mSAEnabled
                && mHasHeadTracker == sads.mHasHeadTracker
                && mHeadTrackerEnabled == sads.mHeadTrackerEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceType, mInternalDeviceType, mDeviceAddress, mSAEnabled,
                mHasHeadTracker, mHeadTrackerEnabled);
    }

    @Override
    public String toString() {
        return "type: " + mDeviceType
                + " internal type: 0x" + Integer.toHexString(mInternalDeviceType)
                + " addr: " + mDeviceAddress + " enabled: " + mSAEnabled
                + " HT: " + mHasHeadTracker + " HTenabled: " + mHeadTrackerEnabled;
    }

    public String toPersistableString() {
        return (new StringBuilder().append(mDeviceType)
                .append(SETTING_FIELD_SEPARATOR).append(mDeviceAddress)
                .append(SETTING_FIELD_SEPARATOR).append(mSAEnabled ? "1" : "0")
                .append(SETTING_FIELD_SEPARATOR).append(mHasHeadTracker ? "1" : "0")
                .append(SETTING_FIELD_SEPARATOR).append(mHeadTrackerEnabled ? "1" : "0")
                .append(SETTING_FIELD_SEPARATOR).append(mInternalDeviceType)
                .toString());
    }

    /**
     * Gets the max size (including separators) when persisting the elements with
     * {@link AdiDeviceState#toPersistableString()}.
     */
    public static int getPeristedMaxSize() {
        return 36;  /* (mDeviceType)2 + (mDeviceAddresss)17 + (mInternalDeviceType)9 + (mSAEnabled)1
                           + (mHasHeadTracker)1 + (mHasHeadTrackerEnabled)1
                           + (SETTINGS_FIELD_SEPARATOR)5 */
    }

    @Nullable
    public static AdiDeviceState fromPersistedString(@Nullable String persistedString) {
        if (persistedString == null) {
            return null;
        }
        if (persistedString.isEmpty()) {
            return null;
        }
        String[] fields = TextUtils.split(persistedString, SETTING_FIELD_SEPARATOR);
        // we may have 5 fields for the legacy AdiDeviceState and 6 containing the internal
        // device type
        if (fields.length != 5 && fields.length != 6) {
            // expecting all fields, fewer may mean corruption, ignore those settings
            return null;
        }
        try {
            final int deviceType = Integer.parseInt(fields[0]);
            int internalDeviceType = -1;
            if (fields.length == 6) {
                internalDeviceType = Integer.parseInt(fields[5]);
            }
            final AdiDeviceState deviceState = new AdiDeviceState(deviceType,
                    internalDeviceType, fields[1]);
            deviceState.setHasHeadTracker(Integer.parseInt(fields[2]) == 1);
            deviceState.setHasHeadTracker(Integer.parseInt(fields[3]) == 1);
            deviceState.setHeadTrackerEnabled(Integer.parseInt(fields[4]) == 1);
            return deviceState;
        } catch (NumberFormatException e) {
            Log.e(TAG, "unable to parse setting for AdiDeviceState: " + persistedString, e);
            return null;
        }
    }

    public AudioDeviceAttributes getAudioDeviceAttributes() {
        return new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                mDeviceType, mDeviceAddress);
    }

}
