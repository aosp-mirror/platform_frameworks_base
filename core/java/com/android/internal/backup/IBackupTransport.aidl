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

import android.app.backup.RestoreSet;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

/** {@hide} */
interface IBackupTransport {
    /**
     * Ask the transport for the name under which it should be registered.  This will
     * typically be its host service's component name, but need not be.
     */
    String name();

	/**
	 * Ask the transport for an Intent that can be used to launch any internal
	 * configuration Activity that it wishes to present.  For example, the transport
	 * may offer a UI for allowing the user to supply login credentials for the
	 * transport's off-device backend.
	 *
	 * If the transport does not supply any user-facing configuration UI, it should
	 * return null from this method.
	 *
	 * @return An Intent that can be passed to Context.startActivity() in order to
	 *         launch the transport's configuration UI.  This method will return null
	 *         if the transport does not offer any user-facing configuration UI.
	 */
	Intent configurationIntent();

	/**
	 * On demand, supply a one-line string that can be shown to the user that
	 * describes the current backend destination.  For example, a transport that
	 * can potentially associate backup data with arbitrary user accounts should
	 * include the name of the currently-active account here.
	 *
	 * @return A string describing the destination to which the transport is currently
	 *         sending data.  This method should not return null.
	 */
	String currentDestinationString();

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
     * Initialize the server side storage for this device, erasing all stored data.
     * The transport may send the request immediately, or may buffer it.  After
     * this is called, {@link #finishBackup} must be called to ensure the request
     * is sent and received successfully.
     *
     * @return One of {@link BackupConstants#TRANSPORT_OK} (OK so far) or
     *   {@link BackupConstants#TRANSPORT_ERROR} (on network error or other failure).
     */
    int initializeDevice();

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
     * @param wipeAllFirst When true, <i>all</i> backed-up data for the current device/account
     *   will be erased prior to the storage of the data provided here.  The purpose of this
     *   is to provide a guarantee that no stale data exists in the restore set when the
     *   device begins providing backups.
     * @return one of {@link BackupConstants#TRANSPORT_OK} (OK so far),
     *  {@link BackupConstants#TRANSPORT_ERROR} (on network error or other failure), or
     *  {@link BackupConstants#TRANSPORT_NOT_INITIALIZED} (if the backend dataset has
     *  become lost due to inactive expiry or some other reason and needs re-initializing)
     */
    int performBackup(in PackageInfo packageInfo, in ParcelFileDescriptor inFd);

    /**
     * Erase the give application's data from the backup destination.  This clears
     * out the given package's data from the current backup set, making it as though
     * the app had never yet been backed up.  After this is called, {@link finishBackup}
     * must be called to ensure that the operation is recorded successfully.
     *
     * @return the same error codes as {@link #performBackup}.
     */
    int clearBackupData(in PackageInfo packageInfo);

    /**
     * Finish sending application data to the backup destination.  This must be
     * called after {@link #performBackup} or {@link clearBackupData} to ensure that
     * all data is sent.  Only when this method returns true can a backup be assumed
     * to have succeeded.
     *
     * @return the same error codes as {@link #performBackup}.
     */
    int finishBackup();

    /**
     * Get the set of all backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    RestoreSet[] getAvailableRestoreSets();

    /**
     * Get the identifying token of the backup set currently being stored from
     * this device.  This is used in the case of applications wishing to restore
     * their last-known-good data.
     *
     * @return A token that can be passed to {@link #startRestore}, or 0 if there
     *   is no backup set available corresponding to the current device state.
     */
    long getCurrentRestoreSet();

    /**
     * Start restoring application data from backup.  After calling this function,
     * alternate calls to {@link #nextRestorePackage} and {@link #nextRestoreData}
     * to walk through the actual application data.
     *
     * @param token A backup token as returned by {@link #getAvailableRestoreSets}
     *   or {@link #getCurrentRestoreSet}.
     * @param packages List of applications to restore (if data is available).
     *   Application data will be restored in the order given.
     * @return One of {@link BackupConstants#TRANSPORT_OK} (OK so far, call
     *   {@link #nextRestorePackage}) or {@link BackupConstants#TRANSPORT_ERROR}
     *   (an error occurred, the restore should be aborted and rescheduled).
     */
    int startRestore(long token, in PackageInfo[] packages);

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
     * @return the same error codes as {@link #nextRestorePackage}.
     */
    int getRestoreData(in ParcelFileDescriptor outFd);

    /**
     * End a restore session (aborting any in-process data transfer as necessary),
     * freeing any resources and connections used during the restore process.
     */
    void finishRestore();
}
