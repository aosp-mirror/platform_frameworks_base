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

package android.app.backup;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;

/**
 * Base class for the {@link android.app.backup.FileBackupHelper} implementation.
 */
class FileBackupHelperBase {
    private static final String TAG = "FileBackupHelperBase";

    int mPtr;
    Context mContext;
    boolean mExceptionLogged;
    
    FileBackupHelperBase(Context context) {
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

    /**
     * Check the parameters so the native code doesn't have to throw all the exceptions
     * since it's easier to do that from Java.
     */
    static void performBackup_checked(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState, String[] files, String[] keys) {
        if (files.length == 0) {
            return;
        }
        // files must be all absolute paths
        for (String f: files) {
            if (f.charAt(0) != '/') {
                throw new RuntimeException("files must have all absolute paths: " + f);
            }
        }
        // the length of files and keys must be the same
        if (files.length != keys.length) {
            throw new RuntimeException("files.length=" + files.length
                    + " keys.length=" + keys.length);
        }
        // oldStateFd can be null
        FileDescriptor oldStateFd = oldState != null ? oldState.getFileDescriptor() : null;
        FileDescriptor newStateFd = newState.getFileDescriptor();
        if (newStateFd == null) {
            throw new NullPointerException();
        }

        int err = performBackup_native(oldStateFd, data.mBackupWriter, newStateFd, files, keys);

        if (err != 0) {
            // TODO: more here
            throw new RuntimeException("Backup failed 0x" + Integer.toHexString(err));
        }
    }

    boolean writeFile(File f, BackupDataInputStream in) {
        int result = -1;

        // Create the enclosing directory.
        File parent = f.getParentFile();
        parent.mkdirs();

        result = writeFile_native(mPtr, f.getAbsolutePath(), in.mData.mBackupReader);
        if (result != 0) {
            // Bail on this entity.  Only log one failure per helper object.
            if (!mExceptionLogged) {
                Log.e(TAG, "Failed restoring file '" + f + "' for app '"
                        + mContext.getPackageName() + "\' result=0x"
                        + Integer.toHexString(result));
                mExceptionLogged = true;
            }
        }
        return (result == 0);
    }

    public void writeNewStateDescription(ParcelFileDescriptor fd) {
        int result = writeSnapshot_native(mPtr, fd.getFileDescriptor());
        // TODO: Do something with the error.
    }

    boolean isKeyInList(String key, String[] list) {
        for (String s: list) {
            if (s.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static native int ctor();
    private static native void dtor(int ptr);

    native private static int performBackup_native(FileDescriptor oldState,
            int data, FileDescriptor newState, String[] files, String[] keys);
    
    private static native int writeFile_native(int ptr, String filename, int backupReader);
    private static native int writeSnapshot_native(int ptr, FileDescriptor fd);
}


