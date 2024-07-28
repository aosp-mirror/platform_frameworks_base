/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import android.annotation.NonNull;
import android.app.ClientTransactionHandler;

/**
 * Base interface for individual requests from server to client.
 * Each of them can be prepared before scheduling and, eventually, executed.
 *
 * @hide
 */
public interface BaseClientRequest {

    /**
     * Prepares the client request before scheduling.
     * An example of this might be informing about pending updates for some values.
     *
     * @param client target client handler.
     */
    default void preExecute(@NonNull ClientTransactionHandler client) {
    }

    /**
     * Executes the request.
     *
     * @param client         target client handler.
     * @param pendingActions container that may have data pending to be used.
     */
    void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions);

    /**
     * Performs all actions that need to happen after execution, e.g. report the result to server.
     *
     * @param client         target client handler.
     * @param pendingActions container that may have data pending to be used.
     */
    default void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
    }
}
