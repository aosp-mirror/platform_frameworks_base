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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastAssistant;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;


/**
 * LocalBluetoothLeBroadcastAssistant provides an interface between the Settings app
 * and the functionality of the local {@link BluetoothLeBroadcastAssistant}.
 * Use the {@link BluetoothLeBroadcastAssistant.Callback} to get the result callback.
 */
public class LocalBluetoothLeBroadcastAssistant implements LocalBluetoothProfile {
    private static final String TAG = "LocalBluetoothLeBroadcastAssistant";
    private static final int UNKNOWN_VALUE_PLACEHOLDER = -1;
    private static final boolean DEBUG = BluetoothUtils.D;

    static final String NAME = "LE_AUDIO_BROADCAST_ASSISTANT";
    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    private LocalBluetoothProfileManager mProfileManager;
    private BluetoothLeBroadcastAssistant mService;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata;
    private BluetoothLeBroadcastMetadata.Builder mBuilder;
    private boolean mIsProfileReady;

    private final ServiceListener mServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service connected");
            }
            mService = (BluetoothLeBroadcastAssistant) proxy;
            // We just bound to the service, so refresh the UI for any connected LeAudio devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    if (DEBUG) {
                        Log.d(TAG, "LocalBluetoothLeBroadcastAssistant found new device: "
                                + nextDevice);
                    }
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(LocalBluetoothLeBroadcastAssistant.this,
                        BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            mProfileManager.callServiceConnectedListeners();
            mIsProfileReady = true;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT) {
                Log.d(TAG, "The profile is not LE_AUDIO_BROADCAST_ASSISTANT");
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service disconnected");
            }
            mProfileManager.callServiceDisconnectedListeners();
            mIsProfileReady = false;
        }
    };

    public LocalBluetoothLeBroadcastAssistant(Context context,
            CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mProfileManager = profileManager;
        mDeviceManager = deviceManager;
        BluetoothAdapter.getDefaultAdapter().
                getProfileProxy(context, mServiceListener,
                        BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
        mBuilder = new BluetoothLeBroadcastMetadata.Builder();
    }

    /**
     * Add a Broadcast Source to the Broadcast Sink with {@link BluetoothLeBroadcastMetadata}.
     *
     * @param sink Broadcast Sink to which the Broadcast Source should be added
     * @param metadata Broadcast Source metadata to be added to the Broadcast Sink
     * @param isGroupOp {@code true} if Application wants to perform this operation for all
     *                  coordinated set members throughout this session. Otherwise, caller
     *                  would have to add, modify, and remove individual set members.
     */
    public void addSource(BluetoothDevice sink, BluetoothLeBroadcastMetadata metadata,
            boolean isGroupOp) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return;
        }
        mService.addSource(sink, metadata, isGroupOp);
    }

    /**
     * Add a Broadcast Source to the Broadcast Sink with the information which are separated from
     * the qr code string.
     *
     * @param sink Broadcast Sink to which the Broadcast Source should be added
     * @param sourceAddressType hardware MAC Address of the device. See
     *                          {@link BluetoothDevice.AddressType}.
     * @param presentationDelayMicros presentation delay of this Broadcast Source in microseconds.
     * @param sourceAdvertisingSid 1-byte long Advertising_SID of the Broadcast Source.
     * @param broadcastId 3-byte long Broadcast_ID of the Broadcast Source.
     * @param paSyncInterval Periodic Advertising Sync interval of the broadcast Source,
     *                       {@link BluetoothLeBroadcastMetadata#PA_SYNC_INTERVAL_UNKNOWN} if
     *                       unknown.
     * @param isEncrypted whether the Broadcast Source is encrypted.
     * @param broadcastCode Broadcast Code for this Broadcast Source, null if code is not required.
     * @param sourceDevice source advertiser address.
     * @param isGroupOp {@code true} if Application wants to perform this operation for all
     *                  coordinated set members throughout this session. Otherwise, caller
     *                  would have to add, modify, and remove individual set members.
     */
    public void addSource(@NonNull BluetoothDevice sink, int sourceAddressType,
            int presentationDelayMicros, int sourceAdvertisingSid, int broadcastId,
            int paSyncInterval, boolean isEncrypted, byte[] broadcastCode,
            BluetoothDevice sourceDevice, boolean isGroupOp) {
        if (DEBUG) {
            Log.d(TAG, "addSource()");
        }
        buildMetadata(sourceAddressType, presentationDelayMicros, sourceAdvertisingSid, broadcastId,
                paSyncInterval, isEncrypted, broadcastCode, sourceDevice);
        addSource(sink, mBluetoothLeBroadcastMetadata, isGroupOp);
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
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return;
        }
        mService.removeSource(sink, sourceId);
    }

    public void startSearchingForSources(@NonNull List<android.bluetooth.le.ScanFilter> filters) {
        if (DEBUG) {
            Log.d(TAG, "startSearchingForSources()");
        }
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return;
        }
        mService.startSearchingForSources(filters);
    }

    /**
     * Return true if a search has been started by this application.
     *
     * @return true if a search has been started by this application
     * @hide
     */
    public boolean isSearchInProgress() {
        if (DEBUG) {
            Log.d(TAG, "isSearchInProgress()");
        }
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return false;
        }
        return mService.isSearchInProgress();
    }

    /**
     * Get information about all Broadcast Sources that a Broadcast Sink knows about.
     *
     * @param sink Broadcast Sink from which to get all Broadcast Sources
     * @return the list of Broadcast Receive State {@link BluetoothLeBroadcastReceiveState}
     *         stored in the Broadcast Sink
     * @throws NullPointerException when <var>sink</var> is null
     */
    public @NonNull List<BluetoothLeBroadcastReceiveState> getAllSources(
            @NonNull BluetoothDevice sink) {
        if (DEBUG) {
            Log.d(TAG, "getAllSources()");
        }
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcastAssistant is null");
            return new ArrayList<BluetoothLeBroadcastReceiveState>();
        }
        return mService.getAllSources(sink);
    }

    public void registerServiceCallBack(@NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothLeBroadcastAssistant.Callback callback) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null.");
            return;
        }

        mService.registerCallback(executor, callback);
    }

    public void unregisterServiceCallBack(
            @NonNull BluetoothLeBroadcastAssistant.Callback callback) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null.");
            return;
        }

        mService.unregisterCallback(callback);
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    public int getProfileId() {
        return BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT;
    }

    public boolean accessProfileEnabled() {
        return false;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        // LE Audio Broadcasts are not connection-oriented.
        return mService.getConnectionState(device);
    }

    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(
                new int[]{BluetoothProfile.STATE_CONNECTED,
                        BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_DISCONNECTING});
    }

    public boolean isEnabled(BluetoothDevice device) {
        if (mService == null || device == null) {
            return false;
        }
        return mService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN;
    }

    public int getConnectionPolicy(BluetoothDevice device) {
        if (mService == null || device == null) {
            return CONNECTION_POLICY_FORBIDDEN;
        }
        return mService.getConnectionPolicy(device);
    }

    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isEnabled = false;
        if (mService == null || device == null) {
            return false;
        }
        if (enabled) {
            if (mService.getConnectionPolicy(device) < CONNECTION_POLICY_ALLOWED) {
                isEnabled = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            }
        } else {
            isEnabled = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isEnabled;
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
                        BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                        mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up LeAudio proxy", t);
            }
        }
    }
}
