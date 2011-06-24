/*
 * Copyright 2009, The Android Open Source Project
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

package android.app;

import android.app.backup.IBackupManager;
import android.os.ParcelFileDescriptor;
 
/**
 * Interface presented by applications being asked to participate in the
 * backup & restore mechanism.  End user code will not typically implement
 * this interface directly; they subclass BackupAgent instead.
 *
 * {@hide}
 */ 
oneway interface IBackupAgent {
    /**
     * Request that the app perform an incremental backup.
     *
     * @param oldState Read-only file containing the description blob of the
     *        app's data state as of the last backup operation's completion.
     *        This file is empty or invalid when a full backup is being
     *        requested.
     *
     * @param data Read-write file, empty when onBackup() is called, that
     *        is the data destination for this backup pass's incrementals.
     *
     * @param newState Read-write file, empty when onBackup() is called,
     *        where the new state blob is to be recorded.
     *
     * @param token Opaque token identifying this transaction.  This must
     *        be echoed back to the backup service binder once the new
     *        data has been written to the data and newState files.
     *
     * @param callbackBinder Binder on which to indicate operation completion,
     *        passed here as a convenience to the agent.
     */
    void doBackup(in ParcelFileDescriptor oldState,
            in ParcelFileDescriptor data,
            in ParcelFileDescriptor newState,
            int token, IBackupManager callbackBinder);

    /**
     * Restore an entire data snapshot to the application.
     *
     * @param data Read-only file containing the full data snapshot of the
     *        app's backup.  This is to be a <i>replacement</i> of the app's
     *        current data, not to be merged into it.
     *
     * @param appVersionCode The android:versionCode attribute of the application
     *        that created this data set.  This can help the agent distinguish among
     *        various historical backup content possibilities.
     *
     * @param newState Read-write file, empty when onRestore() is called,
     *        that is to be written with the state description that holds after
     *        the restore has been completed.
     *
     * @param token Opaque token identifying this transaction.  This must
     *        be echoed back to the backup service binder once the agent is
     *        finished restoring the application based on the restore data
     *        contents.
     *
     * @param callbackBinder Binder on which to indicate operation completion,
     *        passed here as a convenience to the agent.
     */
    void doRestore(in ParcelFileDescriptor data, int appVersionCode,
            in ParcelFileDescriptor newState, int token, IBackupManager callbackBinder);

    /**
     * Perform a "full" backup to the given file descriptor.  The output file is presumed
     * to be a socket or other non-seekable, write-only data sink.  When this method is
     * called, the app should write all of its files to the output.
     *
     * @param data Write-only file to receive the backed-up file content stream.
     *        The data must be formatted correctly for the resulting archive to be
     *        legitimate, so that will be tightly controlled by the available API.
     *
     * @param token Opaque token identifying this transaction.  This must
     *        be echoed back to the backup service binder once the agent is
     *        finished restoring the application based on the restore data
     *        contents.
     *
     * @param callbackBinder Binder on which to indicate operation completion,
     *        passed here as a convenience to the agent.
     */
    void doFullBackup(in ParcelFileDescriptor data, int token, IBackupManager callbackBinder);

    /**
     * Restore a single "file" to the application.  The file was typically obtained from
     * a full-backup dataset.  The agent reads 'size' bytes of file content
     * from the provided file descriptor.
     *
     * @param data Read-only pipe delivering the file content itself.
     *
     * @param size Size of the file being restored.
     * @param type Type of file system entity, e.g. FullBackup.TYPE_DIRECTORY.
     * @param domain Name of the file's semantic domain to which the 'path' argument is a
     *        relative path.  e.g. FullBackup.DATABASE_TREE_TOKEN.
     * @param path Relative path of the file within its semantic domain.
     * @param mode Access mode of the file system entity, e.g. 0660.
     * @param mtime Last modification time of the file system entity.
     */
    void doRestoreFile(in ParcelFileDescriptor data, long size,
            int type, String domain, String path, long mode, long mtime,
            int token, IBackupManager callbackBinder);
}
