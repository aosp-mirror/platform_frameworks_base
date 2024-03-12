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
 * Common constants for biometric requests.
 * @hide
 */
public class BiometricRequestConstants {

    private BiometricRequestConstants() {}

    /** Unknown usage. */
    public static final int REASON_UNKNOWN = 0;
    /** User is about to enroll. */
    public static final int REASON_ENROLL_FIND_SENSOR = 1;
    /** User is enrolling. */
    public static final int REASON_ENROLL_ENROLLING = 2;
    /** Usage from BiometricPrompt. */
    public static final int REASON_AUTH_BP = 3;
    /** Usage from Device Entry. */
    public static final int REASON_AUTH_KEYGUARD = 4;
    /** Non-specific usage (from FingerprintManager). */
    public static final int REASON_AUTH_OTHER = 5;
    /** Usage from Settings. */
    public static final int REASON_AUTH_SETTINGS = 6;

    @IntDef({REASON_UNKNOWN,
            REASON_ENROLL_FIND_SENSOR,
            REASON_ENROLL_ENROLLING,
            REASON_AUTH_BP,
            REASON_AUTH_KEYGUARD,
            REASON_AUTH_OTHER,
            REASON_AUTH_SETTINGS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestReason {}
}
