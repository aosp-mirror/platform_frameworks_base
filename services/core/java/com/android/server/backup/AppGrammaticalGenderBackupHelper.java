/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.backup;

import static android.app.backup.BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;

import android.annotation.UserIdInt;
import android.app.backup.BackupDataOutput;
import android.app.backup.BlobBackupHelper;
import android.os.ParcelFileDescriptor;

import com.android.server.LocalServices;
import com.android.server.grammaticalinflection.GrammaticalInflectionManagerInternal;

public class AppGrammaticalGenderBackupHelper extends BlobBackupHelper {
    private static final int BLOB_VERSION = 1;
    private static final String KEY_APP_GENDER = "app_gender";

    private final @UserIdInt int mUserId;
    private final GrammaticalInflectionManagerInternal mGrammarInflectionManagerInternal;

    public AppGrammaticalGenderBackupHelper(int userId) {
        super(BLOB_VERSION, KEY_APP_GENDER);
        mUserId = userId;
        mGrammarInflectionManagerInternal = LocalServices.getService(
                GrammaticalInflectionManagerInternal.class);
    }

    @Override
    public void performBackup(ParcelFileDescriptor oldStateFd, BackupDataOutput data,
            ParcelFileDescriptor newStateFd) {
        // Only backup the gender data if e2e encryption is present
        if ((data.getTransportFlags() & FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED) == 0) {
            return;
        }

        super.performBackup(oldStateFd, data, newStateFd);
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        return KEY_APP_GENDER.equals(key) && mGrammarInflectionManagerInternal != null ?
                mGrammarInflectionManagerInternal.getBackupPayload(mUserId) : null;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        if (KEY_APP_GENDER.equals(key) && mGrammarInflectionManagerInternal != null) {
            mGrammarInflectionManagerInternal.stageAndApplyRestoredPayload(payload, mUserId);
        }
    }
}