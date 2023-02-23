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
import android.os.CancellationSignal;
import android.telephony.DomainSelectionService.EmergencyScanType;

import java.util.List;
import java.util.function.Consumer;

/**
 * A callback class used to receive the domain selection result.
 * @hide
 */
public interface WwanSelectorCallback {
    /**
     * Notify the framework that the {@link DomainSelectionService} has requested an emergency
     * network scan as part of selection.
     *
     * @param preferredNetworks the ordered list of preferred networks to scan.
     * @param scanType indicates the scan preference, such as full service or limited service.
     * @param signal notifies when the operation is canceled.
     * @param consumer the handler of the response.
     */
    void onRequestEmergencyNetworkScan(@NonNull List<Integer> preferredNetworks,
            @EmergencyScanType int scanType,
            @NonNull CancellationSignal signal, @NonNull Consumer<EmergencyRegResult> consumer);

    /**
     * Notifies the FW that the domain has been selected. After this method is called,
     * this interface can be discarded.
     *
     * @param domain The selected domain.
     * @param useEmergencyPdn Indicates whether emergency services use emergency PDN or not.
     */
    void onDomainSelected(@NetworkRegistrationInfo.Domain int domain, boolean useEmergencyPdn);
}
