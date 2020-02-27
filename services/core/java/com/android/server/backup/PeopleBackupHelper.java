/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.app.backup.BlobBackupHelper;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.people.PeopleServiceInternal;

class PeopleBackupHelper extends BlobBackupHelper {

    private static final String TAG = PeopleBackupHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Current schema of the backup state blob.
    private static final int STATE_VERSION = 1;

    // Key under which conversation infos state blob is committed to backup.
    private static final String KEY_CONVERSATIONS = "people_conversation_infos";

    private final int mUserId;

    PeopleBackupHelper(int userId) {
        super(STATE_VERSION, KEY_CONVERSATIONS);
        mUserId = userId;
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        if (!KEY_CONVERSATIONS.equals(key)) {
            Slog.w(TAG, "Unexpected backup key " + key);
            return new byte[0];
        }
        PeopleServiceInternal ps = LocalServices.getService(PeopleServiceInternal.class);
        if (DEBUG) {
            Slog.d(TAG, "Handling backup of " + key);
        }
        return ps.getBackupPayload(mUserId);
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        if (!KEY_CONVERSATIONS.equals(key)) {
            Slog.w(TAG, "Unexpected restore key " + key);
            return;
        }
        PeopleServiceInternal ps = LocalServices.getService(PeopleServiceInternal.class);
        if (DEBUG) {
            Slog.d(TAG, "Handling restore of " + key);
        }
        ps.restore(mUserId, payload);
    }
}
