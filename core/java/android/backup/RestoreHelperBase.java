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
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.InputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;

class RestoreHelperBase {
    private static final String TAG = "RestoreHelperBase";

    int mPtr;
    Context mContext;
    boolean mExceptionLogged;
    
    RestoreHelperBase(Context context) {
        mPtr = ctor();
        mContext = context;
    }

    protected void finalize() throws Throwable {
        try {
            dtor(mPtr);
        } finally {
            super.finalize();
        }
    }

    void writeFile(File f, InputStream in) {
        if (!(in instanceof BackupDataInputStream)) {
            throw new IllegalStateException("input stream must be a BackupDataInputStream");
        }
        int result = -1;

        // Create the enclosing directory.
        File parent = f.getParentFile();
        parent.mkdirs();

        result = writeFile_native(mPtr, f.getAbsolutePath(),
                ((BackupDataInputStream)in).mData.mBackupReader);
        if (result != 0) {
            // Bail on this entity.  Only log one failure per helper object.
            if (!mExceptionLogged) {
                Log.e(TAG, "Failed restoring file '" + f + "' for app '"
                        + mContext.getPackageName() + "\' result=0x"
                        + Integer.toHexString(result));
                mExceptionLogged = true;
            }
        }
    }

    public void writeSnapshot(ParcelFileDescriptor fd) {
        int result = writeSnapshot_native(mPtr, fd.getFileDescriptor());
        // TODO: Do something with the error.
    }

    private static native int ctor();
    private static native void dtor(int ptr);
    private static native int writeFile_native(int ptr, String filename, int backupReader);
    private static native int writeSnapshot_native(int ptr, FileDescriptor fd);
}


