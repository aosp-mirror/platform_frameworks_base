/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Slog;

import com.android.server.SystemService;

/**
 * Provides a service for sending and processing HDMI control messages,
 * HDMI-CEC and MHL control command, and providing the information on both standard.
 */
public final class HdmiControlService extends SystemService {
    private static final String TAG = "HdmiControlService";

    // A thread to handle synchronous IO of CEC and MHL control service.
    // Since all of CEC and MHL HAL interfaces processed in short time (< 200ms)
    // and sparse call it shares a thread to handle IO operations.
    private final HandlerThread mIoThread = new HandlerThread("Hdmi Control Io Thread");

    // Main handler class to handle incoming message from each controller.
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // TODO: Add handler for each message type.
        }
    };

    @Nullable
    private HdmiCecController mCecController;

    @Nullable
    private HdmiMhlController mMhlController;

    public HdmiControlService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mCecController = HdmiCecController.create(mIoThread.getLooper(), mHandler);
        if (mCecController == null) {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
        }

        mMhlController = HdmiMhlController.create(mIoThread.getLooper(), mHandler);
        if (mMhlController == null) {
            Slog.i(TAG, "Device does not support MHL-control.");
        }
    }
}
