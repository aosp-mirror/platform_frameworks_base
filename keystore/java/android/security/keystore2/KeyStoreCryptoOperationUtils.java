/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keystore2;

import android.app.ActivityThread;
import android.hardware.biometrics.BiometricManager;
import android.hardware.security.keymint.ErrorCode;
import android.security.GateKeeper;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyExpiredException;
import android.security.keystore.KeyNotYetValidException;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.UserNotAuthenticatedException;
import android.system.keystore2.Authorization;
import android.system.keystore2.ResponseCode;
import android.util.Log;

import libcore.util.EmptyArray;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Assorted utility methods for implementing crypto operations on top of KeyStore.
 *
 * @hide
 */
abstract class KeyStoreCryptoOperationUtils {

    private static volatile SecureRandom sRng;

    private KeyStoreCryptoOperationUtils() {}


    public static boolean canUserAuthorizationSucceed(AndroidKeyStoreKey key) {
        List<Long> keySids = new ArrayList<Long>();
        for (Authorization p : key.getAuthorizations()) {
            switch(p.keyParameter.tag) {
                case KeymasterDefs.KM_TAG_USER_SECURE_ID:
                    keySids.add(p.keyParameter.value.getLongInteger());
                    break;
                default:
                    break;
            }
        }
        if (keySids.isEmpty()) {
            // Key is not bound to any SIDs -- no amount of authentication will help here.
            return false;
        }
        long rootSid = GateKeeper.getSecureUserId();
        if ((rootSid != 0) && (keySids.contains(rootSid))) {
            // One of the key's SIDs is the current root SID -- user can be authenticated
            // against that SID.
            return true;
        }

        long[] biometricSids = ActivityThread
                .currentApplication()
                .getSystemService(BiometricManager.class)
                .getAuthenticatorIds();

        // The key must contain every biometric SID. This is because the current API surface
        // treats all biometrics (capable of keystore integration) equally. e.g. if the
        // device has multiple keystore-capable sensors, and one of the sensor's SIDs
        // changed, 1) there is no way for a developer to specify authentication with a
        // specific sensor (the one that hasn't changed), and 2) currently the only
        // signal to developers is the UserNotAuthenticatedException, which doesn't
        // indicate a specific sensor.
        boolean canUnlockViaBiometrics = true;
        for (long sid : biometricSids) {
            if (!keySids.contains(sid)) {
                canUnlockViaBiometrics = false;
                break;
            }
        }

        if (canUnlockViaBiometrics) {
            // All of the biometric SIDs are contained in the key's SIDs.
            return true;
        }

        // None of the key's SIDs can ever be authenticated
        return false;
    }

    /**
     * Returns an {@link InvalidKeyException} corresponding to the provided
     * {@link KeyStoreException}.
     */
    public static InvalidKeyException getInvalidKeyException(
            AndroidKeyStoreKey key, KeyStoreException e) {
        switch (e.getErrorCode()) {
            case KeymasterDefs.KM_ERROR_KEY_EXPIRED:
                return new KeyExpiredException();
            case KeymasterDefs.KM_ERROR_KEY_NOT_YET_VALID:
                return new KeyNotYetValidException();
            case ResponseCode.KEY_NOT_FOUND:
                // TODO is this the right exception in this case?
            case ResponseCode.KEY_PERMANENTLY_INVALIDATED:
                return new KeyPermanentlyInvalidatedException();
            case ResponseCode.LOCKED:
            case ResponseCode.UNINITIALIZED:
            case KeymasterDefs.KM_ERROR_KEY_USER_NOT_AUTHENTICATED:
                // TODO b/173111727 remove response codes LOCKED and UNINITIALIZED
                return new UserNotAuthenticatedException();
            default:
                return new InvalidKeyException("Keystore operation failed", e);
        }
    }

    /**
     * Returns the exception to be thrown by the {@code Cipher.init} method of the crypto operation
     * in response to {@code KeyStore.begin} operation or {@code null} if the {@code init} method
     * should succeed.
     */
    public static GeneralSecurityException getExceptionForCipherInit(
            AndroidKeyStoreKey key, KeyStoreException e) {
        if (e.getErrorCode() == KeyStore.NO_ERROR) {
            return null;
        }

        // Cipher-specific cases
        switch (e.getErrorCode()) {
            case KeymasterDefs.KM_ERROR_INVALID_NONCE:
                return new InvalidAlgorithmParameterException("Invalid IV");
            case KeymasterDefs.KM_ERROR_CALLER_NONCE_PROHIBITED:
                return new InvalidAlgorithmParameterException("Caller-provided IV not permitted");
        }

        // General cases
        return getInvalidKeyException(key, e);
    }

    /**
     * Returns the requested number of random bytes to mix into keystore/keymaster RNG.
     *
     * @param rng RNG from which to obtain the random bytes or {@code null} for the platform-default
     *        RNG.
     */
    static byte[] getRandomBytesToMixIntoKeystoreRng(SecureRandom rng, int sizeBytes) {
        if (sizeBytes <= 0) {
            return EmptyArray.BYTE;
        }
        if (rng == null) {
            rng = getRng();
        }
        byte[] result = new byte[sizeBytes];
        rng.nextBytes(result);
        return result;
    }

    private static SecureRandom getRng() {
        // IMPLEMENTATION NOTE: It's OK to share a SecureRandom instance because SecureRandom is
        // required to be thread-safe.
        if (sRng == null) {
            sRng = new SecureRandom();
        }
        return sRng;
    }

    static void abortOperation(KeyStoreOperation operation) {
        if (operation != null) {
            try {
                operation.abort();
            } catch (KeyStoreException e) {
                // Invalid operation handle is very common at this point. It occurs every time
                // an already finalized operation gets aborted.
                if (e.getErrorCode() != ErrorCode.INVALID_OPERATION_HANDLE) {
                    // This error gets logged but ignored. Dropping the reference
                    // to the KeyStoreOperation is enough to clean up all related resources even
                    // in the Keystore daemon. It gets logged anyway, because it may indicate some
                    // underlying problem that is worth debugging.
                    Log.w(
                            "KeyStoreCryptoOperationUtils",
                            "Encountered error trying to abort a keystore operation.",
                            e
                    );
                }
            }
        }
    }

    static long getOrMakeOperationChallenge(KeyStoreOperation operation, AndroidKeyStoreKey key)
            throws KeyPermanentlyInvalidatedException {
        if (operation.getChallenge() != null) {
            if (!KeyStoreCryptoOperationUtils.canUserAuthorizationSucceed(key)) {
                throw new KeyPermanentlyInvalidatedException();
            }
            return operation.getChallenge();
        } else {
            // Keystore won't give us an operation challenge if the operation doesn't
            // need user authorization. So we make our own.
            return Math.randomLongInternal();
        }
    }
}
