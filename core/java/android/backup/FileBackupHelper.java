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

import java.io.File;
import java.io.FileDescriptor;

/** @hide */
public class FileBackupHelper {
    private static final String TAG = "FileBackupHelper";

    /**
     * Based on oldState, determine which of the files from the application's data directory
     * need to be backed up, write them to the data stream, and fill in newState with the
     * state as it exists now.
     */
    public static void performBackup(Context context,
            ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState, String[] files) {
        File base = context.getFilesDir();
        final int N = files.length;
        String[] fullPaths = new String[N];
        for (int i=0; i<N; i++) {
            fullPaths[i] = (new File(base, files[i])).getAbsolutePath();
        }
        performBackup_checked(oldState, data, newState, fullPaths, files);
    }

    /**
     * Check the parameters so the native code doens't have to throw all the exceptions
     * since it's easier to do that from java.
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

    native private static int performBackup_native(FileDescriptor oldState,
            int data, FileDescriptor newState, String[] files, String[] keys);
}
