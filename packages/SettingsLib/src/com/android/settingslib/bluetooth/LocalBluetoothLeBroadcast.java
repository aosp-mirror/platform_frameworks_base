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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcast;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;


/**
 * LocalBluetoothLeBroadcast provides an interface between the Settings app
 * and the functionality of the local {@link BluetoothLeBroadcast}.
 * Use the {@link BluetoothLeBroadcast.Callback} to get the result callback.
 */
public class LocalBluetoothLeBroadcast implements LocalBluetoothProfile {
    private static final String TAG = "LocalBluetoothLeBroadcast";
    private static final int UNKNOWN_VALUE_PLACEHOLDER = -1;
    private static final boolean DEBUG = BluetoothUtils.D;

    static final String NAME = "LE_AUDIO_BROADCAST";
    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    private BluetoothLeBroadcast mService;
    private BluetoothLeAudioContentMetadata mBluetoothLeAudioContentMetadata;
    private BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata;
    private BluetoothLeAudioContentMetadata.Builder mBuilder;
    private int mBroadcastId = UNKNOWN_VALUE_PLACEHOLDER;
    private boolean mIsProfileReady;

    private final ServiceListener mServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.LE_AUDIO_BROADCAST) {
                Log.d(TAG, "The profile is not LE_AUDIO_BROADCAST");
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service connected");
            }
            mService = (BluetoothLeBroadcast) proxy;
            mIsProfileReady = true;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.LE_AUDIO_BROADCAST) {
                Log.d(TAG, "The profile is not LE_AUDIO_BROADCAST");
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service disconnected");
            }
            mIsProfileReady = false;
        }
    };

    LocalBluetoothLeBroadcast(Context context) {
        BluetoothAdapter.getDefaultAdapter().
                getProfileProxy(context, mServiceListener, BluetoothProfile.LE_AUDIO_BROADCAST);
        mBuilder = new BluetoothLeAudioContentMetadata.Builder();
    }

    public void startBroadcast(byte[] broadcastCode, String language,
            String programInfo) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when starting the broadcast.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "startBroadcast: language = " + language + " ,programInfo = " + programInfo);
        }
        buildContentMetadata(language, programInfo);
        mService.startBroadcast(mBluetoothLeAudioContentMetadata, broadcastCode);
    }

    public void stopBroadcast() {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when stopping the broadcast.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "stopBroadcast()");
        }
        mService.stopBroadcast(mBroadcastId);
    }

    public void updateBroadcast(String language, String programInfo) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when updating the broadcast.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG,
                    "updateBroadcast: language = " + language + " ,programInfo = " + programInfo);
        }
        mBluetoothLeAudioContentMetadata = mBuilder.setProgramInfo(programInfo).build();
        mService.updateBroadcast(mBroadcastId, mBluetoothLeAudioContentMetadata);
    }

    public void registerServiceCallBack(@NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothLeBroadcast.Callback callback) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null.");
            return;
        }

        mService.registerCallback(executor, callback);
    }

    public void unregisterServiceCallBack(@NonNull BluetoothLeBroadcast.Callback callback) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null.");
            return;
        }

        mService.unregisterCallback(callback);
    }

    private void buildContentMetadata(String language, String programInfo) {
        mBluetoothLeAudioContentMetadata = mBuilder.setLanguage(language).setProgramInfo(
                programInfo).build();
    }

    public LocalBluetoothLeBroadcastMetadata getLocalBluetoothLeBroadcastMetaData() {
        return new LocalBluetoothLeBroadcastMetadata(mBluetoothLeBroadcastMetadata);
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.LE_AUDIO_BROADCAST;
    }

    public boolean accessProfileEnabled() {
        return false;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    /**
     * Not supported since LE Audio Broadcasts do not establish a connection.
     */
    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        // LE Audio Broadcasts are not connection-oriented.
        return mService.getConnectionState(device);
    }

    /**
     * Not supported since LE Audio Broadcasts do not establish a connection.
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        // LE Audio Broadcasts are not connection-oriented.
        return mService.getConnectedDevices();
    }

    public @NonNull List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata() {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null.");
            return Collections.emptyList();
        }

        return mService.getAllBroadcastMetadata();
    }

    public boolean isEnabled(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }

        return !mService.getAllBroadcastMetadata().isEmpty();
    }

    /**
     * Service does not provide method to get/set policy.
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return CONNECTION_POLICY_FORBIDDEN;
    }

    /**
     * Service does not provide "setEnabled" method. Please use {@link #startBroadcast},
     * {@link #stopBroadcast()} or {@link #updateBroadcast(String, String)}
     */
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        return false;
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return R.string.summary_empty;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        return BluetoothUtils.getConnectionStateSummary(state);
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return 0;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    protected void finalize() {
        if (DEBUG) {
            Log.d(TAG, "finalize()");
        }
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(
                        BluetoothProfile.LE_AUDIO_BROADCAST,
                        mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up LeAudio proxy", t);
            }
        }
    }
}
