/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.testutils.shadow;

import static android.bluetooth.BluetoothAdapter.ACTIVE_DEVICE_ALL;
import static android.bluetooth.BluetoothAdapter.ACTIVE_DEVICE_AUDIO;
import static android.bluetooth.BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.ParcelUuid;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.List;

@Implements(value = BluetoothAdapter.class)
public class ShadowBluetoothAdapter extends org.robolectric.shadows.ShadowBluetoothAdapter {

    private List<Integer> mSupportedProfiles;
    private List<BluetoothDevice> mMostRecentlyConnectedDevices;
    private BluetoothProfile.ServiceListener mServiceListener;
    private ParcelUuid[] mParcelUuids;
    private int mIsLeAudioBroadcastSourceSupported;
    private int mIsLeAudioBroadcastAssistantSupported;

    @Implementation
    protected boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener,
            int profile) {
        mServiceListener = listener;
        return true;
    }

    public BluetoothProfile.ServiceListener getServiceListener() {
        return mServiceListener;
    }

    @Implementation
    protected List<Integer> getSupportedProfiles() {
        return mSupportedProfiles;
    }

    public void setSupportedProfiles(List<Integer> supportedProfiles) {
        mSupportedProfiles = supportedProfiles;
    }

    @Implementation
    protected List<BluetoothDevice> getMostRecentlyConnectedDevices() {
        return mMostRecentlyConnectedDevices;
    }

    public void setMostRecentlyConnectedDevices(List<BluetoothDevice> list) {
        mMostRecentlyConnectedDevices = list;
    }

    @Implementation
    protected boolean removeActiveDevice(int profiles) {
        if (profiles != ACTIVE_DEVICE_AUDIO && profiles != ACTIVE_DEVICE_PHONE_CALL
                && profiles != ACTIVE_DEVICE_ALL) {
            return false;
        }
        return true;
    }

    @Implementation
    protected boolean setActiveDevice(BluetoothDevice device, int profiles) {
        if (device == null) {
            return false;
        }
        if (profiles != ACTIVE_DEVICE_AUDIO && profiles != ACTIVE_DEVICE_PHONE_CALL
                && profiles != ACTIVE_DEVICE_ALL) {
            return false;
        }
        return true;
    }

    @Implementation
    protected ParcelUuid[] getUuids() {
        return mParcelUuids;
    }

    public void setUuids(ParcelUuid[] uuids) {
        mParcelUuids = uuids;
    }

    @Implementation
    protected int isLeAudioBroadcastSourceSupported() {
        return mIsLeAudioBroadcastSourceSupported;
    }

    public void setIsLeAudioBroadcastSourceSupported(int isSupported) {
        mIsLeAudioBroadcastSourceSupported = isSupported;
    }

    @Implementation
    protected int isLeAudioBroadcastAssistantSupported() {
        return mIsLeAudioBroadcastAssistantSupported;
    }

    public void setIsLeAudioBroadcastAssistantSupported(int isSupported) {
        mIsLeAudioBroadcastAssistantSupported = isSupported;
    }
}
