/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.deviceentry.shared.model

/** List of reasons why device entry can be restricted to certain authentication methods. */
enum class DeviceEntryRestrictionReason {
    /**
     * Reason: Lockdown initiated by the user.
     *
     * Restriction: Only bouncer based device entry is allowed.
     */
    UserLockdown,

    /**
     * Reason: Not unlocked since reboot.
     *
     * Restriction: Only bouncer based device entry is allowed.
     */
    DeviceNotUnlockedSinceReboot,

    /**
     * Reason: Not unlocked since reboot after a mainline update.
     *
     * Restriction: Only bouncer based device entry is allowed.
     */
    DeviceNotUnlockedSinceMainlineUpdate,

    /**
     * Reason: Lockdown initiated by admin through installed device policy
     *
     * Restriction: Only bouncer based device entry is allowed.
     */
    PolicyLockdown,

    /**
     * Reason: Device entry credentials need to be used for an unattended update at a later point in
     * time.
     *
     * Restriction: Only bouncer based device entry is allowed.
     */
    UnattendedUpdate,

    /**
     * Reason: Device was not unlocked using PIN/Pattern/Password for a prolonged period of time.
     *
     * Restriction: Only bouncer based device entry is allowed.
     */
    SecurityTimeout,

    /**
     * Reason: A "class 3"/strong biometrics device entry method was locked out after many incorrect
     * authentication attempts.
     *
     * Restriction: Only bouncer based device entry is allowed.
     *
     * @see
     *   [Biometric classes](https://source.android.com/docs/security/features/biometric/measure#biometric-classes)
     */
    StrongBiometricsLockedOut,

    /**
     * Reason: A weak (class 2)/convenience (class 3) strength face biometrics device entry method
     * was locked out after many incorrect authentication attempts.
     *
     * Restriction: Only stronger authentication methods (class 3 or bouncer) are allowed.
     *
     * @see
     *   [Biometric classes](https://source.android.com/docs/security/features/biometric/measure#biometric-classes)
     */
    NonStrongFaceLockedOut,

    /**
     * Reason: Device was last unlocked using a weak/convenience strength biometrics device entry
     * method and a stronger authentication method wasn't used to unlock the device for a prolonged
     * period of time.
     *
     * Restriction: Only stronger authentication methods (class 3 or bouncer) are allowed.
     *
     * @see
     *   [Biometric classes](https://source.android.com/docs/security/features/biometric/measure#biometric-classes)
     */
    NonStrongBiometricsSecurityTimeout,

    /**
     * Reason: A trust agent that was granting trust has either expired or disabled by the user by
     * opening the power menu.
     *
     * Restriction: Only non trust agent device entry methods are allowed.
     */
    TrustAgentDisabled,

    /**
     * Reason: Theft protection is enabled after too many unlock attempts.
     *
     * Restriction: Only stronger authentication methods (class 3 or bouncer) are allowed.
     */
    AdaptiveAuthRequest,

    /**
     * Reason: Bouncer was locked out after too many incorrect authentication attempts.
     *
     * Restriction: Only bouncer based device entry is allowed.
     */
    BouncerLockedOut,
}
