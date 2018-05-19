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
         * Status of the key sync {@code RecoveryController#setRecoveryStatus}
         */
        static final String COLUMN_NAME_RECOVERY_STATUS = "recovery_status";
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
         * Deprecated.
         */
        static final String COLUMN_NAME_PUBLIC_KEY = "public_key";

        /**
         * The certificate path of the recovery service.
         * Deprecated.
         */
        static final String COLUMN_NAME_CERT_PATH = "cert_path";

        /**
         * The serial number contained in the certificate XML file of the recovery service.
         * Deprecated.
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

        /**
         * Active root of trust
         */
        static final String COLUMN_NAME_ACTIVE_ROOT_OF_TRUST = "active_root_of_trust";
    }

    /**
     * Table data for given recovery agent and root of trust pair.
     */
    static class RootOfTrustEntry implements BaseColumns {
        static final String TABLE_NAME = "root_of_trust";

        /**
         * The user id of the profile the application is running under.
         */
        static final String COLUMN_NAME_USER_ID = "user_id";

        /**
         * The uid of the application that initializes the local recovery components.
         */
        static final String COLUMN_NAME_UID = "uid";

        /**
         * Root of trust alias
         */
        static final String COLUMN_NAME_ROOT_ALIAS = "root_alias";

        /**
         * The certificate path of the recovery service.
         */
        static final String COLUMN_NAME_CERT_PATH = "cert_path";

        /**
         * The serial number contained in the certificate XML file of the recovery service.
         */
        static final String COLUMN_NAME_CERT_SERIAL = "cert_serial";
    }
}
