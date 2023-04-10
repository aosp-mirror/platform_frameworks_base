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

package android.telephony;

import android.annotation.NonNull;
import android.telephony.Annotation.DisconnectCauses;

import java.util.function.Consumer;

/**
 * A callback class used to receive the transport selection result.
 * @hide
 */
public interface TransportSelectorCallback {
    /**
     * Notify that {@link DomainSelector} instance has been created for the selection request.
     *
     * @param selector the {@link DomainSelector} instance created.
     */
    void onCreated(@NonNull DomainSelector selector);

    /**
     * Notify that WLAN transport has been selected.
     *
     * @param useEmergencyPdn Indicates whether Wi-Fi emergency services use emergency PDN or not.
     */
    void onWlanSelected(boolean useEmergencyPdn);

    /**
     * Notify that WWAN transport has been selected.
     */
    @NonNull WwanSelectorCallback onWwanSelected();

    /**
     * Notify that WWAN transport has been selected.
     *
     * @param consumer The callback to receive the result.
     */
    void onWwanSelected(Consumer<WwanSelectorCallback> consumer);

    /**
     * Notify that selection has terminated because there is no decision that can be made
     * or a timeout has occurred. The call should be terminated when this method is called.
     *
     * @param cause indicates the reason.
     */
    void onSelectionTerminated(@DisconnectCauses int cause);
}
