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

package com.android.internal.backup;

import android.backup.RestoreSet;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

/** {@hide} */
interface IBackupTransport {
/* STOPSHIP - don't ship with this comment in place
    Things the transport interface has to do:
    1. set up the connection to the destination
        - set up encryption
        - for Google cloud, log in using the user's gaia credential or whatever
        - for adb, just set up the all-in-one destination file
    2. send each app's backup transaction
        - parse the data file for key/value pointers etc
        - send key/blobsize set to the Google cloud, get back quota ok/rejected response
        - sd/adb doesn't preflight; no per-app quota
        - app's entire change is essentially atomic
        - cloud transaction encrypts then sends each key/value pair separately; we already
          parsed the data when preflighting so we don't have to again here
        - sd target streams raw data into encryption envelope then to sd?
    3. shut down connection to destination
        - cloud: tear down connection etc
        - adb: close the file
*/
    /**
     * Ask the transport where, on local device storage, to keep backup state blobs.
     * This is per-transport so that mock transports used for testing can coexist with
     * "live" backup services without interfering with the live bookkeeping.  The
     * returned string should be a name that is expected to be unambiguous among all
     * available backup transports; the name of the class implementing the transport
     * is a good choice.
     *
     * @return A unique name, suitable for use as a file or directory name, that the
     *         Backup Manager could use to disambiguate state files associated with
     *         different backup transports.
     */
    String transportDirName();

    /**
     * Verify that this is a suitable time for a backup pass.  This should return zero
     * if a backup is reasonable right now, some positive value otherwise.  This method
     * will be called outside of the {@link #startSession}/{@link #endSession} pair.
     *
     * <p>If this is not a suitable time for a backup, the transport should return a
     * backoff delay, in milliseconds, after which the Backup Manager should try again.
     *
     * @return Zero if this is a suitable time for a backup pass, or a positive time delay
     *   in milliseconds to suggest deferring the backup pass for a while.
     */
    long requestBackupTime();

    /**
     * Send one application's data to the backup destination.  The transport may send
     * the data immediately, or may buffer it.  After this is called, {@link #finishBackup}
     * must be called to ensure the data is sent and recorded successfully.
     *
     * @param packageInfo The identity of the application whose data is being backed up.
     *   This specifically includes the signature list for the package.
     * @param data The data stream that resulted from invoking the application's
     *   BackupService.doBackup() method.  This may be a pipe rather than a file on
     *   persistent media, so it may not be seekable.
     * @return false if errors occurred (the backup should be aborted and rescheduled),
     *   true if everything is OK so far (but {@link #finishBackup} must be called).
     */
    boolean performBackup(in PackageInfo packageInfo, in ParcelFileDescriptor inFd);

    /**
     * Erase the give application's data from the backup destination.  This clears
     * out the given package's data from the current backup set, making it as though
     * the app had never yet been backed up.  After this is called, {@link finishBackup}
     * must be called to ensure that the operation is recorded successfully.
     *
     * @return false if errors occurred (the backup should be aborted and rescheduled),
     *   true if everything is OK so far (but {@link #finishBackup} must be called).
     */
    boolean clearBackupData(in PackageInfo packageInfo);

    /**
     * Finish sending application data to the backup destination.  This must be
     * called after {@link #performBackup} or {@link clearBackupData} to ensure that
     * all data is sent.  Only when this method returns true can a backup be assumed
     * to have succeeded.
     *
     * @return false if errors occurred (the backup should be aborted and rescheduled),
     *   true if everything is OK.
     */
    boolean finishBackup();

    /**
     * Get the set of backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    RestoreSet[] getAvailableRestoreSets();

    /**
     * Start restoring application data from backup.  After calling this function,
     * alternate calls to {@link #nextRestorePackage} and {@link #nextRestoreData}
     * to walk through the actual application data.
     *
     * @param token A backup token as returned by {@link #getAvailableRestoreSets}.
     * @param packages List of applications to restore (if data is available).
     *   Application data will be restored in the order given.
     * @return false if errors occurred (the restore should be aborted and rescheduled),
     *   true if everything is OK so far (go ahead and call {@link #nextRestorePackage}).
     */
    boolean startRestore(long token, in PackageInfo[] packages);

    /**
     * Get the package name of the next application with data in the backup store.
     * @return The name of one of the packages supplied to {@link #startRestore},
     *   or "" (the empty string) if no more backup data is available,
     *   or null if an error occurred (the restore should be aborted and rescheduled).
     */
    String nextRestorePackage();

    /**
     * Get the data for the application returned by {@link #nextRestorePackage}.
     * @param data An open, writable file into which the backup data should be stored.
     * @return false if errors occurred (the restore should be aborted and rescheduled),
     *   true if everything is OK so far (go ahead and call {@link #nextRestorePackage}).
     */
    boolean getRestoreData(in ParcelFileDescriptor outFd);

    /**
     * End a restore session (aborting any in-process data transfer as necessary),
     * freeing any resources and connections used during the restore process.
     */
    void finishRestore();
}
