/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.android.internal.backup.IBackupTransport;

/**
 * Concrete class that provides a stable-API bridge between IBackupTransport
 * and its implementations.
 *
 * @hide
 */
@SystemApi
public class BackupTransport {
    // Zero return always means things are okay.  If returned from
    // getNextFullRestoreDataChunk(), it means that no data could be delivered at
    // this time, but the restore is still running and the caller should simply
    // retry.
    public static final int TRANSPORT_OK = 0;

    // -1 is special; it is used in getNextFullRestoreDataChunk() to indicate that
    // we've delivered the entire data stream for the current restore target.
    public static final int NO_MORE_DATA = -1;

    // Result codes that indicate real errors are negative and not -1
    public static final int TRANSPORT_ERROR = -1000;
    public static final int TRANSPORT_NOT_INITIALIZED = -1001;
    public static final int TRANSPORT_PACKAGE_REJECTED = -1002;
    public static final int AGENT_ERROR = -1003;
    public static final int AGENT_UNKNOWN = -1004;

    IBackupTransport mBinderImpl = new TransportImpl();
    /** @hide */
    public IBinder getBinder() {
        return mBinderImpl.asBinder();
    }

    // ------------------------------------------------------------------------------------
    // Transport self-description and general configuration interfaces
    //

    /**
     * Ask the transport for the name under which it should be registered.  This will
     * typically be its host service's component name, but need not be.
     */
    public String name() {
        throw new UnsupportedOperationException("Transport name() not implemented");
    }

    /**
     * Ask the transport for an Intent that can be used to launch any internal
     * configuration Activity that it wishes to present.  For example, the transport
     * may offer a UI for allowing the user to supply login credentials for the
     * transport's off-device backend.
     *
     * <p>If the transport does not supply any user-facing configuration UI, it should
     * return {@code null} from this method.
     *
     * @return An Intent that can be passed to Context.startActivity() in order to
     *         launch the transport's configuration UI.  This method will return {@code null}
     *         if the transport does not offer any user-facing configuration UI.
     */
    public Intent configurationIntent() {
        return null;
    }

    /**
     * On demand, supply a one-line string that can be shown to the user that
     * describes the current backend destination.  For example, a transport that
     * can potentially associate backup data with arbitrary user accounts should
     * include the name of the currently-active account here.
     *
     * @return A string describing the destination to which the transport is currently
     *         sending data.  This method should not return null.
     */
    public String currentDestinationString() {
        throw new UnsupportedOperationException(
                "Transport currentDestinationString() not implemented");
    }

    /**
     * Ask the transport for an Intent that can be used to launch a more detailed
     * secondary data management activity.  For example, the configuration intent might
     * be one for allowing the user to select which account they wish to associate
     * their backups with, and the management intent might be one which presents a
     * UI for managing the data on the backend.
     *
     * <p>In the Settings UI, the configuration intent will typically be invoked
     * when the user taps on the preferences item labeled with the current
     * destination string, and the management intent will be placed in an overflow
     * menu labelled with the management label string.
     *
     * <p>If the transport does not supply any user-facing data management
     * UI, then it should return {@code null} from this method.
     *
     * @return An intent that can be passed to Context.startActivity() in order to
     *         launch the transport's data-management UI.  This method will return
     *         {@code null} if the transport does not offer any user-facing data
     *         management UI.
     */
    public Intent dataManagementIntent() {
        return null;
    }

    /**
     * On demand, supply a short string that can be shown to the user as the label
     * on an overflow menu item used to invoked the data management UI.
     *
     * @return A string to be used as the label for the transport's data management
     *         affordance.  If the transport supplies a data management intent, this
     *         method must not return {@code null}.
     */
    public String dataManagementLabel() {
        throw new UnsupportedOperationException(
                "Transport dataManagementLabel() not implemented");
    }

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
    public String transportDirName() {
        throw new UnsupportedOperationException(
                "Transport transportDirName() not implemented");
    }

    // ------------------------------------------------------------------------------------
    // Device-level operations common to both key/value and full-data storage

    /**
     * Initialize the server side storage for this device, erasing all stored data.
     * The transport may send the request immediately, or may buffer it.  After
     * this is called, {@link #finishBackup} will be called to ensure the request
     * is sent and received successfully.
     *
     * @return One of {@link BackupTransport#TRANSPORT_OK} (OK so far) or
     *   {@link BackupTransport#TRANSPORT_ERROR} (on network error or other failure).
     */
    public int initializeDevice() {
        return BackupTransport.TRANSPORT_ERROR;
    }

    /**
     * Erase the given application's data from the backup destination.  This clears
     * out the given package's data from the current backup set, making it as though
     * the app had never yet been backed up.  After this is called, {@link finishBackup}
     * must be called to ensure that the operation is recorded successfully.
     *
     * @return the same error codes as {@link #performBackup}.
     */
    public int clearBackupData(PackageInfo packageInfo) {
        return BackupTransport.TRANSPORT_ERROR;
    }

    /**
     * Finish sending application data to the backup destination.  This must be
     * called after {@link #performBackup}, {@link #performFullBackup}, or {@link clearBackupData}
     * to ensure that all data is sent and the operation properly finalized.  Only when this
     * method returns true can a backup be assumed to have succeeded.
     *
     * @return the same error codes as {@link #performBackup} or {@link #performFullBackup}.
     */
    public int finishBackup() {
        return BackupTransport.TRANSPORT_ERROR;
    }

    // ------------------------------------------------------------------------------------
    // Key/value incremental backup support interfaces

    /**
     * Verify that this is a suitable time for a key/value backup pass.  This should return zero
     * if a backup is reasonable right now, some positive value otherwise.  This method
     * will be called outside of the {@link #performBackup}/{@link #finishBackup} pair.
     *
     * <p>If this is not a suitable time for a backup, the transport should return a
     * backoff delay, in milliseconds, after which the Backup Manager should try again.
     *
     * @return Zero if this is a suitable time for a backup pass, or a positive time delay
     *   in milliseconds to suggest deferring the backup pass for a while.
     */
    public long requestBackupTime() {
        return 0;
    }

    /**
     * Send one application's key/value data update to the backup destination.  The
     * transport may send the data immediately, or may buffer it.  After this is called,
     * {@link #finishBackup} will be called to ensure the data is sent and recorded successfully.
     *
     * @param packageInfo The identity of the application whose data is being backed up.
     *   This specifically includes the signature list for the package.
     * @param data The data stream that resulted from invoking the application's
     *   BackupService.doBackup() method.  This may be a pipe rather than a file on
     *   persistent media, so it may not be seekable.
     * @param wipeAllFirst When true, <i>all</i> backed-up data for the current device/account
     *   must be erased prior to the storage of the data provided here.  The purpose of this
     *   is to provide a guarantee that no stale data exists in the restore set when the
     *   device begins providing incremental backups.
     * @return one of {@link BackupTransport#TRANSPORT_OK} (OK so far),
     *  {@link BackupTransport#TRANSPORT_ERROR} (on network error or other failure), or
     *  {@link BackupTransport#TRANSPORT_NOT_INITIALIZED} (if the backend dataset has
     *  become lost due to inactivity purge or some other reason and needs re-initializing)
     */
    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd) {
        return BackupTransport.TRANSPORT_ERROR;
    }

    // ------------------------------------------------------------------------------------
    // Key/value dataset restore interfaces

    /**
     * Get the set of all backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    public RestoreSet[] getAvailableRestoreSets() {
        return null;
    }

    /**
     * Get the identifying token of the backup set currently being stored from
     * this device.  This is used in the case of applications wishing to restore
     * their last-known-good data.
     *
     * @return A token that can be passed to {@link #startRestore}, or 0 if there
     *   is no backup set available corresponding to the current device state.
     */
    public long getCurrentRestoreSet() {
        return 0;
    }

    /**
     * Start restoring application data from backup.  After calling this function,
     * alternate calls to {@link #nextRestorePackage} and {@link #nextRestoreData}
     * to walk through the actual application data.
     *
     * @param token A backup token as returned by {@link #getAvailableRestoreSets}
     *   or {@link #getCurrentRestoreSet}.
     * @param packages List of applications to restore (if data is available).
     *   Application data will be restored in the order given.
     * @return One of {@link BackupTransport#TRANSPORT_OK} (OK so far, call
     *   {@link #nextRestorePackage}) or {@link BackupTransport#TRANSPORT_ERROR}
     *   (an error occurred, the restore should be aborted and rescheduled).
     */
    public int startRestore(long token, PackageInfo[] packages) {
        return BackupTransport.TRANSPORT_ERROR;
    }

    /**
     * Get the package name of the next application with data in the backup store, plus
     * a description of the structure of the restored archive: either TYPE_KEY_VALUE for
     * an original-API key/value dataset, or TYPE_FULL_STREAM for a tarball-type archive stream.
     *
     * <p>If the package name in the returned RestoreDescription object is the singleton
     * {@link RestoreDescription#NO_MORE_PACKAGES}, it indicates that no further data is available
     * in the current restore session: all packages described in startRestore() have been
     * processed.
     *
     * <p>If this method returns {@code null}, it means that a transport-level error has
     * occurred and the entire restore operation should be abandoned.
     *
     * <p class="note">The OS may call {@link #nextRestorePackage()} multiple times
     * before calling either {@link #getRestoreData(ParcelFileDescriptor) getRestoreData()}
     * or {@link #getNextFullRestoreDataChunk(ParcelFileDescriptor) getNextFullRestoreDataChunk()}.
     * It does this when it has determined that it needs to skip restore of one or more
     * packages.  The transport should not actually transfer any restore data for
     * the given package in response to {@link #nextRestorePackage()}, but rather wait
     * for an explicit request before doing so.
     *
     * @return A RestoreDescription object containing the name of one of the packages
     *   supplied to {@link #startRestore} plus an indicator of the data type of that
     *   restore data; or {@link RestoreDescription#NO_MORE_PACKAGES} to indicate that
     *   no more packages can be restored in this session; or {@code null} to indicate
     *   a transport-level error.
     */
    public RestoreDescription nextRestorePackage() {
        return null;
    }

    /**
     * Get the data for the application returned by {@link #nextRestorePackage}, if that
     * method reported {@link RestoreDescription#TYPE_KEY_VALUE} as its delivery type.
     * If the package has only TYPE_FULL_STREAM data, then this method will return an
     * error.
     *
     * @param data An open, writable file into which the key/value backup data should be stored.
     * @return the same error codes as {@link #startRestore}.
     */
    public int getRestoreData(ParcelFileDescriptor outFd) {
        return BackupTransport.TRANSPORT_ERROR;
    }

    /**
     * End a restore session (aborting any in-process data transfer as necessary),
     * freeing any resources and connections used during the restore process.
     */
    public void finishRestore() {
        throw new UnsupportedOperationException(
                "Transport finishRestore() not implemented");
    }

    // ------------------------------------------------------------------------------------
    // Full backup interfaces

    /**
     * Verify that this is a suitable time for a full-data backup pass.  This should return zero
     * if a backup is reasonable right now, some positive value otherwise.  This method
     * will be called outside of the {@link #performFullBackup}/{@link #finishBackup} pair.
     *
     * <p>If this is not a suitable time for a backup, the transport should return a
     * backoff delay, in milliseconds, after which the Backup Manager should try again.
     *
     * @return Zero if this is a suitable time for a backup pass, or a positive time delay
     *   in milliseconds to suggest deferring the backup pass for a while.
     *
     * @see #requestBackupTime()
     */
    public long requestFullBackupTime() {
        return 0;
    }

    /**
     * Begin the process of sending an application's full-data archive to the backend.
     * The description of the package whose data will be delivered is provided, as well as
     * the socket file descriptor on which the transport will receive the data itself.
     *
     * <p>If the package is not eligible for backup, the transport should return
     * {@link BackupTransport#TRANSPORT_PACKAGE_REJECTED}.  In this case the system will
     * simply proceed with the next candidate if any, or finish the full backup operation
     * if all apps have been processed.
     *
     * <p>After the transport returns {@link BackupTransport#TRANSPORT_OK} from this
     * method, the OS will proceed to call {@link #sendBackupData()} one or more times
     * to deliver the application's data as a streamed tarball.  The transport should not
     * read() from the socket except as instructed to via the {@link #sendBackupData(int)}
     * method.
     *
     * <p>After all data has been delivered to the transport, the system will call
     * {@link #finishBackup()}.  At this point the transport should commit the data to
     * its datastore, if appropriate, and close the socket that had been provided in
     * {@link #performFullBackup(PackageInfo, ParcelFileDescriptor)}.
     *
     * <p class="note">If the transport returns TRANSPORT_OK from this method, then the
     * OS will always provide a matching call to {@link #finishBackup()} even if sending
     * data via {@link #sendBackupData(int)} failed at some point.
     *
     * @param targetPackage The package whose data is to follow.
     * @param socket The socket file descriptor through which the data will be provided.
     *    If the transport returns {@link #TRANSPORT_PACKAGE_REJECTED} here, it must still
     *    close this file descriptor now; otherwise it should be cached for use during
     *    succeeding calls to {@link #sendBackupData(int)}, and closed in response to
     *    {@link #finishBackup()}.
     * @return TRANSPORT_PACKAGE_REJECTED to indicate that the stated application is not
     *    to be backed up; TRANSPORT_OK to indicate that the OS may proceed with delivering
     *    backup data; TRANSPORT_ERROR to indicate a fatal error condition that precludes
     *    performing a backup at this time.
     */
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket) {
        return BackupTransport.TRANSPORT_PACKAGE_REJECTED;
    }

    /**
     * Tells the transport to read {@code numBytes} bytes of data from the socket file
     * descriptor provided in the {@link #performFullBackup(PackageInfo, ParcelFileDescriptor)}
     * call, and deliver those bytes to the datastore.
     *
     * @param numBytes The number of bytes of tarball data available to be read from the
     *    socket.
     * @return TRANSPORT_OK on successful processing of the data; TRANSPORT_ERROR to
     *    indicate a fatal error situation.  If an error is returned, the system will
     *    call finishBackup() and stop attempting backups until after a backoff and retry
     *    interval.
     */
    public int sendBackupData(int numBytes) {
        return BackupTransport.TRANSPORT_ERROR;
    }

    // ------------------------------------------------------------------------------------
    // Full restore interfaces

    /**
     * Ask the transport to provide data for the "current" package being restored.  This
     * is the package that was just reported by {@link #nextRestorePackage()} as having
     * {@link RestoreDescription#TYPE_FULL_STREAM} data.
     *
     * The transport writes some data to the socket supplied to this call, and returns
     * the number of bytes written.  The system will then read that many bytes and
     * stream them to the application's agent for restore, then will call this method again
     * to receive the next chunk of the archive.  This sequence will be repeated until the
     * transport returns zero indicating that all of the package's data has been delivered
     * (or returns a negative value indicating some sort of hard error condition at the
     * transport level).
     *
     * <p>After this method returns zero, the system will then call
     * {@link #getNextFullRestorePackage()} to begin the restore process for the next
     * application, and the sequence begins again.
     *
     * <p>The transport should always close this socket when returning from this method.
     * Do not cache this socket across multiple calls or you may leak file descriptors.
     *
     * @param socket The file descriptor that the transport will use for delivering the
     *    streamed archive.  The transport must close this socket in all cases when returning
     *    from this method.
     * @return {@link #NO_MORE_DATA} when no more data for the current package is available.
     *    A positive value indicates the presence of that many bytes to be delivered to the app.
     *    A value of zero indicates that no data was deliverable at this time, but the restore
     *    is still running and the caller should retry.  {@link #TRANSPORT_PACKAGE_REJECTED}
     *    means that the current package's restore operation should be aborted, but that
     *    the transport itself is still in a good state and so a multiple-package restore
     *    sequence can still be continued.  Any other negative return value is treated as a
     *    fatal error condition that aborts all further restore operations on the current dataset.
     */
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) {
        return 0;
    }

    /**
     * If the OS encounters an error while processing {@link RestoreDescription#TYPE_FULL_STREAM}
     * data for restore, it will invoke this method to tell the transport that it should
     * abandon the data download for the current package.  The OS will then either call
     * {@link #nextRestorePackage()} again to move on to restoring the next package in the
     * set being iterated over, or will call {@link #finishRestore()} to shut down the restore
     * operation.
     *
     * @return {@link #TRANSPORT_OK} if the transport was successful in shutting down the
     *    current stream cleanly, or {@link #TRANSPORT_ERROR} to indicate a serious
     *    transport-level failure.  If the transport reports an error here, the entire restore
     *    operation will immediately be finished with no further attempts to restore app data.
     */
    public int abortFullRestore() {
        return BackupTransport.TRANSPORT_OK;
    }

    /**
     * Bridge between the actual IBackupTransport implementation and the stable API.  If the
     * binder interface needs to change, we use this layer to translate so that we can
     * (if appropriate) decouple those framework-side changes from the BackupTransport
     * implementations.
     */
    class TransportImpl extends IBackupTransport.Stub {

        @Override
        public String name() throws RemoteException {
            return BackupTransport.this.name();
        }

        @Override
        public Intent configurationIntent() throws RemoteException {
            return BackupTransport.this.configurationIntent();
        }

        @Override
        public String currentDestinationString() throws RemoteException {
            return BackupTransport.this.currentDestinationString();
        }

        @Override
        public Intent dataManagementIntent() {
            return BackupTransport.this.dataManagementIntent();
        }

        @Override
        public String dataManagementLabel() {
            return BackupTransport.this.dataManagementLabel();
        }

        @Override
        public String transportDirName() throws RemoteException {
            return BackupTransport.this.transportDirName();
        }

        @Override
        public long requestBackupTime() throws RemoteException {
            return BackupTransport.this.requestBackupTime();
        }

        @Override
        public int initializeDevice() throws RemoteException {
            return BackupTransport.this.initializeDevice();
        }

        @Override
        public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd)
                throws RemoteException {
            return BackupTransport.this.performBackup(packageInfo, inFd);
        }

        @Override
        public int clearBackupData(PackageInfo packageInfo) throws RemoteException {
            return BackupTransport.this.clearBackupData(packageInfo);
        }

        @Override
        public int finishBackup() throws RemoteException {
            return BackupTransport.this.finishBackup();
        }

        @Override
        public RestoreSet[] getAvailableRestoreSets() throws RemoteException {
            return BackupTransport.this.getAvailableRestoreSets();
        }

        @Override
        public long getCurrentRestoreSet() throws RemoteException {
            return BackupTransport.this.getCurrentRestoreSet();
        }

        @Override
        public int startRestore(long token, PackageInfo[] packages) throws RemoteException {
            return BackupTransport.this.startRestore(token, packages);
        }

        @Override
        public RestoreDescription nextRestorePackage() throws RemoteException {
            return BackupTransport.this.nextRestorePackage();
        }

        @Override
        public int getRestoreData(ParcelFileDescriptor outFd) throws RemoteException {
            return BackupTransport.this.getRestoreData(outFd);
        }

        @Override
        public void finishRestore() throws RemoteException {
            BackupTransport.this.finishRestore();
        }

        @Override
        public long requestFullBackupTime() throws RemoteException {
            return BackupTransport.this.requestFullBackupTime();
        }

        @Override
        public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket) throws RemoteException {
            return BackupTransport.this.performFullBackup(targetPackage, socket);
        }

        @Override
        public int sendBackupData(int numBytes) throws RemoteException {
            return BackupTransport.this.sendBackupData(numBytes);
        }

        @Override
        public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) {
            return BackupTransport.this.getNextFullRestoreDataChunk(socket);
        }

        @Override
        public int abortFullRestore() {
            return BackupTransport.this.abortFullRestore();
        }
    }
}
