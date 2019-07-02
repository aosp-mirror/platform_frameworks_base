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
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Utility functions for the flow where the RecoveryController syncs keys with remote storage.
 *
 * @hide
 */
public class KeySyncUtils {

    private static final String PUBLIC_KEY_FACTORY_ALGORITHM = "EC";
    private static final String RECOVERY_KEY_ALGORITHM = "AES";
    private static final int RECOVERY_KEY_SIZE_BITS = 256;

    private static final byte[] THM_ENCRYPTED_RECOVERY_KEY_HEADER =
            "V1 THM_encrypted_recovery_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOCALLY_ENCRYPTED_RECOVERY_KEY_HEADER =
            "V1 locally_encrypted_recovery_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENCRYPTED_APPLICATION_KEY_HEADER =
            "V1 encrypted_application_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECOVERY_CLAIM_HEADER =
            "V1 KF_claim".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECOVERY_RESPONSE_HEADER =
            "V1 reencrypted_recovery_key".getBytes(StandardCharsets.UTF_8);

    private static final byte[] THM_KF_HASH_PREFIX = "THM_KF_hash".getBytes(StandardCharsets.UTF_8);

    private static final int KEY_CLAIMANT_LENGTH_BYTES = 16;

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
    public static byte[] thmEncryptRecoveryKey(
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
    @VisibleForTesting
    static byte[] locallyEncryptRecoveryKey(byte[] lockScreenHash, SecretKey recoveryKey)
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
        keyGenerator.init(RECOVERY_KEY_SIZE_BITS, new SecureRandom());
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
            SecretKey recoveryKey, Map<String, Pair<SecretKey, byte[]>> keys)
            throws NoSuchAlgorithmException, InvalidKeyException {
        HashMap<String, byte[]> encryptedKeys = new HashMap<>();
        for (String alias : keys.keySet()) {
            SecretKey key = keys.get(alias).first;
            byte[] metadata = keys.get(alias).second;
            byte[] header;
            if (metadata == null) {
                header = ENCRYPTED_APPLICATION_KEY_HEADER;
            } else {
                // The provided metadata, if non-empty, will be bound to the authenticated
                // encryption process of the key material. As a result, the ciphertext cannot be
                // decrypted if a wrong metadata is provided during the recovery/decryption process.
                // Note that Android P devices do not have the API to provide the optional metadata,
                // so all the keys with non-empty metadata stored on Android Q+ devices cannot be
                // recovered on Android P devices.
                header = concat(ENCRYPTED_APPLICATION_KEY_HEADER, metadata);
            }
            byte[] encryptedKey = SecureBox.encrypt(
                    /*theirPublicKey=*/ null,
                    /*sharedSecret=*/ recoveryKey.getEncoded(),
                    /*header=*/ header,
                    /*payload=*/ key.getEncoded());
            encryptedKeys.put(alias, encryptedKey);
        }
        return encryptedKeys;
    }

    /**
     * Returns a random 16-byte key claimant.
     *
     * @hide
     */
    public static byte[] generateKeyClaimant() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] key = new byte[KEY_CLAIMANT_LENGTH_BYTES];
        secureRandom.nextBytes(key);
        return key;
    }

    /**
     * Encrypts a claim to recover a remote recovery key.
     *
     * @param publicKey The public key of the remote server.
     * @param vaultParams Associated vault parameters.
     * @param challenge The challenge issued by the server.
     * @param thmKfHash The THM hash of the lock screen.
     * @param keyClaimant The random key claimant.
     * @return The encrypted recovery claim, to be sent to the remote server.
     * @throws NoSuchAlgorithmException if any SecureBox algorithm is not present.
     * @throws InvalidKeyException if the {@code publicKey} could not be used to encrypt.
     *
     * @hide
     */
    public static byte[] encryptRecoveryClaim(
            PublicKey publicKey,
            byte[] vaultParams,
            byte[] challenge,
            byte[] thmKfHash,
            byte[] keyClaimant) throws NoSuchAlgorithmException, InvalidKeyException {
        return SecureBox.encrypt(
                publicKey,
                /*sharedSecret=*/ null,
                /*header=*/ concat(RECOVERY_CLAIM_HEADER, vaultParams, challenge),
                /*payload=*/ concat(thmKfHash, keyClaimant));
    }

    /**
     * Decrypts response from recovery claim, returning the locally encrypted key.
     *
     * @param keyClaimant The key claimant, used by the remote service to encrypt the response.
     * @param vaultParams Vault params associated with the claim.
     * @param encryptedResponse The encrypted response.
     * @return The locally encrypted recovery key.
     * @throws NoSuchAlgorithmException if any SecureBox algorithm is not present.
     * @throws InvalidKeyException if the {@code keyClaimant} could not be used to decrypt.
     * @throws AEADBadTagException if the message has been tampered with or was encrypted with a
     *     different key.
     */
    public static byte[] decryptRecoveryClaimResponse(
            byte[] keyClaimant, byte[] vaultParams, byte[] encryptedResponse)
            throws NoSuchAlgorithmException, InvalidKeyException, AEADBadTagException {
        return SecureBox.decrypt(
                /*ourPrivateKey=*/ null,
                /*sharedSecret=*/ keyClaimant,
                /*header=*/ concat(RECOVERY_RESPONSE_HEADER, vaultParams),
                /*encryptedPayload=*/ encryptedResponse);
    }

    /**
     * Decrypts a recovery key, after having retrieved it from a remote server.
     *
     * @param lskfHash The lock screen hash associated with the key.
     * @param encryptedRecoveryKey The encrypted key.
     * @return The raw key material.
     * @throws NoSuchAlgorithmException if any SecureBox algorithm is unavailable.
     * @throws AEADBadTagException if the message has been tampered with or was encrypted with a
     *     different key.
     */
    public static byte[] decryptRecoveryKey(byte[] lskfHash, byte[] encryptedRecoveryKey)
            throws NoSuchAlgorithmException, InvalidKeyException, AEADBadTagException {
        return SecureBox.decrypt(
                /*ourPrivateKey=*/ null,
                /*sharedSecret=*/ lskfHash,
                /*header=*/ LOCALLY_ENCRYPTED_RECOVERY_KEY_HEADER,
                /*encryptedPayload=*/ encryptedRecoveryKey);
    }

    /**
     * Decrypts an application key, using the recovery key.
     *
     * @param recoveryKey The recovery key - used to wrap all application keys.
     * @param encryptedApplicationKey The application key to unwrap.
     * @return The raw key material of the application key.
     * @throws NoSuchAlgorithmException if any SecureBox algorithm is unavailable.
     * @throws AEADBadTagException if the message has been tampered with or was encrypted with a
     *     different key.
     */
    public static byte[] decryptApplicationKey(byte[] recoveryKey, byte[] encryptedApplicationKey,
            @Nullable byte[] applicationKeyMetadata)
            throws NoSuchAlgorithmException, InvalidKeyException, AEADBadTagException {
        byte[] header;
        if (applicationKeyMetadata == null) {
            header = ENCRYPTED_APPLICATION_KEY_HEADER;
        } else {
            header = concat(ENCRYPTED_APPLICATION_KEY_HEADER, applicationKeyMetadata);
        }
        return SecureBox.decrypt(
                /*ourPrivateKey=*/ null,
                /*sharedSecret=*/ recoveryKey,
                /*header=*/ header,
                /*encryptedPayload=*/ encryptedApplicationKey);
    }

    /**
     * Deserializes a X509 public key.
     *
     * @param key The bytes of the key.
     * @return The key.
     * @throws InvalidKeySpecException if the bytes of the key are not a valid key.
     */
    public static PublicKey deserializePublicKey(byte[] key) throws InvalidKeySpecException {
        KeyFactory keyFactory;
        try {
            keyFactory = KeyFactory.getInstance(PUBLIC_KEY_FACTORY_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Should not happen
            throw new RuntimeException(e);
        }
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(key);
        return keyFactory.generatePublic(publicKeySpec);
    }

    /**
     * Packs vault params into a binary format.
     *
     * @param thmPublicKey Public key of the trusted hardware module.
     * @param counterId ID referring to the specific counter in the hardware module.
     * @param maxAttempts Maximum allowed guesses before trusted hardware wipes key.
     * @param vaultHandle Handle of the Vault.
     * @return The binary vault params, ready for sync.
     */
    public static byte[] packVaultParams(
            PublicKey thmPublicKey, long counterId, int maxAttempts, byte[] vaultHandle) {
        int vaultParamsLength
                = 65 // public key
                + 8 // counterId
                + 4 // maxAttempts
                + vaultHandle.length;
        return ByteBuffer.allocate(vaultParamsLength)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(SecureBox.encodePublicKey(thmPublicKey))
                .putLong(counterId)
                .putInt(maxAttempts)
                .put(vaultHandle)
                .array();
    }

    /**
     * Returns the concatenation of all the given {@code arrays}.
     */
    @VisibleForTesting
    static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }

        byte[] concatenated = new byte[length];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, /*srcPos=*/ 0, concatenated, pos, array.length);
            pos += array.length;
        }

        return concatenated;
    }

    // Statics only
    private KeySyncUtils() {}
}
