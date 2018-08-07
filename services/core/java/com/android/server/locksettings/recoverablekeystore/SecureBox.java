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
import com.android.internal.annotations.VisibleForTesting;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Implementation of the SecureBox v2 crypto functions.
 *
 * <p>Securebox v2 provides a simple interface to perform encryptions by using any of the following
 * credential types:
 *
 * <ul>
 *   <li>A public key owned by the recipient,
 *   <li>A secret shared between the sender and the recipient, or
 *   <li>Both a recipient's public key and a shared secret.
 * </ul>
 *
 * @hide
 */
public class SecureBox {

    private static final byte[] VERSION = new byte[] {(byte) 0x02, 0}; // LITTLE_ENDIAN_TWO_BYTES(2)
    private static final byte[] HKDF_SALT =
            concat("SECUREBOX".getBytes(StandardCharsets.UTF_8), VERSION);
    private static final byte[] HKDF_INFO_WITH_PUBLIC_KEY =
            "P256 HKDF-SHA-256 AES-128-GCM".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO_WITHOUT_PUBLIC_KEY =
            "SHARED HKDF-SHA-256 AES-128-GCM".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONSTANT_01 = {(byte) 0x01};
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final byte EC_PUBLIC_KEY_PREFIX = (byte) 0x04;

    private static final String CIPHER_ALG = "AES";
    private static final String EC_ALG = "EC";
    private static final String EC_P256_COMMON_NAME = "secp256r1";
    private static final String EC_P256_OPENSSL_NAME = "prime256v1";
    private static final String ENC_ALG = "AES/GCM/NoPadding";
    private static final String KA_ALG = "ECDH";
    private static final String MAC_ALG = "HmacSHA256";

    private static final int EC_COORDINATE_LEN_BYTES = 32;
    private static final int EC_PUBLIC_KEY_LEN_BYTES = 2 * EC_COORDINATE_LEN_BYTES + 1;
    private static final int GCM_NONCE_LEN_BYTES = 12;
    private static final int GCM_KEY_LEN_BYTES = 16;
    private static final int GCM_TAG_LEN_BYTES = 16;

    private static final BigInteger BIG_INT_02 = BigInteger.valueOf(2);

    private enum AesGcmOperation {
        ENCRYPT,
        DECRYPT
    }

    // Parameters for the NIST P-256 curve y^2 = x^3 + ax + b (mod p)
    private static final BigInteger EC_PARAM_P =
            new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger EC_PARAM_A = EC_PARAM_P.subtract(new BigInteger("3"));
    private static final BigInteger EC_PARAM_B =
            new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);

    @VisibleForTesting static final ECParameterSpec EC_PARAM_SPEC;

    static {
        EllipticCurve curveSpec =
                new EllipticCurve(new ECFieldFp(EC_PARAM_P), EC_PARAM_A, EC_PARAM_B);
        ECPoint generator =
                new ECPoint(
                        new BigInteger(
                                "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
                                16),
                        new BigInteger(
                                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
                                16));
        BigInteger generatorOrder =
                new BigInteger(
                        "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
        EC_PARAM_SPEC = new ECParameterSpec(curveSpec, generator, generatorOrder, /* cofactor */ 1);
    }

    private SecureBox() {}

    /**
     * Randomly generates a public-key pair that can be used for the functions {@link #encrypt} and
     * {@link #decrypt}.
     *
     * @return the randomly generated public-key pair
     * @throws NoSuchAlgorithmException if the underlying crypto algorithm is not supported
     * @hide
     */
    public static KeyPair genKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(EC_ALG);
        try {
            // Try using the OpenSSL provider first
            keyPairGenerator.initialize(new ECGenParameterSpec(EC_P256_OPENSSL_NAME));
            return keyPairGenerator.generateKeyPair();
        } catch (InvalidAlgorithmParameterException ex) {
            // Try another name for NIST P-256
        }
        try {
            keyPairGenerator.initialize(new ECGenParameterSpec(EC_P256_COMMON_NAME));
            return keyPairGenerator.generateKeyPair();
        } catch (InvalidAlgorithmParameterException ex) {
            throw new NoSuchAlgorithmException("Unable to find the NIST P-256 curve", ex);
        }
    }

    /**
     * Encrypts {@code payload} by using {@code theirPublicKey} and/or {@code sharedSecret}. At
     * least one of {@code theirPublicKey} and {@code sharedSecret} must be non-null, and an empty
     * {@code sharedSecret} is equivalent to null.
     *
     * <p>Note that {@code header} will be authenticated (but not encrypted) together with {@code
     * payload}, and the same {@code header} has to be provided for {@link #decrypt}.
     *
     * @param theirPublicKey the recipient's public key, or null if the payload is to be encrypted
     *     only with the shared secret
     * @param sharedSecret the secret shared between the sender and the recipient, or null if the
     *     payload is to be encrypted only with the recipient's public key
     * @param header the data that will be authenticated with {@code payload} but not encrypted, or
     *     null if the data is empty
     * @param payload the data to be encrypted, or null if the data is empty
     * @return the encrypted payload
     * @throws NoSuchAlgorithmException if any underlying crypto algorithm is not supported
     * @throws InvalidKeyException if the provided key is invalid for underlying crypto algorithms
     * @hide
     */
    public static byte[] encrypt(
            @Nullable PublicKey theirPublicKey,
            @Nullable byte[] sharedSecret,
            @Nullable byte[] header,
            @Nullable byte[] payload)
            throws NoSuchAlgorithmException, InvalidKeyException {
        sharedSecret = emptyByteArrayIfNull(sharedSecret);
        if (theirPublicKey == null && sharedSecret.length == 0) {
            throw new IllegalArgumentException("Both the public key and shared secret are empty");
        }
        header = emptyByteArrayIfNull(header);
        payload = emptyByteArrayIfNull(payload);

        KeyPair senderKeyPair;
        byte[] dhSecret;
        byte[] hkdfInfo;
        if (theirPublicKey == null) {
            senderKeyPair = null;
            dhSecret = EMPTY_BYTE_ARRAY;
            hkdfInfo = HKDF_INFO_WITHOUT_PUBLIC_KEY;
        } else {
            senderKeyPair = genKeyPair();
            dhSecret = dhComputeSecret(senderKeyPair.getPrivate(), theirPublicKey);
            hkdfInfo = HKDF_INFO_WITH_PUBLIC_KEY;
        }

        byte[] randNonce = genRandomNonce();
        byte[] keyingMaterial = concat(dhSecret, sharedSecret);
        SecretKey encryptionKey = hkdfDeriveKey(keyingMaterial, HKDF_SALT, hkdfInfo);
        byte[] ciphertext = aesGcmEncrypt(encryptionKey, randNonce, payload, header);
        if (senderKeyPair == null) {
            return concat(VERSION, randNonce, ciphertext);
        } else {
            return concat(
                    VERSION, encodePublicKey(senderKeyPair.getPublic()), randNonce, ciphertext);
        }
    }

    /**
     * Decrypts {@code encryptedPayload} by using {@code ourPrivateKey} and/or {@code sharedSecret}.
     * At least one of {@code ourPrivateKey} and {@code sharedSecret} must be non-null, and an empty
     * {@code sharedSecret} is equivalent to null.
     *
     * <p>Note that {@code header} should be the same data used for {@link #encrypt}, which is
     * authenticated (but not encrypted) together with {@code payload}; otherwise, an {@code
     * AEADBadTagException} will be thrown.
     *
     * @param ourPrivateKey the recipient's private key, or null if the payload was encrypted only
     *     with the shared secret
     * @param sharedSecret the secret shared between the sender and the recipient, or null if the
     *     payload was encrypted only with the recipient's public key
     * @param header the data that was authenticated with the original payload but not encrypted, or
     *     null if the data is empty
     * @param encryptedPayload the data to be decrypted
     * @return the original payload that was encrypted
     * @throws NoSuchAlgorithmException if any underlying crypto algorithm is not supported
     * @throws InvalidKeyException if the provided key is invalid for underlying crypto algorithms
     * @throws AEADBadTagException if the authentication tag contained in {@code encryptedPayload}
     *     cannot be validated, or if the payload is not a valid SecureBox V2 payload.
     * @hide
     */
    public static byte[] decrypt(
            @Nullable PrivateKey ourPrivateKey,
            @Nullable byte[] sharedSecret,
            @Nullable byte[] header,
            byte[] encryptedPayload)
            throws NoSuchAlgorithmException, InvalidKeyException, AEADBadTagException {
        sharedSecret = emptyByteArrayIfNull(sharedSecret);
        if (ourPrivateKey == null && sharedSecret.length == 0) {
            throw new IllegalArgumentException("Both the private key and shared secret are empty");
        }
        header = emptyByteArrayIfNull(header);
        if (encryptedPayload == null) {
            throw new NullPointerException("Encrypted payload must not be null.");
        }

        ByteBuffer ciphertextBuffer = ByteBuffer.wrap(encryptedPayload);
        byte[] version = readEncryptedPayload(ciphertextBuffer, VERSION.length);
        if (!Arrays.equals(version, VERSION)) {
            throw new AEADBadTagException("The payload was not encrypted by SecureBox v2");
        }

        byte[] senderPublicKeyBytes;
        byte[] dhSecret;
        byte[] hkdfInfo;
        if (ourPrivateKey == null) {
            dhSecret = EMPTY_BYTE_ARRAY;
            hkdfInfo = HKDF_INFO_WITHOUT_PUBLIC_KEY;
        } else {
            senderPublicKeyBytes = readEncryptedPayload(ciphertextBuffer, EC_PUBLIC_KEY_LEN_BYTES);
            dhSecret = dhComputeSecret(ourPrivateKey, decodePublicKey(senderPublicKeyBytes));
            hkdfInfo = HKDF_INFO_WITH_PUBLIC_KEY;
        }

        byte[] randNonce = readEncryptedPayload(ciphertextBuffer, GCM_NONCE_LEN_BYTES);
        byte[] ciphertext = readEncryptedPayload(ciphertextBuffer, ciphertextBuffer.remaining());
        byte[] keyingMaterial = concat(dhSecret, sharedSecret);
        SecretKey decryptionKey = hkdfDeriveKey(keyingMaterial, HKDF_SALT, hkdfInfo);
        return aesGcmDecrypt(decryptionKey, randNonce, ciphertext, header);
    }

    private static byte[] readEncryptedPayload(ByteBuffer buffer, int length)
            throws AEADBadTagException {
        byte[] output = new byte[length];
        try {
            buffer.get(output);
        } catch (BufferUnderflowException ex) {
            throw new AEADBadTagException("The encrypted payload is too short");
        }
        return output;
    }

    private static byte[] dhComputeSecret(PrivateKey ourPrivateKey, PublicKey theirPublicKey)
            throws NoSuchAlgorithmException, InvalidKeyException {
        KeyAgreement agreement = KeyAgreement.getInstance(KA_ALG);
        try {
            agreement.init(ourPrivateKey);
        } catch (RuntimeException ex) {
            // Rethrow the RuntimeException as InvalidKeyException
            throw new InvalidKeyException(ex);
        }
        agreement.doPhase(theirPublicKey, /*lastPhase=*/ true);
        return agreement.generateSecret();
    }

    /** Derives a 128-bit AES key. */
    private static SecretKey hkdfDeriveKey(byte[] secret, byte[] salt, byte[] info)
            throws NoSuchAlgorithmException {
        Mac mac = Mac.getInstance(MAC_ALG);
        try {
            mac.init(new SecretKeySpec(salt, MAC_ALG));
        } catch (InvalidKeyException ex) {
            // This should never happen
            throw new RuntimeException(ex);
        }
        byte[] pseudorandomKey = mac.doFinal(secret);

        try {
            mac.init(new SecretKeySpec(pseudorandomKey, MAC_ALG));
        } catch (InvalidKeyException ex) {
            // This should never happen
            throw new RuntimeException(ex);
        }
        mac.update(info);
        // Hashing just one block will yield 256 bits, which is enough to construct the AES key
        byte[] hkdfOutput = mac.doFinal(CONSTANT_01);

        return new SecretKeySpec(Arrays.copyOf(hkdfOutput, GCM_KEY_LEN_BYTES), CIPHER_ALG);
    }

    private static byte[] aesGcmEncrypt(SecretKey key, byte[] nonce, byte[] plaintext, byte[] aad)
            throws NoSuchAlgorithmException, InvalidKeyException {
        try {
            return aesGcmInternal(AesGcmOperation.ENCRYPT, key, nonce, plaintext, aad);
        } catch (AEADBadTagException ex) {
            // This should never happen
            throw new RuntimeException(ex);
        }
    }

    private static byte[] aesGcmDecrypt(SecretKey key, byte[] nonce, byte[] ciphertext, byte[] aad)
            throws NoSuchAlgorithmException, InvalidKeyException, AEADBadTagException {
        return aesGcmInternal(AesGcmOperation.DECRYPT, key, nonce, ciphertext, aad);
    }

    private static byte[] aesGcmInternal(
            AesGcmOperation operation, SecretKey key, byte[] nonce, byte[] text, byte[] aad)
            throws NoSuchAlgorithmException, InvalidKeyException, AEADBadTagException {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(ENC_ALG);
        } catch (NoSuchPaddingException ex) {
            // This should never happen because AES-GCM doesn't use padding
            throw new RuntimeException(ex);
        }
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN_BYTES * 8, nonce);
        try {
            if (operation == AesGcmOperation.DECRYPT) {
                cipher.init(Cipher.DECRYPT_MODE, key, spec);
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            }
        } catch (InvalidAlgorithmParameterException ex) {
            // This should never happen
            throw new RuntimeException(ex);
        }
        try {
            cipher.updateAAD(aad);
            return cipher.doFinal(text);
        } catch (AEADBadTagException ex) {
            // Catch and rethrow AEADBadTagException first because it's a subclass of
            // BadPaddingException
            throw ex;
        } catch (IllegalBlockSizeException | BadPaddingException ex) {
            // This should never happen because AES-GCM can handle inputs of any length without
            // padding
            throw new RuntimeException(ex);
        }
    }

    /**
     * Encodes public key in format expected by the secure hardware module. This is used as part
     * of the vault params.
     *
     * @param publicKey The public key.
     * @return The key packed into a 65-byte array.
     */
    static byte[] encodePublicKey(PublicKey publicKey) {
        ECPoint point = ((ECPublicKey) publicKey).getW();
        byte[] x = point.getAffineX().toByteArray();
        byte[] y = point.getAffineY().toByteArray();

        byte[] output = new byte[EC_PUBLIC_KEY_LEN_BYTES];
        // The order of arraycopy() is important, because the coordinates may have a one-byte
        // leading 0 for the sign bit of two's complement form
        System.arraycopy(y, 0, output, EC_PUBLIC_KEY_LEN_BYTES - y.length, y.length);
        System.arraycopy(x, 0, output, 1 + EC_COORDINATE_LEN_BYTES - x.length, x.length);
        output[0] = EC_PUBLIC_KEY_PREFIX;
        return output;
    }

    @VisibleForTesting
    static PublicKey decodePublicKey(byte[] keyBytes)
            throws NoSuchAlgorithmException, InvalidKeyException {
        BigInteger x =
                new BigInteger(
                        /*signum=*/ 1,
                        Arrays.copyOfRange(keyBytes, 1, 1 + EC_COORDINATE_LEN_BYTES));
        BigInteger y =
                new BigInteger(
                        /*signum=*/ 1,
                        Arrays.copyOfRange(
                                keyBytes, 1 + EC_COORDINATE_LEN_BYTES, EC_PUBLIC_KEY_LEN_BYTES));

        // Checks if the point is indeed on the P-256 curve for security considerations
        validateEcPoint(x, y);

        KeyFactory keyFactory = KeyFactory.getInstance(EC_ALG);
        try {
            return keyFactory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), EC_PARAM_SPEC));
        } catch (InvalidKeySpecException ex) {
            // This should never happen
            throw new RuntimeException(ex);
        }
    }

    private static void validateEcPoint(BigInteger x, BigInteger y) throws InvalidKeyException {
        if (x.compareTo(EC_PARAM_P) >= 0
                || y.compareTo(EC_PARAM_P) >= 0
                || x.signum() == -1
                || y.signum() == -1) {
            throw new InvalidKeyException("Point lies outside of the expected curve");
        }

        // Points on the curve satisfy y^2 = x^3 + ax + b (mod p)
        BigInteger lhs = y.modPow(BIG_INT_02, EC_PARAM_P);
        BigInteger rhs =
                x.modPow(BIG_INT_02, EC_PARAM_P) // x^2
                        .add(EC_PARAM_A) // x^2 + a
                        .mod(EC_PARAM_P) // This will speed up the next multiplication
                        .multiply(x) // (x^2 + a) * x = x^3 + ax
                        .add(EC_PARAM_B) // x^3 + ax + b
                        .mod(EC_PARAM_P);
        if (!lhs.equals(rhs)) {
            throw new InvalidKeyException("Point lies outside of the expected curve");
        }
    }

    private static byte[] genRandomNonce() throws NoSuchAlgorithmException {
        byte[] nonce = new byte[GCM_NONCE_LEN_BYTES];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    @VisibleForTesting
    static byte[] concat(byte[]... inputs) {
        int length = 0;
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] == null) {
                inputs[i] = EMPTY_BYTE_ARRAY;
            }
            length += inputs[i].length;
        }

        byte[] output = new byte[length];
        int outputPos = 0;
        for (byte[] input : inputs) {
            System.arraycopy(input, /*srcPos=*/ 0, output, outputPos, input.length);
            outputPos += input.length;
        }
        return output;
    }

    private static byte[] emptyByteArrayIfNull(@Nullable byte[] input) {
        return input == null ? EMPTY_BYTE_ARRAY : input;
    }
}
