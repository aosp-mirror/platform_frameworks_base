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

import android.hardware.hdmi.HdmiDeviceInfo;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Buffer storage to keep incoming messages for later processing. Used to
 * handle messages that arrive when the device is not ready. Useful when
 * keeping the messages from a connected device which are not discovered yet.
 */
final class DelayedMessageBuffer {
    private final ArrayList<HdmiCecMessage> mBuffer = new ArrayList<>();
    private final HdmiCecLocalDevice mDevice;

    DelayedMessageBuffer(HdmiCecLocalDevice device) {
        mDevice = device;
    }

    /**
     * Add a new message to the buffer. The buffer keeps selected messages in
     * the order they are received.
     *
     * @param message {@link HdmiCecMessage} to add
     */
    void add(HdmiCecMessage message) {
        boolean buffered = true;

        // Note that all the messages are not handled in the same manner.
        // For &lt;Active Source&gt; we keep the latest one only.
        // TODO: This might not be the best way to choose the active source.
        //       Devise a better way to pick up the best one.
        switch (message.getOpcode()) {
            case Constants.MESSAGE_ACTIVE_SOURCE:
                removeActiveSource();
                mBuffer.add(message);
                break;
            case Constants.MESSAGE_INITIATE_ARC:
            case Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE:
                mBuffer.add(message);
                break;
            default:
                buffered = false;
                break;
        }
        if (buffered) {
            HdmiLogger.debug("Buffering message:" + message);
        }
    }

    private void removeActiveSource() {
        // Uses iterator to remove elements while looping through the list.
        for (Iterator<HdmiCecMessage> iter = mBuffer.iterator(); iter.hasNext(); ) {
            HdmiCecMessage message = iter.next();
            if (message.getOpcode() == Constants.MESSAGE_ACTIVE_SOURCE) {
                iter.remove();
            }
        }
    }

    boolean isBuffered(int opcode) {
        for (HdmiCecMessage message : mBuffer) {
            if (message.getOpcode() == opcode) {
                return true;
            }
        }
        return false;
    }

    void processAllMessages() {
        // Use the copied buffer.
        ArrayList<HdmiCecMessage> copiedBuffer = new ArrayList<>(mBuffer);
        mBuffer.clear();
        for (HdmiCecMessage message : copiedBuffer) {
            mDevice.onMessage(message);
            HdmiLogger.debug("Processing message:" + message);
        }
    }

    /**
     * Process messages from a given logical device. Called by
     * {@link NewDeviceAction} actions when they finish adding the device
     * information.
     * <p>&lt;Active Source&gt; is processed only when the TV input is ready.
     * If not, {@link #processActiveSource()} will be invoked later to handle it.
     *
     * @param address logical address of CEC device which the messages to process
     *        are associated with
     */
    void processMessagesForDevice(int address) {
        ArrayList<HdmiCecMessage> copiedBuffer = new ArrayList<>(mBuffer);
        mBuffer.clear();
        HdmiLogger.debug("Checking message for address:" + address);
        for (HdmiCecMessage message : copiedBuffer) {
            if (message.getSource() != address) {
                mBuffer.add(message);
                continue;
            }
            if (message.getOpcode() == Constants.MESSAGE_ACTIVE_SOURCE
                    && !mDevice.isInputReady(HdmiDeviceInfo.idForCecDevice(address))) {
                mBuffer.add(message);
                continue;
            }
            mDevice.onMessage(message);
            HdmiLogger.debug("Processing message:" + message);
        }
    }

    /**
     * Process &lt;Active Source&gt;.
     *
     * <p>The message has a dependency on TV input framework. Should be invoked
     * after we get the callback
     * {@link android.media.tv.TvInputManager.TvInputCallback#onInputAdded(String)}
     * to ensure the processing of the message takes effect when transformed
     * to input change callback.
     *
     * @param address logical address of the device to be the active source
     */
    void processActiveSource(int address) {
        ArrayList<HdmiCecMessage> copiedBuffer = new ArrayList<>(mBuffer);
        mBuffer.clear();
        for (HdmiCecMessage message : copiedBuffer) {
            if (message.getOpcode() == Constants.MESSAGE_ACTIVE_SOURCE
                    && message.getSource() == address) {
                mDevice.onMessage(message);
                HdmiLogger.debug("Processing message:" + message);
            } else {
                mBuffer.add(message);
            }
        }
    }
}
