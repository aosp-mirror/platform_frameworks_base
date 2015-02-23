/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keymaster;

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
    public static final int KM_INT = 3 << 28;
    public static final int KM_INT_REP = 4 << 28;
    public static final int KM_LONG = 5 << 28;
    public static final int KM_DATE = 6 << 28;
    public static final int KM_BOOL = 7 << 28;
    public static final int KM_BIGNUM = 8 << 28;
    public static final int KM_BYTES = 9 << 28;

    // Tag values.
    public static final int KM_TAG_INVALID = KM_INVALID | 0;
    public static final int KM_TAG_PURPOSE = KM_ENUM_REP | 1;
    public static final int KM_TAG_ALGORITHM = KM_ENUM | 2;
    public static final int KM_TAG_KEY_SIZE = KM_INT | 3;
    public static final int KM_TAG_BLOCK_MODE = KM_ENUM | 4;
    public static final int KM_TAG_DIGEST = KM_ENUM | 5;
    public static final int KM_TAG_MAC_LENGTH = KM_INT | 6;
    public static final int KM_TAG_PADDING = KM_ENUM | 7;
    public static final int KM_TAG_RETURN_UNAUTHED = KM_BOOL | 8;
    public static final int KM_TAG_CALLER_NONCE = KM_BOOL | 9;

    public static final int KM_TAG_RESCOPING_ADD = KM_ENUM_REP | 101;
    public static final int KM_TAG_RESCOPING_DEL = KM_ENUM_REP | 102;
    public static final int KM_TAG_BLOB_USAGE_REQUIREMENTS = KM_ENUM | 705;

    public static final int KM_TAG_RSA_PUBLIC_EXPONENT = KM_LONG | 200;
    public static final int KM_TAG_DSA_GENERATOR = KM_BIGNUM | 201;
    public static final int KM_TAG_DSA_P = KM_BIGNUM | 202;
    public static final int KM_TAG_DSA_Q = KM_BIGNUM | 203;
    public static final int KM_TAG_ACTIVE_DATETIME = KM_DATE | 400;
    public static final int KM_TAG_ORIGINATION_EXPIRE_DATETIME = KM_DATE | 401;
    public static final int KM_TAG_USAGE_EXPIRE_DATETIME = KM_DATE | 402;
    public static final int KM_TAG_MIN_SECONDS_BETWEEN_OPS = KM_INT | 403;
    public static final int KM_TAG_MAX_USES_PER_BOOT = KM_INT | 404;

    public static final int KM_TAG_ALL_USERS = KM_BOOL | 500;
    public static final int KM_TAG_USER_ID = KM_INT | 501;
    public static final int KM_TAG_NO_AUTH_REQUIRED = KM_BOOL | 502;
    public static final int KM_TAG_USER_AUTH_ID = KM_INT_REP | 503;
    public static final int KM_TAG_AUTH_TIMEOUT = KM_INT | 504;

    public static final int KM_TAG_ALL_APPLICATIONS = KM_BOOL | 600;
    public static final int KM_TAG_APPLICATION_ID = KM_BYTES | 601;

    public static final int KM_TAG_APPLICATION_DATA = KM_BYTES | 700;
    public static final int KM_TAG_CREATION_DATETIME = KM_DATE | 701;
    public static final int KM_TAG_ORIGIN = KM_ENUM | 702;
    public static final int KM_TAG_ROLLBACK_RESISTANT = KM_BOOL | 703;
    public static final int KM_TAG_ROOT_OF_TRUST = KM_BYTES | 704;

    public static final int KM_TAG_ASSOCIATED_DATA = KM_BYTES | 1000;
    public static final int KM_TAG_NONCE = KM_BYTES | 1001;
    public static final int KM_TAG_CHUNK_LENGTH = KM_INT | 1002;

    // Algorithm values.
    public static final int KM_ALGORITHM_RSA = 1;
    public static final int KM_ALGORITHM_DSA = 2;
    public static final int KM_ALGORITHM_ECDSA = 3;
    public static final int KM_ALGORITHM_ECIES = 4;
    public static final int KM_ALGORITHM_AES = 32;
    public static final int KM_ALGORITHM_3DES = 33;
    public static final int KM_ALGORITHM_SKIPJACK = 34;
    public static final int KM_ALGORITHM_MARS = 48;
    public static final int KM_ALGORITHM_RC6 = 49;
    public static final int KM_ALGORITHM_SERPENT = 50;
    public static final int KM_ALGORITHM_TWOFISH = 51;
    public static final int KM_ALGORITHM_IDEA = 52;
    public static final int KM_ALGORITHM_RC5 = 53;
    public static final int KM_ALGORITHM_CAST5 = 54;
    public static final int KM_ALGORITHM_BLOWFISH = 55;
    public static final int KM_ALGORITHM_RC4 = 64;
    public static final int KM_ALGORITHM_CHACHA20 = 65;
    public static final int KM_ALGORITHM_HMAC = 128;

    // Block modes.
    public static final int KM_MODE_FIRST_UNAUTHENTICATED = 1;
    public static final int KM_MODE_ECB = KM_MODE_FIRST_UNAUTHENTICATED;
    public static final int KM_MODE_CBC = 2;
    public static final int KM_MODE_CBC_CTS = 3;
    public static final int KM_MODE_CTR = 4;
    public static final int KM_MODE_OFB = 5;
    public static final int KM_MODE_CFB = 6;
    public static final int KM_MODE_XTS = 7;
    public static final int KM_MODE_FIRST_AUTHENTICATED = 32;
    public static final int KM_MODE_GCM = KM_MODE_FIRST_AUTHENTICATED;
    public static final int KM_MODE_OCB = 33;
    public static final int KM_MODE_CCM = 34;
    public static final int KM_MODE_FIRST_MAC = 128;
    public static final int KM_MODE_CMAC = KM_MODE_FIRST_MAC;
    public static final int KM_MODE_POLY1305 = 129;

    // Padding modes.
    public static final int KM_PAD_NONE = 1;
    public static final int KM_PAD_RSA_OAEP = 2;
    public static final int KM_PAD_RSA_PSS = 3;
    public static final int KM_PAD_RSA_PKCS1_1_5_ENCRYPT = 4;
    public static final int KM_PAD_RSA_PKCS1_1_5_SIGN = 5;
    public static final int KM_PAD_ANSI_X923 = 32;
    public static final int KM_PAD_ISO_10126 = 33;
    public static final int KM_PAD_ZERO = 64;
    public static final int KM_PAD_PKCS7 = 65;
    public static final int KM_PAD_ISO_7816_4 = 66;

    // Digest modes.
    public static final int KM_DIGEST_NONE = 0;
    public static final int KM_DIGEST_MD5 = 1;
    public static final int KM_DIGEST_SHA1 = 2;
    public static final int KM_DIGEST_SHA_2_224 = 3;
    public static final int KM_DIGEST_SHA_2_256 = 4;
    public static final int KM_DIGEST_SHA_2_384 = 5;
    public static final int KM_DIGEST_SHA_2_512 = 6;
    public static final int KM_DIGEST_SHA_3_256 = 7;
    public static final int KM_DIGEST_SHA_3_384 = 8;
    public static final int KM_DIGEST_SHA_3_512 = 9;

    // Key origins.
    public static final int KM_ORIGIN_HARDWARE = 0;
    public static final int KM_ORIGIN_SOFTWARE = 1;
    public static final int KM_ORIGIN_IMPORTED = 2;

    // Key usability requirements.
    public static final int KM_BLOB_STANDALONE = 0;
    public static final int KM_BLOB_REQUIRES_FILE_SYSTEM = 1;

    // Operation Purposes.
    public static final int KM_PURPOSE_ENCRYPT = 0;
    public static final int KM_PURPOSE_DECRYPT = 1;
    public static final int KM_PURPOSE_SIGN = 2;
    public static final int KM_PURPOSE_VERIFY = 3;

    // Key formats.
    public static final int KM_KEY_FORMAT_X509 = 0;
    public static final int KM_KEY_FORMAT_PKCS8 = 1;
    public static final int KM_KEY_FORMAT_PKCS12 = 2;
    public static final int KM_KEY_FORMAT_RAW = 3;

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
    public static final int KM_ERROR_UNSUPPORTED_TAG_LENGTH = -9;
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
    public static final int KM_ERROR_INVALID_DSA_PARAMS = -43;
    public static final int KM_ERROR_IMPORT_PARAMETER_MISMATCH = -44;
    public static final int KM_ERROR_SECURE_HW_ACCESS_DENIED = -45;
    public static final int KM_ERROR_OPERATION_CANCELLED = -46;
    public static final int KM_ERROR_CONCURRENT_ACCESS_CONFLICT = -47;
    public static final int KM_ERROR_SECURE_HW_BUSY = -48;
    public static final int KM_ERROR_SECURE_HW_COMMUNICATION_FAILED = -49;
    public static final int KM_ERROR_UNSUPPORTED_EC_FIELD = -50;
    public static final int KM_ERROR_UNIMPLEMENTED = -100;
    public static final int KM_ERROR_VERSION_MISMATCH = -101;
    public static final int KM_ERROR_UNKNOWN_ERROR = -1000;

    public static int getTagType(int tag) {
        return tag & (0xF << 28);
    }
}
