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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Bundle;

import com.android.server.telecom.flags.Flags;

import java.util.List;

/**
 * CallEventCallback relays call updates (that do not require any action) from the Telecom framework
 * out to the application. This can include operations which the app must implement on a Call due to
 * the presence of other calls on the device, requests relayed from a Bluetooth device,
 * or from another calling surface.
 */
public interface CallEventCallback {
    /**
     * Telecom is informing the client the current {@link CallEndpoint} changed.
     *
     * @param newCallEndpoint The new {@link CallEndpoint} through which call media flows
     *                        (i.e. speaker, bluetooth, etc.).
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

    /**
     * Called when the video state changes.
     *
     * @param videoState The current video state.
     */
    @FlaggedApi(Flags.FLAG_TRANSACTIONAL_VIDEO_STATE)
    default void onVideoStateChanged(@CallAttributes.CallType int videoState) {}

    /**
     * Telecom is informing the client user requested call streaming but the stream can't be
     * started.
     *
     * @param reason Code to indicate the reason of this failure
     */
    void onCallStreamingFailed(@CallStreamingService.StreamingFailedReason int reason);

    /**
     * Informs this {@link android.telecom.CallEventCallback} on events raised from a
     * {@link android.telecom.InCallService} presenting this call. These events and the
     * associated extra keys for the {@code Bundle} parameter are mutually defined by a VoIP
     * application and {@link android.telecom.InCallService}. This enables alternative calling
     * surfaces, such as an automotive UI, to relay requests to perform other non-standard call
     * actions to the app. For example, an automotive calling solution may offer the ability for
     * the user to raise their hand during a meeting.
     *
     * @param event a string event identifier agreed upon between a VoIP application and an
     *              {@link android.telecom.InCallService}
     * @param extras a {@link android.os.Bundle} containing information about the event, as agreed
     *              upon between a VoIP application and {@link android.telecom.InCallService}.
     */
    void onEvent(@NonNull String event, @NonNull Bundle extras);
}
