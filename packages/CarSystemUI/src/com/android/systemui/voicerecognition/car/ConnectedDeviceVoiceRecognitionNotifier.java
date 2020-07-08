/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.voicerecognition.car;

import android.bluetooth.BluetoothHeadsetClient;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysUIToast;
import com.android.systemui.SystemUI;

import javax.inject.Inject;

/**
 * Controller responsible for showing toast message when voice recognition over bluetooth device
 * getting activated.
 */
public class ConnectedDeviceVoiceRecognitionNotifier extends SystemUI {

    private static final String TAG = "CarVoiceRecognition";
    private static final int INVALID_VALUE = -1;
    private static final int VOICE_RECOGNITION_STARTED = 1;

    private Handler mHandler;

    private final BroadcastReceiver mVoiceRecognitionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Voice recognition received an intent!");
            }
            if (intent == null
                    || intent.getAction() == null
                    || !BluetoothHeadsetClient.ACTION_AG_EVENT.equals(intent.getAction())
                    || !intent.hasExtra(BluetoothHeadsetClient.EXTRA_VOICE_RECOGNITION)) {
                return;
            }

            int voiceRecognitionState = intent.getIntExtra(
                    BluetoothHeadsetClient.EXTRA_VOICE_RECOGNITION, INVALID_VALUE);

            if (voiceRecognitionState == VOICE_RECOGNITION_STARTED) {
                mHandler.post(() -> {
                    SysUIToast.makeText(mContext, R.string.voice_recognition_toast,
                            Toast.LENGTH_LONG).show();
                });
            }
        }
    };

    @Inject
    public ConnectedDeviceVoiceRecognitionNotifier() {
        super();
        mHandler = Dependency.get(Dependency.MAIN_HANDLER);
    }

    @Override
    public void start() {
    }

    @Override
    protected void onBootCompleted() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadsetClient.ACTION_AG_EVENT);
        mContext.registerReceiverAsUser(mVoiceRecognitionReceiver, UserHandle.ALL, filter,
                /* broadcastPermission= */ null, /* scheduler= */ null);
    }
}
