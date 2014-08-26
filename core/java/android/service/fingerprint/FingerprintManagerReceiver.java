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
 */

/**
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
     * Fingerprint touch detected, but not processed yet. Clients will use this message to
     * determine a good or bad scan before the fingerprint is processed.  This is meant for the
     * client to provide feedback about the scan or alert the user that recognition is to follow.
     *
     * @param acquiredInfo one of:
     * {@link FingerprintManager#FINGERPRINT_ACQUIRED_GOOD},
     * {@link FingerprintManager#FINGERPRINT_ACQUIRED_PARTIAL},
     * {@link FingerprintManager#FINGERPRINT_ACQUIRED_INSUFFICIENT},
     * {@link FingerprintManager#FINGERPRINT_ACQUIRED_IMAGER_DIRTY},
     * {@link FingerprintManager#FINGERPRINT_ACQUIRED_TOO_SLOW},
     * {@link FingerprintManager#FINGERPRINT_ACQUIRED_TOO_FAST}
     */
    public void onAcquired(int acquiredInfo) { }

    /**
     * Fingerprint has been detected and processed.  A non-zero return indicates a valid
     * fingerprint was detected.
     *
     * @param fingerprintId the finger id, or 0 if not recognized.
     */
    public void onProcessed(int fingerprintId) { }

    /**
     * An error was detected during scan or enrollment.  One of
     * {@link FingerprintManager#FINGERPRINT_ERROR_HW_UNAVAILABLE},
     * {@link FingerprintManager#FINGERPRINT_ERROR_UNABLE_TO_PROCESS} or
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