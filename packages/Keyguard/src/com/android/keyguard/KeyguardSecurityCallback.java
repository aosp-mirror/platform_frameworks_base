/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.keyguard;

public interface KeyguardSecurityCallback {

    /**
     * Dismiss the given security screen.
     * @param securityVerified true if the user correctly entered credentials for the given screen.
     */
    void dismiss(boolean securityVerified);

    /**
     * Manually report user activity to keep the device awake.
     */
    void userActivity();

    /**
     * Checks if keyguard is in "verify credentials" mode.
     * @return true if user has been asked to verify security.
     */
    boolean isVerifyUnlockOnly();

    /**
     * Call to report an unlock attempt.
     * @param success set to 'true' if user correctly entered security credentials.
     */
    void reportUnlockAttempt(boolean success);

    /**
     * Shows the backup security for the current method.  If none available, this call is a no-op.
     */
    void showBackupSecurity();

}
