/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard_obsolete;

import android.content.res.Configuration;

/**
 * Within a keyguard, there may be several screens that need a callback
 * to the host keyguard view.
 */
public interface KeyguardScreenCallback extends KeyguardViewCallback {

    /**
     * Transition to the lock screen.
     */
    void goToLockScreen();

    /**
     * Transition to the unlock screen.
     */
    void goToUnlockScreen();

    /**
     * The user reported that they forgot their pattern (or not, when they want to back out of the
     * forgot pattern screen).
     *
     * @param isForgotten True if the user hit the forgot pattern, false if they want to back out
     *        of the account screen.
     */
    void forgotPattern(boolean isForgotten);

    /**
     * @return Whether the keyguard requires some sort of PIN.
     */
    boolean isSecure();

    /**
     * @return Whether we are in a mode where we only want to verify the
     *   user can get past the keyguard.
     */
    boolean isVerifyUnlockOnly();

    /**
     * Stay on me, but recreate me (so I can use a different layout).
     */
    void recreateMe(Configuration config);

    /**
     * Take action to send an emergency call.
     */
    void takeEmergencyCallAction();

    /**
     * Report that the user had a failed attempt to unlock with password or pattern.
     */
    void reportFailedUnlockAttempt();

    /**
     * Report that the user successfully entered their password or pattern.
     */
    void reportSuccessfulUnlockAttempt();

    /**
     * Report whether we there's another way to unlock the device.
     * @return true
     */
    boolean doesFallbackUnlockScreenExist();
}
