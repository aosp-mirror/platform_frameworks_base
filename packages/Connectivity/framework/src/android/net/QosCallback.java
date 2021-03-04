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

package android.net;

import android.annotation.NonNull;
import android.annotation.SystemApi;

import java.util.concurrent.Executor;

/**
 * Receives Qos information given a {@link Network}.  The callback is registered with
 * {@link ConnectivityManager#registerQosCallback}.
 *
 * <p>
 * <br/>
 * The callback will no longer receive calls if any of the following takes place:
 * <ol>
 * <li>{@link ConnectivityManager#unregisterQosCallback(QosCallback)} is called with the same
 * callback instance.</li>
 * <li>{@link QosCallback#onError(QosCallbackException)} is called.</li>
 * <li>A network specific issue occurs.  eg. Congestion on a carrier network.</li>
 * <li>The network registered with the callback has no associated QoS providers</li>
 * </ul>
 * {@hide}
 */
@SystemApi
public abstract class QosCallback {
    /**
     * Invoked after an error occurs on a registered callback.  Once called, the callback is
     * automatically unregistered and the callback will no longer receive calls.
     *
     * <p>The underlying exception can either be a runtime exception or a custom exception made for
     * {@link QosCallback}. see: {@link QosCallbackException}.
     *
     * @param exception wraps the underlying cause
     */
    public void onError(@NonNull final QosCallbackException exception) {
    }

    /**
     * Called when a Qos Session first becomes available to the callback or if its attributes have
     * changed.
     * <p>
     * Note: The callback may be called multiple times with the same attributes.
     *
     * @param session the available session
     * @param sessionAttributes the attributes of the session
     */
    public void onQosSessionAvailable(@NonNull final QosSession session,
            @NonNull final QosSessionAttributes sessionAttributes) {
    }

    /**
     * Called after a Qos Session is lost.
     * <p>
     * At least one call to
     * {@link QosCallback#onQosSessionAvailable(QosSession, QosSessionAttributes)}
     * with the same {@link QosSession} will precede a call to lost.
     *
     * @param session the lost session
     */
    public void onQosSessionLost(@NonNull final QosSession session) {
    }

    /**
     * Thrown when there is a problem registering {@link QosCallback} with
     * {@link ConnectivityManager#registerQosCallback(QosSocketInfo, QosCallback, Executor)}.
     */
    public static class QosCallbackRegistrationException extends RuntimeException {
        /**
         * @hide
         */
        public QosCallbackRegistrationException() {
            super();
        }
    }
}
