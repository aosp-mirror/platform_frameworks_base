/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.IntDef;
import android.hardware.biometrics.BiometricConstants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for retrieval of current user's lockout state.
 */
public interface LockoutTracker {
    int LOCKOUT_NONE = BiometricConstants.BIOMETRIC_LOCKOUT_NONE;
    int LOCKOUT_TIMED = BiometricConstants.BIOMETRIC_LOCKOUT_TIMED;
    int LOCKOUT_PERMANENT = BiometricConstants.BIOMETRIC_LOCKOUT_PERMANENT;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({LOCKOUT_NONE, LOCKOUT_TIMED, LOCKOUT_PERMANENT})
    @interface LockoutMode {}

    @LockoutMode int getLockoutModeForUser(int userId);
    void setLockoutModeForUser(int userId, @LockoutMode int mode);
    default void resetFailedAttemptsForUser(boolean clearAttemptCounter, int userId) {}
    default void addFailedAttemptForUser(int userId) {}
}
