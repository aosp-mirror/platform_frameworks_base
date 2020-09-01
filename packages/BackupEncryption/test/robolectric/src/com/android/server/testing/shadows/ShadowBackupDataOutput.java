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

package com.android.server.testing.shadows;

import android.app.backup.BackupDataOutput;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Shadow for BackupDataOutput. */
@Implements(BackupDataOutput.class)
public class ShadowBackupDataOutput {
    private static final List<DataEntity> ENTRIES = new ArrayList<>();

    private String mCurrentKey;
    private int mDataSize;

    public static void reset() {
        ENTRIES.clear();
    }

    public static Set<DataEntity> getEntities() {
        return new LinkedHashSet<>(ENTRIES);
    }

    public void __constructor__(FileDescriptor fd) {}

    public void __constructor__(FileDescriptor fd, long quota) {}

    public void __constructor__(FileDescriptor fd, long quota, int transportFlags) {}

    @Implementation
    public int writeEntityHeader(String key, int size) {
        mCurrentKey = key;
        mDataSize = size;
        return 0;
    }

    @Implementation
    public int writeEntityData(byte[] data, int size) {
        Assert.assertEquals("ShadowBackupDataOutput expects size = mDataSize", size, mDataSize);
        ENTRIES.add(new DataEntity(mCurrentKey, data, mDataSize));
        return 0;
    }
}
