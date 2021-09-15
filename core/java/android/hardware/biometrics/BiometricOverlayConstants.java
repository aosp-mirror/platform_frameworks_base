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

package android.hardware.biometrics;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Common constants for biometric overlays.
 * @hide
 */
public interface BiometricOverlayConstants {
    /** Unknown usage. */
    int REASON_UNKNOWN = 0;
    /** User is about to enroll. */
    int REASON_ENROLL_FIND_SENSOR = 1;
    /** User is enrolling. */
    int REASON_ENROLL_ENROLLING = 2;
    /** Usage from BiometricPrompt. */
    int REASON_AUTH_BP = 3;
    /** Usage from Keyguard. */
    int REASON_AUTH_KEYGUARD = 4;
    /** Non-specific usage (from FingerprintManager). */
    int REASON_AUTH_OTHER = 5;

    @IntDef({REASON_UNKNOWN,
            REASON_ENROLL_FIND_SENSOR,
            REASON_ENROLL_ENROLLING,
            REASON_AUTH_BP,
            REASON_AUTH_KEYGUARD,
            REASON_AUTH_OTHER})
    @Retention(RetentionPolicy.SOURCE)
    @interface ShowReason {}
}
