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

package com.android.server.hdmi;

import java.util.ArrayList;
import java.util.List;

/**
 * Buffer for processing the incoming CEC messages while allocating logical addresses.
 */
final class CecMessageBuffer {
    private List<HdmiCecMessage> mBuffer = new ArrayList<>();
    private HdmiControlService mHdmiControlService;

    CecMessageBuffer(HdmiControlService hdmiControlService) {
        mHdmiControlService = hdmiControlService;
    }

    /**
     * Adds a message to the buffer.
     * Only certain types of messages need to be buffered.
     * @param message The message to add to the buffer
     * @return Whether the message was added to the buffer
     */
    public boolean bufferMessage(HdmiCecMessage message) {
        switch (message.getOpcode()) {
            case Constants.MESSAGE_ACTIVE_SOURCE:
                bufferActiveSource(message);
                return true;
            case Constants.MESSAGE_IMAGE_VIEW_ON:
            case Constants.MESSAGE_TEXT_VIEW_ON:
                bufferImageOrTextViewOn(message);
                return true;
            case Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST:
                bufferSystemAudioModeRequest(message);
                return true;
            // Add here if new message that needs to buffer
            default:
                // Do not need to buffer messages other than above
                return false;
        }
    }

    /**
     * Process all messages in the buffer.
     */
    public void processMessages() {
        for (final HdmiCecMessage message : mBuffer) {
            mHdmiControlService.runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    mHdmiControlService.handleCecCommand(message);
                }
            });
        }
        mBuffer.clear();
    }

    private void bufferActiveSource(HdmiCecMessage message) {
        if (!replaceMessageIfBuffered(message, Constants.MESSAGE_ACTIVE_SOURCE)) {
            mBuffer.add(message);
        }
    }

    private void bufferImageOrTextViewOn(HdmiCecMessage message) {
        if (!replaceMessageIfBuffered(message, Constants.MESSAGE_IMAGE_VIEW_ON)
                && !replaceMessageIfBuffered(message, Constants.MESSAGE_TEXT_VIEW_ON)) {
            mBuffer.add(message);
        }
    }

    private void bufferSystemAudioModeRequest(HdmiCecMessage message) {
        if (!replaceMessageIfBuffered(message, Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST)) {
            mBuffer.add(message);
        }
    }

    // Returns true if the message is replaced
    private boolean replaceMessageIfBuffered(HdmiCecMessage message, int opcode) {
        for (int i = 0; i < mBuffer.size(); i++) {
            HdmiCecMessage bufferedMessage = mBuffer.get(i);
            if (bufferedMessage.getOpcode() == opcode) {
                mBuffer.set(i, message);
                return true;
            }
        }
        return false;
    }
}
