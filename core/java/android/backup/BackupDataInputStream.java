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

import android.util.Log;

import java.io.InputStream;
import java.io.IOException;

/** @hide */
public class BackupDataInputStream extends InputStream {

    String key;
    int dataSize;

    BackupDataInput mData;
    byte[] mOneByte;

    BackupDataInputStream(BackupDataInput data) {
        mData = data;
    }

    public int read() throws IOException {
        byte[] one = mOneByte;
        if (mOneByte == null) {
            one = mOneByte = new byte[1];
        }
        mData.readEntityData(one, 0, 1);
        return one[0];
    }

    public int read(byte[] b, int offset, int size) throws IOException {
        return mData.readEntityData(b, offset, size);
    }

    public int read(byte[] b) throws IOException {
        return mData.readEntityData(b, 0, b.length);
    }

    public String getKey() {
        return this.key;
    }
    
    public int size() {
        return this.dataSize;
    }
}


