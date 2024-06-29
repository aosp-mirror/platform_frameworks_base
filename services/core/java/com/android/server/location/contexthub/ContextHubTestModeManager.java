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
import android.hardware.location.NanoAppMessage;
import android.util.Log;

import java.util.Random;

/**
 * A class to manage behaviors during test mode. This is used for testing.
 * @hide
 */
public class ContextHubTestModeManager {
    private static final String TAG = "ContextHubTestModeManager";

    /** Probability (in percent) of duplicating a message. */
    private static final int MESSAGE_DROP_PROBABILITY_PERCENT = 20;

    /** Probability (in percent) of duplicating a message. */
    private static final int MESSAGE_DUPLICATION_PROBABILITY_PERCENT = 20;

    /** The number of total messages to send when the duplicate event happens. */
    private static final int NUM_MESSAGES_TO_DUPLICATE = 3;

    /** A probability percent for a certain event. */
    private static final int MAX_PROBABILITY_PERCENT = 100;

    private final Random mRandom = new Random();

    /**
     * @return whether the message was handled
     * @see ContextHubServiceCallback#handleNanoappMessage
     */
    public boolean handleNanoappMessage(Runnable handleMessage, NanoAppMessage message) {
        if (Flags.reliableMessageDuplicateDetectionService()
                && message.isReliable()
                && mRandom.nextInt(MAX_PROBABILITY_PERCENT)
                        < MESSAGE_DUPLICATION_PROBABILITY_PERCENT) {
            Log.i(TAG, "[TEST MODE] Duplicating message ("
                    + NUM_MESSAGES_TO_DUPLICATE
                    + " sends) with message sequence number: "
                    + message.getMessageSequenceNumber());
            for (int i = 0; i < NUM_MESSAGES_TO_DUPLICATE; ++i) {
                handleMessage.run();
            }
            return true;
        }
        return false;
    }

    /**
     * @return whether the message was handled
     * @see IContextHubWrapper#sendMessageToContextHub
     */
    public boolean sendMessageToContextHub(NanoAppMessage message) {
        if (Flags.reliableMessageRetrySupportService()
                && message.isReliable()
                && mRandom.nextInt(MAX_PROBABILITY_PERCENT)
                        < MESSAGE_DROP_PROBABILITY_PERCENT) {
            Log.i(TAG, "[TEST MODE] Dropping message with message sequence number: "
                    + message.getMessageSequenceNumber());
            return true;
        }
        return false;
    }
}
