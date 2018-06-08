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
 * limitations under the License
 */

package com.android.server.locksettings;

import android.content.Context;

import com.android.server.PersistentDataBlockManagerInternal;

import java.io.File;

public class LockSettingsStorageTestable extends LockSettingsStorage {

    public File mStorageDir;
    public PersistentDataBlockManagerInternal mPersistentDataBlock;

    public LockSettingsStorageTestable(Context context, File storageDir) {
        super(context);
        mStorageDir = storageDir;
    }

    @Override
    String getLockPatternFilename(int userId) {
        return makeDirs(mStorageDir,
                super.getLockPatternFilename(userId)).getAbsolutePath();
    }

    @Override
    String getLockPasswordFilename(int userId) {
        return makeDirs(mStorageDir,
                super.getLockPasswordFilename(userId)).getAbsolutePath();
    }

    @Override
    String getChildProfileLockFile(int userId) {
        return makeDirs(mStorageDir,
                super.getChildProfileLockFile(userId)).getAbsolutePath();
    }

    @Override
    protected File getSyntheticPasswordDirectoryForUser(int userId) {
        return makeDirs(mStorageDir, super.getSyntheticPasswordDirectoryForUser(
                userId).getAbsolutePath());
    }

    @Override
    public PersistentDataBlockManagerInternal getPersistentDataBlock() {
        return mPersistentDataBlock;
    }

    private File makeDirs(File baseDir, String filePath) {
        File path = new File(filePath);
        if (path.getParent() == null) {
            return new File(baseDir, filePath);
        } else {
            File mappedDir = new File(baseDir, path.getParent());
            mappedDir.mkdirs();
            return new File(mappedDir, path.getName());
        }
    }
}
