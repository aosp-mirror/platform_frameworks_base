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
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.settingslib.R;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

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
    private static final String PREF_NAME = "LocalBluetoothLeBroadcast";
    private static final String PREF_PROGRAM_INFO = "PrefProgramInfo";
    private static final String PREF_BROADCAST_CODE = "PrefBroadcastCode";
    private static final String PREF_APP_SOURCE_NAME = "PrefAppSourceName";
    private static final String UNDERLINE = "_";
    private static final int DEFAULT_CODE_MIN = 1000;
    private static final int DEFAULT_CODE_MAX = 9999;

    private BluetoothLeBroadcast mService;
    private BluetoothLeAudioContentMetadata mBluetoothLeAudioContentMetadata;
    private BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata;
    private BluetoothLeAudioContentMetadata.Builder mBuilder;
    private int mBroadcastId = UNKNOWN_VALUE_PLACEHOLDER;
    private String mAppSourceName = "";
    private String mNewAppSourceName = "";
    private boolean mIsProfileReady;
    private String mProgramInfo;
    private byte[] mBroadcastCode;
    private SharedPreferences mSharedPref;
    private Executor mExecutor;

    private final ServiceListener mServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service connected");
            }
            mService = (BluetoothLeBroadcast) proxy;
            mIsProfileReady = true;
            registerServiceCallBack(mExecutor, mBroadcastCallback);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service disconnected");
            }
            mIsProfileReady = false;
            unregisterServiceCallBack(mBroadcastCallback);
        }
    };

    private final BluetoothLeBroadcast.Callback mBroadcastCallback =
            new BluetoothLeBroadcast.Callback() {
                @Override
                public void onBroadcastStarted(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "onBroadcastStarted(), reason = " + reason + ", broadcastId = "
                                        + broadcastId);
                    }
                    setLatestBroadcastId(broadcastId);
                    setAppSourceName(mNewAppSourceName);
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
                    setLatestBluetoothLeBroadcastMetadata(metadata);
                }

                @Override
                public void onBroadcastStopped(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "onBroadcastStopped(), reason = " + reason + ", broadcastId = "
                                        + broadcastId);
                    }
                    resetCacheInfo();
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
                                "onBroadcastUpdated(), reason = " + reason + ", broadcastId = "
                                        + broadcastId);
                    }
                    setLatestBroadcastId(broadcastId);
                    setAppSourceName(mNewAppSourceName);
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
            };

    LocalBluetoothLeBroadcast(Context context) {
        mExecutor = Executors.newSingleThreadExecutor();
        BluetoothAdapter.getDefaultAdapter().
                getProfileProxy(context, mServiceListener, BluetoothProfile.LE_AUDIO_BROADCAST);
        mBuilder = new BluetoothLeAudioContentMetadata.Builder();
        mSharedPref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (mSharedPref != null) {
            String programInfo = mSharedPref.getString(PREF_PROGRAM_INFO, "");
            if (programInfo.isEmpty()) {
                programInfo = getDefaultValueOfProgramInfo();
            }
            setProgramInfo(programInfo);

            String prefBroadcastCode = mSharedPref.getString(PREF_BROADCAST_CODE, "");
            byte[] broadcastCode;
            if (prefBroadcastCode.isEmpty()) {
                broadcastCode = getDefaultValueOfBroadcastCode();
            } else {
                broadcastCode = prefBroadcastCode.getBytes(StandardCharsets.UTF_8);
            }
            setBroadcastCode(broadcastCode);

            mAppSourceName = mSharedPref.getString(PREF_APP_SOURCE_NAME, "");
        }
    }

    /**
     * Start the LE Broadcast. If the system started the LE Broadcast, then the system calls the
     * corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void startBroadcast(String appSourceName, String language) {
        mNewAppSourceName = appSourceName;
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when starting the broadcast.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG,
                    "startBroadcast: language = " + language + " ,programInfo = " + mProgramInfo);
        }
        buildContentMetadata(language, mProgramInfo);
        mService.startBroadcast(mBluetoothLeAudioContentMetadata, mBroadcastCode);
    }

    public String getProgramInfo() {
        return mProgramInfo;
    }

    public void setProgramInfo(String programInfo) {
        if (programInfo == null || programInfo.isEmpty()) {
            Log.d(TAG, "setProgramInfo: programInfo is null or empty");
            return;
        }
        Log.d(TAG, "setProgramInfo: " + programInfo);
        mProgramInfo = programInfo;

        if (mSharedPref == null) {
            Log.d(TAG, "setProgramInfo: sharedPref is null");
            return;
        }
        SharedPreferences.Editor editor = mSharedPref.edit();
        editor.putString(PREF_PROGRAM_INFO, mProgramInfo);
        editor.apply();

    }

    public byte[] getBroadcastCode() {
        return mBroadcastCode;
    }

    public void setBroadcastCode(byte[] broadcastCode) {
        if (broadcastCode == null || broadcastCode.length == 0) {
            Log.d(TAG, "setBroadcastCode: broadcastCode is null or empty");
            return;
        }
        mBroadcastCode = broadcastCode;

        if (mSharedPref == null) {
            Log.d(TAG, "setBroadcastCode: sharedPref is null");
            return;
        }
        SharedPreferences.Editor editor = mSharedPref.edit();
        editor.putString(PREF_BROADCAST_CODE, new String(broadcastCode, StandardCharsets.UTF_8));
        editor.apply();
    }

    private void setLatestBroadcastId(int broadcastId) {
        mBroadcastId = broadcastId;
    }

    public int getLatestBroadcastId() {
        return mBroadcastId;
    }

    private void setAppSourceName(String appSourceName) {
        if (TextUtils.isEmpty(appSourceName)) {
            appSourceName = "";
        }
        mAppSourceName = appSourceName;
        mNewAppSourceName = "";
        if (mSharedPref == null) {
            Log.d(TAG, "setBroadcastCode: sharedPref is null");
            return;
        }
        SharedPreferences.Editor editor = mSharedPref.edit();
        editor.putString(PREF_APP_SOURCE_NAME, appSourceName);
        editor.apply();
    }

    public String getAppSourceName() {
        return mAppSourceName;
    }

    private void setLatestBluetoothLeBroadcastMetadata(
            BluetoothLeBroadcastMetadata bluetoothLeBroadcastMetadata) {
        if (bluetoothLeBroadcastMetadata != null
                && bluetoothLeBroadcastMetadata.getBroadcastId() == mBroadcastId) {
            mBluetoothLeBroadcastMetadata = bluetoothLeBroadcastMetadata;
        }
    }

    public BluetoothLeBroadcastMetadata getLatestBluetoothLeBroadcastMetadata() {
        return mBluetoothLeBroadcastMetadata;
    }

    /**
     * Stop the latest LE Broadcast. If the system stopped the LE Broadcast, then the system
     * calls the corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void stopLatestBroadcast() {
        stopBroadcast(mBroadcastId);
    }

    /**
     * Stop the LE Broadcast. If the system stopped the LE Broadcast, then the system calls the
     * corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void stopBroadcast(int broadcastId) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when stopping the broadcast.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "stopBroadcast()");
        }
        mService.stopBroadcast(broadcastId);
    }

    /**
     * Update the LE Broadcast. If the system stopped the LE Broadcast, then the system calls the
     * corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void updateBroadcast(String appSourceName, String language) {
        if (mService == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when updating the broadcast.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG,
                    "updateBroadcast: language = " + language + " ,programInfo = " + mProgramInfo);
        }
        mNewAppSourceName = appSourceName;
        mBluetoothLeAudioContentMetadata = mBuilder.setProgramInfo(mProgramInfo).build();
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

    public @NonNull
    List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata() {
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

    private String getDefaultValueOfProgramInfo() {
        //set the default value;
        int postfix = ThreadLocalRandom.current().nextInt(DEFAULT_CODE_MIN, DEFAULT_CODE_MAX);
        return BluetoothAdapter.getDefaultAdapter().getName() + UNDERLINE + postfix;
    }

    private byte[] getDefaultValueOfBroadcastCode() {
        //set the default value;
        return generateRandomPassword().getBytes(StandardCharsets.UTF_8);
    }

    private void resetCacheInfo() {
        if (DEBUG) {
            Log.d(TAG, "resetCacheInfo:");
        }
        mNewAppSourceName = "";
        mAppSourceName = "";
        mBluetoothLeBroadcastMetadata = null;
        mBroadcastId = UNKNOWN_VALUE_PLACEHOLDER;
    }

    private String generateRandomPassword() {
        String randomUUID = UUID.randomUUID().toString();
        //first 12 chars from xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
        return randomUUID.substring(0, 8) + randomUUID.substring(9, 13);
    }
}
