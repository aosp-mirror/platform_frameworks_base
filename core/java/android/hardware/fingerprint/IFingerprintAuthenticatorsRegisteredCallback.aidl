/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.hardware.fingerprint;

import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import java.util.List;

/**
 * Callback to notify FingerprintManager that FingerprintService has registered all of the
 * fingerprint authenticators (HALs).
 * See {@link android.hardware.fingerprint.IFingerprintService#registerAuthenticators}.
 *
 * @hide
 */
oneway interface IFingerprintAuthenticatorsRegisteredCallback {
    /**
     * Notifies FingerprintManager that all of the fingerprint authenticators have been registered.
     *
     * @param sensors A consolidated list of sensor properties for all of the authenticators.
     */
    void onAllAuthenticatorsRegistered(in List<FingerprintSensorPropertiesInternal> sensors);
}
