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

package com.android.server.location.contexthub;

import android.chre.flags.Flags;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppMessage;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;

/**
 * A class to manage behaviors during test mode. This is used for testing.
 * @hide
 */
public class ContextHubTestModeManager {
    private static final String TAG = "ContextHubTestModeManager";

    private static final int DROP_MESSAGE_TO_HOST_EVENT = 0;
    private static final int DROP_MESSAGE_TO_CONTEXT_HUB_EVENT = 1;
    private static final int DUPLICATE_MESSAGE_TO_HOST_EVENT = 2;
    private static final int DUPLICATE_MESSAGE_TO_CONTEXT_HUB_EVENT = 3;
    private static final int NUMBER_OF_EVENTS = 4;

    /** The number of total messages to send when the duplication event happens. */
    private static final int NUM_MESSAGES_TO_DUPLICATE = 3;

    /** The counter to track the number of interactions with the test mode manager. */
    private final AtomicLong mCounter = new AtomicLong(0);

    /**
     * @return whether the message was handled
     * @see ContextHubServiceCallback#handleNanoappMessage
     */
    public boolean handleNanoappMessage(Runnable handleMessage, NanoAppMessage message) {
        if (!message.isReliable()) {
            return false;
        }

        long counterValue = mCounter.getAndIncrement();
        if (Flags.reliableMessageDuplicateDetectionService()
                && counterValue % NUMBER_OF_EVENTS == DUPLICATE_MESSAGE_TO_HOST_EVENT) {
            Log.i(TAG, "[TEST MODE] Duplicating message to host ("
                    + NUM_MESSAGES_TO_DUPLICATE
                    + " sends) with message sequence number: "
                    + message.getMessageSequenceNumber());
            for (int i = 0; i < NUM_MESSAGES_TO_DUPLICATE; ++i) {
                handleMessage.run();
            }
            return true;
        }

        if (counterValue % NUMBER_OF_EVENTS == DROP_MESSAGE_TO_HOST_EVENT) {
            Log.i(TAG, "[TEST MODE] Dropping message to host with "
                    + "message sequence number: "
                    + message.getMessageSequenceNumber());
            return true;
        }

        return false;
    }

    /**
     * @return whether the message was handled
     * @see IContextHubWrapper#sendMessageToContextHub
     */
    public boolean sendMessageToContextHub(Callable<Integer> sendMessage, NanoAppMessage message) {
        if (!message.isReliable()) {
            return false;
        }

        long counterValue = mCounter.getAndIncrement();
        if (counterValue % NUMBER_OF_EVENTS == DUPLICATE_MESSAGE_TO_CONTEXT_HUB_EVENT) {
            Log.i(TAG, "[TEST MODE] Duplicating message to the Context Hub ("
                    + NUM_MESSAGES_TO_DUPLICATE
                    + " sends) with message sequence number: "
                    + message.getMessageSequenceNumber());
            for (int i = 0; i < NUM_MESSAGES_TO_DUPLICATE; ++i) {
                try {
                    int result = sendMessage.call();
                    if (result != ContextHubTransaction.RESULT_SUCCESS) {
                        Log.e(TAG, "sendMessage returned an error: " + result);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception in sendMessageToContextHub: "
                            + e.getMessage());
                }
            }
            return true;
        }

        if (Flags.reliableMessageRetrySupportService()
                && counterValue % NUMBER_OF_EVENTS == DROP_MESSAGE_TO_CONTEXT_HUB_EVENT) {
            Log.i(TAG, "[TEST MODE] Dropping message to the Context Hub with "
                    + "message sequence number: "
                    + message.getMessageSequenceNumber());
            return true;
        }

        return false;
    }
}
