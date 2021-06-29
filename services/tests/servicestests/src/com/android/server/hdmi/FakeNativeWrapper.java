/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.HdmiCecController.NativeWrapper;

import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fake {@link NativeWrapper} useful for testing. */
final class FakeNativeWrapper implements NativeWrapper {
    private static final String TAG = "FakeNativeWrapper";

    private final int[] mPollAddressResponse =
            new int[] {
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
            };

    private final List<HdmiCecMessage> mResultMessages = new ArrayList<>();
    private final Map<Integer, Boolean> mPortConnectionStatus = new HashMap<>();
    private final HashMap<Integer, Integer> mMessageSendResult = new HashMap<>();
    private int mMyPhysicalAddress = 0;
    private int mVendorId = 0;
    private HdmiPortInfo[] mHdmiPortInfo = null;
    private HdmiCecController.HdmiCecCallback mCallback = null;
    private int mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

    @Override
    public String nativeInit() {
        return "[class or subclass of IHdmiCec]@Proxy";
    }

    @Override
    public void setCallback(HdmiCecController.HdmiCecCallback callback) {
        this.mCallback = callback;
    }

    @Override
    public int nativeSendCecCommand(
            int srcAddress, int dstAddress, byte[] body) {
        if (body.length == 0) {
            return mPollAddressResponse[dstAddress];
        } else {
            HdmiCecMessage message = HdmiCecMessageBuilder.of(srcAddress, dstAddress, body);
            mResultMessages.add(message);
            return mMessageSendResult.getOrDefault(message.getOpcode(), SendMessageResult.SUCCESS);
        }
    }

    @Override
    public int nativeAddLogicalAddress(int logicalAddress) {
        return 0;
    }

    @Override
    public void nativeClearLogicalAddress() {}

    @Override
    public int nativeGetPhysicalAddress() {
        return mMyPhysicalAddress;
    }

    @Override
    public int nativeGetVersion() {
        return mCecVersion;
    }

    @Override
    public int nativeGetVendorId() {
        return mVendorId;
    }

    @Override
    public HdmiPortInfo[] nativeGetPortInfos() {
        if (mHdmiPortInfo == null) {
            mHdmiPortInfo = new HdmiPortInfo[1];
            mHdmiPortInfo[0] = new HdmiPortInfo(1, 1, 0x1000, true, true, true);
        }
        return mHdmiPortInfo;
    }

    @Override
    public void nativeSetOption(int flag, boolean enabled) {}

    @Override
    public void nativeSetLanguage(String language) {}

    @Override
    public void nativeEnableAudioReturnChannel(int port, boolean flag) {}

    @Override
    public boolean nativeIsConnected(int port) {
        Boolean isConnected = mPortConnectionStatus.get(port);
        return isConnected == null ? false : isConnected;
    }

    public void setPortConnectionStatus(int port, boolean connected) {
        mPortConnectionStatus.put(port, connected);
    }

    public void setCecVersion(@HdmiControlManager.HdmiCecVersion int cecVersion) {
        mCecVersion = cecVersion;
    }

    public void onCecMessage(HdmiCecMessage hdmiCecMessage) {
        if (mCallback == null) {
            return;
        }
        int source = hdmiCecMessage.getSource();
        int destination = hdmiCecMessage.getDestination();
        byte[] body = new byte[hdmiCecMessage.getParams().length + 1];
        int i = 0;
        body[i++] = (byte) hdmiCecMessage.getOpcode();
        for (byte param : hdmiCecMessage.getParams()) {
            body[i++] = param;
        }
        mCallback.onCecMessage(source, destination, body);
    }

    public void onHotplugEvent(int port, boolean connected) {
        if (mCallback == null) {
            return;
        }

        mCallback.onHotplugEvent(port, connected);
    }

    public List<HdmiCecMessage> getResultMessages() {
        return new ArrayList<>(mResultMessages);
    }

    public HdmiCecMessage getOnlyResultMessage() throws IllegalArgumentException {
        return Iterables.getOnlyElement(mResultMessages);
    }

    public void clearResultMessages() {
        mResultMessages.clear();
    }

    public void setPollAddressResponse(int logicalAddress, int response) {
        mPollAddressResponse[logicalAddress] = response;
    }

    public void setMessageSendResult(int opcode, int result) {
        mMessageSendResult.put(opcode, result);
    }

    @VisibleForTesting
    protected void setVendorId(int vendorId) {
        mVendorId = vendorId;
    }

    @VisibleForTesting
    protected void setPhysicalAddress(int physicalAddress) {
        mMyPhysicalAddress = physicalAddress;
    }

    @VisibleForTesting
    protected void setPortInfo(HdmiPortInfo[] hdmiPortInfo) {
        mHdmiPortInfo = new HdmiPortInfo[hdmiPortInfo.length];
        System.arraycopy(hdmiPortInfo, 0, mHdmiPortInfo, 0, hdmiPortInfo.length);
    }
}
