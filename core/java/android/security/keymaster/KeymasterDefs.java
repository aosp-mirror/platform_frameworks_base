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

import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.BlockMode;
import android.hardware.security.keymint.Digest;
import android.hardware.security.keymint.ErrorCode;
import android.hardware.security.keymint.HardwareAuthenticatorType;
import android.hardware.security.keymint.KeyFormat;
import android.hardware.security.keymint.KeyOrigin;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.PaddingMode;
import android.hardware.security.keymint.SecurityLevel;
import android.hardware.security.keymint.Tag;
import android.hardware.security.keymint.TagType;

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
    public static final int KM_INVALID = TagType.INVALID;
    public static final int KM_ENUM = TagType.ENUM;
    public static final int KM_ENUM_REP = TagType.ENUM_REP;
    public static final int KM_UINT = TagType.UINT;
    public static final int KM_UINT_REP = TagType.UINT_REP;
    public static final int KM_ULONG = TagType.ULONG;
    public static final int KM_DATE = TagType.DATE;
    public static final int KM_BOOL = TagType.BOOL;
    public static final int KM_BIGNUM = TagType.BIGNUM;
    public static final int KM_BYTES = TagType.BYTES;
    public static final int KM_ULONG_REP = TagType.ULONG_REP;

    // Tag values.
    public static final int KM_TAG_INVALID = Tag.INVALID; // KM_INVALID | 0;
    public static final int KM_TAG_PURPOSE = Tag.PURPOSE; // KM_ENUM_REP | 1;
    public static final int KM_TAG_ALGORITHM = Tag.ALGORITHM; // KM_ENUM | 2;
    public static final int KM_TAG_KEY_SIZE = Tag.KEY_SIZE; // KM_UINT | 3;
    public static final int KM_TAG_BLOCK_MODE = Tag.BLOCK_MODE; // KM_ENUM_REP | 4;
    public static final int KM_TAG_DIGEST = Tag.DIGEST; // KM_ENUM_REP | 5;
    public static final int KM_TAG_PADDING = Tag.PADDING; // KM_ENUM_REP | 6;
    public static final int KM_TAG_CALLER_NONCE = Tag.CALLER_NONCE; // KM_BOOL | 7;
    public static final int KM_TAG_MIN_MAC_LENGTH = Tag.MIN_MAC_LENGTH; // KM_UINT | 8;

    public static final int KM_TAG_RSA_PUBLIC_EXPONENT = Tag.RSA_PUBLIC_EXPONENT; // KM_ULONG | 200;
    public static final int KM_TAG_INCLUDE_UNIQUE_ID = Tag.INCLUDE_UNIQUE_ID; // KM_BOOL | 202;

    public static final int KM_TAG_ACTIVE_DATETIME = Tag.ACTIVE_DATETIME; // KM_DATE | 400;
    public static final int KM_TAG_ORIGINATION_EXPIRE_DATETIME =
            Tag.ORIGINATION_EXPIRE_DATETIME; // KM_DATE | 401;
    public static final int KM_TAG_USAGE_EXPIRE_DATETIME =
            Tag.USAGE_EXPIRE_DATETIME; // KM_DATE | 402;
    public static final int KM_TAG_MIN_SECONDS_BETWEEN_OPS =
            Tag.MIN_SECONDS_BETWEEN_OPS; // KM_UINT | 403;
    public static final int KM_TAG_MAX_USES_PER_BOOT = Tag.MAX_USES_PER_BOOT; // KM_UINT | 404;
    public static final int KM_TAG_USAGE_COUNT_LIMIT = Tag.USAGE_COUNT_LIMIT; // KM_UINT | 405;

    public static final int KM_TAG_USER_ID = Tag.USER_ID; // KM_UINT | 501;
    public static final int KM_TAG_USER_SECURE_ID = Tag.USER_SECURE_ID; // KM_ULONG_REP | 502;
    public static final int KM_TAG_NO_AUTH_REQUIRED = Tag.NO_AUTH_REQUIRED; // KM_BOOL | 503;
    public static final int KM_TAG_USER_AUTH_TYPE = Tag.USER_AUTH_TYPE; // KM_ENUM | 504;
    public static final int KM_TAG_AUTH_TIMEOUT = Tag.AUTH_TIMEOUT; // KM_UINT | 505;
    public static final int KM_TAG_ALLOW_WHILE_ON_BODY = Tag.ALLOW_WHILE_ON_BODY; // KM_BOOL | 506;
    public static final int KM_TAG_TRUSTED_USER_PRESENCE_REQUIRED =
            Tag.TRUSTED_USER_PRESENCE_REQUIRED; // KM_BOOL | 507;
    public static final int KM_TAG_TRUSTED_CONFIRMATION_REQUIRED =
            Tag.TRUSTED_CONFIRMATION_REQUIRED; // KM_BOOL | 508;
    public static final int KM_TAG_UNLOCKED_DEVICE_REQUIRED =
            Tag.UNLOCKED_DEVICE_REQUIRED; // KM_BOOL | 509;

    public static final int KM_TAG_APPLICATION_ID = Tag.APPLICATION_ID; // KM_BYTES | 601;

    public static final int KM_TAG_CREATION_DATETIME = Tag.CREATION_DATETIME; // KM_DATE | 701;
    public static final int KM_TAG_ORIGIN = Tag.ORIGIN; // KM_ENUM | 702;
    public static final int KM_TAG_ROLLBACK_RESISTANT = Tag.ROLLBACK_RESISTANCE; // KM_BOOL | 703;
    public static final int KM_TAG_ROOT_OF_TRUST = Tag.ROOT_OF_TRUST; // KM_BYTES | 704;
    public static final int KM_TAG_UNIQUE_ID = Tag.UNIQUE_ID; // KM_BYTES | 707;
    public static final int KM_TAG_ATTESTATION_CHALLENGE =
            Tag.ATTESTATION_CHALLENGE; // KM_BYTES | 708;
    public static final int KM_TAG_ATTESTATION_ID_BRAND =
            Tag.ATTESTATION_ID_BRAND; // KM_BYTES | 710;
    public static final int KM_TAG_ATTESTATION_ID_DEVICE =
            Tag.ATTESTATION_ID_DEVICE; // KM_BYTES | 711;
    public static final int KM_TAG_ATTESTATION_ID_PRODUCT =
            Tag.ATTESTATION_ID_PRODUCT; // KM_BYTES | 712;
    public static final int KM_TAG_ATTESTATION_ID_SERIAL =
            Tag.ATTESTATION_ID_SERIAL; // KM_BYTES | 713;
    public static final int KM_TAG_ATTESTATION_ID_IMEI =
            Tag.ATTESTATION_ID_IMEI; // KM_BYTES | 714;
    public static final int KM_TAG_ATTESTATION_ID_MEID =
            Tag.ATTESTATION_ID_MEID; // KM_BYTES | 715;
    public static final int KM_TAG_ATTESTATION_ID_MANUFACTURER =
            Tag.ATTESTATION_ID_MANUFACTURER; // KM_BYTES | 716;
    public static final int KM_TAG_ATTESTATION_ID_MODEL =
            Tag.ATTESTATION_ID_MODEL; // KM_BYTES | 717;
    public static final int KM_TAG_VENDOR_PATCHLEVEL =
            Tag.VENDOR_PATCHLEVEL; // KM_UINT | 718;
    public static final int KM_TAG_BOOT_PATCHLEVEL =
            Tag.BOOT_PATCHLEVEL; // KM_UINT | 719;
    public static final int KM_TAG_DEVICE_UNIQUE_ATTESTATION =
            Tag.DEVICE_UNIQUE_ATTESTATION; // KM_BOOL | 720;

    public static final int KM_TAG_NONCE = Tag.NONCE; // KM_BYTES | 1001;
    public static final int KM_TAG_MAC_LENGTH = Tag.MAC_LENGTH; // KM_UINT | 1003;
    public static final int KM_TAG_RESET_SINCE_ID_ROTATION =
            Tag.RESET_SINCE_ID_ROTATION;     // KM_BOOL | 1004
    public static final int KM_TAG_CONFIRMATION_TOKEN = Tag.CONFIRMATION_TOKEN; // KM_BYTES | 1005;
    public static final int KM_TAG_CERTIFICATE_SERIAL = Tag.CERTIFICATE_SERIAL; // KM_UINT | 1006;
    public static final int KM_TAG_CERTIFICATE_SUBJECT = Tag.CERTIFICATE_SUBJECT; // KM_UINT | 1007;
    public static final int KM_TAG_CERTIFICATE_NOT_BEFORE =
            Tag.CERTIFICATE_NOT_BEFORE; // KM_DATE | 1008;
    public static final int KM_TAG_CERTIFICATE_NOT_AFTER =
            Tag.CERTIFICATE_NOT_AFTER; // KM_DATE | 1009;

    // Algorithm values.
    public static final int KM_ALGORITHM_RSA = Algorithm.RSA;
    public static final int KM_ALGORITHM_EC = Algorithm.EC;
    public static final int KM_ALGORITHM_AES = Algorithm.AES;
    public static final int KM_ALGORITHM_3DES = Algorithm.TRIPLE_DES;
    public static final int KM_ALGORITHM_HMAC = Algorithm.HMAC;

    // Block modes.
    public static final int KM_MODE_ECB = BlockMode.ECB;
    public static final int KM_MODE_CBC = BlockMode.CBC;
    public static final int KM_MODE_CTR = BlockMode.CTR;
    public static final int KM_MODE_GCM = BlockMode.GCM;

    // Padding modes.
    public static final int KM_PAD_NONE = PaddingMode.NONE;
    public static final int KM_PAD_RSA_OAEP = PaddingMode.RSA_OAEP;
    public static final int KM_PAD_RSA_PSS = PaddingMode.RSA_PSS;
    public static final int KM_PAD_RSA_PKCS1_1_5_ENCRYPT = PaddingMode.RSA_PKCS1_1_5_ENCRYPT;
    public static final int KM_PAD_RSA_PKCS1_1_5_SIGN = PaddingMode.RSA_PKCS1_1_5_SIGN;
    public static final int KM_PAD_PKCS7 = PaddingMode.PKCS7;

    // Digest modes.
    public static final int KM_DIGEST_NONE = Digest.NONE;
    public static final int KM_DIGEST_MD5 = Digest.MD5;
    public static final int KM_DIGEST_SHA1 = Digest.SHA1;
    public static final int KM_DIGEST_SHA_2_224 = Digest.SHA_2_224;
    public static final int KM_DIGEST_SHA_2_256 = Digest.SHA_2_256;
    public static final int KM_DIGEST_SHA_2_384 = Digest.SHA_2_384;
    public static final int KM_DIGEST_SHA_2_512 = Digest.SHA_2_512;

    // Key origins.
    public static final int KM_ORIGIN_GENERATED = KeyOrigin.GENERATED;
    public static final int KM_ORIGIN_DERIVED = KeyOrigin.DERIVED;
    public static final int KM_ORIGIN_IMPORTED = KeyOrigin.IMPORTED;
    public static final int KM_ORIGIN_UNKNOWN = KeyOrigin.RESERVED;
    public static final int KM_ORIGIN_SECURELY_IMPORTED = KeyOrigin.SECURELY_IMPORTED;

    // Key usability requirements.
    public static final int KM_BLOB_STANDALONE = 0;
    public static final int KM_BLOB_REQUIRES_FILE_SYSTEM = 1;

    // Operation Purposes.
    public static final int KM_PURPOSE_ENCRYPT = KeyPurpose.ENCRYPT;
    public static final int KM_PURPOSE_DECRYPT = KeyPurpose.DECRYPT;
    public static final int KM_PURPOSE_SIGN = KeyPurpose.SIGN;
    public static final int KM_PURPOSE_VERIFY = KeyPurpose.VERIFY;
    public static final int KM_PURPOSE_WRAP = KeyPurpose.WRAP_KEY;
    public static final int KM_PURPOSE_AGREE_KEY = KeyPurpose.AGREE_KEY;
    public static final int KM_PURPOSE_ATTEST_KEY = KeyPurpose.ATTEST_KEY;

    // Key formats.
    public static final int KM_KEY_FORMAT_X509 = KeyFormat.X509;
    public static final int KM_KEY_FORMAT_PKCS8 = KeyFormat.PKCS8;
    public static final int KM_KEY_FORMAT_RAW = KeyFormat.RAW;

    // User authenticators.
    public static final int HW_AUTH_PASSWORD = HardwareAuthenticatorType.PASSWORD;
    public static final int HW_AUTH_BIOMETRIC = HardwareAuthenticatorType.FINGERPRINT;

    // Security Levels.
    public static final int KM_SECURITY_LEVEL_SOFTWARE = SecurityLevel.SOFTWARE;
    public static final int KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT =
            SecurityLevel.TRUSTED_ENVIRONMENT;
    public static final int KM_SECURITY_LEVEL_STRONGBOX = SecurityLevel.STRONGBOX;

    // Error codes.
    public static final int KM_ERROR_OK = ErrorCode.OK;
    public static final int KM_ERROR_ROOT_OF_TRUST_ALREADY_SET =
            ErrorCode.ROOT_OF_TRUST_ALREADY_SET; // -1;
    public static final int KM_ERROR_UNSUPPORTED_PURPOSE =
            ErrorCode.UNSUPPORTED_PURPOSE; // -2;
    public static final int KM_ERROR_INCOMPATIBLE_PURPOSE =
            ErrorCode.INCOMPATIBLE_PURPOSE; // -3;
    public static final int KM_ERROR_UNSUPPORTED_ALGORITHM =
            ErrorCode.UNSUPPORTED_ALGORITHM; // -4;
    public static final int KM_ERROR_INCOMPATIBLE_ALGORITHM =
            ErrorCode.INCOMPATIBLE_ALGORITHM; // -5;
    public static final int KM_ERROR_UNSUPPORTED_KEY_SIZE =
            ErrorCode.UNSUPPORTED_KEY_SIZE; // -6;
    public static final int KM_ERROR_UNSUPPORTED_BLOCK_MODE =
            ErrorCode.UNSUPPORTED_BLOCK_MODE; // -7;
    public static final int KM_ERROR_INCOMPATIBLE_BLOCK_MODE =
            ErrorCode.INCOMPATIBLE_BLOCK_MODE; // -8;
    public static final int KM_ERROR_UNSUPPORTED_MAC_LENGTH =
            ErrorCode.UNSUPPORTED_MAC_LENGTH; // -9;
    public static final int KM_ERROR_UNSUPPORTED_PADDING_MODE =
            ErrorCode.UNSUPPORTED_PADDING_MODE; // -10;
    public static final int KM_ERROR_INCOMPATIBLE_PADDING_MODE =
            ErrorCode.INCOMPATIBLE_PADDING_MODE; // -11;
    public static final int KM_ERROR_UNSUPPORTED_DIGEST =
            ErrorCode.UNSUPPORTED_DIGEST; // -12;
    public static final int KM_ERROR_INCOMPATIBLE_DIGEST =
            ErrorCode.INCOMPATIBLE_DIGEST; // -13;
    public static final int KM_ERROR_INVALID_EXPIRATION_TIME =
            ErrorCode.INVALID_EXPIRATION_TIME; // -14;
    public static final int KM_ERROR_INVALID_USER_ID =
            ErrorCode.INVALID_USER_ID; // -15;
    public static final int KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT =
            ErrorCode.INVALID_AUTHORIZATION_TIMEOUT; // -16;
    public static final int KM_ERROR_UNSUPPORTED_KEY_FORMAT =
            ErrorCode.UNSUPPORTED_KEY_FORMAT; // -17;
    public static final int KM_ERROR_INCOMPATIBLE_KEY_FORMAT =
            ErrorCode.INCOMPATIBLE_KEY_FORMAT; // -18;
    public static final int KM_ERROR_UNSUPPORTED_KEY_ENCRYPTION_ALGORITHM =
            ErrorCode.UNSUPPORTED_KEY_ENCRYPTION_ALGORITHM; // -19;
    public static final int KM_ERROR_UNSUPPORTED_KEY_VERIFICATION_ALGORITHM =
            ErrorCode.UNSUPPORTED_KEY_VERIFICATION_ALGORITHM; // -20;
    public static final int KM_ERROR_INVALID_INPUT_LENGTH =
            ErrorCode.INVALID_INPUT_LENGTH; // -21;
    public static final int KM_ERROR_KEY_EXPORT_OPTIONS_INVALID =
            ErrorCode.KEY_EXPORT_OPTIONS_INVALID; // -22;
    public static final int KM_ERROR_DELEGATION_NOT_ALLOWED =
            ErrorCode.DELEGATION_NOT_ALLOWED; // -23;
    public static final int KM_ERROR_KEY_NOT_YET_VALID =
            ErrorCode.KEY_NOT_YET_VALID; // -24;
    public static final int KM_ERROR_KEY_EXPIRED =
            ErrorCode.KEY_EXPIRED; // -25;
    public static final int KM_ERROR_KEY_USER_NOT_AUTHENTICATED =
            ErrorCode.KEY_USER_NOT_AUTHENTICATED; // -26;
    public static final int KM_ERROR_OUTPUT_PARAMETER_NULL =
            ErrorCode.OUTPUT_PARAMETER_NULL; // -27;
    public static final int KM_ERROR_INVALID_OPERATION_HANDLE =
            ErrorCode.INVALID_OPERATION_HANDLE; // -28;
    public static final int KM_ERROR_INSUFFICIENT_BUFFER_SPACE =
            ErrorCode.INSUFFICIENT_BUFFER_SPACE; // -29;
    public static final int KM_ERROR_VERIFICATION_FAILED =
            ErrorCode.VERIFICATION_FAILED; // -30;
    public static final int KM_ERROR_TOO_MANY_OPERATIONS =
            ErrorCode.TOO_MANY_OPERATIONS; // -31;
    public static final int KM_ERROR_UNEXPECTED_NULL_POINTER =
            ErrorCode.UNEXPECTED_NULL_POINTER; // -32;
    public static final int KM_ERROR_INVALID_KEY_BLOB =
            ErrorCode.INVALID_KEY_BLOB; // -33;
    public static final int KM_ERROR_IMPORTED_KEY_NOT_ENCRYPTED =
            ErrorCode.IMPORTED_KEY_NOT_ENCRYPTED; // -34;
    public static final int KM_ERROR_IMPORTED_KEY_DECRYPTION_FAILED =
            ErrorCode.IMPORTED_KEY_DECRYPTION_FAILED; // -35;
    public static final int KM_ERROR_IMPORTED_KEY_NOT_SIGNED =
            ErrorCode.IMPORTED_KEY_NOT_SIGNED; // -36;
    public static final int KM_ERROR_IMPORTED_KEY_VERIFICATION_FAILED =
            ErrorCode.IMPORTED_KEY_VERIFICATION_FAILED; // -37;
    public static final int KM_ERROR_INVALID_ARGUMENT =
            ErrorCode.INVALID_ARGUMENT; // -38;
    public static final int KM_ERROR_UNSUPPORTED_TAG =
            ErrorCode.UNSUPPORTED_TAG; // -39;
    public static final int KM_ERROR_INVALID_TAG =
            ErrorCode.INVALID_TAG; // -40;
    public static final int KM_ERROR_MEMORY_ALLOCATION_FAILED =
            ErrorCode.MEMORY_ALLOCATION_FAILED; // -41;
    public static final int KM_ERROR_IMPORT_PARAMETER_MISMATCH =
            ErrorCode.IMPORT_PARAMETER_MISMATCH; // -44;
    public static final int KM_ERROR_SECURE_HW_ACCESS_DENIED =
            ErrorCode.SECURE_HW_ACCESS_DENIED; // -45;
    public static final int KM_ERROR_OPERATION_CANCELLED =
            ErrorCode.OPERATION_CANCELLED; // -46;
    public static final int KM_ERROR_CONCURRENT_ACCESS_CONFLICT =
            ErrorCode.CONCURRENT_ACCESS_CONFLICT; // -47;
    public static final int KM_ERROR_SECURE_HW_BUSY =
            ErrorCode.SECURE_HW_BUSY; // -48;
    public static final int KM_ERROR_SECURE_HW_COMMUNICATION_FAILED =
            ErrorCode.SECURE_HW_COMMUNICATION_FAILED; // -49;
    public static final int KM_ERROR_UNSUPPORTED_EC_FIELD =
            ErrorCode.UNSUPPORTED_EC_FIELD; // -50;
    public static final int KM_ERROR_MISSING_NONCE =
            ErrorCode.MISSING_NONCE; // -51;
    public static final int KM_ERROR_INVALID_NONCE =
            ErrorCode.INVALID_NONCE; // -52;
    public static final int KM_ERROR_MISSING_MAC_LENGTH =
            ErrorCode.MISSING_MAC_LENGTH; // -53;
    public static final int KM_ERROR_KEY_RATE_LIMIT_EXCEEDED =
            ErrorCode.KEY_RATE_LIMIT_EXCEEDED; // -54;
    public static final int KM_ERROR_CALLER_NONCE_PROHIBITED =
            ErrorCode.CALLER_NONCE_PROHIBITED; // -55;
    public static final int KM_ERROR_KEY_MAX_OPS_EXCEEDED =
            ErrorCode.KEY_MAX_OPS_EXCEEDED; // -56;
    public static final int KM_ERROR_INVALID_MAC_LENGTH =
            ErrorCode.INVALID_MAC_LENGTH; // -57;
    public static final int KM_ERROR_MISSING_MIN_MAC_LENGTH =
            ErrorCode.MISSING_MIN_MAC_LENGTH; // -58;
    public static final int KM_ERROR_UNSUPPORTED_MIN_MAC_LENGTH =
            ErrorCode.UNSUPPORTED_MIN_MAC_LENGTH; // -59;
    public static final int KM_ERROR_UNSUPPORTED_KDF = ErrorCode.UNSUPPORTED_KDF; // -60
    public static final int KM_ERROR_UNSUPPORTED_EC_CURVE = ErrorCode.UNSUPPORTED_EC_CURVE; // -61
    // -62 is KEY_REQUIRES_UPGRADE and is handled by Keystore.
    public static final int KM_ERROR_ATTESTATION_CHALLENGE_MISSING =
            ErrorCode.ATTESTATION_CHALLENGE_MISSING; // -63
    public static final int KM_ERROR_KEYMINT_NOT_CONFIGURED =
            ErrorCode.KEYMINT_NOT_CONFIGURED; // -64
    public static final int KM_ERROR_ATTESTATION_APPLICATION_ID_MISSING =
            ErrorCode.ATTESTATION_APPLICATION_ID_MISSING; // -65;
    public static final int KM_ERROR_CANNOT_ATTEST_IDS =
            ErrorCode.CANNOT_ATTEST_IDS; // -66;
    public static final int KM_ERROR_ROLLBACK_RESISTANCE_UNAVAILABLE =
            ErrorCode.ROLLBACK_RESISTANCE_UNAVAILABLE; // -67;
    public static final int KM_ERROR_HARDWARE_TYPE_UNAVAILABLE =
            ErrorCode.HARDWARE_TYPE_UNAVAILABLE; // -68;
    public static final int KM_ERROR_DEVICE_LOCKED =
            ErrorCode.DEVICE_LOCKED; // -72;
    public static final int KM_ERROR_STORAGE_KEY_UNSUPPORTED =
            ErrorCode.STORAGE_KEY_UNSUPPORTED; // -77,
    public static final int KM_ERROR_INCOMPATIBLE_MGF_DIGEST =
            ErrorCode.INCOMPATIBLE_MGF_DIGEST; // -78,
    public static final int KM_ERROR_UNSUPPORTED_MGF_DIGEST =
            ErrorCode.UNSUPPORTED_MGF_DIGEST; // -79,
    public static final int KM_ERROR_MISSING_NOT_BEFORE =
            ErrorCode.MISSING_NOT_BEFORE; // -80;
    public static final int KM_ERROR_MISSING_NOT_AFTER =
            ErrorCode.MISSING_NOT_AFTER; // -80;
    public static final int KM_ERROR_HARDWARE_NOT_YET_AVAILABLE =
            ErrorCode.HARDWARE_NOT_YET_AVAILABLE; // -85
    public static final int KM_ERROR_UNIMPLEMENTED =
            ErrorCode.UNIMPLEMENTED; // -100;
    public static final int KM_ERROR_VERSION_MISMATCH =
            ErrorCode.VERSION_MISMATCH; // -101;
    public static final int KM_ERROR_UNKNOWN_ERROR =
            ErrorCode.UNKNOWN_ERROR; // -1000;

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
        sErrorCodeToString.put(KM_ERROR_HARDWARE_TYPE_UNAVAILABLE, "Requested security level "
                        + "(likely Strongbox) is not available.");
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
