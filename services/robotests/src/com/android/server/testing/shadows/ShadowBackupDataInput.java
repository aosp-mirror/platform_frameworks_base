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

import android.app.backup.BackupDataInput;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.FileDescriptor;
import java.io.IOException;

@Implements(BackupDataInput.class)
public class ShadowBackupDataInput {
    @Implementation
    public void __constructor__(FileDescriptor fd) {
    }

    @Implementation
    protected void finalize() throws Throwable {
    }

    @Implementation
    public boolean readNextHeader() throws IOException {
        return false;
    }

    @Implementation
    public String getKey() {
        throw new AssertionError("Can't call because readNextHeader() returned false");
    }

    @Implementation
    public int getDataSize() {
        throw new AssertionError("Can't call because readNextHeader() returned false");
    }

    @Implementation
    public int readEntityData(byte[] data, int offset, int size) throws IOException {
        throw new AssertionError("Can't call because readNextHeader() returned false");
    }

    @Implementation
    public void skipEntityData() throws IOException {
        throw new AssertionError("Can't call because readNextHeader() returned false");
    }
}
