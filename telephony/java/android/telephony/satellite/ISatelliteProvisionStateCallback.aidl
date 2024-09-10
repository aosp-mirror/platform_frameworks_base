/*
 * Copyright 2023 The Android Open Source Project
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

package android.telephony.satellite;

import android.telephony.satellite.SatelliteSubscriberProvisionStatus;

/**
 * Interface for satellite provision state callback.
 * @hide
 */
oneway interface ISatelliteProvisionStateCallback {
    /**
     * Indicates that the satellite provision state has changed.
     *
     * @param provisioned True means the service is provisioned and false means it is not.
     */
    void onSatelliteProvisionStateChanged(in boolean provisioned);

    /**
     * Called when the provisioning state of one or more SatelliteSubscriberInfos changes.
     *
     * @param satelliteSubscriberProvisionStatus The List contains the latest provisioning states of
     * the SatelliteSubscriberInfos.
     * @hide
     */
    void onSatelliteSubscriptionProvisionStateChanged(in List<SatelliteSubscriberProvisionStatus>
        satelliteSubscriberProvisionStatus);
}
