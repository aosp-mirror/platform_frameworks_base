/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.PasswordMetrics;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
/**
 * LockSettingsService local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class LockSettingsInternal {
    /** ErrorCode for armRebootEscrow failures. **/
    @IntDef(prefix = {"ARM_REBOOT_ERROR_"}, value = {
            ARM_REBOOT_ERROR_NONE,
            ARM_REBOOT_ERROR_UNSPECIFIED,
            ARM_REBOOT_ERROR_ESCROW_NOT_READY,
            ARM_REBOOT_ERROR_NO_PROVIDER,
            ARM_REBOOT_ERROR_PROVIDER_MISMATCH,
            ARM_REBOOT_ERROR_NO_ESCROW_KEY,
            ARM_REBOOT_ERROR_KEYSTORE_FAILURE,
            ARM_REBOOT_ERROR_STORE_ESCROW_KEY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ArmRebootEscrowErrorCode {}

    public static final int ARM_REBOOT_ERROR_NONE = 0;
    public static final int ARM_REBOOT_ERROR_UNSPECIFIED = 1;
    public static final int ARM_REBOOT_ERROR_ESCROW_NOT_READY = 2;
    public static final int ARM_REBOOT_ERROR_NO_PROVIDER = 3;
    public static final int ARM_REBOOT_ERROR_PROVIDER_MISMATCH = 4;
    public static final int ARM_REBOOT_ERROR_NO_ESCROW_KEY = 5;
    public static final int ARM_REBOOT_ERROR_KEYSTORE_FAILURE = 6;
    public static final int ARM_REBOOT_ERROR_STORE_ESCROW_KEY = 7;
    // TODO(b/183140900) split store escrow key errors into detailed ones.

    /**
     * This is called when Weaver is guaranteed to be available (if the device supports Weaver).
     * It does any synthetic password related work that was delayed from earlier in the boot.
     */
    public abstract void onThirdPartyAppsStarted();

    /**
     * Creates the locksettings state for a new user.
     * <p>
     * This includes creating a synthetic password and protecting it with an empty LSKF.
     *
     * @param userId the ID of the new user
     * @param userSerialNumber the serial number of the new user
     */
    public abstract void createNewUser(@UserIdInt int userId, int userSerialNumber);

    /**
     * Removes the locksettings state for the given user.
     * <p>
     * This includes removing the user's synthetic password and any protectors that are protecting
     * it.
     *
     * @param userId the ID of the user being removed
     */
    public abstract void removeUser(@UserIdInt int userId);

    /**
     * Create an escrow token for the current user, which can later be used to unlock FBE
     * or change user password.
     *
     * After adding, if the user currently has lockscreen password, they will need to perform a
     * confirm credential operation in order to activate the token for future use.
     * Once the token is activated, the callback that is passed here is called.   If the user
     * has no secure lockscreen, then the token is activated immediately.
     *
     * @return a unique 64-bit token handle which is needed to refer to this token later.
     */
    public abstract long addEscrowToken(byte[] token, int userId,
            LockPatternUtils.EscrowTokenStateChangeCallback callback);

    /**
     * Remove an escrow token.
     *
     * @return true if the given handle refers to a valid token previously returned from
     * {@link #addEscrowToken}, whether it's active or not. return false otherwise.
     */
    public abstract boolean removeEscrowToken(long handle, int userId);

    /**
     * Check if the given escrow token is active or not. Only active token can be used to call
     * {@link #setLockCredentialWithToken} and {@link #unlockUserWithToken}
     */
    public abstract boolean isEscrowTokenActive(long handle, int userId);

    /**
     * Set the lock credential.
     *
     * @return true if password is set.
     */
    public abstract boolean setLockCredentialWithToken(LockscreenCredential credential,
            long tokenHandle, byte[] token, int userId);

    public abstract boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId);

    /**
     * Returns PasswordMetrics object corresponding to the given user's lockscreen password.
     * If the user has a password but its metrics isn't known yet (for example if the device
     * has not been unlocked since boot), this method will return {@code null}.
     * If the user has no password, a default PasswordMetrics (PASSWORD_QUALITY_UNSPECIFIED)
     * will be returned.
     *
     * Calling this method on a managed profile user with unified challenge is undefined.
     *
     * @param userHandle the user for whom to provide metrics.
     * @return the user password metrics.
     */
    public abstract @Nullable PasswordMetrics getUserPasswordMetrics(int userHandle);

    /**
     * Prepare for reboot escrow. This triggers the strong auth to be required. After the escrow
     * is complete as indicated by calling to the listener registered with {@link
     * #setRebootEscrowListener}, then {@link #armRebootEscrow()} should be called before
     * rebooting to apply the update.
     */
    public abstract boolean prepareRebootEscrow();

    /**
     * Registers a listener for when the RebootEscrow HAL has stored its data needed for rebooting
     * for an OTA.
     *
     * @see RebootEscrowListener
     * @param listener
     */
    public abstract void setRebootEscrowListener(RebootEscrowListener listener);

    /**
     * Requests that any data needed for rebooting is cleared from the RebootEscrow HAL.
     */
    public abstract boolean clearRebootEscrow();

    /**
     * Should be called immediately before rebooting for an update. This depends on {@link
     * #prepareRebootEscrow()} having been called and the escrow completing.
     *
     * @return ARM_ERROR_NONE if the arming worked
     */
    public abstract @ArmRebootEscrowErrorCode int armRebootEscrow();


    /**
     * Refreshes pending strong auth timeout with the latest admin requirement set by device policy.
     */
    public abstract void refreshStrongAuthTimeout(int userId);

    /**
     * Register a LockSettingsStateListener
     * @param listener The listener to be registered
     */
    public abstract void registerLockSettingsStateListener(ILockSettingsStateListener listener);

    /**
     * Unregister a LockSettingsStateListener
     * @param listener The listener to be unregistered
     */
    public abstract void unregisterLockSettingsStateListener(ILockSettingsStateListener listener);
}
