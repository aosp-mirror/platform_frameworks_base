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

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Utility functions for the flow where the RecoverableKeyStoreLoader syncs keys with remote
 * storage.
 *
 * @hide
 */
public class KeySyncUtils {

    private static final String RECOVERY_KEY_ALGORITHM = "AES";
    private static final int RECOVERY_KEY_SIZE_BITS = 256;

    private static final byte[] THM_ENCRYPTED_RECOVERY_KEY_HEADER =
            "V1 THM_encrypted_recovery_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOCALLY_ENCRYPTED_RECOVERY_KEY_HEADER =
            "V1 locally_encrypted_recovery_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENCRYPTED_APPLICATION_KEY_HEADER =
            "V1 encrypted_application_key".getBytes(StandardCharsets.UTF_8);

    private static final byte[] THM_KF_HASH_PREFIX = "THM_KF_hash".getBytes(StandardCharsets.UTF_8);

    /**
     * Encrypts the recovery key using both the lock screen hash and the remote storage's public
     * key.
     *
     * @param publicKey The public key of the remote storage.
     * @param lockScreenHash The user's lock screen hash.
     * @param vaultParams Additional parameters to send to the remote storage.
     * @param recoveryKey The recovery key.
     * @return The encrypted bytes.
     * @throws NoSuchAlgorithmException if any SecureBox algorithm is unavailable.
     * @throws InvalidKeyException if the public key or the lock screen could not be used to encrypt
     *     the data.
     *
     * @hide
     */
    public byte[] thmEncryptRecoveryKey(
            PublicKey publicKey,
            byte[] lockScreenHash,
            byte[] vaultParams,
            SecretKey recoveryKey
    ) throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] encryptedRecoveryKey = locallyEncryptRecoveryKey(lockScreenHash, recoveryKey);
        byte[] thmKfHash = calculateThmKfHash(lockScreenHash);
        byte[] header = concat(THM_ENCRYPTED_RECOVERY_KEY_HEADER, vaultParams);
        return SecureBox.encrypt(
                /*theirPublicKey=*/ publicKey,
                /*sharedSecret=*/ thmKfHash,
                /*header=*/ header,
                /*payload=*/ encryptedRecoveryKey);
    }

    /**
     * Calculates the THM_KF hash of the lock screen hash.
     *
     * @param lockScreenHash The lock screen hash.
     * @return The hash.
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable (should never happen).
     *
     * @hide
     */
    public static byte[] calculateThmKfHash(byte[] lockScreenHash)
            throws NoSuchAlgorithmException {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        messageDigest.update(THM_KF_HASH_PREFIX);
        messageDigest.update(lockScreenHash);
        return messageDigest.digest();
    }

    /**
     * Encrypts the recovery key using the lock screen hash.
     *
     * @param lockScreenHash The raw lock screen hash.
     * @param recoveryKey The recovery key.
     * @return The encrypted bytes.
     * @throws NoSuchAlgorithmException if any SecureBox algorithm is unavailable.
     * @throws InvalidKeyException if the hash cannot be used to encrypt for some reason.
     */
    private static byte[] locallyEncryptRecoveryKey(byte[] lockScreenHash, SecretKey recoveryKey)
            throws NoSuchAlgorithmException, InvalidKeyException {
        return SecureBox.encrypt(
                /*theirPublicKey=*/ null,
                /*sharedSecret=*/ lockScreenHash,
                /*header=*/ LOCALLY_ENCRYPTED_RECOVERY_KEY_HEADER,
                /*payload=*/ recoveryKey.getEncoded());
    }

    /**
     * Returns a new random 256-bit AES recovery key.
     *
     * @hide
     */
    public static SecretKey generateRecoveryKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(RECOVERY_KEY_ALGORITHM);
        keyGenerator.init(RECOVERY_KEY_SIZE_BITS, SecureRandom.getInstanceStrong());
        return keyGenerator.generateKey();
    }

    /**
     * Encrypts all of the given keys with the recovery key, using SecureBox.
     *
     * @param recoveryKey The recovery key.
     * @param keys The keys, indexed by their aliases.
     * @return The encrypted key material, indexed by aliases.
     * @throws NoSuchAlgorithmException if any of the SecureBox algorithms are unavailable.
     * @throws InvalidKeyException if the recovery key is not appropriate for encrypting the keys.
     *
     * @hide
     */
    public static Map<String, byte[]> encryptKeysWithRecoveryKey(
            SecretKey recoveryKey, Map<String, SecretKey> keys)
            throws NoSuchAlgorithmException, InvalidKeyException {
        HashMap<String, byte[]> encryptedKeys = new HashMap<>();
        for (String alias : keys.keySet()) {
            SecretKey key = keys.get(alias);
            byte[] encryptedKey = SecureBox.encrypt(
                    /*theirPublicKey=*/ null,
                    /*sharedSecret=*/ recoveryKey.getEncoded(),
                    /*header=*/ ENCRYPTED_APPLICATION_KEY_HEADER,
                    /*payload=*/ key.getEncoded());
            encryptedKeys.put(alias, encryptedKey);
        }
        return encryptedKeys;
    }

    /**
     * Returns a new array, the contents of which are the concatenation of {@code a} and {@code b}.
     */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    // Statics only
    private KeySyncUtils() {}
}
