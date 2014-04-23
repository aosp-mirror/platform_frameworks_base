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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * Manages HDMI-CEC command and behaviors. It converts user's command into CEC command
 * and pass it to CEC HAL so that it sends message to other device. For incoming
 * message it translates the message and delegates it to proper module.
 *
 * <p>It can be created only by {@link HdmiCecController#create}
 *
 * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
 */
class HdmiCecController {
    private static final String TAG = "HdmiCecController";

    // Handler instance to process synchronous I/O (mainly send) message.
    private Handler mIoHandler;

    // Handler instance to process various messages coming from other CEC
    // device or issued by internal state change.
    private Handler mMessageHandler;

    // Stores the pointer to the native implementation of the service that
    // interacts with HAL.
    private long mNativePtr;

    // Private constructor.  Use HdmiCecController.create().
    private HdmiCecController() {
    }

    /**
     * A factory method to get {@link HdmiCecController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     *
     * @param ioLooper a Looper instance to handle IO (mainly send message) operation.
     * @param messageHandler a message handler that processes a message coming from other
     *                       CEC compatible device or callback of internal state change.
     * @return {@link HdmiCecController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiCecController create(Looper ioLooper, Handler messageHandler) {
        HdmiCecController handler = new HdmiCecController();
        long nativePtr = nativeInit(handler);
        if (nativePtr == 0L) {
            handler = null;
            return null;
        }

        handler.init(ioLooper, messageHandler, nativePtr);
        return handler;
    }

    private void init(Looper ioLooper, Handler messageHandler, long nativePtr) {
        mIoHandler = new Handler(ioLooper) {
                @Override
            public void handleMessage(Message msg) {
                // TODO: Call native sendMessage.
            }
        };

        mMessageHandler = messageHandler;
        mNativePtr = nativePtr;
    }

    /**
     * Called by native when an HDMI-CEC message arrived.
     */
    private void handleMessage(int srcAddress, int dstAddres, int opcode, byte[] params) {
        // TODO: Translate message and delegate it to main message handler.
    }

    private static native long nativeInit(HdmiCecController handler);
}
