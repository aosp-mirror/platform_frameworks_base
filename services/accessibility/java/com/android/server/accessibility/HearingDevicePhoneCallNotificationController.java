/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.accessibility;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A controller class to handle notification for hearing device during phone calls.
 */
public class HearingDevicePhoneCallNotificationController {

    private final TelephonyManager mTelephonyManager;
    private final TelephonyCallback mTelephonyListener;
    private final Executor mCallbackExecutor;

    public HearingDevicePhoneCallNotificationController(@NonNull Context context) {
        mTelephonyListener = new CallStateListener(context);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mCallbackExecutor = Executors.newSingleThreadExecutor();
    }

    @VisibleForTesting
    HearingDevicePhoneCallNotificationController(@NonNull Context context,
            TelephonyCallback telephonyCallback) {
        mTelephonyListener = telephonyCallback;
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mCallbackExecutor = context.getMainExecutor();
    }

    /**
     * Registers a telephony callback to listen for call state changed to handle notification for
     * hearing device during phone calls.
     */
    public void startListenForCallState() {
        mTelephonyManager.registerTelephonyCallback(mCallbackExecutor, mTelephonyListener);
    }

    /**
     * A telephony callback listener to listen to call state changes and show/dismiss notification
     */
    @VisibleForTesting
    static class CallStateListener extends TelephonyCallback implements
            TelephonyCallback.CallStateListener {

        private static final String TAG =
                "HearingDevice_CallStateListener";
        private static final String ACTION_SWITCH_TO_BUILTIN_MIC =
                "com.android.server.accessibility.hearingdevice.action.SWITCH_TO_BUILTIN_MIC";
        private static final String ACTION_SWITCH_TO_HEARING_MIC =
                "com.android.server.accessibility.hearingdevice.action.SWITCH_TO_HEARING_MIC";
        private static final String ACTION_BLUETOOTH_DEVICE_DETAILS =
                "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS";
        private static final String KEY_BLUETOOTH_ADDRESS = "device_address";
        private static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
        private static final int MICROPHONE_SOURCE_VOICE_COMMUNICATION =
                MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        private static final AudioDeviceAttributes BUILTIN_MIC = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_INPUT, AudioDeviceInfo.TYPE_BUILTIN_MIC, "");

        private final Context mContext;
        private NotificationManager mNotificationManager;
        private AudioManager mAudioManager;
        private BroadcastReceiver mHearingDeviceActionReceiver;
        private BluetoothDevice mHearingDevice;
        private boolean mIsNotificationShown = false;

        CallStateListener(@NonNull Context context) {
            mContext = context;
        }

        @Override
        @SuppressLint("AndroidFrameworkRequiresPermission")
        public void onCallStateChanged(int state) {
            // NotificationManagerService and AudioService are all initialized after
            // AccessibilityManagerService.
            // Can not get them in constructor. Need to get these services until callback is
            // triggered.
            mNotificationManager = mContext.getSystemService(NotificationManager.class);
            mAudioManager = mContext.getSystemService(AudioManager.class);
            if (mNotificationManager == null || mAudioManager == null) {
                Log.w(TAG, "NotificationManager or AudioManager is not prepare yet.");
                return;
            }

            if (state == TelephonyManager.CALL_STATE_IDLE) {
                dismissNotificationIfNeeded();

                if (mHearingDevice != null) {
                    // reset to its original status
                    setMicrophonePreferredForCalls(mHearingDevice.isMicrophonePreferredForCalls());
                }
                mHearingDevice = null;
            }
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                mHearingDevice = getSupportedInputHearingDeviceInfo(
                        mAudioManager.getAvailableCommunicationDevices());
                if (mHearingDevice != null) {
                    showNotificationIfNeeded();
                }
            }
        }

        private void showNotificationIfNeeded() {
            if (mIsNotificationShown) {
                return;
            }

            showNotification(mHearingDevice.isMicrophonePreferredForCalls());
            mIsNotificationShown = true;
        }

        private void dismissNotificationIfNeeded() {
            if (!mIsNotificationShown) {
                return;
            }

            dismissNotification();
            mIsNotificationShown = false;
        }

        private void showNotification(boolean useRemoteMicrophone) {
            mNotificationManager.notify(
                    SystemMessageProto.SystemMessage.NOTE_HEARING_DEVICE_INPUT_SWITCH,
                    createSwitchInputNotification(useRemoteMicrophone));
            registerReceiverIfNeeded();
        }

        private void dismissNotification() {
            unregisterReceiverIfNeeded();
            mNotificationManager.cancel(
                    SystemMessageProto.SystemMessage.NOTE_HEARING_DEVICE_INPUT_SWITCH);
        }

        private BluetoothDevice getSupportedInputHearingDeviceInfo(List<AudioDeviceInfo> infoList) {
            final BluetoothAdapter bluetoothAdapter = mContext.getSystemService(
                    BluetoothManager.class).getAdapter();
            if (bluetoothAdapter == null) {
                return null;
            }
            if (!isHapClientSupported()) {
                return null;
            }

            final Set<String> inputDeviceAddress = Arrays.stream(
                    mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).map(
                    AudioDeviceInfo::getAddress).collect(Collectors.toSet());

            //TODO: b/370812132 - Need to update if TYPE_LEA_HEARING_AID is added
            final AudioDeviceInfo hearingDeviceInfo = infoList.stream()
                    .filter(info -> info.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET)
                    .filter(info -> inputDeviceAddress.contains(info.getAddress()))
                    .filter(info -> isHapClientDevice(bluetoothAdapter, info))
                    .findAny()
                    .orElse(null);

            return (hearingDeviceInfo != null) ? bluetoothAdapter.getRemoteDevice(
                    hearingDeviceInfo.getAddress()) : null;
        }

        @VisibleForTesting
        boolean isHapClientDevice(BluetoothAdapter bluetoothAdapter, AudioDeviceInfo info) {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(info.getAddress());
            return ArrayUtils.contains(device.getUuids(), BluetoothUuid.HAS);
        }

        @VisibleForTesting
        boolean isHapClientSupported() {
            return BluetoothAdapter.getDefaultAdapter().getSupportedProfiles().contains(
                    BluetoothProfile.HAP_CLIENT);
        }

        private Notification createSwitchInputNotification(boolean useRemoteMicrophone) {
            final CharSequence message = getSwitchInputMessage(useRemoteMicrophone);
            return new Notification.Builder(mContext,
                    SystemNotificationChannels.ACCESSIBILITY_HEARING_DEVICE)
                    .setContentTitle(getSwitchInputTitle(useRemoteMicrophone))
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_settings_24dp)
                    .setColor(mContext.getResources().getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setLocalOnly(true)
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .setContentIntent(createPendingIntent(ACTION_BLUETOOTH_DEVICE_DETAILS))
                    .setActions(buildSwitchInputAction(useRemoteMicrophone),
                            buildOpenSettingsAction())
                    .build();
        }

        private Notification.Action buildSwitchInputAction(boolean useRemoteMicrophone) {
            return useRemoteMicrophone
                    ? new Notification.Action.Builder(null,
                            mContext.getString(R.string.hearing_device_notification_switch_button),
                            createPendingIntent(ACTION_SWITCH_TO_BUILTIN_MIC)).build()
                    : new Notification.Action.Builder(null,
                            mContext.getString(R.string.hearing_device_notification_switch_button),
                            createPendingIntent(ACTION_SWITCH_TO_HEARING_MIC)).build();
        }

        private Notification.Action buildOpenSettingsAction() {
            return new Notification.Action.Builder(null,
                    mContext.getString(R.string.hearing_device_notification_settings_button),
                    createPendingIntent(ACTION_BLUETOOTH_DEVICE_DETAILS)).build();
        }

        private PendingIntent createPendingIntent(String action) {
            final Intent intent = new Intent(action);

            switch (action) {
                case ACTION_SWITCH_TO_BUILTIN_MIC, ACTION_SWITCH_TO_HEARING_MIC -> {
                    intent.setPackage(mContext.getPackageName());
                    return PendingIntent.getBroadcast(mContext, /* requestCode = */ 0, intent,
                            PendingIntent.FLAG_IMMUTABLE);
                }
                case ACTION_BLUETOOTH_DEVICE_DETAILS -> {
                    Bundle bundle = new Bundle();
                    bundle.putString(KEY_BLUETOOTH_ADDRESS, mHearingDevice.getAddress());
                    intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle);
                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    return PendingIntent.getActivity(mContext, /* requestCode = */ 0, intent,
                            PendingIntent.FLAG_IMMUTABLE);
                }
            }
            return null;
        }

        private void setMicrophonePreferredForCalls(boolean useRemoteMicrophone) {
            if (useRemoteMicrophone) {
                switchToHearingMic();
            } else {
                switchToBuiltinMic();
            }
        }

        @SuppressLint("AndroidFrameworkRequiresPermission")
        private void switchToBuiltinMic() {
            mAudioManager.clearPreferredDevicesForCapturePreset(
                    MICROPHONE_SOURCE_VOICE_COMMUNICATION);
            mAudioManager.setPreferredDeviceForCapturePreset(MICROPHONE_SOURCE_VOICE_COMMUNICATION,
                    BUILTIN_MIC);
        }

        @SuppressLint("AndroidFrameworkRequiresPermission")
        private void switchToHearingMic() {
            // clear config to let audio manager to determine next priority device. We can assume
            // user connects to hearing device here, so next priority device should be hearing
            // device.
            mAudioManager.clearPreferredDevicesForCapturePreset(
                    MICROPHONE_SOURCE_VOICE_COMMUNICATION);
        }

        private void registerReceiverIfNeeded() {
            if (mHearingDeviceActionReceiver != null) {
                return;
            }
            mHearingDeviceActionReceiver = new HearingDeviceActionReceiver();
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_SWITCH_TO_BUILTIN_MIC);
            intentFilter.addAction(ACTION_SWITCH_TO_HEARING_MIC);
            mContext.registerReceiver(mHearingDeviceActionReceiver, intentFilter,
                    Manifest.permission.MANAGE_ACCESSIBILITY, null, Context.RECEIVER_NOT_EXPORTED);
        }

        private void unregisterReceiverIfNeeded() {
            if (mHearingDeviceActionReceiver == null) {
                return;
            }
            mContext.unregisterReceiver(mHearingDeviceActionReceiver);
            mHearingDeviceActionReceiver = null;
        }

        private CharSequence getSwitchInputTitle(boolean useRemoteMicrophone) {
            return useRemoteMicrophone
                    ? mContext.getString(
                            R.string.hearing_device_switch_phone_mic_notification_title)
                    : mContext.getString(
                            R.string.hearing_device_switch_hearing_mic_notification_title);
        }

        private CharSequence getSwitchInputMessage(boolean useRemoteMicrophone) {
            return useRemoteMicrophone
                    ? mContext.getString(
                            R.string.hearing_device_switch_phone_mic_notification_text)
                    : mContext.getString(
                            R.string.hearing_device_switch_hearing_mic_notification_text);
        }

        private class HearingDeviceActionReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (TextUtils.isEmpty(action)) {
                    return;
                }

                if (ACTION_SWITCH_TO_BUILTIN_MIC.equals(action)) {
                    switchToBuiltinMic();
                    showNotification(/* useRemoteMicrophone= */ false);
                } else if (ACTION_SWITCH_TO_HEARING_MIC.equals(action)) {
                    switchToHearingMic();
                    showNotification(/* useRemoteMicrophone= */ true);
                }
            }
        }
    }
}
