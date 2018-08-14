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

package android.security.keymaster;

import java.util.HashMap;
import java.util.Map;

/**
 * Class tracking all the keymaster enum values needed for the binder API to keystore.
 * This must be kept in sync with hardware/libhardware/include/hardware/keymaster_defs.h
 * See keymaster_defs.h for detailed descriptions of each constant.
 * @hide
 */
public final class KeymasterDefs {

    private KeymasterDefs() {}

    // Tag types.
    public static final int KM_INVALID = 0 << 28;
    public static final int KM_ENUM = 1 << 28;
    public static final int KM_ENUM_REP = 2 << 28;
    public static final int KM_UINT = 3 << 28;
    public static final int KM_UINT_REP = 4 << 28;
    public static final int KM_ULONG = 5 << 28;
    public static final int KM_DATE = 6 << 28;
    public static final int KM_BOOL = 7 << 28;
    public static final int KM_BIGNUM = 8 << 28;
    public static final int KM_BYTES = 9 << 28;
    public static final int KM_ULONG_REP = 10 << 28;

    // Tag values.
    public static final int KM_TAG_INVALID = KM_INVALID | 0;
    public static final int KM_TAG_PURPOSE = KM_ENUM_REP | 1;
    public static final int KM_TAG_ALGORITHM = KM_ENUM | 2;
    public static final int KM_TAG_KEY_SIZE = KM_UINT | 3;
    public static final int KM_TAG_BLOCK_MODE = KM_ENUM_REP | 4;
    public static final int KM_TAG_DIGEST = KM_ENUM_REP | 5;
    public static final int KM_TAG_PADDING = KM_ENUM_REP | 6;
    public static final int KM_TAG_CALLER_NONCE = KM_BOOL | 7;
    public static final int KM_TAG_MIN_MAC_LENGTH = KM_UINT | 8;

    public static final int KM_TAG_RESCOPING_ADD = KM_ENUM_REP | 101;
    public static final int KM_TAG_RESCOPING_DEL = KM_ENUM_REP | 102;
    public static final int KM_TAG_BLOB_USAGE_REQUIREMENTS = KM_ENUM | 705;

    public static final int KM_TAG_RSA_PUBLIC_EXPONENT = KM_ULONG | 200;
    public static final int KM_TAG_INCLUDE_UNIQUE_ID = KM_BOOL | 202;

    public static final int KM_TAG_ACTIVE_DATETIME = KM_DATE | 400;
    public static final int KM_TAG_ORIGINATION_EXPIRE_DATETIME = KM_DATE | 401;
    public static final int KM_TAG_USAGE_EXPIRE_DATETIME = KM_DATE | 402;
    public static final int KM_TAG_MIN_SECONDS_BETWEEN_OPS = KM_UINT | 403;
    public static final int KM_TAG_MAX_USES_PER_BOOT = KM_UINT | 404;

    public static final int KM_TAG_ALL_USERS = KM_BOOL | 500;
    public static final int KM_TAG_USER_ID = KM_UINT | 501;
    public static final int KM_TAG_USER_SECURE_ID = KM_ULONG_REP | 502;
    public static final int KM_TAG_NO_AUTH_REQUIRED = KM_BOOL | 503;
    public static final int KM_TAG_USER_AUTH_TYPE = KM_ENUM | 504;
    public static final int KM_TAG_AUTH_TIMEOUT = KM_UINT | 505;
    public static final int KM_TAG_ALLOW_WHILE_ON_BODY = KM_BOOL | 506;
    public static final int KM_TAG_TRUSTED_USER_PRESENCE_REQUIRED = KM_BOOL | 507;
    public static final int KM_TAG_TRUSTED_CONFIRMATION_REQUIRED = KM_BOOL | 508;
    public static final int KM_TAG_UNLOCKED_DEVICE_REQUIRED = KM_BOOL | 509;

    public static final int KM_TAG_ALL_APPLICATIONS = KM_BOOL | 600;
    public static final int KM_TAG_APPLICATION_ID = KM_BYTES | 601;

    public static final int KM_TAG_CREATION_DATETIME = KM_DATE | 701;
    public static final int KM_TAG_ORIGIN = KM_ENUM | 702;
    public static final int KM_TAG_ROLLBACK_RESISTANT = KM_BOOL | 703;
    public static final int KM_TAG_ROOT_OF_TRUST = KM_BYTES | 704;
    public static final int KM_TAG_UNIQUE_ID = KM_BYTES | 707;
    public static final int KM_TAG_ATTESTATION_CHALLENGE = KM_BYTES | 708;
    public static final int KM_TAG_ATTESTATION_ID_BRAND = KM_BYTES | 710;
    public static final int KM_TAG_ATTESTATION_ID_DEVICE = KM_BYTES | 711;
    public static final int KM_TAG_ATTESTATION_ID_PRODUCT = KM_BYTES | 712;
    public static final int KM_TAG_ATTESTATION_ID_SERIAL = KM_BYTES | 713;
    public static final int KM_TAG_ATTESTATION_ID_IMEI = KM_BYTES | 714;
    public static final int KM_TAG_ATTESTATION_ID_MEID = KM_BYTES | 715;
    public static final int KM_TAG_ATTESTATION_ID_MANUFACTURER = KM_BYTES | 716;
    public static final int KM_TAG_ATTESTATION_ID_MODEL = KM_BYTES | 717;

    public static final int KM_TAG_ASSOCIATED_DATA = KM_BYTES | 1000;
    public static final int KM_TAG_NONCE = KM_BYTES | 1001;
    public static final int KM_TAG_AUTH_TOKEN = KM_BYTES | 1002;
    public static final int KM_TAG_MAC_LENGTH = KM_UINT | 1003;

    // Algorithm values.
    public static final int KM_ALGORITHM_RSA = 1;
    public static final int KM_ALGORITHM_EC = 3;
    public static final int KM_ALGORITHM_AES = 32;
    public static final int KM_ALGORITHM_3DES = 33;
    public static final int KM_ALGORITHM_HMAC = 128;

    // Block modes.
    public static final int KM_MODE_ECB = 1;
    public static final int KM_MODE_CBC = 2;
    public static final int KM_MODE_CTR = 3;
    public static final int KM_MODE_GCM = 32;

    // Padding modes.
    public static final int KM_PAD_NONE = 1;
    public static final int KM_PAD_RSA_OAEP = 2;
    public static final int KM_PAD_RSA_PSS = 3;
    public static final int KM_PAD_RSA_PKCS1_1_5_ENCRYPT = 4;
    public static final int KM_PAD_RSA_PKCS1_1_5_SIGN = 5;
    public static final int KM_PAD_PKCS7 = 64;

    // Digest modes.
    public static final int KM_DIGEST_NONE = 0;
    public static final int KM_DIGEST_MD5 = 1;
    public static final int KM_DIGEST_SHA1 = 2;
    public static final int KM_DIGEST_SHA_2_224 = 3;
    public static final int KM_DIGEST_SHA_2_256 = 4;
    public static final int KM_DIGEST_SHA_2_384 = 5;
    public static final int KM_DIGEST_SHA_2_512 = 6;

    // Key origins.
    public static final int KM_ORIGIN_GENERATED = 0;
    public static final int KM_ORIGIN_IMPORTED = 2;
    public static final int KM_ORIGIN_UNKNOWN = 3;
    public static final int KM_ORIGIN_SECURELY_IMPORTED = 4;

    // Key usability requirements.
    public static final int KM_BLOB_STANDALONE = 0;
    public static final int KM_BLOB_REQUIRES_FILE_SYSTEM = 1;

    // Operation Purposes.
    public static final int KM_PURPOSE_ENCRYPT = 0;
    public static final int KM_PURPOSE_DECRYPT = 1;
    public static final int KM_PURPOSE_SIGN = 2;
    public static final int KM_PURPOSE_VERIFY = 3;
    public static final int KM_PURPOSE_WRAP = 5;

    // Key formats.
    public static final int KM_KEY_FORMAT_X509 = 0;
    public static final int KM_KEY_FORMAT_PKCS8 = 1;
    public static final int KM_KEY_FORMAT_RAW = 3;

    // User authenticators.
    public static final int HW_AUTH_PASSWORD = 1 << 0;
    public static final int HW_AUTH_FINGERPRINT = 1 << 1;

    // Error codes.
    public static final int KM_ERROR_OK = 0;
    public static final int KM_ERROR_ROOT_OF_TRUST_ALREADY_SET = -1;
    public static final int KM_ERROR_UNSUPPORTED_PURPOSE = -2;
    public static final int KM_ERROR_INCOMPATIBLE_PURPOSE = -3;
    public static final int KM_ERROR_UNSUPPORTED_ALGORITHM = -4;
    public static final int KM_ERROR_INCOMPATIBLE_ALGORITHM = -5;
    public static final int KM_ERROR_UNSUPPORTED_KEY_SIZE = -6;
    public static final int KM_ERROR_UNSUPPORTED_BLOCK_MODE = -7;
    public static final int KM_ERROR_INCOMPATIBLE_BLOCK_MODE = -8;
    public static final int KM_ERROR_UNSUPPORTED_MAC_LENGTH = -9;
    public static final int KM_ERROR_UNSUPPORTED_PADDING_MODE = -10;
    public static final int KM_ERROR_INCOMPATIBLE_PADDING_MODE = -11;
    public static final int KM_ERROR_UNSUPPORTED_DIGEST = -12;
    public static final int KM_ERROR_INCOMPATIBLE_DIGEST = -13;
    public static final int KM_ERROR_INVALID_EXPIRATION_TIME = -14;
    public static final int KM_ERROR_INVALID_USER_ID = -15;
    public static final int KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT = -16;
    public static final int KM_ERROR_UNSUPPORTED_KEY_FORMAT = -17;
    public static final int KM_ERROR_INCOMPATIBLE_KEY_FORMAT = -18;
    public static final int KM_ERROR_UNSUPPORTED_KEY_ENCRYPTION_ALGORITHM = -19;
    public static final int KM_ERROR_UNSUPPORTED_KEY_VERIFICATION_ALGORITHM = -20;
    public static final int KM_ERROR_INVALID_INPUT_LENGTH = -21;
    public static final int KM_ERROR_KEY_EXPORT_OPTIONS_INVALID = -22;
    public static final int KM_ERROR_DELEGATION_NOT_ALLOWED = -23;
    public static final int KM_ERROR_KEY_NOT_YET_VALID = -24;
    public static final int KM_ERROR_KEY_EXPIRED = -25;
    public static final int KM_ERROR_KEY_USER_NOT_AUTHENTICATED = -26;
    public static final int KM_ERROR_OUTPUT_PARAMETER_NULL = -27;
    public static final int KM_ERROR_INVALID_OPERATION_HANDLE = -28;
    public static final int KM_ERROR_INSUFFICIENT_BUFFER_SPACE = -29;
    public static final int KM_ERROR_VERIFICATION_FAILED = -30;
    public static final int KM_ERROR_TOO_MANY_OPERATIONS = -31;
    public static final int KM_ERROR_UNEXPECTED_NULL_POINTER = -32;
    public static final int KM_ERROR_INVALID_KEY_BLOB = -33;
    public static final int KM_ERROR_IMPORTED_KEY_NOT_ENCRYPTED = -34;
    public static final int KM_ERROR_IMPORTED_KEY_DECRYPTION_FAILED = -35;
    public static final int KM_ERROR_IMPORTED_KEY_NOT_SIGNED = -36;
    public static final int KM_ERROR_IMPORTED_KEY_VERIFICATION_FAILED = -37;
    public static final int KM_ERROR_INVALID_ARGUMENT = -38;
    public static final int KM_ERROR_UNSUPPORTED_TAG = -39;
    public static final int KM_ERROR_INVALID_TAG = -40;
    public static final int KM_ERROR_MEMORY_ALLOCATION_FAILED = -41;
    public static final int KM_ERROR_INVALID_RESCOPING = -42;
    public static final int KM_ERROR_IMPORT_PARAMETER_MISMATCH = -44;
    public static final int KM_ERROR_SECURE_HW_ACCESS_DENIED = -45;
    public static final int KM_ERROR_OPERATION_CANCELLED = -46;
    public static final int KM_ERROR_CONCURRENT_ACCESS_CONFLICT = -47;
    public static final int KM_ERROR_SECURE_HW_BUSY = -48;
    public static final int KM_ERROR_SECURE_HW_COMMUNICATION_FAILED = -49;
    public static final int KM_ERROR_UNSUPPORTED_EC_FIELD = -50;
    public static final int KM_ERROR_MISSING_NONCE = -51;
    public static final int KM_ERROR_INVALID_NONCE = -52;
    public static final int KM_ERROR_MISSING_MAC_LENGTH = -53;
    public static final int KM_ERROR_KEY_RATE_LIMIT_EXCEEDED = -54;
    public static final int KM_ERROR_CALLER_NONCE_PROHIBITED = -55;
    public static final int KM_ERROR_KEY_MAX_OPS_EXCEEDED = -56;
    public static final int KM_ERROR_INVALID_MAC_LENGTH = -57;
    public static final int KM_ERROR_MISSING_MIN_MAC_LENGTH = -58;
    public static final int KM_ERROR_UNSUPPORTED_MIN_MAC_LENGTH = -59;
    public static final int KM_ERROR_CANNOT_ATTEST_IDS = -66;
    public static final int KM_ERROR_DEVICE_LOCKED = -72;
    public static final int KM_ERROR_UNIMPLEMENTED = -100;
    public static final int KM_ERROR_VERSION_MISMATCH = -101;
    public static final int KM_ERROR_UNKNOWN_ERROR = -1000;

    public static final Map<Integer, String> sErrorCodeToString = new HashMap<Integer, String>();
    static {
        sErrorCodeToString.put(KM_ERROR_OK, "OK");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_PURPOSE, "Unsupported purpose");
        sErrorCodeToString.put(KM_ERROR_INCOMPATIBLE_PURPOSE, "Incompatible purpose");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_ALGORITHM, "Unsupported algorithm");
        sErrorCodeToString.put(KM_ERROR_INCOMPATIBLE_ALGORITHM, "Incompatible algorithm");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_KEY_SIZE, "Unsupported key size");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_BLOCK_MODE, "Unsupported block mode");
        sErrorCodeToString.put(KM_ERROR_INCOMPATIBLE_BLOCK_MODE, "Incompatible block mode");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_MAC_LENGTH,
                "Unsupported MAC or authentication tag length");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_PADDING_MODE, "Unsupported padding mode");
        sErrorCodeToString.put(KM_ERROR_INCOMPATIBLE_PADDING_MODE, "Incompatible padding mode");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_DIGEST, "Unsupported digest");
        sErrorCodeToString.put(KM_ERROR_INCOMPATIBLE_DIGEST, "Incompatible digest");
        sErrorCodeToString.put(KM_ERROR_INVALID_EXPIRATION_TIME, "Invalid expiration time");
        sErrorCodeToString.put(KM_ERROR_INVALID_USER_ID, "Invalid user ID");
        sErrorCodeToString.put(KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT,
                "Invalid user authorization timeout");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_KEY_FORMAT, "Unsupported key format");
        sErrorCodeToString.put(KM_ERROR_INCOMPATIBLE_KEY_FORMAT, "Incompatible key format");
        sErrorCodeToString.put(KM_ERROR_INVALID_INPUT_LENGTH, "Invalid input length");
        sErrorCodeToString.put(KM_ERROR_KEY_NOT_YET_VALID, "Key not yet valid");
        sErrorCodeToString.put(KM_ERROR_KEY_EXPIRED, "Key expired");
        sErrorCodeToString.put(KM_ERROR_KEY_USER_NOT_AUTHENTICATED, "Key user not authenticated");
        sErrorCodeToString.put(KM_ERROR_INVALID_OPERATION_HANDLE, "Invalid operation handle");
        sErrorCodeToString.put(KM_ERROR_VERIFICATION_FAILED, "Signature/MAC verification failed");
        sErrorCodeToString.put(KM_ERROR_TOO_MANY_OPERATIONS, "Too many operations");
        sErrorCodeToString.put(KM_ERROR_INVALID_KEY_BLOB, "Invalid key blob");
        sErrorCodeToString.put(KM_ERROR_INVALID_ARGUMENT, "Invalid argument");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_TAG, "Unsupported tag");
        sErrorCodeToString.put(KM_ERROR_INVALID_TAG, "Invalid tag");
        sErrorCodeToString.put(KM_ERROR_MEMORY_ALLOCATION_FAILED, "Memory allocation failed");
        sErrorCodeToString.put(KM_ERROR_UNSUPPORTED_EC_FIELD, "Unsupported EC field");
        sErrorCodeToString.put(KM_ERROR_MISSING_NONCE, "Required IV missing");
        sErrorCodeToString.put(KM_ERROR_INVALID_NONCE, "Invalid IV");
        sErrorCodeToString.put(KM_ERROR_CALLER_NONCE_PROHIBITED,
                "Caller-provided IV not permitted");
        sErrorCodeToString.put(KM_ERROR_INVALID_MAC_LENGTH,
                "Invalid MAC or authentication tag length");
        sErrorCodeToString.put(KM_ERROR_CANNOT_ATTEST_IDS, "Unable to attest device ids");
        sErrorCodeToString.put(KM_ERROR_DEVICE_LOCKED, "Device locked");
        sErrorCodeToString.put(KM_ERROR_UNIMPLEMENTED, "Not implemented");
        sErrorCodeToString.put(KM_ERROR_UNKNOWN_ERROR, "Unknown error");
    }

    public static int getTagType(int tag) {
        return tag & (0xF << 28);
    }

    public static String getErrorMessage(int errorCode) {
        String result = sErrorCodeToString.get(errorCode);
        if (result != null) {
            return result;
        }
        return String.valueOf(errorCode);
    }
}
