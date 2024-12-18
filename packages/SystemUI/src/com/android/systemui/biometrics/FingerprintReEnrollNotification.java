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

package com.android.systemui.biometrics;

import android.hardware.biometrics.BiometricFingerprintConstants;

/**
 * Checks if the fingerprint HAL has sent a re-enrollment request.
 */
public interface FingerprintReEnrollNotification {
    //TODO: Remove this class and add a constant in the HAL API instead (b/281841852)
    /**
     * Returns true if msgId corresponds to FINGERPRINT_ACQUIRED_RE_ENROLL_OPTIONAL or
     * FINGERPRINT_ACQUIRED_RE_ENROLL_FORCED.
     */
    boolean isFingerprintReEnrollRequested(
            @BiometricFingerprintConstants.FingerprintAcquired int msgId);

    /**
     * Returns true if msgId corresponds to FINGERPRINT_ACQUIRED_RE_ENROLL_FORCED.
     */
    boolean isFingerprintReEnrollForced(
            @BiometricFingerprintConstants.FingerprintAcquired int msgId);
}
