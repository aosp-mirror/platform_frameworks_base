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

package com.android.server.locksettings;

import android.annotation.IntDef;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.crypto.SecretKey;

/**
 * Provides APIs for {@link RebootEscrowManager} to access and manage the reboot escrow key.
 * Implementations need to find a way to persist the key across a reboot, and securely discards the
 * persisted copy.
 *
 * @hide
 */
public interface RebootEscrowProviderInterface {
    @IntDef(prefix = {"TYPE_"}, value = {
            TYPE_HAL,
            TYPE_SERVER_BASED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RebootEscrowProviderType {
    }
    int TYPE_HAL = 0;
    int TYPE_SERVER_BASED = 1;

    /**
     * Returns the reboot escrow provider type.
     */
    @RebootEscrowProviderType int getType();

    /**
     * Returns true if the secure store/discard of reboot escrow key is supported.
     */
    boolean hasRebootEscrowSupport();

    /**
     * Returns the stored RebootEscrowKey, and clears the storage. If the stored key is encrypted,
     * use the input key to decrypt the RebootEscrowKey. Returns null on failure. Throws an
     * IOException if the failure is non-fatal, and a retry may succeed.
     */
    RebootEscrowKey getAndClearRebootEscrowKey(SecretKey decryptionKey) throws IOException;

    /**
     * Clears the stored RebootEscrowKey.
     */
    void clearRebootEscrowKey();

    /**
     * Saves the given RebootEscrowKey, optionally encrypt the storage with the encryptionKey.
     */
    boolean storeRebootEscrowKey(RebootEscrowKey escrowKey, SecretKey encryptionKey);
}
