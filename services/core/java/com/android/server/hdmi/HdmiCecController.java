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

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    // A message to pass cec send command to IO looper.
    private static final int MSG_SEND_CEC_COMMAND = 1;

    // Message types to handle incoming message in main service looper.
    private final static int MSG_RECEIVE_CEC_COMMAND = 1;

    // TODO: move these values to HdmiCec.java once make it internal constant class.
    // CEC's ABORT reason values.
    private static final int ABORT_UNRECOGNIZED_MODE = 0;
    private static final int ABORT_NOT_IN_CORRECT_MODE = 1;
    private static final int ABORT_CANNOT_PROVIDE_SOURCE = 2;
    private static final int ABORT_INVALID_OPERAND = 3;
    private static final int ABORT_REFUSED = 4;
    private static final int ABORT_UNABLE_TO_DETERMINE = 5;

    // Handler instance to process synchronous I/O (mainly send) message.
    private Handler mIoHandler;

    // Handler instance to process various messages coming from other CEC
    // device or issued by internal state change.
    private Handler mControlHandler;

    // Stores the pointer to the native implementation of the service that
    // interacts with HAL.
    private long mNativePtr;

    // Map-like container of all cec devices. A logical address of device is
    // used as key of container.
    private final SparseArray<HdmiCecDeviceInfo> mDeviceInfos =
            new SparseArray<HdmiCecDeviceInfo>();

    // Private constructor.  Use HdmiCecController.create().
    private HdmiCecController() {
    }

    /**
     * A factory method to get {@link HdmiCecController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     * @param service {@link HdmiControlService} instance used to create internal handler
     *                and to pass callback for incoming message or event.
     * @return {@link HdmiCecController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiCecController create(HdmiControlService service) {
        HdmiCecController handler = new HdmiCecController();
        long nativePtr = nativeInit(handler);
        if (nativePtr == 0L) {
            handler = null;
            return null;
        }

        handler.init(service, nativePtr);
        return handler;
    }

    private static byte[] buildBody(int opcode, byte[] params) {
        byte[] body = new byte[params.length + 1];
        body[0] = (byte) opcode;
        System.arraycopy(params, 0, body, 1, params.length);
        return body;
    }

    private final class IoHandler extends Handler {
        private IoHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SEND_CEC_COMMAND:
                    HdmiCecMessage cecMessage = (HdmiCecMessage) msg.obj;
                    byte[] body = buildBody(cecMessage.getOpcode(), cecMessage.getParams());
                    nativeSendCecCommand(mNativePtr, cecMessage.getSource(),
                            cecMessage.getDestination(), body);
                    break;
                default:
                    Slog.w(TAG, "Unsupported CEC Io request:" + msg.what);
                    break;
            }
        }
    }

    private final class ControlHandler extends Handler {
        private ControlHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RECEIVE_CEC_COMMAND:
                    // TODO: delegate it to HdmiControl service.
                    onReceiveCommand((HdmiCecMessage) msg.obj);
                    break;
                default:
                    Slog.i(TAG, "Unsupported message type:" + msg.what);
                    break;
            }
        }
    }

    /**
     * Add a new {@link HdmiCecDeviceInfo}. It returns old device info which has the same
     * logical address as new device info's.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param deviceInfo a new {@link HdmiCecDeviceInfo} to be added.
     * @return {@code null} if it is new device. Otherwise, returns old {@HdmiCecDeviceInfo}
     *         that has the same logical address as new one has.
     */
    HdmiCecDeviceInfo addDeviceInfo(HdmiCecDeviceInfo deviceInfo) {
        HdmiCecDeviceInfo oldDeviceInfo = getDeviceInfo(deviceInfo.getLogicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getLogicalAddress());
        }
        mDeviceInfos.append(deviceInfo.getLogicalAddress(), deviceInfo);
        return oldDeviceInfo;
    }

    /**
     * Remove a device info corresponding to the given {@code logicalAddress}.
     * It returns removed {@link HdmiCecDeviceInfo} if exists.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param logicalAddress logical address of device to be removed
     * @return removed {@link HdmiCecDeviceInfo} it exists. Otherwise, returns {@code null}
     */
    HdmiCecDeviceInfo removeDeviceInfo(int logicalAddress) {
        HdmiCecDeviceInfo deviceInfo = mDeviceInfos.get(logicalAddress);
        if (deviceInfo != null) {
            mDeviceInfos.remove(logicalAddress);
        }
        return deviceInfo;
    }

    /**
     * Return a list of all {@HdmiCecDeviceInfo}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    List<HdmiCecDeviceInfo> getDeviceInfoList() {
        List<HdmiCecDeviceInfo> deviceInfoList = new ArrayList<HdmiCecDeviceInfo>(
                mDeviceInfos.size());
        for (int i = 0; i < mDeviceInfos.size(); ++i) {
            deviceInfoList.add(mDeviceInfos.valueAt(i));
        }
        return deviceInfoList;
    }

    /**
     * Return a {@link HdmiCecDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiCecDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    HdmiCecDeviceInfo getDeviceInfo(int logicalAddress) {
        return mDeviceInfos.get(logicalAddress);
    }

    private void init(HdmiControlService service, long nativePtr) {
        mIoHandler = new IoHandler(service.getServiceLooper());
        mControlHandler = new ControlHandler(service.getServiceLooper());
        mNativePtr = nativePtr;
    }

    private void onReceiveCommand(HdmiCecMessage message) {
        // TODO: Handle message according to opcode type.

        // TODO: Use device's source address for broadcast message.
        int sourceAddress = message.getDestination() != HdmiCec.ADDR_BROADCAST ?
                message.getDestination() : 0;
        // Reply <Feature Abort> to initiator (source) for all requests.
        sendFeatureAbort(sourceAddress, message.getSource(), message.getOpcode(),
                ABORT_REFUSED);
    }

    private void sendFeatureAbort(int srcAddress, int destAddress, int originalOpcode,
            int reason) {
        byte[] params = new byte[2];
        params[0] = (byte) originalOpcode;
        params[1] = (byte) reason;

        HdmiCecMessage cecMessage = new HdmiCecMessage(srcAddress, destAddress,
                HdmiCec.MESSAGE_FEATURE_ABORT, params);
        Message message = mIoHandler.obtainMessage(MSG_SEND_CEC_COMMAND, cecMessage);
        mIoHandler.sendMessage(message);
    }

    /**
     * Called by native when incoming CEC message arrived.
     */
    private void handleIncomingCecCommand(int srcAddress, int dstAddress, byte[] body) {
        byte opcode = body[0];
        byte params[] = Arrays.copyOfRange(body, 1, body.length);
        HdmiCecMessage cecMessage = new HdmiCecMessage(srcAddress, dstAddress, opcode, params);

        // Delegate message to main handler so that it handles in main thread.
        Message message = mControlHandler.obtainMessage(
                MSG_RECEIVE_CEC_COMMAND, cecMessage);
        mControlHandler.sendMessage(message);
    }

    /**
     * Called by native when a hotplug event issues.
     */
    private void handleHotplug(boolean connected) {
        // TODO: Delegate event to main message handler.
    }

    private static native long nativeInit(HdmiCecController handler);
    private static native int nativeSendCecCommand(long contollerPtr, int srcAddress,
            int dstAddress, byte[] body);
}
