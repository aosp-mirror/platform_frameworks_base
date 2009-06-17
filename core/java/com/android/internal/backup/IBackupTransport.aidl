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
     * Establish a connection to the back-end data repository, if necessary.  If the transport
     * needs to initialize state that is not tied to individual applications' backup operations,
     * this is where it should be done.
     *
     * @return Zero on success; a nonzero error code on failure.
     */
    int startSession();

    /**
     * Send one application's data to the backup destination.
     *
     * @param packageInfo The identity of the application whose data is being backed up.
     *   This specifically includes the signature list for the package.
     * @param data The data stream that resulted from invoking the application's
     *   BackupService.doBackup() method.  This may be a pipe rather than a file on
     *   persistent media, so it may not be seekable.
     * @return Zero on success; a nonzero error code on failure.
     */
    int performBackup(in PackageInfo packageInfo, in ParcelFileDescriptor data);

    /**
     * Get the set of backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device.
     **/
    RestoreSet[] getAvailableRestoreSets();

    /**
     * Get the set of applications from a given restore image.
     *
     * @param token A backup token as returned by {@link #getAvailableRestoreSets}.
     * @return An array of PackageInfo objects describing all of the applications
     *   available for restore from this restore image.  This should include the list
     *   of signatures for each package so that the Backup Manager can filter using that
     *   information.
     */
    PackageInfo[] getAppSet(int token);

    /**
     * Retrieve one application's data from the backing store.
     *
     * @param token The backup record from which a restore is being requested.
     * @param packageInfo The identity of the application whose data is being restored.
     *   This must include the signature list for the package; it is up to the transport
     *   to verify that the requested app's signatures match the saved backup record
     *   because the transport cannot necessarily trust the client device.
     * @param data An open, writable file into which the backup image should be stored.
     * @return Zero on success; a nonzero error code on failure.
     */
    int getRestoreData(int token, in PackageInfo packageInfo, in ParcelFileDescriptor data);

    /**
     * Terminate the backup session, closing files, freeing memory, and cleaning up whatever
     * other state the transport required.
     *
     * @return Zero on success; a nonzero error code on failure.  Even on failure, the session
     *         is torn down and must be restarted if another backup is attempted.
     */
    int endSession();
}
