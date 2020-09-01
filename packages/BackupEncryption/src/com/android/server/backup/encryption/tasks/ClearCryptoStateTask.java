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

package com.android.server.backup.encryption.tasks;

import android.content.Context;
import android.util.Slog;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.chunking.ProtoStore;
import com.android.server.backup.encryption.storage.BackupEncryptionDb;
import com.android.server.backup.encryption.storage.EncryptionDbException;

import java.io.IOException;

/**
 * Task to clear local crypto state.
 *
 * <p>Needs to run whenever the user changes their backup account.
 */
public class ClearCryptoStateTask {
    private static final String TAG = "ClearCryptoStateTask";

    private final Context mContext;
    private final CryptoSettings mCryptoSettings;

    /**
     * A new instance.
     *
     * @param context for finding local storage.
     * @param cryptoSettings to clear
     */
    public ClearCryptoStateTask(Context context, CryptoSettings cryptoSettings) {
        mContext = context;
        mCryptoSettings = cryptoSettings;
    }

    /** Deletes all local state for backup (not restore). */
    public void run() {
        Slog.d(TAG, "Clearing local crypto state.");
        try {
            BackupEncryptionDb.newInstance(mContext).clear();
        } catch (EncryptionDbException e) {
            Slog.e(TAG, "Error clearing encryption database", e);
        }
        mCryptoSettings.clearAllSettingsForBackup();
        try {
            ProtoStore.createChunkListingStore(mContext).deleteAllProtos();
        } catch (IOException e) {
            Slog.e(TAG, "Error clearing chunk listing store", e);
        }
        try {
            ProtoStore.createKeyValueListingStore(mContext).deleteAllProtos();
        } catch (IOException e) {
            Slog.e(TAG, "Error clearing key-value store", e);
        }
    }
}
