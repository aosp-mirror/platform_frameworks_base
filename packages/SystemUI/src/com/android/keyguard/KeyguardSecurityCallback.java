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

import com.android.keyguard.KeyguardSecurityModel.SecurityMode;

public interface KeyguardSecurityCallback {

    /**
     * Dismiss the given security screen.
     * @param securityVerified true if the user correctly entered credentials for the given screen.
     * @param targetUserId a user that needs to be the foreground user at the dismissal completion.
     * @param expectedSecurityMode The security mode that is invoking this dismiss.
     */
    default void dismiss(boolean securityVerified, int targetUserId,
            SecurityMode expectedSecurityMode) {
    }

    /**
     * Dismiss the given security screen.
     * @param securityVerified true if the user correctly entered credentials for the given screen.
     * @param targetUserId a user that needs to be the foreground user at the dismissal completion.
     * @param bypassSecondaryLockScreen true if the user can bypass the secondary lock screen,
     *                                  if any, during this dismissal.
     * @param expectedSecurityMode The security mode that is invoking this dismiss.
     */
    default boolean dismiss(boolean securityVerified, int targetUserId,
            boolean bypassSecondaryLockScreen,
            SecurityMode expectedSecurityMode) {
        return false;
    }

    /**
     * Manually report user activity to keep the device awake.
     */
    default void userActivity() {
    }

    /**
     * Checks if keyguard is in "verify credentials" mode.
     *
     * @return true if user has been asked to verify security.
     */
    default boolean isVerifyUnlockOnly() {
        return false;
    }

    /**
     * Call to report an unlock attempt.
     * @param userId id of the user whose unlock attempt is recorded.
     * @param success set to 'true' if user correctly entered security credentials.
     * @param timeoutMs timeout in milliseconds to wait before reattempting an unlock.
     *                  Only nonzero if 'success' is false
     */
    default void reportUnlockAttempt(int userId, boolean success, int timeoutMs) {
    }

    /**
     * Resets the keyguard view.
     */
    default void reset() {
    }

    /**
     * Call when cancel button is pressed in bouncer.
     */
    default void onCancelClicked() {
        // No-op
    }

    /**
     * Invoked whenever users are typing their password or drawing a pattern.
     */
    default void onUserInput() {
    }

    /**
     * Invoked when the auth input is disabled for specified number of seconds.
     * @param seconds Number of seconds for which the auth input is disabled.
     */
    default void onAttemptLockoutStart(long seconds) {}

    /**
     * Dismisses keyguard and go to unlocked state.
     */
    default void finish(int targetUserId) {
    }

    /**
     * Specifies that security mode has changed.
     */
    default void onSecurityModeChanged(SecurityMode securityMode, boolean needsInput) {
    }

    /**
     * Shows the security screen that should be shown.
     *
     * This can be considered as a "refresh" of the bouncer view. Based on certain parameters,
     * we might switch to a different bouncer screen. e.g. SimPin to SimPuk.
     */
    default void showCurrentSecurityScreen() {

    }
}
