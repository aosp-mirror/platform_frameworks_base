/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.backup;

import android.content.Context;

import java.io.FileDescriptor;

/** @hide */
public class BackupDataOutput {
    /* package */ FileDescriptor fd;

    public static final int OP_UPDATE = 1;
    public static final int OP_DELETE = 2;

    public BackupDataOutput(Context context, FileDescriptor fd) {
        this.fd = fd;
    }

    public void close() {
        // do we close the fd?
    }
    public native void flush();
    public native void write(byte[] buffer);
    public native void write(int oneByte);
    public native void write(byte[] buffer, int offset, int count);

    public native void writeOperation(int op);
    public native void writeKey(String key);
}

