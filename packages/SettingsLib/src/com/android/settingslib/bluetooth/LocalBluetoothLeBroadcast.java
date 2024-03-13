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
import android.annotation.IntDef;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcast;
import android.bluetooth.BluetoothLeBroadcastAssistant;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.settingslib.R;

import com.google.common.collect.ImmutableList;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * LocalBluetoothLeBroadcast provides an interface between the Settings app and the functionality of
 * the local {@link BluetoothLeBroadcast}. Use the {@link BluetoothLeBroadcast.Callback} to get the
 * result callback.
 */
public class LocalBluetoothLeBroadcast implements LocalBluetoothProfile {
    public static final String ACTION_LE_AUDIO_SHARING_STATE_CHANGE =
            "com.android.settings.action.BLUETOOTH_LE_AUDIO_SHARING_STATE_CHANGE";
    public static final String EXTRA_LE_AUDIO_SHARING_STATE = "BLUETOOTH_LE_AUDIO_SHARING_STATE";
    public static final int BROADCAST_STATE_UNKNOWN = 0;
    public static final int BROADCAST_STATE_ON = 1;
    public static final int BROADCAST_STATE_OFF = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"BROADCAST_STATE_"},
            value = {BROADCAST_STATE_UNKNOWN, BROADCAST_STATE_ON, BROADCAST_STATE_OFF})
    public @interface BroadcastState {}

    private static final String SETTINGS_PKG = "com.android.settings";
    private static final String TAG = "LocalBluetoothLeBroadcast";
    private static final boolean DEBUG = BluetoothUtils.D;

    static final String NAME = "LE_AUDIO_BROADCAST";
    private static final String UNDERLINE = "_";
    private static final int DEFAULT_CODE_MAX = 9999;
    private static final int DEFAULT_CODE_MIN = 1000;
    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;
    private static final int UNKNOWN_VALUE_PLACEHOLDER = -1;
    private static final Uri[] SETTINGS_URIS =
            new Uri[] {
                Settings.Secure.getUriFor(Settings.Secure.BLUETOOTH_LE_BROADCAST_PROGRAM_INFO),
                Settings.Secure.getUriFor(Settings.Secure.BLUETOOTH_LE_BROADCAST_CODE),
                Settings.Secure.getUriFor(Settings.Secure.BLUETOOTH_LE_BROADCAST_APP_SOURCE_NAME),
                Settings.Secure.getUriFor(
                        Settings.Secure.BLUETOOTH_LE_BROADCAST_IMPROVE_COMPATIBILITY),
            };
    private final Context mContext;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private BluetoothLeBroadcast mServiceBroadcast;
    private BluetoothLeBroadcastAssistant mServiceBroadcastAssistant;
    private BluetoothLeAudioContentMetadata mBluetoothLeAudioContentMetadata;
    private BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata;
    private BluetoothLeAudioContentMetadata.Builder mBuilder;
    private int mBroadcastId = UNKNOWN_VALUE_PLACEHOLDER;
    private String mAppSourceName = "";
    private String mNewAppSourceName = "";
    private boolean mIsBroadcastProfileReady = false;
    private boolean mIsBroadcastAssistantProfileReady = false;
    private boolean mImproveCompatibility = false;
    private String mProgramInfo;
    private byte[] mBroadcastCode;
    private Executor mExecutor;
    private ContentResolver mContentResolver;
    private ContentObserver mSettingsObserver;
    // Cached broadcast callbacks being register before service is connected.
    private Map<BluetoothLeBroadcast.Callback, Executor> mCachedBroadcastCallbackExecutorMap =
            new ConcurrentHashMap<>();

    private final ServiceListener mServiceListener =
            new ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (DEBUG) {
                        Log.d(TAG, "Bluetooth service connected: " + profile);
                    }
                    if ((profile == BluetoothProfile.LE_AUDIO_BROADCAST)
                            && !mIsBroadcastProfileReady) {
                        mServiceBroadcast = (BluetoothLeBroadcast) proxy;
                        mIsBroadcastProfileReady = true;
                        registerServiceCallBack(mExecutor, mBroadcastCallback);
                        List<BluetoothLeBroadcastMetadata> metadata = getAllBroadcastMetadata();
                        if (!metadata.isEmpty()) {
                            updateBroadcastInfoFromBroadcastMetadata(metadata.get(0));
                        }
                        registerContentObserver();
                        if (DEBUG) {
                            Log.d(
                                    TAG,
                                    "onServiceConnected: register "
                                            + "mCachedBroadcastCallbackExecutorMap = "
                                            + mCachedBroadcastCallbackExecutorMap);
                        }
                        mCachedBroadcastCallbackExecutorMap.forEach(
                                (callback, executor) ->
                                        registerServiceCallBack(executor, callback));
                    } else if ((profile == BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)
                            && !mIsBroadcastAssistantProfileReady) {
                        mIsBroadcastAssistantProfileReady = true;
                        mServiceBroadcastAssistant = (BluetoothLeBroadcastAssistant) proxy;
                        registerBroadcastAssistantCallback(mExecutor, mBroadcastAssistantCallback);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (DEBUG) {
                        Log.d(TAG, "Bluetooth service disconnected: " + profile);
                    }
                    if ((profile == BluetoothProfile.LE_AUDIO_BROADCAST)
                            && mIsBroadcastProfileReady) {
                        mIsBroadcastProfileReady = false;
                        unregisterServiceCallBack(mBroadcastCallback);
                        mCachedBroadcastCallbackExecutorMap.clear();
                    }
                    if ((profile == BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)
                            && mIsBroadcastAssistantProfileReady) {
                        mIsBroadcastAssistantProfileReady = false;
                        unregisterBroadcastAssistantCallback(mBroadcastAssistantCallback);
                    }

                    if (!mIsBroadcastAssistantProfileReady && !mIsBroadcastProfileReady) {
                        unregisterContentObserver();
                    }
                }
            };

    private final BluetoothLeBroadcast.Callback mBroadcastCallback =
            new BluetoothLeBroadcast.Callback() {
                @Override
                public void onBroadcastStarted(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onBroadcastStarted(), reason = "
                                        + reason
                                        + ", broadcastId = "
                                        + broadcastId);
                    }
                    setLatestBroadcastId(broadcastId);
                    setAppSourceName(mNewAppSourceName, /* updateContentResolver= */ true);
                }

                @Override
                public void onBroadcastStartFailed(int reason) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastStartFailed(), reason = " + reason);
                    }
                }

                @Override
                public void onBroadcastMetadataChanged(
                        int broadcastId, @NonNull BluetoothLeBroadcastMetadata metadata) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastMetadataChanged(), broadcastId = " + broadcastId);
                    }
                    setLatestBluetoothLeBroadcastMetadata(metadata);
                    notifyBroadcastStateChange(BROADCAST_STATE_ON);
                }

                @Override
                public void onBroadcastStopped(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onBroadcastStopped(), reason = "
                                        + reason
                                        + ", broadcastId = "
                                        + broadcastId);
                    }
                    notifyBroadcastStateChange(BROADCAST_STATE_OFF);
                    stopLocalSourceReceivers();
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
                        Log.d(
                                TAG,
                                "onBroadcastUpdated(), reason = "
                                        + reason
                                        + ", broadcastId = "
                                        + broadcastId);
                    }
                    setLatestBroadcastId(broadcastId);
                    setAppSourceName(mNewAppSourceName, /* updateContentResolver= */ true);
                }

                @Override
                public void onBroadcastUpdateFailed(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onBroadcastUpdateFailed(), reason = "
                                        + reason
                                        + ", broadcastId = "
                                        + broadcastId);
                    }
                }

                @Override
                public void onPlaybackStarted(int reason, int broadcastId) {}

                @Override
                public void onPlaybackStopped(int reason, int broadcastId) {}
            };

    private final BluetoothLeBroadcastAssistant.Callback mBroadcastAssistantCallback =
            new BluetoothLeBroadcastAssistant.Callback() {
                @Override
                public void onSourceAdded(@NonNull BluetoothDevice sink, int sourceId, int reason) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onSourceAdded(), sink = "
                                        + sink
                                        + ", reason = "
                                        + reason
                                        + ", sourceId = "
                                        + sourceId);
                    }
                    updateFallbackActiveDeviceIfNeeded();
                }

                @Override
                public void onSearchStarted(int reason) {}

                @Override
                public void onSearchStartFailed(int reason) {}

                @Override
                public void onSearchStopped(int reason) {}

                @Override
                public void onSearchStopFailed(int reason) {}

                @Override
                public void onSourceFound(@NonNull BluetoothLeBroadcastMetadata source) {}

                @Override
                public void onSourceAddFailed(
                        @NonNull BluetoothDevice sink,
                        @NonNull BluetoothLeBroadcastMetadata source,
                        int reason) {}

                @Override
                public void onSourceModified(
                        @NonNull BluetoothDevice sink, int sourceId, int reason) {}

                @Override
                public void onSourceModifyFailed(
                        @NonNull BluetoothDevice sink, int sourceId, int reason) {}

                @Override
                public void onSourceRemoved(
                        @NonNull BluetoothDevice sink, int sourceId, int reason) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onSourceRemoved(), sink = "
                                        + sink
                                        + ", reason = "
                                        + reason
                                        + ", sourceId = "
                                        + sourceId);
                    }
                    updateFallbackActiveDeviceIfNeeded();
                }

                @Override
                public void onSourceRemoveFailed(
                        @NonNull BluetoothDevice sink, int sourceId, int reason) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onSourceRemoveFailed(), sink = "
                                        + sink
                                        + ", reason = "
                                        + reason
                                        + ", sourceId = "
                                        + sourceId);
                    }
                }

                @Override
                public void onReceiveStateChanged(
                        @NonNull BluetoothDevice sink,
                        int sourceId,
                        @NonNull BluetoothLeBroadcastReceiveState state) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onReceiveStateChanged(), sink = "
                                        + sink
                                        + ", sourceId = "
                                        + sourceId
                                        + ", state = "
                                        + state);
                    }
                }
            };

    private class BroadcastSettingsObserver extends ContentObserver {
        BroadcastSettingsObserver(Handler h) {
            super(h);
        }

        @Override
        public void onChange(boolean selfChange) {
            Log.d(TAG, "BroadcastSettingsObserver: onChange");
            updateBroadcastInfoFromContentProvider();
        }
    }

    LocalBluetoothLeBroadcast(Context context, CachedBluetoothDeviceManager deviceManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mExecutor = Executors.newSingleThreadExecutor();
        mBuilder = new BluetoothLeAudioContentMetadata.Builder();
        mContentResolver = context.getContentResolver();
        Handler handler = new Handler(Looper.getMainLooper());
        mSettingsObserver = new BroadcastSettingsObserver(handler);
        updateBroadcastInfoFromContentProvider();

        // Before registering callback, the constructor should finish creating the all of variables.
        BluetoothAdapter.getDefaultAdapter()
                .getProfileProxy(context, mServiceListener, BluetoothProfile.LE_AUDIO_BROADCAST);
        BluetoothAdapter.getDefaultAdapter()
                .getProfileProxy(
                        context, mServiceListener, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
    }

    /**
     * Start the LE Broadcast. If the system started the LE Broadcast, then the system calls the
     * corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void startBroadcast(String appSourceName, String language) {
        mNewAppSourceName = appSourceName;
        if (mServiceBroadcast == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when starting the broadcast.");
            return;
        }
        String programInfo = getProgramInfo();
        if (DEBUG) {
            Log.d(TAG, "startBroadcast: language = " + language + " ,programInfo = " + programInfo);
        }
        buildContentMetadata(language, programInfo);
        mServiceBroadcast.startBroadcast(
                mBluetoothLeAudioContentMetadata,
                (mBroadcastCode != null && mBroadcastCode.length > 0) ? mBroadcastCode : null);
    }

    /**
     * Start the private Broadcast for personal audio sharing or qr code sharing.
     *
     * <p>The broadcast will use random string for both broadcast name and subgroup program info;
     * The broadcast will use random string for broadcast code; The broadcast will only have one
     * subgroup due to system limitation; The subgroup language will be null.
     *
     * <p>If the system started the LE Broadcast, then the system calls the corresponding callback
     * {@link BluetoothLeBroadcast.Callback}.
     */
    public void startPrivateBroadcast() {
        mNewAppSourceName = "Sharing audio";
        if (mServiceBroadcast == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when starting the private broadcast.");
            return;
        }
        if (mServiceBroadcast.getAllBroadcastMetadata().size()
                >= mServiceBroadcast.getMaximumNumberOfBroadcasts()) {
            Log.d(TAG, "Skip starting the broadcast due to number limit.");
            return;
        }
        String programInfo = getProgramInfo();
        boolean improveCompatibility = getImproveCompatibility();
        if (DEBUG) {
            Log.d(
                    TAG,
                    "startBroadcast: language = null , programInfo = "
                            + programInfo
                            + ", improveCompatibility = "
                            + improveCompatibility);
        }
        // Current broadcast framework only support one subgroup
        BluetoothLeBroadcastSubgroupSettings subgroupSettings =
                buildBroadcastSubgroupSettings(
                        /* language= */ null, programInfo, improveCompatibility);
        BluetoothLeBroadcastSettings settings =
                buildBroadcastSettings(
                        true, // TODO: set to false after framework fix
                        TextUtils.isEmpty(programInfo) ? null : programInfo,
                        (mBroadcastCode != null && mBroadcastCode.length > 0)
                                ? mBroadcastCode
                                : null,
                        ImmutableList.of(subgroupSettings));
        mServiceBroadcast.startBroadcast(settings);
    }

    private BluetoothLeBroadcastSettings buildBroadcastSettings(
            boolean isPublic,
            @Nullable String broadcastName,
            @Nullable byte[] broadcastCode,
            List<BluetoothLeBroadcastSubgroupSettings> subgroupSettingsList) {
        BluetoothLeBroadcastSettings.Builder builder =
                new BluetoothLeBroadcastSettings.Builder()
                        .setPublicBroadcast(isPublic)
                        .setBroadcastName(broadcastName)
                        .setBroadcastCode(broadcastCode);
        for (BluetoothLeBroadcastSubgroupSettings subgroupSettings : subgroupSettingsList) {
            builder.addSubgroupSettings(subgroupSettings);
        }
        return builder.build();
    }

    private BluetoothLeBroadcastSubgroupSettings buildBroadcastSubgroupSettings(
            @Nullable String language, @Nullable String programInfo, boolean improveCompatibility) {
        BluetoothLeAudioContentMetadata metadata =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage(language)
                        .setProgramInfo(programInfo)
                        .build();
        // Current broadcast framework only support one subgroup, thus we still maintain the latest
        // metadata to keep legacy UI working.
        mBluetoothLeAudioContentMetadata = metadata;
        return new BluetoothLeBroadcastSubgroupSettings.Builder()
                .setPreferredQuality(
                        improveCompatibility
                                ? BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD
                                : BluetoothLeBroadcastSubgroupSettings.QUALITY_HIGH)
                .setContentMetadata(mBluetoothLeAudioContentMetadata)
                .build();
    }

    public String getProgramInfo() {
        return mProgramInfo;
    }

    public void setProgramInfo(String programInfo) {
        setProgramInfo(programInfo, /* updateContentResolver= */ true);
    }

    private void setProgramInfo(String programInfo, boolean updateContentResolver) {
        if (TextUtils.isEmpty(programInfo)) {
            Log.d(TAG, "setProgramInfo: programInfo is null or empty");
            return;
        }
        if (mProgramInfo != null && TextUtils.equals(mProgramInfo, programInfo)) {
            Log.d(TAG, "setProgramInfo: programInfo is not changed");
            return;
        }
        Log.d(TAG, "setProgramInfo: " + programInfo);
        mProgramInfo = programInfo;
        if (updateContentResolver) {
            if (mContentResolver == null) {
                Log.d(TAG, "mContentResolver is null");
                return;
            }
            Settings.Secure.putString(
                    mContentResolver,
                    Settings.Secure.BLUETOOTH_LE_BROADCAST_PROGRAM_INFO,
                    programInfo);
        }
    }

    public byte[] getBroadcastCode() {
        return mBroadcastCode;
    }

    public void setBroadcastCode(byte[] broadcastCode) {
        setBroadcastCode(broadcastCode, /* updateContentResolver= */ true);
    }

    private void setBroadcastCode(byte[] broadcastCode, boolean updateContentResolver) {
        if (broadcastCode == null) {
            Log.d(TAG, "setBroadcastCode: broadcastCode is null");
            return;
        }
        if (mBroadcastCode != null && Arrays.equals(broadcastCode, mBroadcastCode)) {
            Log.d(TAG, "setBroadcastCode: broadcastCode is not changed");
            return;
        }
        mBroadcastCode = broadcastCode;
        if (updateContentResolver) {
            if (mContentResolver == null) {
                Log.d(TAG, "mContentResolver is null");
                return;
            }
            Settings.Secure.putString(
                    mContentResolver,
                    Settings.Secure.BLUETOOTH_LE_BROADCAST_CODE,
                    new String(broadcastCode, StandardCharsets.UTF_8));
        }
    }

    /** Get compatibility config for broadcast. */
    public boolean getImproveCompatibility() {
        return mImproveCompatibility;
    }

    /** Set compatibility config for broadcast. */
    public void setImproveCompatibility(boolean improveCompatibility) {
        setImproveCompatibility(improveCompatibility, /* updateContentResolver= */ true);
    }

    private void setImproveCompatibility(
            boolean improveCompatibility, boolean updateContentResolver) {
        if (mImproveCompatibility == improveCompatibility) {
            Log.d(TAG, "setImproveCompatibility: improveCompatibility is not changed");
            return;
        }
        mImproveCompatibility = improveCompatibility;
        if (updateContentResolver) {
            if (mContentResolver == null) {
                Log.d(TAG, "mContentResolver is null");
                return;
            }
            Log.d(TAG, "Set improveCompatibility to: " + improveCompatibility);
            Settings.Secure.putString(
                    mContentResolver,
                    Settings.Secure.BLUETOOTH_LE_BROADCAST_IMPROVE_COMPATIBILITY,
                    improveCompatibility ? "1" : "0");
        }
    }

    private void setLatestBroadcastId(int broadcastId) {
        Log.d(TAG, "setLatestBroadcastId: mBroadcastId is " + broadcastId);
        mBroadcastId = broadcastId;
    }

    public int getLatestBroadcastId() {
        return mBroadcastId;
    }

    private void setAppSourceName(String appSourceName, boolean updateContentResolver) {
        if (TextUtils.isEmpty(appSourceName)) {
            appSourceName = "";
        }
        if (mAppSourceName != null && TextUtils.equals(mAppSourceName, appSourceName)) {
            Log.d(TAG, "setAppSourceName: appSourceName is not changed");
            return;
        }
        mAppSourceName = appSourceName;
        mNewAppSourceName = "";
        if (updateContentResolver) {
            if (mContentResolver == null) {
                Log.d(TAG, "mContentResolver is null");
                return;
            }
            Settings.Secure.putString(
                    mContentResolver,
                    Settings.Secure.BLUETOOTH_LE_BROADCAST_APP_SOURCE_NAME,
                    mAppSourceName);
        }
    }

    public String getAppSourceName() {
        return mAppSourceName;
    }

    private void setLatestBluetoothLeBroadcastMetadata(
            BluetoothLeBroadcastMetadata bluetoothLeBroadcastMetadata) {
        if (bluetoothLeBroadcastMetadata != null
                && bluetoothLeBroadcastMetadata.getBroadcastId() == mBroadcastId) {
            mBluetoothLeBroadcastMetadata = bluetoothLeBroadcastMetadata;
            updateBroadcastInfoFromBroadcastMetadata(bluetoothLeBroadcastMetadata);
        }
    }

    public BluetoothLeBroadcastMetadata getLatestBluetoothLeBroadcastMetadata() {
        if (mServiceBroadcast == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null");
            return null;
        }
        if (mBluetoothLeBroadcastMetadata == null) {
            final List<BluetoothLeBroadcastMetadata> metadataList =
                    mServiceBroadcast.getAllBroadcastMetadata();
            mBluetoothLeBroadcastMetadata =
                    metadataList.stream()
                            .filter(i -> i.getBroadcastId() == mBroadcastId)
                            .findFirst()
                            .orElse(null);
        }
        return mBluetoothLeBroadcastMetadata;
    }

    private void updateBroadcastInfoFromContentProvider() {
        if (mContentResolver == null) {
            Log.d(TAG, "updateBroadcastInfoFromContentProvider: mContentResolver is null");
            return;
        }
        String programInfo =
                Settings.Secure.getString(
                        mContentResolver, Settings.Secure.BLUETOOTH_LE_BROADCAST_PROGRAM_INFO);
        if (programInfo == null) {
            programInfo = getDefaultValueOfProgramInfo();
        }
        setProgramInfo(programInfo, /* updateContentResolver= */ false);

        String prefBroadcastCode =
                Settings.Secure.getString(
                        mContentResolver, Settings.Secure.BLUETOOTH_LE_BROADCAST_CODE);
        byte[] broadcastCode =
                (prefBroadcastCode == null)
                        ? getDefaultValueOfBroadcastCode()
                        : prefBroadcastCode.getBytes(StandardCharsets.UTF_8);
        setBroadcastCode(broadcastCode, /* updateContentResolver= */ false);

        String appSourceName =
                Settings.Secure.getString(
                        mContentResolver, Settings.Secure.BLUETOOTH_LE_BROADCAST_APP_SOURCE_NAME);
        setAppSourceName(appSourceName, /* updateContentResolver= */ false);

        String improveCompatibility =
                Settings.Secure.getString(
                        mContentResolver,
                        Settings.Secure.BLUETOOTH_LE_BROADCAST_IMPROVE_COMPATIBILITY);
        setImproveCompatibility(
                improveCompatibility == null ? false : improveCompatibility.equals("1"),
                /* updateContentResolver= */ false);
    }

    private void updateBroadcastInfoFromBroadcastMetadata(
            BluetoothLeBroadcastMetadata bluetoothLeBroadcastMetadata) {
        if (bluetoothLeBroadcastMetadata == null) {
            Log.d(TAG, "The bluetoothLeBroadcastMetadata is null");
            return;
        }
        setBroadcastCode(bluetoothLeBroadcastMetadata.getBroadcastCode());
        setLatestBroadcastId(bluetoothLeBroadcastMetadata.getBroadcastId());

        List<BluetoothLeBroadcastSubgroup> subgroup = bluetoothLeBroadcastMetadata.getSubgroups();
        if (subgroup == null || subgroup.size() < 1) {
            Log.d(TAG, "The subgroup is not valid value");
            return;
        }
        BluetoothLeAudioContentMetadata contentMetadata = subgroup.get(0).getContentMetadata();
        setProgramInfo(contentMetadata.getProgramInfo());
        setAppSourceName(getAppSourceName(), /* updateContentResolver= */ true);
    }

    /**
     * Stop the latest LE Broadcast. If the system stopped the LE Broadcast, then the system calls
     * the corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void stopLatestBroadcast() {
        stopBroadcast(mBroadcastId);
    }

    /**
     * Stop the LE Broadcast. If the system stopped the LE Broadcast, then the system calls the
     * corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void stopBroadcast(int broadcastId) {
        if (mServiceBroadcast == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when stopping the broadcast.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "stopBroadcast()");
        }
        mServiceBroadcast.stopBroadcast(broadcastId);
    }

    /**
     * Update the LE Broadcast. If the system stopped the LE Broadcast, then the system calls the
     * corresponding callback {@link BluetoothLeBroadcast.Callback}.
     */
    public void updateBroadcast(String appSourceName, String language) {
        if (mServiceBroadcast == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when updating the broadcast.");
            return;
        }
        String programInfo = getProgramInfo();
        if (DEBUG) {
            Log.d(
                    TAG,
                    "updateBroadcast: language = " + language + " ,programInfo = " + programInfo);
        }
        mNewAppSourceName = appSourceName;
        mBluetoothLeAudioContentMetadata = mBuilder.setProgramInfo(programInfo).build();
        mServiceBroadcast.updateBroadcast(mBroadcastId, mBluetoothLeAudioContentMetadata);
    }

    /**
     * Update the LE Broadcast by calling {@link BluetoothLeBroadcast#updateBroadcast(int,
     * BluetoothLeAudioContentMetadata)}, currently only updates programInfo.
     */
    public void updateBroadcast() {
        if (mServiceBroadcast == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null when updating the broadcast.");
            return;
        }
        String programInfo = getProgramInfo();
        mBluetoothLeAudioContentMetadata = mBuilder.setProgramInfo(programInfo).build();
        mServiceBroadcast.updateBroadcast(mBroadcastId, mBluetoothLeAudioContentMetadata);
    }

    /**
     * Register Broadcast Callbacks to track its state and receivers
     *
     * @param executor Executor object for callback
     * @param callback Callback object to be registered
     */
    public void registerServiceCallBack(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothLeBroadcast.Callback callback) {
        if (mServiceBroadcast == null) {
            Log.d(TAG, "registerServiceCallBack failed, the BluetoothLeBroadcast is null.");
            mCachedBroadcastCallbackExecutorMap.putIfAbsent(callback, executor);
            return;
        }

        try {
            mServiceBroadcast.registerCallback(executor, callback);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "registerServiceCallBack failed. " + e.getMessage());
        }
    }

    /**
     * Register Broadcast Assistant Callbacks to track its state and receivers
     *
     * @param executor Executor object for callback
     * @param callback Callback object to be registered
     */
    private void registerBroadcastAssistantCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothLeBroadcastAssistant.Callback callback) {
        if (mServiceBroadcastAssistant == null) {
            Log.d(
                    TAG,
                    "registerBroadcastAssistantCallback failed, "
                            + "the BluetoothLeBroadcastAssistant is null.");
            return;
        }

        mServiceBroadcastAssistant.registerCallback(executor, callback);
    }

    /**
     * Unregister previously registered Broadcast Callbacks
     *
     * @param callback Callback object to be unregistered
     */
    public void unregisterServiceCallBack(@NonNull BluetoothLeBroadcast.Callback callback) {
        mCachedBroadcastCallbackExecutorMap.remove(callback);
        if (mServiceBroadcast == null) {
            Log.d(TAG, "unregisterServiceCallBack failed, the BluetoothLeBroadcast is null.");
            return;
        }

        try {
            mServiceBroadcast.unregisterCallback(callback);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "unregisterServiceCallBack failed. " + e.getMessage());
        }
    }

    /**
     * Unregister previously registered Broadcast Assistant Callbacks
     *
     * @param callback Callback object to be unregistered
     */
    private void unregisterBroadcastAssistantCallback(
            @NonNull BluetoothLeBroadcastAssistant.Callback callback) {
        if (mServiceBroadcastAssistant == null) {
            Log.d(
                    TAG,
                    "unregisterBroadcastAssistantCallback, "
                            + "the BluetoothLeBroadcastAssistant is null.");
            return;
        }

        mServiceBroadcastAssistant.unregisterCallback(callback);
    }

    private void buildContentMetadata(String language, String programInfo) {
        mBluetoothLeAudioContentMetadata =
                mBuilder.setLanguage(language).setProgramInfo(programInfo).build();
    }

    public LocalBluetoothLeBroadcastMetadata getLocalBluetoothLeBroadcastMetaData() {
        final BluetoothLeBroadcastMetadata metadata = getLatestBluetoothLeBroadcastMetadata();
        if (metadata == null) {
            Log.d(TAG, "The BluetoothLeBroadcastMetadata is null.");
            return null;
        }
        return new LocalBluetoothLeBroadcastMetadata(metadata);
    }

    public boolean isProfileReady() {
        return mIsBroadcastProfileReady;
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

    /** Not supported since LE Audio Broadcasts do not establish a connection. */
    public int getConnectionStatus(BluetoothDevice device) {
        if (mServiceBroadcast == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        // LE Audio Broadcasts are not connection-oriented.
        return mServiceBroadcast.getConnectionState(device);
    }

    /** Not supported since LE Audio Broadcasts do not establish a connection. */
    public List<BluetoothDevice> getConnectedDevices() {
        if (mServiceBroadcast == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        // LE Audio Broadcasts are not connection-oriented.
        return mServiceBroadcast.getConnectedDevices();
    }

    /** Get all broadcast metadata. */
    public @NonNull List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata() {
        if (mServiceBroadcast == null) {
            Log.d(TAG, "The BluetoothLeBroadcast is null.");
            return Collections.emptyList();
        }

        return mServiceBroadcast.getAllBroadcastMetadata();
    }

    public boolean isEnabled(BluetoothDevice device) {
        if (mServiceBroadcast == null) {
            return false;
        }

        return !mServiceBroadcast.getAllBroadcastMetadata().isEmpty();
    }

    /** Service does not provide method to get/set policy. */
    public int getConnectionPolicy(BluetoothDevice device) {
        return CONNECTION_POLICY_FORBIDDEN;
    }

    /**
     * Service does not provide "setEnabled" method. Please use {@link #startBroadcast}, {@link
     * #stopBroadcast()} or {@link #updateBroadcast(String, String)}
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
        if (mServiceBroadcast != null) {
            try {
                BluetoothAdapter.getDefaultAdapter()
                        .closeProfileProxy(BluetoothProfile.LE_AUDIO_BROADCAST, mServiceBroadcast);
                mServiceBroadcast = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up LeAudio proxy", t);
            }
        }
    }

    private String getDefaultValueOfProgramInfo() {
        // set the default value;
        int postfix = ThreadLocalRandom.current().nextInt(DEFAULT_CODE_MIN, DEFAULT_CODE_MAX);
        return BluetoothAdapter.getDefaultAdapter().getName() + UNDERLINE + postfix;
    }

    private byte[] getDefaultValueOfBroadcastCode() {
        // set the default value;
        return generateRandomPassword().getBytes(StandardCharsets.UTF_8);
    }

    private void resetCacheInfo() {
        if (DEBUG) {
            Log.d(TAG, "resetCacheInfo:");
        }
        setAppSourceName("", /* updateContentResolver= */ true);
        mBluetoothLeBroadcastMetadata = null;
        mBroadcastId = UNKNOWN_VALUE_PLACEHOLDER;
    }

    private String generateRandomPassword() {
        String randomUUID = UUID.randomUUID().toString();
        // first 12 chars from xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
        return randomUUID.substring(0, 8) + randomUUID.substring(9, 13);
    }

    private void registerContentObserver() {
        if (mContentResolver == null) {
            Log.d(TAG, "mContentResolver is null");
            return;
        }
        for (Uri uri : SETTINGS_URIS) {
            mContentResolver.registerContentObserver(uri, false, mSettingsObserver);
        }
    }

    private void unregisterContentObserver() {
        if (mContentResolver == null) {
            Log.d(TAG, "mContentResolver is null");
            return;
        }
        mContentResolver.unregisterContentObserver(mSettingsObserver);
    }

    private void stopLocalSourceReceivers() {
        if (DEBUG) {
            Log.d(TAG, "stopLocalSourceReceivers()");
        }
        for (BluetoothDevice device : mServiceBroadcastAssistant.getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState :
                    mServiceBroadcastAssistant.getAllSources(device)) {
                /* Check if local/last broadcast is the synced one */
                int localBroadcastId = getLatestBroadcastId();
                if (receiveState.getBroadcastId() != localBroadcastId) continue;

                mServiceBroadcastAssistant.removeSource(device, receiveState.getSourceId());
            }
        }
    }

    /** Update fallback active device if needed. */
    public void updateFallbackActiveDeviceIfNeeded() {
        if (mServiceBroadcastAssistant == null) {
            Log.d(TAG, "Skip updateFallbackActiveDeviceIfNeeded due to assistant profile is null");
            return;
        }
        List<BluetoothDevice> connectedDevices = mServiceBroadcastAssistant.getConnectedDevices();
        List<BluetoothDevice> devicesInSharing =
                connectedDevices.stream()
                        .filter(
                                bluetoothDevice -> {
                                    List<BluetoothLeBroadcastReceiveState> sourceList =
                                            mServiceBroadcastAssistant.getAllSources(
                                                    bluetoothDevice);
                                    return !sourceList.isEmpty();
                                })
                        .collect(Collectors.toList());
        if (devicesInSharing.isEmpty()) {
            Log.d(TAG, "Skip updateFallbackActiveDeviceIfNeeded due to no sinks in broadcast");
            return;
        }
        List<BluetoothDevice> devices =
                BluetoothAdapter.getDefaultAdapter().getMostRecentlyConnectedDevices();
        BluetoothDevice targetDevice = null;
        // Find the earliest connected device in sharing session.
        int targetDeviceIdx = -1;
        for (BluetoothDevice device : devicesInSharing) {
            if (devices.contains(device)) {
                int idx = devices.indexOf(device);
                if (idx > targetDeviceIdx) {
                    targetDeviceIdx = idx;
                    targetDevice = device;
                }
            }
        }
        if (targetDevice == null) {
            Log.d(TAG, "Skip updateFallbackActiveDeviceIfNeeded, target is null");
            return;
        }
        Log.d(
                TAG,
                "updateFallbackActiveDeviceIfNeeded, set active device: "
                        + targetDevice.getAnonymizedAddress());
        CachedBluetoothDevice targetCachedDevice = mDeviceManager.findDevice(targetDevice);
        if (targetCachedDevice == null) {
            Log.d(TAG, "Skip updateFallbackActiveDeviceIfNeeded, fail to find cached bt device");
            return;
        }
        int fallbackActiveGroupId = getFallbackActiveGroupId();
        if (getGroupId(targetCachedDevice) == fallbackActiveGroupId) {
            Log.d(
                    TAG,
                    "Skip updateFallbackActiveDeviceIfNeeded, already is fallback: "
                            + fallbackActiveGroupId);
            return;
        }
        targetCachedDevice.setActive();
    }

    private boolean isDecryptedSource(BluetoothLeBroadcastReceiveState state) {
        return state.getPaSyncState() == BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED
                && state.getBigEncryptionState()
                        == BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING;
    }

    private int getFallbackActiveGroupId() {
        return Settings.Secure.getInt(
                mContext.getContentResolver(),
                "bluetooth_le_broadcast_fallback_active_group_id",
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
    }

    private int getGroupId(CachedBluetoothDevice cachedDevice) {
        int groupId = cachedDevice.getGroupId();
        String anonymizedAddress = cachedDevice.getDevice().getAnonymizedAddress();
        if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
            Log.d(TAG, "getGroupId by CSIP profile for device: " + anonymizedAddress);
            return groupId;
        }
        for (LocalBluetoothProfile profile : cachedDevice.getProfiles()) {
            if (profile instanceof LeAudioProfile) {
                Log.d(TAG, "getGroupId by LEA profile for device: " + anonymizedAddress);
                return ((LeAudioProfile) profile).getGroupId(cachedDevice.getDevice());
            }
        }
        Log.d(TAG, "getGroupId return invalid id for device: " + anonymizedAddress);
        return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    private void notifyBroadcastStateChange(@BroadcastState int state) {
        if (!mContext.getPackageName().equals(SETTINGS_PKG)) {
            Log.d(TAG, "Skip notifyBroadcastStateChange, not triggered by Settings.");
            return;
        }
        Intent intent = new Intent(ACTION_LE_AUDIO_SHARING_STATE_CHANGE);
        intent.putExtra(EXTRA_LE_AUDIO_SHARING_STATE, state);
        intent.setPackage(mContext.getPackageName());
        mContext.sendBroadcast(intent);
    }
}
