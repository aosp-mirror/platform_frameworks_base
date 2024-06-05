/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.hardware.fingerprint.Fingerprint;

/**
 * Communication channel from the FingerprintService back to FingerprintManager.
 * @hide
 */
oneway interface IFingerprintServiceReceiver {
    void onEnrollResult(in Fingerprint fp, int remaining);
    void onAcquired(int acquiredInfo, int vendorCode);
    void onAuthenticationSucceeded(in Fingerprint fp, int userId, boolean isStrongBiometric);
    void onFingerprintDetected(int sensorId, int userId, boolean isStrongBiometric);
    void onAuthenticationFailed();
    void onError(int error, int vendorCode);
    void onRemoved(in Fingerprint fp, int remaining);
    void onChallengeGenerated(int sensorId, int userId, long challenge);
    void onUdfpsPointerDown(int sensorId);
    void onUdfpsPointerUp(int sensorId);
    void onUdfpsOverlayShown();
}
