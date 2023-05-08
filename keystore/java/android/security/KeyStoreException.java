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

package android.security;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.security.keymaster.KeymasterDefs;
import android.system.keystore2.ResponseCode;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;

/**
 * Exception containing information about the failure at the Keystore / KeyMint layer while
 * generating or using a key.
 *
 * The public error codes indicate the cause of the error and the methods indicate whether
 * it's a system/key issue and whether re-trying the operation (with the same key or a new key)
 * is likely to succeed.
 */
public class KeyStoreException extends Exception {
    private static final String TAG = "KeyStoreException";

    /**
     * This error code is for mapping errors that the caller will not know about. If the caller is
     * targeting an API level earlier than the one the error was introduced in, then the error will
     * be mapped to this one.
     * In API level 33 no errors map to this error.
     */
    public static final int ERROR_OTHER = 1;
    /**
     * Indicating the key could not be used because the user needs to authenticate first.
     * See
     * {@link android.security.keystore.KeyGenParameterSpec.Builder#setUserAuthenticationRequired(boolean)}.
     */
    public static final int ERROR_USER_AUTHENTICATION_REQUIRED = 2;
    /**
     * Indicating that {@code load()} has not been called on the Keystore instance, or an attempt
     * has been made to generate an authorization bound key while the user has not set a lock
     * screen knowledge factor (LSKF). Instruct the user to set an LSKF and retry.
     */
    public static final int ERROR_KEYSTORE_UNINITIALIZED = 3;
    /**
     * An internal system error - refer to {@link #isTransientFailure()} to determine whether
     * re-trying the operation is likely to yield different results.
     */
    public static final int ERROR_INTERNAL_SYSTEM_ERROR = 4;
    /**
     * The caller has requested key parameters or operation which are only available to system
     * or privileged apps.
     */
    public static final int ERROR_PERMISSION_DENIED = 5;
    /**
     * The key the operation refers to doesn't exist.
     */
    public static final int ERROR_KEY_DOES_NOT_EXIST = 6;
    /**
     * The key is corrupted and could not be recovered.
     */
    public static final int ERROR_KEY_CORRUPTED = 7;
    /**
     * The error related to inclusion of device identifiers in the attestation record.
     */
    public static final int ERROR_ID_ATTESTATION_FAILURE = 8;
    /**
     * The attestation challenge specified is too large.
     */
    public static final int ERROR_ATTESTATION_CHALLENGE_TOO_LARGE = 9;
    /**
     * General error in the KeyMint layer.
     */
    public static final int ERROR_KEYMINT_FAILURE = 10;
    /**
     * Failure in the Keystore layer.
     */
    public static final int ERROR_KEYSTORE_FAILURE = 11;
    /**
     * The feature the caller is trying to use is not implemented by the underlying
     * KeyMint implementation.
     * This could happen when an unsupported algorithm is requested, or when trying to import
     * a key in a format other than raw or PKCS#8.
     */
    public static final int ERROR_UNIMPLEMENTED = 12;
    /**
     * The feature the caller is trying to use is not compatible with the parameters used to
     * generate the key. For example, trying to use a key generated for a different signature
     * algorithm, or a digest not specified during key creation.
     * Another case is the attempt to generate a symmetric AES key and requesting key attestation.
     */
    public static final int ERROR_INCORRECT_USAGE = 13;
    /**
     * The key is not currently valid: Either at has expired or it will be valid for use in the
     * future.
     */
    public static final int ERROR_KEY_NOT_TEMPORALLY_VALID = 14;
    /**
     * The crypto object the caller has been using held a reference to a KeyMint operation that
     * has been evacuated (likely due to other concurrent operations taking place).
     * The caller should re-create the crypto object and try again.
     */
    public static final int ERROR_KEY_OPERATION_EXPIRED = 15;
    /**
     * There are no keys available for attestation.
     * This error is returned only on devices that rely solely on remotely-provisioned keys (see
     * <a href=
     * "https://android-developers.googleblog.com/2022/03/upgrading-android-attestation-remote.html"
     * >Remote Key Provisioning</a>).
     *
     * <p>On such a device, if the caller requests key generation and includes an attestation
     * challenge (indicating key attestation is required), the error will be returned in one of
     * the following cases:
     * <ul>
     *     <li>The pool of remotely-provisioned keys has been exhausted.</li>
     *     <li>The device is not registered with the key provisioning server.</li>
     * </ul>
     * </p>
     *
     * <p>This error is a transient one if the pool of remotely-provisioned keys has been
     * exhausted. However, if the device is not registered with the server, or the key
     * provisioning server refuses key issuance, this is a permanent error.</p>
     */
    public static final int ERROR_ATTESTATION_KEYS_UNAVAILABLE = 16;
    /**
     * This device requires a software upgrade to use the key provisioning server. The software
     * is outdated and this error is returned only on devices that rely solely on
     * remotely-provisioned keys (see <a href=
     * "https://android-developers.googleblog.com/2022/03/upgrading-android-attestation-remote.html"
     * >Remote Key Provisioning</a>).
     *
     * @hide
     */
    public static final int ERROR_DEVICE_REQUIRES_UPGRADE_FOR_ATTESTATION = 17;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"ERROR_"}, value = {
            ERROR_OTHER,
            ERROR_USER_AUTHENTICATION_REQUIRED,
            ERROR_KEYSTORE_UNINITIALIZED,
            ERROR_INTERNAL_SYSTEM_ERROR,
            ERROR_PERMISSION_DENIED,
            ERROR_KEY_DOES_NOT_EXIST,
            ERROR_KEY_CORRUPTED,
            ERROR_ID_ATTESTATION_FAILURE,
            ERROR_ATTESTATION_CHALLENGE_TOO_LARGE,
            ERROR_KEYMINT_FAILURE,
            ERROR_KEYSTORE_FAILURE,
            ERROR_UNIMPLEMENTED,
            ERROR_INCORRECT_USAGE,
            ERROR_KEY_NOT_TEMPORALLY_VALID,
            ERROR_KEY_OPERATION_EXPIRED,
            ERROR_ATTESTATION_KEYS_UNAVAILABLE,
            ERROR_DEVICE_REQUIRES_UPGRADE_FOR_ATTESTATION,
    })
    public @interface PublicErrorCode {
    }

    /**
     * Never re-try the operation that led to this error, since it's a permanent error.
     *
     * This value is always returned when {@link #isTransientFailure()} is {@code false}.
     */
    public static final int RETRY_NEVER = 1;
    /**
     * Re-try the operation that led to this error with an exponential back-off delay.
     * The first delay should be between 5 to 30 seconds, and each subsequent re-try should double
     * the delay time.
     *
     * This value is returned when {@link #isTransientFailure()} is {@code true}.
     */
    public static final int RETRY_WITH_EXPONENTIAL_BACKOFF = 2;
    /**
     * Re-try the operation that led to this error when the device regains connectivity.
     * Remote provisioning of keys requires reaching the remote server, and the device is
     * currently unable to due that due to lack of network connectivity.
     *
     * This value is returned when {@link #isTransientFailure()} is {@code true}.
     */
    public static final int RETRY_WHEN_CONNECTIVITY_AVAILABLE = 3;
    /**
     * Re-try the operation that led to this error when the device has a software update
     * downloaded and on the next reboot. The Remote provisioning server recognizes
     * the device, but refuses issuance of attestation keys because it contains a software
     * version that could potentially be vulnerable and needs an update. Re-trying after the
     * device has upgraded and rebooted may alleviate the problem.
     *
     * <p>This value is returned when {@link #isTransientFailure()} is {@code true}.
     */
    public static final int RETRY_AFTER_NEXT_REBOOT = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"RETRY_"}, value = {
            RETRY_NEVER,
            RETRY_WITH_EXPONENTIAL_BACKOFF,
            RETRY_WHEN_CONNECTIVITY_AVAILABLE,
            RETRY_AFTER_NEXT_REBOOT,
    })
    public @interface RetryPolicy {
    }

    // RKP-specific error information.
    /**
     * Remote provisioning of attestation keys has completed successfully.
     * @hide */
    public static final int RKP_SUCCESS = 0;
    /**
     * Remotely-provisioned keys are temporarily unavailable. This could be because of RPC
     * error when talking to the remote provisioner or keys are being currently fetched and will
     * be available soon.
     * @hide */
    public static final int RKP_TEMPORARILY_UNAVAILABLE = 1;
    /**
     * Permanent failure: The RKP server has declined issuance of keys to this device. Either
     * because the device is not registered with the server or the server considers the device
     * not to be trustworthy.
     * @hide */
    public static final int RKP_SERVER_REFUSED_ISSUANCE = 2;
    /**
     * The RKP server is unavailable due to lack of connectivity. The caller should re-try
     * when the device has connectivity again.
     * @hide */
    public static final int RKP_FETCHING_PENDING_CONNECTIVITY = 3;
    /**
     * The RKP server recognizes the device, but the device may be running vulnerable software,
     * and thus refusing issuance of RKP keys to it.
     *
     * @hide
     */
    public static final int RKP_FETCHING_PENDING_SOFTWARE_REBOOT = 4;

    // Constants for encoding information about the error encountered:
    // Whether the error relates to the system state/implementation as a whole, or a specific key.
    private static final int IS_SYSTEM_ERROR = 1 << 1;
    // Whether the error is permanent.
    private static final int IS_TRANSIENT_ERROR = 1 << 2;
    // Whether the cause of the error is the user not having authenticated recently.
    private static final int REQUIRES_USER_AUTHENTICATION = 1 << 3;

    // The internal error code. NOT to be returned directly to callers or made part of the
    // public API.
    private final int mErrorCode;
    // The Remote Key Provisioning status. Applicable if and only if {@link #mErrorCode} is equal
    // to {@link ResponseCode.OUT_OF_KEYS}.
    private final int mRkpStatus;

    private static int initializeRkpStatusForRegularErrors(int errorCode) {
        // Check if the system code mistakenly called a constructor of KeyStoreException with
        // the OUT_OF_KEYS error code but without RKP status.
        if (errorCode == ResponseCode.OUT_OF_KEYS) {
            Log.e(TAG, "RKP error code without RKP status");
            // Set RKP status to RKP_SERVER_REFUSED_ISSUANCE so that the caller never retries.
            return RKP_SERVER_REFUSED_ISSUANCE;
        } else {
            return RKP_SUCCESS;
        }
    }

    /**
     * @hide
     */
    public KeyStoreException(int errorCode, @Nullable String message) {
        super(message);
        mErrorCode = errorCode;
        mRkpStatus = initializeRkpStatusForRegularErrors(errorCode);
    }

    /**
     * @hide
     */
    public KeyStoreException(int errorCode, @Nullable String message,
            @Nullable String keystoreErrorMessage) {
        super(message + " (internal Keystore code: " + errorCode + " message: "
                + keystoreErrorMessage + ")");
        mErrorCode = errorCode;
        mRkpStatus = initializeRkpStatusForRegularErrors(errorCode);
    }

    /**
     * @hide
     */
    public KeyStoreException(int errorCode, @Nullable String message, int rkpStatus) {
        super(message);
        mErrorCode = errorCode;
        mRkpStatus = rkpStatus;
        if (mErrorCode != ResponseCode.OUT_OF_KEYS) {
            Log.e(TAG, "Providing RKP status for error code " + errorCode + " has no effect.");
        }
    }

    /**
     * Returns the internal error code. Only for use by the platform.
     *
     * @hide
     */
    @TestApi
    public int getErrorCode() {
        return mErrorCode;
    }

    /**
     * Returns one of the error codes exported by the class.
     *
     * @return a public error code, one of the values in {@link PublicErrorCode}.
     */
    @PublicErrorCode
    public int getNumericErrorCode() {
        PublicErrorInformation failureInfo = getErrorInformation(mErrorCode);
        return failureInfo.errorCode;
    }

    /**
     * Returns true if the failure is a transient failure - that is, performing the same operation
     * again at a late time is likely to succeed.
     *
     * If {@link #isSystemError()} returns true, the transient nature of the failure relates to the
     * device, otherwise relates to the key (so a permanent failure with an existing key likely
     * requires creating another key to repeat the operation with).
     */
    public boolean isTransientFailure() {
        PublicErrorInformation failureInfo = getErrorInformation(mErrorCode);
        // Special-case handling for RKP failures:
        if (mRkpStatus != RKP_SUCCESS && mErrorCode == ResponseCode.OUT_OF_KEYS) {
            switch (mRkpStatus) {
                case RKP_TEMPORARILY_UNAVAILABLE:
                case RKP_FETCHING_PENDING_CONNECTIVITY:
                case RKP_FETCHING_PENDING_SOFTWARE_REBOOT:
                    return true;
                case RKP_SERVER_REFUSED_ISSUANCE:
                default:
                    return false;
            }
        }
        return (failureInfo.indicators & IS_TRANSIENT_ERROR) != 0;
    }

    /**
     * Indicates whether the failure is due to the device being locked.
     *
     * @return true if the key operation failed because the user has to authenticate
     * (e.g. by unlocking the device).
     */
    public boolean requiresUserAuthentication() {
        PublicErrorInformation failureInfo = getErrorInformation(mErrorCode);
        return (failureInfo.indicators & REQUIRES_USER_AUTHENTICATION) != 0;
    }

    /**
     * Indicates whether the error related to the Keystore/KeyMint implementation and not
     * a specific key.
     *
     * @return true if the error is related to the system, not the key in use. System
     * errors indicate a feature isn't working, whereas key-related errors are likely
     * to succeed with a new key.
     */
    public boolean isSystemError() {
        PublicErrorInformation failureInfo = getErrorInformation(mErrorCode);
        return (failureInfo.indicators & IS_SYSTEM_ERROR) != 0;
    }

    /**
     * Returns the re-try policy for transient failures. Valid only if
     * {@link #isTransientFailure()} returns {@code True}.
     */
    @RetryPolicy
    public int getRetryPolicy() {
        PublicErrorInformation failureInfo = getErrorInformation(mErrorCode);
        // Special-case handling for RKP failures (To be removed in API 34)
        if (mRkpStatus != RKP_SUCCESS) {
            switch (mRkpStatus) {
                case RKP_TEMPORARILY_UNAVAILABLE:
                    return RETRY_WITH_EXPONENTIAL_BACKOFF;
                case RKP_FETCHING_PENDING_CONNECTIVITY:
                    return RETRY_WHEN_CONNECTIVITY_AVAILABLE;
                case RKP_SERVER_REFUSED_ISSUANCE:
                    return RETRY_NEVER;
                case RKP_FETCHING_PENDING_SOFTWARE_REBOOT:
                    return RETRY_AFTER_NEXT_REBOOT;
                default:
                    return (failureInfo.indicators & IS_TRANSIENT_ERROR) != 0
                            ? RETRY_WITH_EXPONENTIAL_BACKOFF : RETRY_NEVER;
            }
        }
        switch (mErrorCode) {
            case ResponseCode.OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE:
                return RETRY_AFTER_NEXT_REBOOT;
            case ResponseCode.OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY:
                return RETRY_WHEN_CONNECTIVITY_AVAILABLE;
            default:
                return (failureInfo.indicators & IS_TRANSIENT_ERROR) != 0
                        ? RETRY_WITH_EXPONENTIAL_BACKOFF : RETRY_NEVER;
        }
    }

    @Override
    public String toString() {
        String errorCodes = String.format(" (public error code: %d internal Keystore code: %d)",
                getNumericErrorCode(), mErrorCode);
        return super.toString() + errorCodes;
    }

    private static PublicErrorInformation getErrorInformation(int internalErrorCode) {
        PublicErrorInformation errorInfo = sErrorCodeToFailureInfo.get(internalErrorCode);
        if (errorInfo != null) {
            return errorInfo;
        }

        /**
         * KeyStore/keymaster exception with positive error codes coming from the KeyStore and
         * negative ones from keymaster.
         * This is a safety fall-back: All error codes should be present in the map.
         */
        if (internalErrorCode > 0) {
            return GENERAL_KEYSTORE_ERROR;
        } else {
            return GENERAL_KEYMINT_ERROR;
        }
    }

    private static final class PublicErrorInformation {
        public final int indicators;
        public final int errorCode;

        PublicErrorInformation(int indicators, @PublicErrorCode int errorCode) {
            this.indicators = indicators;
            this.errorCode = errorCode;
        }
    }

    private static final PublicErrorInformation GENERAL_KEYMINT_ERROR =
            new PublicErrorInformation(0, ERROR_KEYMINT_FAILURE);

    private static final PublicErrorInformation GENERAL_KEYSTORE_ERROR =
            new PublicErrorInformation(0, ERROR_KEYSTORE_FAILURE);

    private static final PublicErrorInformation KEYMINT_UNIMPLEMENTED_ERROR =
            new PublicErrorInformation(IS_SYSTEM_ERROR, ERROR_UNIMPLEMENTED);

    private static final PublicErrorInformation KEYMINT_RETRYABLE_ERROR =
            new PublicErrorInformation(IS_SYSTEM_ERROR | IS_TRANSIENT_ERROR,
                    ERROR_KEYMINT_FAILURE);

    private static final PublicErrorInformation KEYMINT_INCORRECT_USAGE_ERROR =
            new PublicErrorInformation(0, ERROR_INCORRECT_USAGE);

    private static final PublicErrorInformation KEYMINT_TEMPORAL_VALIDITY_ERROR =
            new PublicErrorInformation(0, ERROR_KEY_NOT_TEMPORALLY_VALID);


    private static final Map<Integer, PublicErrorInformation> sErrorCodeToFailureInfo =
            new HashMap();

    /**
     * @hide
     */
    @TestApi
    public static boolean hasFailureInfoForError(int internalErrorCode) {
        return sErrorCodeToFailureInfo.containsKey(internalErrorCode);
    }

    static {
        // KeyMint error codes
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_OK, GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_ROOT_OF_TRUST_ALREADY_SET,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_PURPOSE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INCOMPATIBLE_PURPOSE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_ALGORITHM,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INCOMPATIBLE_ALGORITHM,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_KEY_SIZE,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_BLOCK_MODE,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INCOMPATIBLE_BLOCK_MODE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_MAC_LENGTH,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_PADDING_MODE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INCOMPATIBLE_PADDING_MODE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_DIGEST,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INCOMPATIBLE_DIGEST,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_EXPIRATION_TIME,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_USER_ID,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_KEY_FORMAT,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INCOMPATIBLE_KEY_FORMAT,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_KEY_ENCRYPTION_ALGORITHM,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_KEY_VERIFICATION_ALGORITHM,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_INPUT_LENGTH,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_KEY_EXPORT_OPTIONS_INVALID,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_DELEGATION_NOT_ALLOWED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_KEY_NOT_YET_VALID,
                KEYMINT_TEMPORAL_VALIDITY_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_KEY_EXPIRED,
                KEYMINT_TEMPORAL_VALIDITY_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_KEY_USER_NOT_AUTHENTICATED,
                new PublicErrorInformation(REQUIRES_USER_AUTHENTICATION,
                        ERROR_USER_AUTHENTICATION_REQUIRED));
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_OUTPUT_PARAMETER_NULL,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_OPERATION_HANDLE,
                new PublicErrorInformation(IS_SYSTEM_ERROR | IS_TRANSIENT_ERROR,
                        ERROR_KEY_OPERATION_EXPIRED));
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INSUFFICIENT_BUFFER_SPACE,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_VERIFICATION_FAILED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_TOO_MANY_OPERATIONS,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNEXPECTED_NULL_POINTER,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_KEY_BLOB,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_IMPORTED_KEY_NOT_ENCRYPTED,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_IMPORTED_KEY_DECRYPTION_FAILED,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_IMPORTED_KEY_NOT_SIGNED,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_IMPORTED_KEY_VERIFICATION_FAILED,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_ARGUMENT,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_TAG,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_TAG,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_MEMORY_ALLOCATION_FAILED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_IMPORT_PARAMETER_MISMATCH,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_SECURE_HW_ACCESS_DENIED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_OPERATION_CANCELLED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_CONCURRENT_ACCESS_CONFLICT,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_SECURE_HW_BUSY,
                KEYMINT_RETRYABLE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_SECURE_HW_COMMUNICATION_FAILED,
                KEYMINT_RETRYABLE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_EC_FIELD,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_MISSING_NONCE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_NONCE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_MISSING_MAC_LENGTH,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_KEY_RATE_LIMIT_EXCEEDED,
                KEYMINT_RETRYABLE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_CALLER_NONCE_PROHIBITED,
                GENERAL_KEYMINT_ERROR);
        // Error related to MAX_USES_PER_BOOT, restricting the number of uses per boot.
        // It is not re-tryable.
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_KEY_MAX_OPS_EXCEEDED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_MAC_LENGTH,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_MISSING_MIN_MAC_LENGTH,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_MIN_MAC_LENGTH,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_KDF,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_EC_CURVE,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_ATTESTATION_CHALLENGE_MISSING,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_KEYMINT_NOT_CONFIGURED,
                new PublicErrorInformation(IS_SYSTEM_ERROR, ERROR_KEYMINT_FAILURE));
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_ATTESTATION_APPLICATION_ID_MISSING,
                KEYMINT_RETRYABLE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_CANNOT_ATTEST_IDS,
                new PublicErrorInformation(IS_SYSTEM_ERROR,
                        ERROR_ID_ATTESTATION_FAILURE));
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_ROLLBACK_RESISTANCE_UNAVAILABLE,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_HARDWARE_TYPE_UNAVAILABLE,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_PROOF_OF_PRESENCE_REQUIRED,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_CONCURRENT_PROOF_OF_PRESENCE_REQUESTED,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_NO_USER_CONFIRMATION,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_DEVICE_LOCKED,
                new PublicErrorInformation(IS_SYSTEM_ERROR | REQUIRES_USER_AUTHENTICATION,
                        ERROR_USER_AUTHENTICATION_REQUIRED));
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_EARLY_BOOT_ENDED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_ATTESTATION_KEYS_NOT_PROVISIONED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_ATTESTATION_IDS_NOT_PROVISIONED,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_OPERATION,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_STORAGE_KEY_UNSUPPORTED,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INCOMPATIBLE_MGF_DIGEST,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNSUPPORTED_MGF_DIGEST,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_MISSING_NOT_BEFORE,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_MISSING_NOT_AFTER,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_MISSING_ISSUER_SUBJECT,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_INVALID_ISSUER_SUBJECT,
                KEYMINT_INCORRECT_USAGE_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_BOOT_LEVEL_EXCEEDED,
                KEYMINT_INCORRECT_USAGE_ERROR);
        // This should not be exposed to apps as it's handled by Keystore.
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_HARDWARE_NOT_YET_AVAILABLE,
                GENERAL_KEYMINT_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNIMPLEMENTED,
                KEYMINT_UNIMPLEMENTED_ERROR);
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_UNKNOWN_ERROR,
                new PublicErrorInformation(IS_SYSTEM_ERROR,
                        ERROR_KEYMINT_FAILURE));
        sErrorCodeToFailureInfo.put(KeymasterDefs.KM_ERROR_VERSION_MISMATCH, GENERAL_KEYMINT_ERROR);

        // Keystore error codes
        sErrorCodeToFailureInfo.put(ResponseCode.LOCKED,
                new PublicErrorInformation(REQUIRES_USER_AUTHENTICATION,
                        ERROR_USER_AUTHENTICATION_REQUIRED));
        sErrorCodeToFailureInfo.put(ResponseCode.UNINITIALIZED,
                new PublicErrorInformation(IS_SYSTEM_ERROR, ERROR_KEYSTORE_UNINITIALIZED));
        sErrorCodeToFailureInfo.put(ResponseCode.SYSTEM_ERROR,
                new PublicErrorInformation(IS_SYSTEM_ERROR,
                        ERROR_INTERNAL_SYSTEM_ERROR));
        sErrorCodeToFailureInfo.put(ResponseCode.PERMISSION_DENIED,
                new PublicErrorInformation(0, ERROR_PERMISSION_DENIED));
        sErrorCodeToFailureInfo.put(ResponseCode.KEY_NOT_FOUND,
                new PublicErrorInformation(0, ERROR_KEY_DOES_NOT_EXIST));
        sErrorCodeToFailureInfo.put(ResponseCode.VALUE_CORRUPTED,
                new PublicErrorInformation(0, ERROR_KEY_CORRUPTED));
        sErrorCodeToFailureInfo.put(ResponseCode.KEY_PERMANENTLY_INVALIDATED,
                new PublicErrorInformation(0, ERROR_KEY_DOES_NOT_EXIST));
        sErrorCodeToFailureInfo.put(ResponseCode.OUT_OF_KEYS,
                new PublicErrorInformation(IS_SYSTEM_ERROR, ERROR_ATTESTATION_KEYS_UNAVAILABLE));
        sErrorCodeToFailureInfo.put(ResponseCode.OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE,
                new PublicErrorInformation(IS_SYSTEM_ERROR | IS_TRANSIENT_ERROR,
                        ERROR_DEVICE_REQUIRES_UPGRADE_FOR_ATTESTATION));
        sErrorCodeToFailureInfo.put(ResponseCode.OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY,
                new PublicErrorInformation(IS_SYSTEM_ERROR | IS_TRANSIENT_ERROR,
                        ERROR_ATTESTATION_KEYS_UNAVAILABLE));
        sErrorCodeToFailureInfo.put(ResponseCode.OUT_OF_KEYS_TRANSIENT_ERROR,
                new PublicErrorInformation(IS_SYSTEM_ERROR | IS_TRANSIENT_ERROR,
                        ERROR_ATTESTATION_KEYS_UNAVAILABLE));
        sErrorCodeToFailureInfo.put(ResponseCode.OUT_OF_KEYS_PERMANENT_ERROR,
                new PublicErrorInformation(IS_SYSTEM_ERROR, ERROR_ATTESTATION_KEYS_UNAVAILABLE));
    }
}
