/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import android.app.backup.BlobBackupHelper;

public class ShortcutBackupAgent extends BlobBackupHelper {
    private static final String TAG = "ShortcutBackupAgent";
    private static final int BLOB_VERSION = 1;

    public ShortcutBackupAgent(int currentBlobVersion, String... keys) {
        super(currentBlobVersion, keys);
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        throw new RuntimeException("not implemented yet"); // todo
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        throw new RuntimeException("not implemented yet"); // todo
    }
}
