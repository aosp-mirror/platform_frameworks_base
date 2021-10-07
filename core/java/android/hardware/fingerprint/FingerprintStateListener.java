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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for handling state changes in fingerprint-related events.
 * @hide
 */
public abstract class FingerprintStateListener extends IFingerprintStateListener.Stub {
    // Operation has not started yet.
    public static final int STATE_IDLE = 0;

    // Enrollment is in progress.
    public static final int STATE_ENROLLING = 1;

    // Lockscreen authentication in progress.
    public static final int STATE_KEYGUARD_AUTH = 2;

    // BiometricPrompt authentication in progress.
    public static final int STATE_BP_AUTH = 3;

    // Other Authentication State
    public static final int STATE_AUTH_OTHER = 4;

    @IntDef({STATE_IDLE, STATE_ENROLLING, STATE_KEYGUARD_AUTH, STATE_BP_AUTH, STATE_AUTH_OTHER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * Defines behavior in response to state update
     * @param newState new state of fingerprint sensor
     */
    public abstract void onStateChanged(@FingerprintStateListener.State int newState);
}
