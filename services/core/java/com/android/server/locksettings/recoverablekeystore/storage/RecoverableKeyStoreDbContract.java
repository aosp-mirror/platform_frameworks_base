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

package com.android.server.locksettings.recoverablekeystore.storage;

import android.provider.BaseColumns;

/**
 * Contract for recoverable key database. Describes the tables present.
 */
class RecoverableKeyStoreDbContract {
    /**
     * Table holding wrapped keys, and information about when they were last synced.
     */
    static class KeysEntry implements BaseColumns {
        static final String TABLE_NAME = "keys";

        /**
         * The user id of the profile the application is running under.
         */
        static final String COLUMN_NAME_USER_ID = "user_id";

        /**
         * The uid of the application that generated the key.
         */
        static final String COLUMN_NAME_UID = "uid";

        /**
         * The alias of the key, as set in AndroidKeyStore.
         */
        static final String COLUMN_NAME_ALIAS = "alias";

        /**
         * Nonce with which the key was encrypted.
         */
        static final String COLUMN_NAME_NONCE = "nonce";

        /**
         * Encrypted bytes of the key.
         */
        static final String COLUMN_NAME_WRAPPED_KEY = "wrapped_key";

        /**
         * Generation ID of the platform key that was used to encrypt this key.
         */
        static final String COLUMN_NAME_GENERATION_ID = "platform_key_generation_id";

        /**
         * Timestamp of when this key was last synced with remote storage, or -1 if never synced.
         */
        static final String COLUMN_NAME_LAST_SYNCED_AT = "last_synced_at";

        /**
         * Status of the key sync {@code RecoveryManager#setRecoveryStatus}
         */
        static final String COLUMN_NAME_RECOVERY_STATUS = "recovery_status";
    }

    /**
     * Table holding encrypted snapshots of the recoverable key store.
     */
    static class SnapshotsEntry implements BaseColumns {
        static final String TABLE_NAME = "snapshots";

        /**
         * The version number of the snapshot.
         */
        static final String COLUMN_NAME_VERSION = "version";

        /**
         * The ID of the user whose keystore was snapshotted.
         */
        static final String COLUMN_NAME_USER_ID = "user_id";

        /**
         * The UID of the app that owns the snapshot (i.e., the recovery agent).
         */
        static final String COLUMN_NAME_UID = "uid";

        /**
         * The maximum number of attempts allowed to attempt to decrypt the recovery key.
         */
        static final String COLUMN_NAME_MAX_ATTEMPTS = "max_attempts";

        /**
         * The ID of the counter in the trusted hardware module.
         */
        static final String COLUMN_NAME_COUNTER_ID = "counter_id";

        /**
         * Server parameters used to help identify the device (during recovery).
         */
        static final String SERVER_PARAMS = "server_params";

        /**
         * The public key of the trusted hardware module. This key has been used to encrypt the
         * snapshot, to ensure that it can only be read by the trusted module.
         */
        static final String TRUSTED_HARDWARE_PUBLIC_KEY = "thm_public_key";

        /**
         * {@link java.security.cert.CertPath} signing the trusted hardware module to whose public
         * key this snapshot is encrypted.
         */
        static final String CERT_PATH = "cert_path";

        /**
         * The recovery key, encrypted with the user's lock screen and the trusted hardware module's
         * public key.
         */
        static final String ENCRYPTED_RECOVERY_KEY = "encrypted_recovery_key";
    }

    /**
     * Table holding encrypted keys belonging to a particular snapshot.
     */
    static class SnapshotKeysEntry implements BaseColumns {
        static final String TABLE_NAME = "snapshot_keys";

        /**
         * ID of the associated snapshot entry in {@link SnapshotsEntry}.
         */
        static final String COLUMN_NAME_SNAPSHOT_ID = "snapshot_id";

        /**
         * Alias of the key.
         */
        static final String COLUMN_NAME_ALIAS = "alias";

        /**
         * Key material, encrypted with the recovery key from the snapshot.
         */
        static final String COLUMN_NAME_ENCRYPTED_BYTES = "encrypted_key_bytes";
    }

    /**
     * A layer of protection associated with a snapshot.
     */
    static class SnapshotProtectionParams implements BaseColumns {
        static final String TABLE_NAME = "snapshot_protection_params";

        /**
         * ID of the associated snapshot entry in {@link SnapshotsEntry}.
         */
        static final String COLUMN_NAME_SNAPSHOT_ID = "snapshot_id";

        /**
         * Type of secret used to generate recovery key. One of
         * {@link android.security.keystore.recovery.KeyChainProtectionParams#TYPE_LOCKSCREEN} or
         * {@link android.security.keystore.recovery.KeyChainProtectionParams#TYPE_CUSTOM_PASSWORD}.
         */
        static final String COLUMN_NAME_SECRET_TYPE = "secret_type";

        /**
         * If a lock screen, the type of UI used. One of
         * {@link android.security.keystore.recovery.KeyChainProtectionParams#UI_FORMAT_PATTERN},
         * {@link android.security.keystore.recovery.KeyChainProtectionParams#UI_FORMAT_PIN}, or
         * {@link android.security.keystore.recovery.KeyChainProtectionParams#UI_FORMAT_PASSWORD}.
         */
        static final String COLUMN_NAME_LOCKSCREEN_UI_TYPE = "lock_screen_ui_type";

        /**
         * The algorithm used to derive cryptographic material from the key and salt. One of
         * {@link android.security.keystore.recovery.KeyDerivationParams#ALGORITHM_SHA256} or
         * {@link android.security.keystore.recovery.KeyDerivationParams#ALGORITHM_ARGON2ID}.
         */
        static final String COLUMN_NAME_KEY_DERIVATION_ALGORITHM = "key_derivation_algorithm";

        /**
         * The salt used along with the secret to generate cryptographic material.
         */
        static final String COLUMN_NAME_KEY_DERIVATION_SALT = "key_derivation_salt";
    }

    /**
     * Recoverable KeyStore metadata for a specific user profile.
     */
    static class UserMetadataEntry implements BaseColumns {
        static final String TABLE_NAME = "user_metadata";

        /**
         * User ID of the profile.
         */
        static final String COLUMN_NAME_USER_ID = "user_id";

        /**
         * Every time a new platform key is generated for a user, this increments. The platform key
         * is used to wrap recoverable keys on disk.
         */
        static final String COLUMN_NAME_PLATFORM_KEY_GENERATION_ID = "platform_key_generation_id";
    }

    /**
     * Table holding metadata of the recovery service.
     */
    static class RecoveryServiceMetadataEntry implements BaseColumns {
        static final String TABLE_NAME = "recovery_service_metadata";

        /**
         * The user id of the profile the application is running under.
         */
        static final String COLUMN_NAME_USER_ID = "user_id";

        /**
         * The uid of the application that initializes the local recovery components.
         */
        static final String COLUMN_NAME_UID = "uid";

        /**
         * Version of the latest recovery snapshot.
         */
        static final String COLUMN_NAME_SNAPSHOT_VERSION = "snapshot_version";

        /**
         * Flag to generate new snapshot.
         */
        static final String COLUMN_NAME_SHOULD_CREATE_SNAPSHOT = "should_create_snapshot";

        /**
         * The public key of the recovery service.
         */
        static final String COLUMN_NAME_PUBLIC_KEY = "public_key";

        /**
         * The certificate path of the recovery service.
         */
        static final String COLUMN_NAME_CERT_PATH = "cert_path";

        /**
         * The serial number contained in the certificate XML file of the recovery service.
         */
        static final String COLUMN_NAME_CERT_SERIAL = "cert_serial";

        /**
         * Secret types used for end-to-end encryption.
         */
        static final String COLUMN_NAME_SECRET_TYPES = "secret_types";

        /**
         * Locally generated random number.
         */
        static final String COLUMN_NAME_COUNTER_ID = "counter_id";

        /**
         * The server parameters of the recovery service.
         */
        static final String COLUMN_NAME_SERVER_PARAMS = "server_params";
    }
}
