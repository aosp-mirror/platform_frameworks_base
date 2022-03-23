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

package com.android.settingslib.bluetooth;

import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcast;
import android.bluetooth.BluetoothLeBroadcastAssistant;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * LocalBluetoothLeBroadcastAssistant provides an interface between the Settings app
 * and the functionality of the local {@link BluetoothLeBroadcastAssistant}.
 */
public class LocalBluetoothLeBroadcastAssistant implements
        BluetoothLeBroadcastAssistant.Callback {

    private static final String TAG = "LocalBluetoothLeBroadcastAssistant";
    private static final int UNKNOWN_VALUE_PLACEHOLDER = -1;
    private static final boolean DEBUG = BluetoothUtils.D;

    private LocalBluetoothProfileManager mProfileManager;
    private BluetoothLeBroadcastAssistant mBluetoothLeBroadcastAssistant;
    private BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata;
    private BluetoothLeBroadcastMetadata.Builder mBuilder;
    private boolean mIsProfileReady;

    private final ServiceListener mServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.LE_AUDIO_BROADCAST) {
                if (DEBUG) {
                    Log.d(TAG,"Bluetooth service connected");
                }
                mBluetoothLeBroadcastAssistant = (BluetoothLeBroadcastAssistant) proxy;
                mProfileManager.callServiceConnectedListeners();
                mIsProfileReady = true;
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.LE_AUDIO_BROADCAST) {
                if (DEBUG) {
                    Log.d(TAG,"Bluetooth service disconnected");
                }
                mIsProfileReady = false;
            }
        }
    };

    LocalBluetoothLeBroadcastAssistant(Context context,
            LocalBluetoothProfileManager profileManager) {
        mProfileManager = profileManager;
        BluetoothAdapter.getDefaultAdapter().
                getProfileProxy(context, mServiceListener,
                        BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
        mBuilder = new BluetoothLeBroadcastMetadata.Builder();
    }

    public void addSource(@NonNull BluetoothDevice sink, int sourceAddressType,
            int presentationDelayMicros, int sourceAdvertisingSid, int broadcastId,
            int paSyncInterval, boolean isEncrypted, byte[] broadcastCode,
            BluetoothDevice sourceDevice, boolean isGroupOp) {
        if (DEBUG) {
            Log.d(TAG, "addSource()");
        }
        if (mBluetoothLeBroadcastAssistant == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return ;
        }
        buildMetadata(sourceAddressType, presentationDelayMicros, sourceAdvertisingSid, broadcastId,
                paSyncInterval, isEncrypted, broadcastCode, sourceDevice);
        mBluetoothLeBroadcastAssistant.addSource(sink, mBluetoothLeBroadcastMetadata, isGroupOp);
    }

    private void buildMetadata(int sourceAddressType, int presentationDelayMicros,
            int sourceAdvertisingSid, int broadcastId, int paSyncInterval, boolean isEncrypted,
            byte[] broadcastCode, BluetoothDevice sourceDevice) {
        mBluetoothLeBroadcastMetadata =
                mBuilder.setSourceDevice(sourceDevice, sourceAddressType)
                        .setSourceAdvertisingSid(sourceAdvertisingSid)
                        .setBroadcastId(broadcastId)
                        .setPaSyncInterval(paSyncInterval)
                        .setEncrypted(isEncrypted)
                        .setBroadcastCode(broadcastCode)
                        .setPresentationDelayMicros(presentationDelayMicros)
                        .build();
    }

    public void removeSource(@NonNull BluetoothDevice sink, int sourceId) {
        if (DEBUG) {
            Log.d(TAG, "removeSource()");
        }
        if (mBluetoothLeBroadcastAssistant == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return ;
        }
        mBluetoothLeBroadcastAssistant.removeSource(sink, sourceId);
    }

    public void startSearchingForSources(@NonNull List<android.bluetooth.le.ScanFilter> filters) {
        if (DEBUG) {
            Log.d(TAG, "startSearchingForSources()");
        }
        if (mBluetoothLeBroadcastAssistant == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return ;
        }
        mBluetoothLeBroadcastAssistant.startSearchingForSources(filters);
    }

    @Override
    public void onSourceAdded(@NonNull BluetoothDevice sink, int sourceId, int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSourceAdded(), reason = " + reason + " , sourceId = " + sourceId);
        }

    }

    @Override
    public void onSourceAddFailed(@NonNull BluetoothDevice sink,
            @NonNull BluetoothLeBroadcastMetadata source, int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSourceAddFailed(), reason = " + reason);
        }
    }

    @Override
    public void onSourceRemoved(@NonNull BluetoothDevice sink, int sourceId, int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSourceRemoved(), reason = " + reason + " , sourceId = " + sourceId);
        }
    }

    @Override
    public void onSourceRemoveFailed(@NonNull BluetoothDevice sink, int sourceId, int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSourceRemoveFailed(), reason = " + reason + " , sourceId = " + sourceId);
        }
    }

    @Override
    public void onSearchStarted(int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSearchStarted(), reason = " + reason);
        }
    }

    @Override
    public void onSearchStartFailed(int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSearchStartFailed(), reason = " + reason);
        }
    }

    @Override
    public void onSearchStopped(int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSearchStopped(), reason = " + reason);
        }
    }

    @Override
    public void onSearchStopFailed(int reason) {
        if (DEBUG) {
            Log.d(TAG, "onSearchStopFailed(), reason = " + reason);
        }
    }

    @Override
    public void onSourceFound(@NonNull BluetoothLeBroadcastMetadata source) {
    }

    @Override
    public void onSourceModified(@NonNull BluetoothDevice sink, int sourceId, int reason) {
    }

    @Override
    public void onSourceModifyFailed(@NonNull BluetoothDevice sink, int sourceId, int reason) {
    }

    @Override
    public void onReceiveStateChanged(@NonNull BluetoothDevice sink, int sourceId,
            @NonNull BluetoothLeBroadcastReceiveState state) {
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

}
