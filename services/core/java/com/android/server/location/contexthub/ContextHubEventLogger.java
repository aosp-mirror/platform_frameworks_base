/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.hardware.location.NanoAppMessage;

/**
 * A class to log events and useful metrics within the Context Hub service.
 *
 * The class holds a queue of the last NUM_EVENTS_TO_STORE events for each
 * event category: nanoapp load, nanoapp unload, message from a nanoapp,
 * message to a nanoapp, and context hub restarts. The dump() function
 * will be called during debug dumps, giving access to the event information
 * and aggregate data since the instantiation of this class.
 *
 * @hide
 */
public class ContextHubEventLogger {
    private static final String TAG = "ContextHubEventLogger";

    ContextHubEventLogger() {
        throw new RuntimeException("Not implemented");
    }

    /**
     * Logs a nanoapp load event
     *
     * @param contextHubId      the ID of the context hub
     * @param nanoAppId         the ID of the nanoapp
     * @param nanoAppVersion    the version of the nanoapp
     * @param nanoAppSize       the size in bytes of the nanoapp
     * @param success           whether the load was successful
     */
    public void logNanoAppLoad(int contextHubId, long nanoAppId, int nanoAppVersion,
                               long nanoAppSize, boolean success) {
        throw new RuntimeException("Not implemented");
    }

    /**
     * Logs a nanoapp unload event
     *
     * @param contextHubId      the ID of the context hub
     * @param nanoAppId         the ID of the nanoapp
     * @param success           whether the unload was successful
     */
    public void logNanoAppUnload(int contextHubId, long nanoAppId, boolean success) {
        throw new RuntimeException("Not implemented");
    }

    /**
     * Logs the event where a nanoapp sends a message to a client
     *
     * @param contextHubId      the ID of the context hub
     * @param message           the message that was sent
     */
    public void logMessageFromNanoApp(int contextHubId, NanoAppMessage message) {
        throw new RuntimeException("Not implemented");
    }

    /**
     * Logs the event where a client sends a message to a nanoapp
     *
     * @param contextHubId      the ID of the context hub
     * @param message           the message that was sent
     * @param success           whether the message was sent successfully
     */
    public void logMessageToNanoApp(int contextHubId, NanoAppMessage message, boolean success) {
        throw new RuntimeException("Not implemented");
    }

    /**
     * Logs a context hub restart event
     *
     * @param contextHubId      the ID of the context hub
     */
    public void logContextHubRestarts(int contextHubId) {
        throw new RuntimeException("Not implemented");
    }

    /**
     * Creates a string representation of the logged events
     *
     * @return the dumped events
     */
    public String dump() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public String toString() {
        return dump();
    }
}
