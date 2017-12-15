/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import android.annotation.Nullable;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;

import javax.crypto.AEADBadTagException;

/**
 * TODO(b/69056040) Add implementation of SecureBox. This is a placeholder so KeySyncUtils compiles.
 *
 * @hide
 */
public class SecureBox {
    /**
     * TODO(b/69056040) Add implementation of encrypt.
     *
     * @hide
     */
    public static byte[] encrypt(
            @Nullable PublicKey theirPublicKey,
            @Nullable byte[] sharedSecret,
            @Nullable byte[] header,
            @Nullable byte[] payload)
            throws NoSuchAlgorithmException, InvalidKeyException {
        throw new UnsupportedOperationException("Needs to be implemented.");
    }

    /**
     * TODO(b/69056040) Add implementation of decrypt.
     *
     * @hide
     */
    public static byte[] decrypt(
            @Nullable PrivateKey ourPrivateKey,
            @Nullable byte[] sharedSecret,
            @Nullable byte[] header,
            byte[] encryptedPayload)
            throws NoSuchAlgorithmException, InvalidKeyException, AEADBadTagException {
        throw new UnsupportedOperationException("Needs to be implemented.");
    }
}
