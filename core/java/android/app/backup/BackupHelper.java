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

import android.os.ParcelFileDescriptor;

/**
 * Defines the calling interface that {@link BackupAgentHelper} uses
 * when dispatching backup and restore operations to the installed helpers.
 * Applications can define and install their own helpers as well as using those
 * provided as part of the Android framework.
 * <p>
 * Although multiple helper objects may be installed simultaneously, each helper
 * is responsible only for handling its own data, and will not see entities
 * created by other components within the backup system.  Invocations of multiple
 * helpers are performed sequentially by the {@link BackupAgentHelper}, with each
 * helper given a chance to access its own saved state from within the state record
 * produced during the previous backup operation.
 *
 * @see BackupAgentHelper
 * @see FileBackupHelper
 * @see SharedPreferencesBackupHelper
 */
public interface BackupHelper {
    /**
     * Based on <code>oldState</code>, determine what application content
     * needs to be backed up, write it to <code>data</code>, and fill in
     * <code>newState</code> with the complete state as it exists now.
     * <p>
     * Implementing this method is much like implementing
     * {@link BackupAgent#onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)
     * onBackup()} &mdash; the method parameters are the same.  When this method is invoked the
     * {@code oldState} descriptor points to the beginning of the state data
     * written during this helper's previous backup operation, and the {@code newState}
     * descriptor points to the file location at which the helper should write its
     * new state after performing the backup operation.
     * <p class="note">
     * <strong>Note:</strong> The helper should not close or seek either the {@code oldState} or
     * the {@code newState} file descriptors.</p>
     *
     * @param oldState An open, read-only {@link android.os.ParcelFileDescriptor} pointing to the
     *            last backup state provided by the application. May be
     *            <code>null</code>, in which case no prior state is being
     *            provided and the application should perform a full backup.
     * @param data An open, read/write {@link BackupDataOutput}
     *            pointing to the backup data destination.
     *            Typically the application will use backup helper classes to
     *            write to this file.
     * @param newState An open, read/write {@link android.os.ParcelFileDescriptor} pointing to an
     *            empty file. The application should record the final backup
     *            state here after writing the requested data to the <code>data</code>
     *            output stream.
     */
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState);

    /**
     * Called by {@link android.app.backup.BackupAgentHelper BackupAgentHelper}
     * to restore a single entity from the restore data set.  This method will be
     * called for each entity in the data set that belongs to this handler.
     * <p class="note">
     * <strong>Note:</strong> Do not close the <code>data</code> stream.  Do not read more than
     * {@link android.app.backup.BackupDataInputStream#size() size()} bytes from
     * <code>data</code>.</p>
     *
     * @param data An open {@link BackupDataInputStream} from which the backup data can be read.
     */
    public void restoreEntity(BackupDataInputStream data);

    /**
     * Called by {@link android.app.backup.BackupAgentHelper BackupAgentHelper}
     * after a restore operation to write the backup state file corresponding to
     * the data as processed by the helper.  The data written here will be
     * available to the helper during the next call to its
     * {@link #performBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)
     * performBackup()} method.
     * <p>
     * This method will be called even if the handler's
     * {@link #restoreEntity(BackupDataInputStream) restoreEntity()} method was never invoked during
     * the restore operation.
     * <p class="note">
     * <strong>Note:</strong> The helper should not close or seek the {@code newState}
     * file descriptor.</p>
     *
     * @param newState A {@link android.os.ParcelFileDescriptor} to which the new state will be
     * written.
     */
    public void writeNewStateDescription(ParcelFileDescriptor newState);
}

