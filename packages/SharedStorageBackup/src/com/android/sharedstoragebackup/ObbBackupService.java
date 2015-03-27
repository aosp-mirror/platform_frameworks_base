/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.sharedstoragebackup;

import android.app.Service;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.app.backup.IBackupManager;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import com.android.internal.backup.IObbBackupService;

/**
 * Service that the Backup Manager Services delegates OBB backup/restore operations to,
 * because those require accessing external storage.
 *
 * {@hide}
 */
public class ObbBackupService extends Service {
    static final String TAG = "ObbBackupService";
    static final boolean DEBUG = true;

    /**
     * IObbBackupService interface implementation
     */
    IObbBackupService mService = new IObbBackupService.Stub() {
        /*
         * Back up a package's OBB directory tree
         */
        @Override
        public void backupObbs(String packageName, ParcelFileDescriptor data,
                int token, IBackupManager callbackBinder) {
            final FileDescriptor outFd = data.getFileDescriptor();
            try {
                File obbDir = Environment.buildExternalStorageAppObbDirs(packageName)[0];
                if (obbDir != null) {
                    if (obbDir.exists()) {
                        ArrayList<File> obbList = allFileContents(obbDir);
                        if (obbList != null) {
                            // okay, there's at least something there
                            if (DEBUG) {
                                Log.i(TAG, obbList.size() + " files to back up");
                            }
                            final String rootPath = obbDir.getCanonicalPath();
                            final FullBackupDataOutput out = new FullBackupDataOutput(data);
                            for (File f : obbList) {
                                final String filePath = f.getCanonicalPath();
                                if (DEBUG) {
                                    Log.i(TAG, "storing: " + filePath);
                                }
                                FullBackup.backupToTar(packageName, FullBackup.OBB_TREE_TOKEN, null,
                                        rootPath, filePath, out);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Exception backing up OBBs for " + packageName, e);
            } finally {
                // Send the EOD marker indicating that there is no more data
                try {
                    FileOutputStream out = new FileOutputStream(outFd);
                    byte[] buf = new byte[4];
                    out.write(buf);
                } catch (IOException e) {
                    Log.e(TAG, "Unable to finalize obb backup stream!");
                }

                try {
                    callbackBinder.opComplete(token, 0);
                } catch (RemoteException e) {
                }
            }
        }

        /*
         * Restore an OBB file for the given package from the incoming stream
         */
        @Override
        public void restoreObbFile(String packageName, ParcelFileDescriptor data,
                long fileSize, int type, String path, long mode, long mtime,
                int token, IBackupManager callbackBinder) {
            try {
                File outFile = Environment.buildExternalStorageAppObbDirs(packageName)[0];
                if (outFile != null) {
                    outFile = new File(outFile, path);
                }
                // outFile is null here if we couldn't get access to external storage,
                // in which case restoreFile() will discard the data cleanly and let
                // us proceed with the next file segment in the stream.  We pass -1
                // for the file mode to suppress attempts to chmod() on shared storage.
                FullBackup.restoreFile(data, fileSize, type, -1, mtime, outFile);
            } catch (IOException e) {
                Log.i(TAG, "Exception restoring OBB " + path, e);
            } finally {
                try {
                    callbackBinder.opComplete(token, 0);
                } catch (RemoteException e) {
                }
            }
        }

        ArrayList<File> allFileContents(File rootDir) {
            final ArrayList<File> files = new ArrayList<File>();
            final ArrayList<File> dirs = new ArrayList<File>();

            dirs.add(rootDir);
            while (!dirs.isEmpty()) {
                File dir = dirs.remove(0);
                File[] contents = dir.listFiles();
                if (contents != null) {
                    for (File f : contents) {
                        if (f.isDirectory()) dirs.add(f);
                        else if (f.isFile()) files.add(f);
                    }
                }
            }
            return files;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mService.asBinder();
    }
}
