/*
 * Copyright 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.internal.backup;
 
import android.app.backup.IBackupManager;
import android.os.ParcelFileDescriptor;

/**
 * Interface for the Backup Manager Service to communicate with a helper service that
 * handles local (whole-file) backup & restore of OBB content on behalf of applications.
 * This can't be done within the Backup Manager Service itself because of the restrictions
 * on system-user access to external storage, and can't be left to the apps because even
 * apps that do not have permission to access external storage in the usual way can still
 * use OBBs.
 *
 * {@hide}
 */
oneway interface IObbBackupService {
    /*
     * Back up a package's OBB directory tree
     */
    void backupObbs(in String packageName, in ParcelFileDescriptor data,
            int token, in IBackupManager callbackBinder);

    /*
     * Restore an OBB file for the given package from the incoming stream
     */
    void restoreObbFile(in String pkgName, in ParcelFileDescriptor data,
            long fileSize, int type, in String path, long mode, long mtime,
            int token, in IBackupManager callbackBinder);
}
