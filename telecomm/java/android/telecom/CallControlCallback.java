/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telecom;

import android.annotation.NonNull;

import java.util.function.Consumer;

/**
 * CallControlCallback relays call updates (that require a response) from the Telecom framework out
 * to the application.This can include operations which the app must implement on a Call due to the
 * presence of other calls on the device, requests relayed from a Bluetooth device, or from another
 * calling surface.
 *
 * <p>
 * All CallControlCallbacks are transactional, meaning that a client must
 * complete the {@link Consumer} via {@link Consumer#accept(Object)} in order to complete the
 * CallControlCallbacks. If a CallControlCallbacks can be completed, the
 * {@link Consumer#accept(Object)} should be called with {@link Boolean#TRUE}. Otherwise,
 * {@link Consumer#accept(Object)} should be called with {@link Boolean#FALSE} to represent the
 * CallControlCallbacks cannot be completed on the client side.
 *
 * <p>
 * Note: Each CallEventCallback has a timeout of 5000 milliseconds. Failing to complete the
 * {@link Consumer} before the timeout will result in a failed transaction.
 */
public interface CallControlCallback {
    /**
     * Telecom is informing the client to set the call active
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can set the call
     *                     active on their end, the {@link Consumer#accept(Object)} should be
     *                     called with {@link Boolean#TRUE}.
     *
     *                     Otherwise, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#FALSE}.  Telecom will effectively ignore the remote
     *                     setActive request and the call will remain in whatever state it is in.
     */
    void onSetActive(@NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to set the call inactive. This is the same as holding a call
     * for two endpoints but can be extended to setting a meeting inactive.
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can set the call
     *                     inactive on their end, the {@link Consumer#accept(Object)} should be
     *                     called with {@link Boolean#TRUE}.
     *
     *                     Otherwise, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#FALSE}.  Telecom will effectively ignore the remote
     *                     setInactive request and the call will remain in whatever state it is in.
     */
    void onSetInactive(@NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to answer an incoming call and set it to active.
     *
     * @param videoState   the video state
     * @param wasCompleted The {@link Consumer} to be completed. If the client can answer the call
     *                     on their end, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#TRUE}.
     *
     *                     Otherwise,{@link Consumer#accept(Object)} should  be called with
     *                     {@link Boolean#FALSE}. However, Telecom will still disconnect
     *                     the call and remove it from tracking.
     */
    void onAnswer(@android.telecom.CallAttributes.CallType int videoState,
            @NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to disconnect the call
     *
     * @param disconnectCause represents the cause for disconnecting the call.
     * @param wasCompleted    The {@link Consumer} to be completed. If the client can disconnect
     *                        the call on their end, {@link Consumer#accept(Object)} should be
     *                        called with {@link Boolean#TRUE}.
     *
     *                        Otherwise,{@link Consumer#accept(Object)} should  be called with
     *                        {@link Boolean#FALSE}. However, Telecom will still disconnect
     *                        the call and remove it from tracking.
     */
    void onDisconnect(@NonNull DisconnectCause disconnectCause,
            @NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to set the call in streaming.
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can stream the
     *                     call on their end, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#TRUE}. Otherwise, {@link Consumer#accept(Object)}
     *                     should be called with {@link Boolean#FALSE}.
     */
    void onCallStreamingStarted(@NonNull Consumer<Boolean> wasCompleted);
}
