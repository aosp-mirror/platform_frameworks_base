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


/**
 * LockSettingsService local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class LockSettingsInternal {

    /**
     * Create an escrow token for the current user, which can later be used to unlock FBE
     * or change user password.
     *
     * After adding, if the user currently has lockscreen password, he will need to perform a
     * confirm credential operation in order to activate the token for future use. If the user
     * has no secure lockscreen, then the token is activated immediately.
     *
     * @return a unique 64-bit token handle which is needed to refer to this token later.
     */
    public abstract long addEscrowToken(byte[] token, int userId);

    /**
     * Remove an escrow token.
     * @return true if the given handle refers to a valid token previously returned from
     * {@link #addEscrowToken}, whether it's active or not. return false otherwise.
     */
    public abstract boolean removeEscrowToken(long handle, int userId);

    /**
     * Check if the given escrow token is active or not. Only active token can be used to call
     * {@link #setLockCredentialWithToken} and {@link #unlockUserWithToken}
     */
    public abstract boolean isEscrowTokenActive(long handle, int userId);

    public abstract boolean setLockCredentialWithToken(String credential, int type,
            long tokenHandle, byte[] token, int requestedQuality, int userId);

    public abstract boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId);
}
