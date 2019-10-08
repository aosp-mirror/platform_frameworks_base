/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.storage;

import android.provider.BaseColumns;

/** Contract for the backup encryption database. Describes tables present. */
class BackupEncryptionDbContract {
    /**
     * Table containing tertiary keys belonging to the user. Tertiary keys are wrapped by a
     * secondary key, which never leaves {@code AndroidKeyStore} (a provider for {@link
     * java.security.KeyStore}). Each application has a tertiary key, which is used to encrypt the
     * backup data.
     */
    static class TertiaryKeysEntry implements BaseColumns {
        static final String TABLE_NAME = "tertiary_keys";

        /** Alias of the secondary key used to wrap the tertiary key. */
        static final String COLUMN_NAME_SECONDARY_KEY_ALIAS = "secondary_key_alias";

        /** Name of the package to which the tertiary key belongs. */
        static final String COLUMN_NAME_PACKAGE_NAME = "package_name";

        /** Encrypted bytes of the tertiary key. */
        static final String COLUMN_NAME_WRAPPED_KEY_BYTES = "wrapped_key_bytes";
    }
}
