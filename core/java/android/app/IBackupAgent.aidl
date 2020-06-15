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

import android.app.backup.IBackupCallback;
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
     * @param quotaBytes Quota reported by the transport for this backup operation (in bytes).
     *
     * @param callbackBinder Binder on which to indicate operation completion.
     *
     * @param transportFlags Flags with additional information about the transport.
     */
    void doBackup(in ParcelFileDescriptor oldState,
            in ParcelFileDescriptor data,
            in ParcelFileDescriptor newState,
            long quotaBytes, IBackupCallback callbackBinder, int transportFlags);

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
    void doRestore(in ParcelFileDescriptor data,
            long appVersionCode, in ParcelFileDescriptor newState,
            int token, IBackupManager callbackBinder);

     /**
     * Restore an entire data snapshot to the application and pass the list of excluded keys to the
     * backup agent.
     *
     * @param excludedKeys List of keys to be excluded from the restore. It will be passed to the
     *        backup agent to make it aware of what data has been removed (in case it has any
     *        application-level implications) as well as the data that should be removed by the
     *        agent itself.
     */
    void doRestoreWithExcludedKeys(in ParcelFileDescriptor data,
            long appVersionCode, in ParcelFileDescriptor newState,
            int token, IBackupManager callbackBinder, in List<String> excludedKeys);

    /**
     * Perform a "full" backup to the given file descriptor.  The output file is presumed
     * to be a socket or other non-seekable, write-only data sink.  When this method is
     * called, the app should write all of its files to the output.
     *
     * @param data Write-only file to receive the backed-up file content stream.
     *        The data must be formatted correctly for the resulting archive to be
     *        legitimate, so that will be tightly controlled by the available API.
     *
     * @param quotaBytes Quota reported by the transport for this backup operation (in bytes).
     *
     * @param token Opaque token identifying this transaction.  This must
     *        be echoed back to the backup service binder once the agent is
     *        finished restoring the application based on the restore data
     *        contents.
     *
     * @param callbackBinder Binder on which to indicate operation completion,
     *        passed here as a convenience to the agent.
     *
     * @param transportFlags Flags with additional information about transport.
     */
    void doFullBackup(in ParcelFileDescriptor data, long quotaBytes, int token,
            IBackupManager callbackBinder, int transportFlags);

    /**
     * Estimate how much data a full backup will deliver
     */
    void doMeasureFullBackup(long quotaBytes, int token, IBackupManager callbackBinder,
            int transportFlags);

    /**
     * Tells the application agent that the backup data size exceeded current transport quota.
     * Later calls to {@link #onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)}
     * and {@link #onFullBackup(FullBackupDataOutput)} could use this information
     * to reduce backup size under the limit.
     * However, the quota can change, so do not assume that the value passed in here is absolute,
     * similarly all subsequent backups should not be restricted to this size.
     * This callback will be invoked before data has been put onto the wire in a preflight check,
     * so it is relatively inexpensive to hit your quota.
     * Apps that hit quota repeatedly without dealing with it can be subject to having their backup
     * schedule reduced.
     * The {@code quotaBytes} is a loose guideline b/c of metadata added by the backupmanager
     * so apps should be more aggressive in trimming their backup set.
     *
     * @param backupDataBytes Expected or already processed amount of data.
     *                        Could be less than total backup size if backup process was interrupted
     *                        before finish of processing all backup data.
     * @param quotaBytes Current amount of backup data that is allowed for the app.
     * @param callbackBinder Binder on which to indicate operation completion.
     */
    void doQuotaExceeded(long backupDataBytes, long quotaBytes, IBackupCallback callbackBinder);

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
     * @param token Opaque token identifying this transaction.  This must
     *        be echoed back to the backup service binder once the agent is
     *        finished restoring the application based on the restore data
     *        contents.
     * @param callbackBinder Binder on which to indicate operation completion,
     *        passed here as a convenience to the agent.
     */
    void doRestoreFile(in ParcelFileDescriptor data, long size,
            int type, String domain, String path, long mode, long mtime,
            int token, IBackupManager callbackBinder);

    /**
     * Provide the app with a canonical "all data has been delivered" end-of-restore
     * callback so that it can do any postprocessing of the restored data that might
     * be appropriate.  This is issued after both key/value and full data restore
     * operations have completed.
     *
     * @param token Opaque token identifying this transaction.  This must
     *        be echoed back to the backup service binder once the agent is
     *        finished restoring the application based on the restore data
     *        contents.
     * @param callbackBinder Binder on which to indicate operation completion,
     *        passed here as a convenience to the agent.
     */
    void doRestoreFinished(int token, IBackupManager callbackBinder);

    /**
     * Out of band: instruct the agent to crash within the client process.  This is used
     * when the backup infrastructure detects a semantic error post-hoc and needs to
     * pass the problem back to the app.
     *
     * @param message The message to be passed to the agent's application in an exception.
     */
    void fail(String message);
}
