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

package android.telecom;


import android.annotation.NonNull;

import java.util.List;
import java.util.function.Consumer;

/**
 * CallEventCallback relays updates to a call from the Telecom framework.
 * This can include operations which the app must implement on a Call due to the presence of other
 * calls on the device, requests relayed from a Bluetooth device, or from another calling surface.
 *
 * <p>
 * CallEventCallbacks with {@link Consumer}s are transactional, meaning that a client must
 * complete the {@link Consumer} via {@link Consumer#accept(Object)} in order to complete the
 * CallEventCallback. If a CallEventCallback can be completed, the
 * {@link Consumer#accept(Object)} should be called with {@link Boolean#TRUE}. Otherwise,
 * {@link Consumer#accept(Object)} should be called with {@link Boolean#FALSE} to represent the
 * CallEventCallback cannot be completed on the client side.
 *
 * <p>
 * Note: Each CallEventCallback has a timeout of 5000 milliseconds. Failing to complete the
 * {@link Consumer} before the timeout will result in a failed transaction.
 */
public interface CallEventCallback {
    /**
     * Telecom is informing the client to set the call active
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can set the call
     *                     active on their end, the {@link Consumer#accept(Object)} should be
     *                     called with {@link Boolean#TRUE}. Otherwise,
     *                     {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#FALSE}.
     */
    void onSetActive(@NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to set the call inactive. This is the same as holding a call
     * for two endpoints but can be extended to setting a meeting inactive.
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can set the call
     *                     inactive on their end, the {@link Consumer#accept(Object)} should be
     *                     called with {@link Boolean#TRUE}. Otherwise,
     *                     {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#FALSE}.
     */
    void onSetInactive(@NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to answer an incoming call and set it to active.
     *
     * @param videoState   see {@link android.telecom.CallAttributes.CallType} for valid states
     * @param wasCompleted The {@link Consumer} to be completed. If the client can answer the call
     *                     on their end, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#TRUE}. Otherwise, {@link Consumer#accept(Object)} should
     *                     be called with {@link Boolean#FALSE}.
     */
    void onAnswer(@android.telecom.CallAttributes.CallType int videoState,
            @NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to reject the incoming call
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can reject the
     *                     incoming call, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#TRUE}. Otherwise, {@link Consumer#accept(Object)}
     *                     should  be called with {@link Boolean#FALSE}.
     */
    void onReject(@NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to disconnect the call
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can disconnect the
     *                     call on their end, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#TRUE}. Otherwise, {@link Consumer#accept(Object)}
     *                     should  be called with {@link Boolean#FALSE}.
     */
    void onDisconnect(@NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client to set the call in streaming.
     *
     * @param wasCompleted The {@link Consumer} to be completed. If the client can stream the
     *                     call on their end, {@link Consumer#accept(Object)} should be called with
     *                     {@link Boolean#TRUE}. Otherwise, {@link Consumer#accept(Object)}
     *                     should be called with {@link Boolean#FALSE}.
     */
    void onCallStreamingStarted(@NonNull Consumer<Boolean> wasCompleted);

    /**
     * Telecom is informing the client user requested call streaming but the stream can't be
     * started.
     *
     * @param reason Code to indicate the reason of this failure
     */
    void onCallStreamingFailed(@CallStreamingService.StreamingFailedReason int reason);

    /**
     * Telecom is informing the client the current {@link CallEndpoint} changed.
     *
     * @param newCallEndpoint The new {@link CallEndpoint} through which call media flows
     *                       (i.e. speaker, bluetooth, etc.).
     */
    void onCallEndpointChanged(@NonNull CallEndpoint newCallEndpoint);

    /**
     * Telecom is informing the client that the available {@link CallEndpoint}s have changed.
     *
     * @param availableEndpoints The set of available {@link CallEndpoint}s reported by Telecom.
     */
    void onAvailableCallEndpointsChanged(@NonNull List<CallEndpoint> availableEndpoints);

    /**
     * Called when the mute state changes.
     *
     * @param isMuted The current mute state.
     */
    void onMuteStateChanged(boolean isMuted);
}
