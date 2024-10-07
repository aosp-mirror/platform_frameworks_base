/*
 * Copyright 2019 The Android Open Source Project
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

import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_DEFAULT;
import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET;
import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_WATCH;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_CARKIT;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_HEADPHONES;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_HEARING_AID;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_RECEIVER;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_SPEAKER;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_UNKNOWN;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_WATCH;
import static android.media.audio.Flags.automaticBtDeviceType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.AudioManager.AudioDeviceCategory;
import android.media.AudioSystem;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @hide
 * Class to encapsulate all communication with Bluetooth services
 */
public class BtHelper {

    private static final String TAG = "AS.BtHelper";

    private final @NonNull AudioDeviceBroker mDeviceBroker;
    private final @NonNull Context mContext;

    BtHelper(@NonNull AudioDeviceBroker broker, Context context) {
        mDeviceBroker = broker;
        mContext = context;
    }

    // BluetoothHeadset API to control SCO connection
    @GuardedBy("BtHelper.this")
    private @Nullable BluetoothHeadset mBluetoothHeadset;

    // Bluetooth headset device
    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private @Nullable BluetoothDevice mBluetoothHeadsetDevice;

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private final Map<BluetoothDevice, AudioDeviceAttributes> mResolvedScoAudioDevices =
            new HashMap<>();

    @GuardedBy("BtHelper.this")
    private @Nullable BluetoothHearingAid mHearingAid = null;

    @GuardedBy("BtHelper.this")
    private @Nullable BluetoothLeAudio mLeAudio = null;

    @GuardedBy("BtHelper.this")
    private @Nullable BluetoothLeAudioCodecConfig mLeAudioCodecConfig;

    // Reference to BluetoothA2dp to query for AbsoluteVolume.
    @GuardedBy("BtHelper.this")
    private @Nullable BluetoothA2dp mA2dp = null;

    @GuardedBy("BtHelper.this")
    private @Nullable BluetoothCodecConfig mA2dpCodecConfig;

    @GuardedBy("BtHelper.this")
    private @AudioSystem.AudioFormatNativeEnumForBtCodec
            int mLeAudioBroadcastCodec = AudioSystem.AUDIO_FORMAT_DEFAULT;

    // If absolute volume is supported in AVRCP device
    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private boolean mAvrcpAbsVolSupported = false;

    // Current connection state indicated by bluetooth headset
    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private int mScoConnectionState;

    // Indicate if SCO audio connection is currently active and if the initiator is
    // audio service (internal) or bluetooth headset (external)
    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private int mScoAudioState;

    // Indicates the mode used for SCO audio connection. The mode is virtual call if the request
    // originated from an app targeting an API version before JB MR2 and raw audio after that.
    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private int mScoAudioMode;

    // SCO audio state is not active
    private static final int SCO_STATE_INACTIVE = 0;
    // SCO audio activation request waiting for headset service to connect
    private static final int SCO_STATE_ACTIVATE_REQ = 1;
    // SCO audio state is active due to an action in BT handsfree (either voice recognition or
    // in call audio)
    private static final int SCO_STATE_ACTIVE_EXTERNAL = 2;
    // SCO audio state is active or starting due to a request from AudioManager API
    private static final int SCO_STATE_ACTIVE_INTERNAL = 3;
    // SCO audio deactivation request waiting for headset service to connect
    private static final int SCO_STATE_DEACTIVATE_REQ = 4;
    // SCO audio deactivation in progress, waiting for Bluetooth audio intent
    private static final int SCO_STATE_DEACTIVATING = 5;

    // SCO audio mode is undefined
    /*package*/  static final int SCO_MODE_UNDEFINED = -1;
    // SCO audio mode is virtual voice call (BluetoothHeadset.startScoUsingVirtualVoiceCall())
    /*package*/  static final int SCO_MODE_VIRTUAL_CALL = 0;
    // SCO audio mode is Voice Recognition (BluetoothHeadset.startVoiceRecognition())
    private static final int SCO_MODE_VR = 2;
    // max valid SCO audio mode values
    private static final int SCO_MODE_MAX = 2;

    private static final int BT_HEARING_AID_GAIN_MIN = -128;
    private static final int BT_LE_AUDIO_MAX_VOL = 255;

    // BtDevice constants currently rolling out under flag protection. Use own
    // constants instead to avoid mainline dependency from flag library import
    // TODO(b/335936458): remove once the BtDevice flag is rolled out
    private static final String DEVICE_TYPE_SPEAKER = "Speaker";
    private static final String DEVICE_TYPE_HEADSET = "Headset";
    private static final String DEVICE_TYPE_CARKIT = "Carkit";
    private static final String DEVICE_TYPE_HEARING_AID = "HearingAid";

    /**
     * Returns a string representation of the scoAudioMode.
     */
    public static String scoAudioModeToString(int scoAudioMode) {
        switch (scoAudioMode) {
            case SCO_MODE_UNDEFINED:
                return "SCO_MODE_UNDEFINED";
            case SCO_MODE_VIRTUAL_CALL:
                return "SCO_MODE_VIRTUAL_CALL";
            case SCO_MODE_VR:
                return "SCO_MODE_VR";
            default:
                return "SCO_MODE_(" + scoAudioMode + ")";
        }
    }

    /**
     * Returns a string representation of the scoAudioState.
     */
    public static String scoAudioStateToString(int scoAudioState) {
        switch (scoAudioState) {
            case SCO_STATE_INACTIVE:
                return "SCO_STATE_INACTIVE";
            case SCO_STATE_ACTIVATE_REQ:
                return "SCO_STATE_ACTIVATE_REQ";
            case SCO_STATE_ACTIVE_EXTERNAL:
                return "SCO_STATE_ACTIVE_EXTERNAL";
            case SCO_STATE_ACTIVE_INTERNAL:
                return "SCO_STATE_ACTIVE_INTERNAL";
            case SCO_STATE_DEACTIVATING:
                return "SCO_STATE_DEACTIVATING";
            default:
                return "SCO_STATE_(" + scoAudioState + ")";
        }
    }

    // A2DP device events
    /*package*/ static final int EVENT_DEVICE_CONFIG_CHANGE = 0;

    /*package*/ static String deviceEventToString(int event) {
        switch (event) {
            case EVENT_DEVICE_CONFIG_CHANGE: return "DEVICE_CONFIG_CHANGE";
            default:
                return new String("invalid event:" + event);
        }
    }

    /*package*/ @NonNull static String getName(@NonNull BluetoothDevice device) {
        final String deviceName = device.getName();
        if (deviceName == null) {
            return "";
        }
        return deviceName;
    }

    //----------------------------------------------------------------------
    // Interface for AudioDeviceBroker

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void onSystemReady() {
        mScoConnectionState = android.media.AudioManager.SCO_AUDIO_STATE_ERROR;
        resetBluetoothSco();
        getBluetoothHeadset();

        //FIXME: this is to maintain compatibility with deprecated intent
        // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
        Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
        newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        sendStickyBroadcastToAll(newIntent);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.A2DP);
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.A2DP_SINK);
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.HEARING_AID);
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.LE_AUDIO);
            adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.LE_AUDIO_BROADCAST);
        }
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ void setAvrcpAbsoluteVolumeSupported(boolean supported) {
        mAvrcpAbsVolSupported = supported;
        Log.i(TAG, "setAvrcpAbsoluteVolumeSupported supported=" + supported);
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void setAvrcpAbsoluteVolumeIndex(int index) {
        if (mA2dp == null) {
            if (AudioService.DEBUG_VOL) {
                AudioService.sVolumeLogger.enqueue(new EventLogger.StringEvent(
                        "setAvrcpAbsoluteVolumeIndex: bailing due to null mA2dp").printLog(TAG));
            }
            return;
        }
        if (!mAvrcpAbsVolSupported) {
            AudioService.sVolumeLogger.enqueue(new EventLogger.StringEvent(
                    "setAvrcpAbsoluteVolumeIndex: abs vol not supported ").printLog(TAG));
            return;
        }
        if (AudioService.DEBUG_VOL) {
            Log.i(TAG, "setAvrcpAbsoluteVolumeIndex index=" + index);
        }
        AudioService.sVolumeLogger.enqueue(new AudioServiceEvents.VolumeEvent(
                AudioServiceEvents.VolumeEvent.VOL_SET_AVRCP_VOL, index));
        try {
            mA2dp.setAvrcpAbsoluteVolume(index);
        } catch (Exception e) {
            Log.e(TAG, "Exception while changing abs volume", e);
        }
    }

    private synchronized Pair<Integer, Boolean> getCodec(
            @NonNull BluetoothDevice device, @AudioService.BtProfile int profile) {

        switch (profile) {
            case BluetoothProfile.A2DP: {
                boolean changed = mA2dpCodecConfig != null;
                if (mA2dp == null) {
                    mA2dpCodecConfig = null;
                    return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, changed);
                }
                BluetoothCodecStatus btCodecStatus = null;
                try {
                    btCodecStatus = mA2dp.getCodecStatus(device);
                } catch (Exception e) {
                    Log.e(TAG, "Exception while getting status of " + device, e);
                }
                if (btCodecStatus == null) {
                    Log.e(TAG, "getCodec, null A2DP codec status for device: " + device);
                    mA2dpCodecConfig = null;
                    return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, changed);
                }
                final BluetoothCodecConfig btCodecConfig = btCodecStatus.getCodecConfig();
                if (btCodecConfig == null) {
                    mA2dpCodecConfig = null;
                    return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, changed);
                }
                changed = !btCodecConfig.equals(mA2dpCodecConfig);
                mA2dpCodecConfig = btCodecConfig;
                return new Pair<>(AudioSystem.bluetoothA2dpCodecToAudioFormat(
                        btCodecConfig.getCodecType()), changed);
            }
            case BluetoothProfile.LE_AUDIO: {
                boolean changed = mLeAudioCodecConfig != null;
                if (mLeAudio == null) {
                    mLeAudioCodecConfig = null;
                    return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, changed);
                }
                BluetoothLeAudioCodecStatus btLeCodecStatus = null;
                int groupId = mLeAudio.getGroupId(device);
                try {
                    btLeCodecStatus = mLeAudio.getCodecStatus(groupId);
                } catch (Exception e) {
                    Log.e(TAG, "Exception while getting status of " + device, e);
                }
                if (btLeCodecStatus == null) {
                    Log.e(TAG, "getCodec, null LE codec status for device: " + device);
                    mLeAudioCodecConfig = null;
                    return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, changed);
                }
                BluetoothLeAudioCodecConfig btLeCodecConfig =
                        btLeCodecStatus.getOutputCodecConfig();
                if (btLeCodecConfig == null) {
                    mLeAudioCodecConfig = null;
                    return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, changed);
                }
                changed = !btLeCodecConfig.equals(mLeAudioCodecConfig);
                mLeAudioCodecConfig = btLeCodecConfig;
                return new Pair<>(AudioSystem.bluetoothLeCodecToAudioFormat(
                        btLeCodecConfig.getCodecType()), changed);
            }
            case BluetoothProfile.LE_AUDIO_BROADCAST: {
                // We assume LC3 for LE Audio broadcast codec as there is no API to get the codec
                // config on LE Broadcast profile proxy.
                boolean changed = mLeAudioBroadcastCodec != AudioSystem.AUDIO_FORMAT_LC3;
                mLeAudioBroadcastCodec = AudioSystem.AUDIO_FORMAT_LC3;
                return new Pair<>(mLeAudioBroadcastCodec, changed);
            }
            default:
                return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, false);
        }
    }

    /*package*/ synchronized Pair<Integer, Boolean>
                    getCodecWithFallback(@NonNull BluetoothDevice device,
                                         @AudioService.BtProfile int profile,
                                         boolean isLeOutput, @NonNull String source) {
        // For profiles other than A2DP and LE Audio output, the audio codec format must be
        // AUDIO_FORMAT_DEFAULT as native audio policy manager expects a specific audio format
        // only if audio HW module selection based on format is supported for the device type.
        if (!(profile == BluetoothProfile.A2DP
                || (isLeOutput && ((profile == BluetoothProfile.LE_AUDIO)
                        || (profile == BluetoothProfile.LE_AUDIO_BROADCAST))))) {
            return new Pair<>(AudioSystem.AUDIO_FORMAT_DEFAULT, false);
        }
        Pair<Integer, Boolean> codecAndChanged =
                getCodec(device, profile);
        if (codecAndChanged.first == AudioSystem.AUDIO_FORMAT_DEFAULT) {
            AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                    "getCodec DEFAULT from " + source + " fallback to "
                            + (profile == BluetoothProfile.A2DP ? "SBC" : "LC3")));
            return new Pair<>(profile == BluetoothProfile.A2DP
                    ? AudioSystem.AUDIO_FORMAT_SBC : AudioSystem.AUDIO_FORMAT_LC3, true);
        }

        return codecAndChanged;
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void onReceiveBtEvent(Intent intent) {
        final String action = intent.getAction();

        if (action.equals(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED)) {
            BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                    android.bluetooth.BluetoothDevice.class);
            if (btDevice != null && !isProfilePoxyConnected(BluetoothProfile.HEADSET)) {
                AudioService.sDeviceLogger.enqueue((new EventLogger.StringEvent(
                        "onReceiveBtEvent ACTION_ACTIVE_DEVICE_CHANGED "
                                + "received with null profile proxy for device: "
                                + btDevice)).printLog(TAG));
                return;
            }
            onSetBtScoActiveDevice(btDevice);
        } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
            int btState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            onScoAudioStateChanged(btState);
        }
    }

    /**
     * Exclusively called from AudioDeviceBroker (with mDeviceStateLock held)
     * when handling MSG_L_RECEIVED_BT_EVENT in {@link #onReceiveBtEvent(Intent)}
     * as part of the serialization of the communication route selection
     */
    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private synchronized void onScoAudioStateChanged(int state) {
        boolean broadcast = false;
        int scoAudioState = AudioManager.SCO_AUDIO_STATE_ERROR;
        Log.i(TAG, "onScoAudioStateChanged  state: " + state
                + ", mScoAudioState: " + mScoAudioState);
        switch (state) {
            case BluetoothHeadset.STATE_AUDIO_CONNECTED:
                scoAudioState = AudioManager.SCO_AUDIO_STATE_CONNECTED;
                if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL
                        && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
                    mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                } else if (mDeviceBroker.isBluetoothScoRequested()) {
                    // broadcast intent if the connection was initated by AudioService
                    broadcast = true;
                }
                if (!mDeviceBroker.isScoManagedByAudio()) {
                    mDeviceBroker.setBluetoothScoOn(
                            true, "BtHelper.onScoAudioStateChanged, state: " + state);
                }
                break;
            case BluetoothHeadset.STATE_AUDIO_DISCONNECTED:
                if (!mDeviceBroker.isScoManagedByAudio()) {
                    mDeviceBroker.setBluetoothScoOn(
                            false, "BtHelper.onScoAudioStateChanged, state: " + state);
                }
                scoAudioState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
                // There are two cases where we want to immediately reconnect audio:
                // 1) If a new start request was received while disconnecting: this was
                // notified by requestScoState() setting state to SCO_STATE_ACTIVATE_REQ.
                // 2) If audio was connected then disconnected via Bluetooth APIs and
                // we still have pending activation requests by apps: this is indicated by
                // state SCO_STATE_ACTIVE_EXTERNAL and BT SCO is requested.
                if (mScoAudioState == SCO_STATE_ACTIVATE_REQ) {
                    if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null
                            && connectBluetoothScoAudioHelper(mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode)) {
                        mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                        scoAudioState = AudioManager.SCO_AUDIO_STATE_CONNECTING;
                        broadcast = true;
                        break;
                    }
                }
                if (mScoAudioState != SCO_STATE_ACTIVE_EXTERNAL) {
                    broadcast = true;
                }
                mScoAudioState = SCO_STATE_INACTIVE;
                break;
            case BluetoothHeadset.STATE_AUDIO_CONNECTING:
                if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL
                        && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
                    mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                }
                break;
            default:
                break;
        }
        if (broadcast) {
            Log.i(TAG, "onScoAudioStateChanged  broadcasting state: " + scoAudioState);
            broadcastScoConnectionState(scoAudioState);
            //FIXME: this is to maintain compatibility with deprecated intent
            // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
            Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
            newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, scoAudioState);
            sendStickyBroadcastToAll(newIntent);
        }
    }
    /**
     *
     * @return false if SCO isn't connected
     */
    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized boolean isBluetoothScoOn() {
        if (mBluetoothHeadset == null || mBluetoothHeadsetDevice == null) {
            return false;
        }
        try {
            return mBluetoothHeadset.getAudioState(mBluetoothHeadsetDevice)
                    == BluetoothHeadset.STATE_AUDIO_CONNECTED;
        } catch (Exception e) {
            Log.e(TAG, "Exception while getting audio state of " + mBluetoothHeadsetDevice, e);
        }
        return false;
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ boolean isBluetoothScoRequestedInternally() {
        return mScoAudioState == SCO_STATE_ACTIVE_INTERNAL
              || mScoAudioState == SCO_STATE_ACTIVATE_REQ;
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized boolean startBluetoothSco(int scoAudioMode,
                @NonNull String eventSource) {
        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(eventSource));
        return requestScoState(BluetoothHeadset.STATE_AUDIO_CONNECTED, scoAudioMode);
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized boolean stopBluetoothSco(@NonNull String eventSource) {
        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(eventSource));
        return requestScoState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED, SCO_MODE_VIRTUAL_CALL);
    }

    /*package*/ synchronized void setLeAudioVolume(int index, int maxIndex, int streamType) {
        if (mLeAudio == null) {
            if (AudioService.DEBUG_VOL) {
                Log.i(TAG, "setLeAudioVolume: null mLeAudio");
            }
            return;
        }
        /* leaudio expect volume value in range 0 to 255 */
        int volume = (int) Math.round((double) index * BT_LE_AUDIO_MAX_VOL / maxIndex);

        if (AudioService.DEBUG_VOL) {
            Log.i(TAG, "setLeAudioVolume: calling mLeAudio.setVolume idx="
                    + index + " volume=" + volume);
        }
        AudioService.sVolumeLogger.enqueue(new AudioServiceEvents.VolumeEvent(
                AudioServiceEvents.VolumeEvent.VOL_SET_LE_AUDIO_VOL, streamType, index,
                maxIndex, /*caller=*/null));
        try {
            mLeAudio.setVolume(volume);
        } catch (Exception e) {
            Log.e(TAG, "Exception while setting LE volume", e);
        }
    }

    /*package*/ synchronized void setHearingAidVolume(int index, int streamType,
            boolean isHeadAidConnected) {
        if (mHearingAid == null) {
            if (AudioService.DEBUG_VOL) {
                Log.i(TAG, "setHearingAidVolume: null mHearingAid");
            }
            return;
        }
        //hearing aid expect volume value in range -128dB to 0dB
        int gainDB = (int) AudioSystem.getStreamVolumeDB(streamType, index / 10,
                AudioSystem.DEVICE_OUT_HEARING_AID);
        if (gainDB < BT_HEARING_AID_GAIN_MIN) {
            gainDB = BT_HEARING_AID_GAIN_MIN;
        }
        if (AudioService.DEBUG_VOL) {
            Log.i(TAG, "setHearingAidVolume: calling mHearingAid.setVolume idx="
                    + index + " gain=" + gainDB);
        }
        // do not log when hearing aid is not connected to avoid confusion when reading dumpsys
        if (isHeadAidConnected) {
            AudioService.sVolumeLogger.enqueue(new AudioServiceEvents.VolumeEvent(
                    AudioServiceEvents.VolumeEvent.VOL_SET_HEARING_AID_VOL, index, gainDB));
        }
        try {
            mHearingAid.setVolume(gainDB);
        } catch (Exception e) {
            Log.i(TAG, "Exception while setting hearing aid volume", e);
        }
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ void onBroadcastScoConnectionState(int state) {
        if (state == mScoConnectionState) {
            return;
        }
        Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, state);
        newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE,
                mScoConnectionState);
        sendStickyBroadcastToAll(newIntent);
        mScoConnectionState = state;
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ void resetBluetoothSco() {
        mScoAudioState = SCO_STATE_INACTIVE;
        broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        mDeviceBroker.clearA2dpSuspended(false /* internalOnly */);
        mDeviceBroker.clearLeAudioSuspended(false /* internalOnly */);
        mDeviceBroker.setBluetoothScoOn(false, "resetBluetoothSco");
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void onBtProfileDisconnected(int profile) {
        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                "BT profile " + BluetoothProfile.getProfileName(profile)
                + " disconnected").printLog(TAG));
        switch (profile) {
            case BluetoothProfile.HEADSET:
                mBluetoothHeadset = null;
                break;
            case BluetoothProfile.A2DP:
                mA2dp = null;
                mA2dpCodecConfig = null;
                break;
            case BluetoothProfile.HEARING_AID:
                mHearingAid = null;
                break;
            case BluetoothProfile.LE_AUDIO:
                if (mLeAudio != null && mLeAudioCallback != null) {
                    try {
                        mLeAudio.unregisterCallback(mLeAudioCallback);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception while unregistering callback for LE audio", e);
                    }
                }
                mLeAudio = null;
                mLeAudioCallback = null;
                mLeAudioCodecConfig = null;
                break;
            case BluetoothProfile.LE_AUDIO_BROADCAST:
                mLeAudioBroadcastCodec = AudioSystem.AUDIO_FORMAT_DEFAULT;
                break;
            case BluetoothProfile.A2DP_SINK:
                // nothing to do in BtHelper
                break;
            default:
                // Not a valid profile to disconnect
                Log.e(TAG, "onBtProfileDisconnected: Not a valid profile to disconnect "
                        + BluetoothProfile.getProfileName(profile));
                break;
        }
    }

    // BluetoothLeAudio callback used to update the list of addresses in the same group as a
    // connected LE Audio device
    class MyLeAudioCallback implements BluetoothLeAudio.Callback {
        @Override
        public void onCodecConfigChanged(int groupId,
                                  @NonNull BluetoothLeAudioCodecStatus status) {
            // Do nothing
        }

        @Override
        public void onGroupNodeAdded(@NonNull BluetoothDevice device, int groupId) {
            mDeviceBroker.postUpdateLeAudioGroupAddresses(groupId);
        }

        @Override
        public void onGroupNodeRemoved(@NonNull BluetoothDevice device, int groupId) {
            mDeviceBroker.postUpdateLeAudioGroupAddresses(groupId);
        }
        @Override
        public void onGroupStatusChanged(int groupId, int groupStatus) {
            mDeviceBroker.postUpdateLeAudioGroupAddresses(groupId);
        }
    }

    @GuardedBy("BtHelper.this")
    MyLeAudioCallback mLeAudioCallback = null;

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package*/ synchronized void onBtProfileConnected(int profile, BluetoothProfile proxy) {
        AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                "BT profile " + BluetoothProfile.getProfileName(profile) + " connected to proxy "
                + proxy).printLog(TAG));
        if (proxy == null) {
            Log.e(TAG, "onBtProfileConnected: null proxy for profile: " + profile);
            return;
        }
        switch (profile) {
            case BluetoothProfile.HEADSET:
                onHeadsetProfileConnected((BluetoothHeadset) proxy);
                return;
            case BluetoothProfile.A2DP:
                if (((BluetoothA2dp) proxy).equals(mA2dp)) {
                    return;
                }
                mA2dp = (BluetoothA2dp) proxy;
                break;
            case BluetoothProfile.HEARING_AID:
                if (((BluetoothHearingAid) proxy).equals(mHearingAid)) {
                    return;
                }
                mHearingAid = (BluetoothHearingAid) proxy;
                break;
            case BluetoothProfile.LE_AUDIO:
                if (((BluetoothLeAudio) proxy).equals(mLeAudio)) {
                    return;
                }
                if (mLeAudio != null && mLeAudioCallback != null) {
                    try {
                        mLeAudio.unregisterCallback(mLeAudioCallback);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception while unregistering callback for LE audio", e);
                    }
                }
                mLeAudio = (BluetoothLeAudio) proxy;
                mLeAudioCallback = new MyLeAudioCallback();
                try{
                    mLeAudio.registerCallback(
                                mContext.getMainExecutor(), mLeAudioCallback);
                } catch (Exception e) {
                    mLeAudioCallback = null;
                    Log.e(TAG, "Exception while registering callback for LE audio", e);
                }
                break;
            case BluetoothProfile.A2DP_SINK:
            case BluetoothProfile.LE_AUDIO_BROADCAST:
                // nothing to do in BtHelper
                return;
            default:
                // Not a valid profile to connect
                Log.e(TAG, "onBtProfileConnected: Not a valid profile to connect "
                        + BluetoothProfile.getProfileName(profile));
                return;
        }

        // this part is only for A2DP, LE Audio unicast and Hearing aid
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "onBtProfileConnected: Null BluetoothAdapter when connecting profile: "
                    + BluetoothProfile.getProfileName(profile));
            return;
        }
        List<BluetoothDevice> activeDevices = adapter.getActiveDevices(profile);
        if (activeDevices.isEmpty() || activeDevices.get(0) == null) {
            return;
        }
        BluetoothDevice device = activeDevices.get(0);
        switch (profile) {
            case BluetoothProfile.A2DP: {
                BluetoothProfileConnectionInfo bpci =
                        BluetoothProfileConnectionInfo.createA2dpInfo(false, -1);
                postBluetoothActiveDevice(device, bpci);
            } break;
            case BluetoothProfile.HEARING_AID: {
                BluetoothProfileConnectionInfo bpci =
                        BluetoothProfileConnectionInfo.createHearingAidInfo(false);
                postBluetoothActiveDevice(device, bpci);
            } break;
            case BluetoothProfile.LE_AUDIO: {
                int groupId = mLeAudio.getGroupId(device);
                BluetoothLeAudioCodecStatus btLeCodecStatus = null;
                try {
                    btLeCodecStatus = mLeAudio.getCodecStatus(groupId);
                } catch (Exception e) {
                    Log.e(TAG, "Exception while getting status of " + device, e);
                }
                if (btLeCodecStatus == null) {
                    Log.i(TAG, "onBtProfileConnected null LE codec status for groupId: "
                            + groupId + ", device: " + device);
                    break;
                }
                List<BluetoothLeAudioCodecConfig> outputCodecConfigs =
                        btLeCodecStatus.getOutputCodecSelectableCapabilities();
                if (!outputCodecConfigs.isEmpty()) {
                    BluetoothProfileConnectionInfo bpci =
                            BluetoothProfileConnectionInfo.createLeAudioInfo(
                                    false /*suppressNoisyIntent*/, true /*isLeOutput*/);
                    postBluetoothActiveDevice(device, bpci);
                }
                List<BluetoothLeAudioCodecConfig> inputCodecConfigs =
                        btLeCodecStatus.getInputCodecSelectableCapabilities();
                if (!inputCodecConfigs.isEmpty()) {
                    BluetoothProfileConnectionInfo bpci =
                            BluetoothProfileConnectionInfo.createLeAudioInfo(
                                    false /*suppressNoisyIntent*/, false /*isLeOutput*/);
                    postBluetoothActiveDevice(device, bpci);
                }
            } break;
            default:
                // Not a valid profile to connect
                Log.wtf(TAG, "Invalid profile! onBtProfileConnected");
                break;
        }
    }

    private void postBluetoothActiveDevice(
            BluetoothDevice device, BluetoothProfileConnectionInfo bpci) {
        AudioDeviceBroker.BtDeviceChangedData data = new AudioDeviceBroker.BtDeviceChangedData(
                device, null, bpci, "mBluetoothProfileServiceListener");
        AudioDeviceBroker.BtDeviceInfo info = mDeviceBroker.createBtDeviceInfo(
                data, device, BluetoothProfile.STATE_CONNECTED);
        mDeviceBroker.postBluetoothActiveDevice(info, 0 /* delay */);
    }

    /*package*/ synchronized boolean isProfilePoxyConnected(int profile) {
        switch (profile) {
            case BluetoothProfile.HEADSET:
                return mBluetoothHeadset != null;
            case BluetoothProfile.A2DP:
                return mA2dp != null;
            case BluetoothProfile.HEARING_AID:
                return mHearingAid != null;
            case BluetoothProfile.LE_AUDIO:
                return mLeAudio != null;
            case BluetoothProfile.A2DP_SINK:
            case BluetoothProfile.LE_AUDIO_BROADCAST:
            default:
                // return true for profiles that are not managed by the BtHelper because
                // the fact that the profile proxy is not connected does not affect
                // the device connection handling.
                return true;
        }
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private synchronized void onHeadsetProfileConnected(@NonNull BluetoothHeadset headset) {
        // Discard timeout message
        mDeviceBroker.handleCancelFailureToConnectToBtHeadsetService();
        mBluetoothHeadset = headset;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            List<BluetoothDevice> activeDevices =
                    adapter.getActiveDevices(BluetoothProfile.HEADSET);
            for (BluetoothDevice device : activeDevices) {
                if (device == null) {
                    continue;
                }
                onSetBtScoActiveDevice(device);
            }
        } else {
            Log.e(TAG, "onHeadsetProfileConnected: Null BluetoothAdapter");
        }

        // Refresh SCO audio state
        checkScoAudioState();
        if (mScoAudioState != SCO_STATE_ACTIVATE_REQ
                && mScoAudioState != SCO_STATE_DEACTIVATE_REQ) {
            return;
        }
        boolean status = false;
        if (mBluetoothHeadsetDevice != null) {
            switch (mScoAudioState) {
                case SCO_STATE_ACTIVATE_REQ:
                    status = connectBluetoothScoAudioHelper(
                            mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode);
                    if (status) {
                        mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                    }
                    break;
                case SCO_STATE_DEACTIVATE_REQ:
                    status = disconnectBluetoothScoAudioHelper(
                            mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode);
                    if (status) {
                        mScoAudioState = SCO_STATE_DEACTIVATING;
                    }
                    break;
            }
        }
        if (!status) {
            mScoAudioState = SCO_STATE_INACTIVE;
            broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        }
    }

    //----------------------------------------------------------------------
    private void broadcastScoConnectionState(int state) {
        mDeviceBroker.postBroadcastScoConnectionState(state);
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    @Nullable AudioDeviceAttributes getHeadsetAudioDevice() {
        if (mBluetoothHeadsetDevice == null) {
            return null;
        }
        return getHeadsetAudioDevice(mBluetoothHeadsetDevice);
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private @NonNull AudioDeviceAttributes getHeadsetAudioDevice(BluetoothDevice btDevice) {
        AudioDeviceAttributes deviceAttr = mResolvedScoAudioDevices.get(btDevice);
        if (deviceAttr != null) {
            // Returns the cached device attributes so that it is consistent as the previous one.
            return deviceAttr;
        }
        return btHeadsetDeviceToAudioDevice(btDevice);
    }

    private static AudioDeviceAttributes btHeadsetDeviceToAudioDevice(BluetoothDevice btDevice) {
        if (btDevice == null) {
            return new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO, "");
        }
        String address = btDevice.getAddress();
        String name = getName(btDevice);
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        BluetoothClass btClass = btDevice.getBluetoothClass();
        int nativeType = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO;
        if (btClass != null) {
            switch (btClass.getDeviceClass()) {
                case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                    nativeType = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET;
                    break;
                case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                    nativeType = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT;
                    break;
            }
        }
        if (AudioService.DEBUG_DEVICES) {
            Log.i(TAG, "btHeadsetDeviceToAudioDevice btDevice: " + btDevice
                    + " btClass: " + (btClass == null ? "Unknown" : btClass)
                    + " nativeType: " + nativeType + " address: " + address);
        }
        return new AudioDeviceAttributes(nativeType, address, name);
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private boolean handleBtScoActiveDeviceChange(BluetoothDevice btDevice, boolean isActive) {
        if (btDevice == null) {
            return true;
        }
        boolean result = false;
        AudioDeviceAttributes audioDevice = null; // Only used if isActive is true
        String address = btDevice.getAddress();
        String name = getName(btDevice);
        // Handle output device
        if (isActive) {
            audioDevice = btHeadsetDeviceToAudioDevice(btDevice);
            result = mDeviceBroker.handleDeviceConnection(
                    audioDevice, true /*connect*/, btDevice);
        } else {
            AudioDeviceAttributes ada = mResolvedScoAudioDevices.get(btDevice);
            if (ada != null) {
                result = mDeviceBroker.handleDeviceConnection(
                    ada, false /*connect*/, btDevice);
            } else {
                // Disconnect all possible audio device types if the disconnected device type is
                // unknown
                int[] outDeviceTypes = {
                    AudioSystem.DEVICE_OUT_BLUETOOTH_SCO,
                    AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET,
                    AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT
                };
                for (int outDeviceType : outDeviceTypes) {
                    result |= mDeviceBroker.handleDeviceConnection(new AudioDeviceAttributes(
                            outDeviceType, address, name), false /*connect*/, btDevice);
                }
            }
        }
        // Handle input device
        int inDevice = AudioSystem.DEVICE_IN_BLUETOOTH_SCO_HEADSET;
        // handleDeviceConnection() && result to make sure the method get executed
        result = mDeviceBroker.handleDeviceConnection(new AudioDeviceAttributes(
                        inDevice, address, name),
                isActive, btDevice) && result;
        if (result) {
            if (isActive) {
                mResolvedScoAudioDevices.put(btDevice, audioDevice);
            } else {
                mResolvedScoAudioDevices.remove(btDevice);
            }
        }
        return result;
    }

    // Return `(null)` if given BluetoothDevice is null. Otherwise, return the anonymized address.
    private String getAnonymizedAddress(BluetoothDevice btDevice) {
        return btDevice == null ? "(null)" : btDevice.getAnonymizedAddress();
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    /*package */ void onSetBtScoActiveDevice(BluetoothDevice btDevice) {
        Log.i(TAG, "onSetBtScoActiveDevice: " + getAnonymizedAddress(mBluetoothHeadsetDevice)
                + " -> " + getAnonymizedAddress(btDevice));
        final BluetoothDevice previousActiveDevice = mBluetoothHeadsetDevice;
        if (Objects.equals(btDevice, previousActiveDevice)) {
            return;
        }
        if (!handleBtScoActiveDeviceChange(previousActiveDevice, false)) {
            Log.w(TAG, "onSetBtScoActiveDevice() failed to remove previous device "
                    + getAnonymizedAddress(previousActiveDevice));
        }
        if (!handleBtScoActiveDeviceChange(btDevice, true)) {
            Log.e(TAG, "onSetBtScoActiveDevice() failed to add new device "
                    + getAnonymizedAddress(btDevice));
            // set mBluetoothHeadsetDevice to null when failing to add new device
            btDevice = null;
        }
        mBluetoothHeadsetDevice = btDevice;
        if (mBluetoothHeadsetDevice == null) {
            resetBluetoothSco();
        }
    }

    // NOTE this listener is NOT called from AudioDeviceBroker event thread, only call async
    //      methods inside listener.
    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
            new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    switch(profile) {
                        case BluetoothProfile.A2DP:
                        case BluetoothProfile.HEADSET:
                        case BluetoothProfile.HEARING_AID:
                        case BluetoothProfile.LE_AUDIO:
                        case BluetoothProfile.A2DP_SINK:
                        case BluetoothProfile.LE_AUDIO_BROADCAST:
                            AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                                    "BT profile service: connecting "
                                    + BluetoothProfile.getProfileName(profile)
                                    + " profile").printLog(TAG));
                            mDeviceBroker.postBtProfileConnected(profile, proxy);
                            break;

                        default:
                            break;
                    }
                }
                public void onServiceDisconnected(int profile) {

                    switch (profile) {
                        case BluetoothProfile.A2DP:
                        case BluetoothProfile.HEADSET:
                        case BluetoothProfile.HEARING_AID:
                        case BluetoothProfile.LE_AUDIO:
                        case BluetoothProfile.A2DP_SINK:
                        case BluetoothProfile.LE_AUDIO_BROADCAST:
                            AudioService.sDeviceLogger.enqueue(new EventLogger.StringEvent(
                                    "BT profile service: disconnecting "
                                        + BluetoothProfile.getProfileName(profile)
                                        + " profile").printLog(TAG));
                            mDeviceBroker.postBtProfileDisconnected(profile);
                            break;

                        default:
                            break;
                    }
                }
            };

    //----------------------------------------------------------------------

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private synchronized boolean requestScoState(int state, int scoAudioMode) {
        checkScoAudioState();
        if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
            // Make sure that the state transitions to CONNECTING even if we cannot initiate
            // the connection except if already connected internally
            if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL) {
                broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTING);
            }
            switch (mScoAudioState) {
                case SCO_STATE_INACTIVE:
                    mScoAudioMode = scoAudioMode;
                    if (scoAudioMode == SCO_MODE_UNDEFINED) {
                        mScoAudioMode = SCO_MODE_VIRTUAL_CALL;
                        if (mBluetoothHeadsetDevice != null) {
                            mScoAudioMode = Settings.Global.getInt(
                                    mDeviceBroker.getContentResolver(),
                                    "bluetooth_sco_channel_"
                                            + mBluetoothHeadsetDevice.getAddress(),
                                    SCO_MODE_VIRTUAL_CALL);
                            if (mScoAudioMode > SCO_MODE_MAX || mScoAudioMode < 0) {
                                mScoAudioMode = SCO_MODE_VIRTUAL_CALL;
                            }
                        }
                    }
                    if (mBluetoothHeadset == null) {
                        if (getBluetoothHeadset()) {
                            mScoAudioState = SCO_STATE_ACTIVATE_REQ;
                        } else {
                            Log.w(TAG, "requestScoState: getBluetoothHeadset failed during"
                                    + " connection, mScoAudioMode=" + mScoAudioMode);
                            broadcastScoConnectionState(
                                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                            return false;
                        }
                        break;
                    }
                    if (mBluetoothHeadsetDevice == null) {
                        Log.w(TAG, "requestScoState: no active device while connecting,"
                                + " mScoAudioMode=" + mScoAudioMode);
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                        return false;
                    }
                    if (connectBluetoothScoAudioHelper(mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode)) {
                        mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                    } else {
                        Log.w(TAG, "requestScoState: connect to "
                                + getAnonymizedAddress(mBluetoothHeadsetDevice)
                                + " failed, mScoAudioMode=" + mScoAudioMode);
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                        return false;
                    }
                    break;
                case SCO_STATE_DEACTIVATING:
                    mScoAudioState = SCO_STATE_ACTIVATE_REQ;
                    break;
                case SCO_STATE_DEACTIVATE_REQ:
                    mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTED);
                    break;
                case SCO_STATE_ACTIVE_INTERNAL:
                    // Already in ACTIVE mode, simply return
                    break;
                case SCO_STATE_ACTIVE_EXTERNAL:
                    /* Confirm SCO Audio connection to requesting app as it is already connected
                     * externally (i.e. through SCO APIs by Telecom service).
                     * Once SCO Audio is disconnected by the external owner, we will reconnect it
                     * automatically on behalf of the requesting app and the state will move to
                     * SCO_STATE_ACTIVE_INTERNAL.
                     */
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTED);
                    break;
                default:
                    Log.w(TAG, "requestScoState: failed to connect in state "
                            + mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    return false;
            }
        } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
            switch (mScoAudioState) {
                case SCO_STATE_ACTIVE_INTERNAL:
                    if (mBluetoothHeadset == null) {
                        if (getBluetoothHeadset()) {
                            mScoAudioState = SCO_STATE_DEACTIVATE_REQ;
                        } else {
                            Log.w(TAG, "requestScoState: getBluetoothHeadset failed during"
                                    + " disconnection, mScoAudioMode=" + mScoAudioMode);
                            mScoAudioState = SCO_STATE_INACTIVE;
                            broadcastScoConnectionState(
                                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                            return false;
                        }
                        break;
                    }
                    if (mBluetoothHeadsetDevice == null) {
                        mScoAudioState = SCO_STATE_INACTIVE;
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                        break;
                    }
                    if (disconnectBluetoothScoAudioHelper(mBluetoothHeadset,
                            mBluetoothHeadsetDevice, mScoAudioMode)) {
                        mScoAudioState = SCO_STATE_DEACTIVATING;
                    } else {
                        mScoAudioState = SCO_STATE_INACTIVE;
                        broadcastScoConnectionState(
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    }
                    break;
                case SCO_STATE_ACTIVATE_REQ:
                    mScoAudioState = SCO_STATE_INACTIVE;
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    break;
                default:
                    Log.w(TAG, "requestScoState: failed to disconnect in state "
                            + mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    return false;
            }
        }
        return true;
    }

    //-----------------------------------------------------
    // Utilities

    // suppress warning due to generic Intent passed as param
    @SuppressWarnings("AndroidFrameworkRequiresPermission")
    private void sendStickyBroadcastToAll(Intent intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final long ident = Binder.clearCallingIdentity();
        try {
            mDeviceBroker.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static boolean disconnectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset,
            BluetoothDevice device, int scoAudioMode) {
        switch (scoAudioMode) {
            case SCO_MODE_VIRTUAL_CALL:
                return bluetoothHeadset.stopScoUsingVirtualVoiceCall();
            case SCO_MODE_VR:
                return bluetoothHeadset.stopVoiceRecognition(device);
            default:
                return false;
        }
    }

    private static boolean connectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset,
            BluetoothDevice device, int scoAudioMode) {
        switch (scoAudioMode) {
            case SCO_MODE_VIRTUAL_CALL:
                return bluetoothHeadset.startScoUsingVirtualVoiceCall();
            case SCO_MODE_VR:
                return bluetoothHeadset.startVoiceRecognition(device);
            default:
                return false;
        }
    }

    @GuardedBy("mDeviceBroker.mDeviceStateLock")
    private synchronized void checkScoAudioState() {
        try {
            if (mBluetoothHeadset != null
                    && mBluetoothHeadsetDevice != null
                    && mScoAudioState == SCO_STATE_INACTIVE
                    && mBluetoothHeadset.getAudioState(mBluetoothHeadsetDevice)
                        != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while getting audio state of " + mBluetoothHeadsetDevice, e);
        }
    }

    private boolean getBluetoothHeadset() {
        boolean result = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            result = adapter.getProfileProxy(mDeviceBroker.getContext(),
                    mBluetoothProfileServiceListener, BluetoothProfile.HEADSET);
        }
        // If we could not get a bluetooth headset proxy, send a failure message
        // without delay to reset the SCO audio state and clear SCO clients.
        // If we could get a proxy, send a delayed failure message that will reset our state
        // in case we don't receive onServiceConnected().
        mDeviceBroker.handleFailureToConnectToBtHeadsetService(
                result ? AudioDeviceBroker.BT_HEADSET_CNCT_TIMEOUT_MS : 0);
        return result;
    }

    /*package*/ synchronized int getLeAudioDeviceGroupId(BluetoothDevice device) {
        if (mLeAudio == null || device == null) {
            return BluetoothLeAudio.GROUP_ID_INVALID;
        }
        return mLeAudio.getGroupId(device);
    }

    /**
     * Returns all addresses and identity addresses for LE Audio devices a group.
     * @param groupId The ID of the group from which to get addresses.
     * @return A List of Pair(String main_address, String identity_address). Note that the
     * addresses returned by BluetoothDevice can be null.
     */
    /*package*/ synchronized List<Pair<String, String>> getLeAudioGroupAddresses(int groupId) {
        List<Pair<String, String>> addresses = new ArrayList<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || mLeAudio == null) {
            return addresses;
        }
        List<BluetoothDevice> activeDevices = adapter.getActiveDevices(BluetoothProfile.LE_AUDIO);
        for (BluetoothDevice device : activeDevices) {
            if (device != null && mLeAudio.getGroupId(device) == groupId) {
                addresses.add(new Pair(device.getAddress(), device.getIdentityAddress()));
            }
        }
        return addresses;
    }

    /**
     * Returns the String equivalent of the btCodecType.
     *
     * This uses an "ENCODING_" prefix for consistency with Audio;
     * we could alternately use the "SOURCE_CODEC_TYPE_" prefix from Bluetooth.
     */
    public static String bluetoothCodecToEncodingString(int btCodecType) {
        switch (btCodecType) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
                return "ENCODING_SBC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
                return "ENCODING_AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
                return "ENCODING_APTX";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
                return "ENCODING_APTX_HD";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:
                return "ENCODING_LDAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS:
                return "ENCODING_OPUS";
            default:
                return "ENCODING_BT_CODEC_TYPE(" + btCodecType + ")";
        }
    }

    /*package */ static int getProfileFromType(int deviceType) {
        if (AudioSystem.isBluetoothA2dpOutDevice(deviceType)) {
            return BluetoothProfile.A2DP;
        } else if (AudioSystem.isBluetoothScoDevice(deviceType)) {
            return BluetoothProfile.HEADSET;
        } else if (AudioSystem.isBluetoothLeDevice(deviceType)) {
            return BluetoothProfile.LE_AUDIO;
        }
        return 0; // 0 is not a valid profile
    }

    /*package */ static Bundle getPreferredAudioProfiles(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter.getPreferredAudioProfiles(adapter.getRemoteDevice(address));
    }

    @Nullable
    /*package */ static BluetoothDevice getBluetoothDevice(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return null;
        }

        return adapter.getRemoteDevice(address);
    }

    @AudioDeviceCategory
    /*package*/ static int getBtDeviceCategory(String address) {
        if (!automaticBtDeviceType()) {
            return AUDIO_DEVICE_CATEGORY_UNKNOWN;
        }

        BluetoothDevice device = BtHelper.getBluetoothDevice(address);
        if (device == null) {
            return AUDIO_DEVICE_CATEGORY_UNKNOWN;
        }

        byte[] deviceType = device.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE);
        if (deviceType == null) {
            return AUDIO_DEVICE_CATEGORY_UNKNOWN;
        }
        String deviceCategory = new String(deviceType);
        switch (deviceCategory) {
            case DEVICE_TYPE_HEARING_AID:
                return AUDIO_DEVICE_CATEGORY_HEARING_AID;
            case DEVICE_TYPE_CARKIT:
                return AUDIO_DEVICE_CATEGORY_CARKIT;
            case DEVICE_TYPE_HEADSET:
            case DEVICE_TYPE_UNTETHERED_HEADSET:
                return AUDIO_DEVICE_CATEGORY_HEADPHONES;
            case DEVICE_TYPE_SPEAKER:
                return AUDIO_DEVICE_CATEGORY_SPEAKER;
            case DEVICE_TYPE_WATCH:
                return AUDIO_DEVICE_CATEGORY_WATCH;
            case DEVICE_TYPE_DEFAULT:
            default:
                // fall through
        }

        BluetoothClass deviceClass = device.getBluetoothClass();
        if (deviceClass == null) {
            return AUDIO_DEVICE_CATEGORY_UNKNOWN;
        }

        switch (deviceClass.getDeviceClass()) {
            case BluetoothClass.Device.WEARABLE_WRIST_WATCH:
                return AUDIO_DEVICE_CATEGORY_WATCH;
            case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER:
            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                return AUDIO_DEVICE_CATEGORY_SPEAKER;
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                return AUDIO_DEVICE_CATEGORY_HEADPHONES;
            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                return AUDIO_DEVICE_CATEGORY_RECEIVER;
            default:
                return AUDIO_DEVICE_CATEGORY_UNKNOWN;
        }
    }

    /**
     * Notifies Bluetooth framework that new preferred audio profiles for Bluetooth devices
     * have been applied.
     */
    public static void onNotifyPreferredAudioProfileApplied(BluetoothDevice btDevice) {
        BluetoothAdapter.getDefaultAdapter().notifyActiveDeviceChangeApplied(btDevice);
    }

    /**
     * Returns the string equivalent for the btDeviceClass class.
     */
    public static String btDeviceClassToString(int btDeviceClass) {
        switch (btDeviceClass) {
            case BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED:
                return "AUDIO_VIDEO_UNCATEGORIZED";
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                return "AUDIO_VIDEO_WEARABLE_HEADSET";
            case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                return "AUDIO_VIDEO_HANDSFREE";
            case 0x040C:
                return "AUDIO_VIDEO_RESERVED_0x040C"; // uncommon
            case BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE:
                return "AUDIO_VIDEO_MICROPHONE";
            case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
                return "AUDIO_VIDEO_LOUDSPEAKER";
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                return "AUDIO_VIDEO_HEADPHONES";
            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                return "AUDIO_VIDEO_PORTABLE_AUDIO";
            case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                return "AUDIO_VIDEO_CAR_AUDIO";
            case BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX:
                return "AUDIO_VIDEO_SET_TOP_BOX";
            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                return "AUDIO_VIDEO_HIFI_AUDIO";
            case BluetoothClass.Device.AUDIO_VIDEO_VCR:
                return "AUDIO_VIDEO_VCR";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA:
                return "AUDIO_VIDEO_VIDEO_CAMERA";
            case BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER:
                return "AUDIO_VIDEO_CAMCORDER";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR:
                return "AUDIO_VIDEO_VIDEO_MONITOR";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER:
                return "AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING:
                return "AUDIO_VIDEO_VIDEO_CONFERENCING";
            case 0x0444:
                return "AUDIO_VIDEO_RESERVED_0x0444"; // uncommon
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY:
                return "AUDIO_VIDEO_VIDEO_GAMING_TOY";
            default: // other device classes printed as a hex string.
                return TextUtils.formatSimple("0x%04x", btDeviceClass);
        }
    }

    //------------------------------------------------------------
    /*package*/ void dump(PrintWriter pw, String prefix) {
        pw.println("\n" + prefix + "mBluetoothHeadset: " + mBluetoothHeadset);
        pw.println(prefix + "mBluetoothHeadsetDevice: " + mBluetoothHeadsetDevice);
        if (mBluetoothHeadsetDevice != null) {
            final BluetoothClass bluetoothClass = mBluetoothHeadsetDevice.getBluetoothClass();
            if (bluetoothClass != null) {
                pw.println(prefix + "mBluetoothHeadsetDevice.DeviceClass: "
                        + btDeviceClassToString(bluetoothClass.getDeviceClass()));
            }
        }
        pw.println(prefix + "mScoAudioState: " + scoAudioStateToString(mScoAudioState));
        pw.println(prefix + "mScoAudioMode: " + scoAudioModeToString(mScoAudioMode));
        pw.println("\n" + prefix + "mHearingAid: " + mHearingAid);
        pw.println("\n" + prefix + "mLeAudio: " + mLeAudio);
        pw.println(prefix + "mA2dp: " + mA2dp);
        pw.println(prefix + "mAvrcpAbsVolSupported: " + mAvrcpAbsVolSupported);
    }

}
