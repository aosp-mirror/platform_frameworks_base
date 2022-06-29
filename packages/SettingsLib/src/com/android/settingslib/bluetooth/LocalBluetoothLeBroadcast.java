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
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcast;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.Context;
import android.util.Log;

/**
 * LocalBluetoothLeBroadcast provides an interface between the Settings app
 * and the functionality of the local {@link BluetoothLeBroadcast}.
 */
public class LocalBluetoothLeBroadcast implements BluetoothLeBroadcast.Callback {

    private static final String TAG = "LocalBluetoothLeBroadcast";
    private static final int UNKNOWN_VALUE_PLACEHOLDER = -1;
    private static final boolean DEBUG = BluetoothUtils.D;

    private BluetoothLeBroadcast mBluetoothLeBroadcast;
    private LocalBluetoothProfileManager mProfileManager;
    private BluetoothLeAudioContentMetadata mBluetoothLeAudioContentMetadata;
    private BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata;
    private BluetoothLeAudioContentMetadata.Builder mBuilder;
    private int mBroadcastId = UNKNOWN_VALUE_PLACEHOLDER;
    private boolean mIsProfileReady;

    private final ServiceListener mServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.LE_AUDIO_BROADCAST) {
                if (DEBUG) {
                    Log.d(TAG,"Bluetooth service connected");
                }
                mBluetoothLeBroadcast = (BluetoothLeBroadcast) proxy;
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

    LocalBluetoothLeBroadcast(Context context, LocalBluetoothProfileManager profileManager) {
        mProfileManager = profileManager;
        BluetoothAdapter.getDefaultAdapter().
                getProfileProxy(context, mServiceListener, BluetoothProfile.LE_AUDIO_BROADCAST);
        mBuilder = new BluetoothLeAudioContentMetadata.Builder();
    }

    public void startBroadcast(byte[] broadcastCode, String language,
            String programInfo) {
        if (DEBUG) {
            if (mBluetoothLeBroadcast == null) {
                Log.d(TAG, "The BluetoothLeBroadcast is null when starting the broadcast.");
                return;
            }
            Log.d(TAG, "startBroadcast: language = " + language + " ,programInfo = " + programInfo);
        }
        buildContentMetadata(language, programInfo);
        mBluetoothLeBroadcast.startBroadcast(mBluetoothLeAudioContentMetadata, broadcastCode);
    }

    public void stopBroadcast() {
        if (DEBUG) {
            if (mBluetoothLeBroadcast == null) {
                Log.d(TAG, "The BluetoothLeBroadcast is null when stopping the broadcast.");
                return;
            }
            Log.d(TAG, "stopBroadcast()");
        }
        mBluetoothLeBroadcast.stopBroadcast(mBroadcastId);
    }

    public void updateBroadcast(String language, String programInfo) {
        if (DEBUG) {
            if (mBluetoothLeBroadcast == null) {
                Log.d(TAG, "The BluetoothLeBroadcast is null when updating the broadcast.");
                return;
            }
            Log.d(TAG,
                    "updateBroadcast: language = " + language + " ,programInfo = " + programInfo);
        }
        mBluetoothLeAudioContentMetadata = mBuilder.setProgramInfo(programInfo).build();
        mBluetoothLeBroadcast.updateBroadcast(mBroadcastId, mBluetoothLeAudioContentMetadata);
    }

    private void buildContentMetadata(String language, String programInfo) {
        mBluetoothLeAudioContentMetadata = mBuilder.setLanguage(language).setProgramInfo(
                programInfo).build();
    }

    public LocalBluetoothLeBroadcastMetadata getLocalBluetoothLeBroadcastMetaData() {
        return new LocalBluetoothLeBroadcastMetadata(mBluetoothLeBroadcastMetadata);
    }

    @Override
    public void onBroadcastStarted(int reason, int broadcastId) {
        if (DEBUG) {
            Log.d(TAG,
                    "onBroadcastStarted(), reason = " + reason + ", broadcastId = " + broadcastId);
        }
    }

    @Override
    public void onBroadcastStartFailed(int reason) {
        if (DEBUG) {
            Log.d(TAG, "onBroadcastStartFailed(), reason = " + reason);
        }
    }

    @Override
    public void onBroadcastMetadataChanged(int broadcastId,
            @NonNull BluetoothLeBroadcastMetadata metadata) {
        if (DEBUG) {
            Log.d(TAG, "onBroadcastMetadataChanged(), broadcastId = " + broadcastId);
        }
        mBluetoothLeBroadcastMetadata = metadata;
    }

    @Override
    public void onBroadcastStopped(int reason, int broadcastId) {
        if (DEBUG) {
            Log.d(TAG,
                    "onBroadcastStopped(), reason = " + reason + ", broadcastId = " + broadcastId);
        }
    }

    @Override
    public void onBroadcastStopFailed(int reason) {
        if (DEBUG) {
            Log.d(TAG, "onBroadcastStopFailed(), reason = " + reason);
        }
    }

    @Override
    public void onBroadcastUpdated(int reason, int broadcastId) {
        if (DEBUG) {
            Log.d(TAG,
                    "onBroadcastUpdated(), reason = " + reason + ", broadcastId = " + broadcastId);
        }
    }

    @Override
    public void onBroadcastUpdateFailed(int reason, int broadcastId) {
        if (DEBUG) {
            Log.d(TAG,
                    "onBroadcastUpdateFailed(), reason = " + reason + ", broadcastId = "
                            + broadcastId);
        }
    }

    @Override
    public void onPlaybackStarted(int reason, int broadcastId) {
    }

    @Override
    public void onPlaybackStopped(int reason, int broadcastId) {
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }
}
