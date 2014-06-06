package android.service.fingerprint;
/**
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
 * @hide
 */

public class FingerprintManagerReceiver {
    /**
     * Fingerprint enrollment progress update. Enrollment is considered complete if
     * remaining hits 0 without {@link #onError(int)} being called.
     *
     * @param fingerprintId the fingerprint we're currently enrolling
     * @param remaining the number of samples required to complete enrollment. It's up to
     * the hardware to define what each step in enrollment means. Some hardware
     * requires multiple samples of the same part of the finger.  Others require sampling of
     * different parts of the finger.  The enrollment flow can use remaining to
     * mean "step x" of the process or "just need another sample."
     */
    public void onEnrollResult(int fingerprintId,  int remaining) { }

    /**
     * Fingerprint scan detected. Most clients will use this function to detect a fingerprint
     *
     * @param fingerprintId is the finger the hardware has detected.
     * @param confidence from 0 (no confidence) to 65535 (high confidence). Fingerprint 0 has
     * special meaning - the finger wasn't recognized.
     */
    public void onScanned(int fingerprintId, int confidence) { }

    /**
     * An error was detected during scan or enrollment.  One of
     * {@link FingerprintManager#FINGERPRINT_ERROR_HW_UNAVAILABLE},
     * {@link FingerprintManager#FINGERPRINT_ERROR_BAD_CAPTURE} or
     * {@link FingerprintManager#FINGERPRINT_ERROR_TIMEOUT}
     * {@link FingerprintManager#FINGERPRINT_ERROR_NO_SPACE}
     *
     * @param error one of the above error codes
     */
    public void onError(int error) { }

    /**
     * The given fingerprint template was successfully removed by the driver.
     * See {@link FingerprintManager#remove(int)}
     *
     * @param fingerprintId id of template to remove.
     */
    public void onRemoved(int fingerprintId) { }
}