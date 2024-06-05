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

package com.android.server.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.Objects;

/* package */ class BluetoothProfileMonitor {

    /* package */ static final long GROUP_ID_NO_GROUP = -1L;

    @NonNull
    private final ProfileListener mProfileListener = new ProfileListener();

    @NonNull
    private final Context mContext;
    @NonNull
    private final BluetoothAdapter mBluetoothAdapter;

    @Nullable
    private BluetoothA2dp mA2dpProfile;
    @Nullable
    private BluetoothHearingAid mHearingAidProfile;
    @Nullable
    private BluetoothLeAudio mLeAudioProfile;

    BluetoothProfileMonitor(@NonNull Context context,
            @NonNull BluetoothAdapter bluetoothAdapter) {
        mContext = Objects.requireNonNull(context);
        mBluetoothAdapter = Objects.requireNonNull(bluetoothAdapter);
    }

    /* package */ void start() {
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.A2DP);
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.HEARING_AID);
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.LE_AUDIO);
    }

    /* package */ boolean isProfileSupported(int profile, @NonNull BluetoothDevice device) {
        BluetoothProfile bluetoothProfile;

        synchronized (this) {
            switch (profile) {
                case BluetoothProfile.A2DP:
                    bluetoothProfile = mA2dpProfile;
                    break;
                case BluetoothProfile.LE_AUDIO:
                    bluetoothProfile = mLeAudioProfile;
                    break;
                case BluetoothProfile.HEARING_AID:
                    bluetoothProfile = mHearingAidProfile;
                    break;
                default:
                    throw new IllegalArgumentException(profile
                            + " is not supported as Bluetooth profile");
            }
        }

        if (bluetoothProfile == null) {
            return false;
        }

        return bluetoothProfile.getConnectedDevices().contains(device);
    }

    /* package */ long getGroupId(int profile, @NonNull BluetoothDevice device) {
        synchronized (this) {
            switch (profile) {
                case BluetoothProfile.A2DP:
                    return GROUP_ID_NO_GROUP;
                case BluetoothProfile.LE_AUDIO:
                    return mLeAudioProfile == null ? GROUP_ID_NO_GROUP : mLeAudioProfile.getGroupId(
                            device);
                case BluetoothProfile.HEARING_AID:
                    return mHearingAidProfile == null
                            ? GROUP_ID_NO_GROUP : mHearingAidProfile.getHiSyncId(device);
                default:
                    throw new IllegalArgumentException(profile
                            + " is not supported as Bluetooth profile");
            }
        }
    }

    private final class ProfileListener implements BluetoothProfile.ServiceListener {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            synchronized (BluetoothProfileMonitor.this) {
                switch (profile) {
                    case BluetoothProfile.A2DP:
                        mA2dpProfile = (BluetoothA2dp) proxy;
                        break;
                    case BluetoothProfile.HEARING_AID:
                        mHearingAidProfile = (BluetoothHearingAid) proxy;
                        break;
                    case BluetoothProfile.LE_AUDIO:
                        mLeAudioProfile = (BluetoothLeAudio) proxy;
                        break;
                }
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            synchronized (BluetoothProfileMonitor.this) {
                switch (profile) {
                    case BluetoothProfile.A2DP:
                        mA2dpProfile = null;
                        break;
                    case BluetoothProfile.HEARING_AID:
                        mHearingAidProfile = null;
                        break;
                    case BluetoothProfile.LE_AUDIO:
                        mLeAudioProfile = null;
                        break;
                }
            }
        }
    }
}
