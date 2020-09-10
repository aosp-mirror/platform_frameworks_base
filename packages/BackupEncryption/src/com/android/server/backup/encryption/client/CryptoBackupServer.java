/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.client;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.util.Map;

/**
 * Contains methods for communicating with the parts of the backup server relevant to encryption.
 */
public interface CryptoBackupServer {
    /**
     * Uploads an incremental backup to the server.
     *
     * <p>Handles setting up and tearing down the connection.
     *
     * @param packageName the package to associate the data with
     * @param oldDocId the id of the previous backup doc in Drive
     * @param diffScript containing the actual backup data
     * @param tertiaryKey the wrapped key used to encrypt this backup
     * @return the id of the new backup doc in Drive.
     */
    String uploadIncrementalBackup(
            String packageName,
            String oldDocId,
            byte[] diffScript,
            WrappedKeyProto.WrappedKey tertiaryKey);

    /**
     * Uploads non-incremental backup to the server.
     *
     * <p>Handles setting up and tearing down the connection.
     *
     * @param packageName the package to associate the data with
     * @param data the actual backup data
     * @param tertiaryKey the wrapped key used to encrypt this backup
     * @return the id of the new backup doc in Drive.
     */
    String uploadNonIncrementalBackup(
            String packageName, byte[] data, WrappedKeyProto.WrappedKey tertiaryKey);

    /**
     * Sets the alias of the active secondary key. This is the alias used to refer to the key in the
     * {@link java.security.KeyStore}. It is also used to key storage for tertiary keys on the
     * backup server. Also has to upload all existing tertiary keys, wrapped with the new key.
     *
     * @param keyAlias The ID of the secondary key.
     * @param tertiaryKeys The tertiary keys, wrapped with the new secondary key.
     */
    void setActiveSecondaryKeyAlias(
            String keyAlias, Map<String, WrappedKeyProto.WrappedKey> tertiaryKeys);
}
