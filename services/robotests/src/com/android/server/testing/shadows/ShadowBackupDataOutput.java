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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import android.app.backup.BackupDataOutput;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.FileDescriptor;
import java.io.IOException;

@Implements(BackupDataOutput.class)
public class ShadowBackupDataOutput {
    private long mQuota;
    private int mTransportFlags;

    @Implementation
    public void __constructor__(FileDescriptor fd, long quota, int transportFlags) {
        mQuota = quota;
        mTransportFlags = transportFlags;
    }

    @Implementation
    public long getQuota() {
        return mQuota;
    }

    @Implementation
    public int getTransportFlags() {
        return mTransportFlags;
    }

    @Implementation
    public int writeEntityHeader(String key, int dataSize) throws IOException {
        return 0;
    }

    @Implementation
    public int writeEntityData(byte[] data, int size) throws IOException {
        return 0;
    }
}
