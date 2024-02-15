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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.telephony.Annotation.DisconnectCauses;

import com.android.internal.telephony.flags.Flags;

import java.util.function.Consumer;

/**
 * A callback class used by the domain selection module to notify the framework of the result of
 * selecting a domain for a call.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_USE_OEM_DOMAIN_SELECTION_SERVICE)
public interface TransportSelectorCallback {
    /**
     * Notify that {@link DomainSelector} instance has been created for the selection request.
     * <p>
     * DomainSelector callbacks run using the executor specified in
     * {@link DomainSelectionService#onCreateExecutor}.
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
     * Notify that WWAN transport has been selected and the next phase of selecting
     * the PS or CS domain is starting.
     *
     * @param consumer The callback used by the {@link DomainSelectionService} to optionally run
     *        emergency network scans and notify the framework of the WWAN transport result.
     */
    void onWwanSelected(@NonNull Consumer<WwanSelectorCallback> consumer);

    /**
     * Notify that selection has terminated because there is no decision that can be made
     * or a timeout has occurred. The call should be terminated when this method is called.
     *
     * @param cause indicates the reason.
     */
    void onSelectionTerminated(@DisconnectCauses int cause);
}
