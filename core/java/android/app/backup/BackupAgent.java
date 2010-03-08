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

import android.app.IBackupAgent;
import android.app.backup.IBackupManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

/**
 * This is the central interface between an application and Android's settings
 * backup mechanism. Any implementation of a backup agent should perform backup
 * and restore actions in
 * {@link #onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)}
 * and {@link #onRestore(BackupDataInput, int, ParcelFileDescriptor)}
 * respectively.
 * <p>
 * A backup agent based on convenient helper classes is available in
 * {@link android.app.backup.BackupHelperAgent} for less complex implementation
 * requirements.
 * <p>
 * STOPSHIP write more documentation about the backup process here.
 */
public abstract class BackupAgent extends ContextWrapper {
    private static final String TAG = "BackupAgent";
    private static final boolean DEBUG = false;

    public BackupAgent() {
        super(null);
    }

    public void onCreate() {
    }

    public void onDestroy() {
    }

    /**
     * The application is being asked to write any data changed since the last
     * time it performed a backup operation. The state data recorded during the
     * last backup pass is provided in the <code>oldState</code> file
     * descriptor. If <code>oldState</code> is <code>null</code>, no old state
     * is available and the application should perform a full backup. In both
     * cases, a representation of the final backup state after this pass should
     * be written to the file pointed to by the file descriptor wrapped in
     * <code>newState</code>.
     * 
     * @param oldState An open, read-only ParcelFileDescriptor pointing to the
     *            last backup state provided by the application. May be
     *            <code>null</code>, in which case no prior state is being
     *            provided and the application should perform a full backup.
     * @param data A structured wrapper around an open, read/write
     *            ParcelFileDescriptor pointing to the backup data destination.
     *            Typically the application will use backup helper classes to
     *            write to this file.
     * @param newState An open, read/write ParcelFileDescriptor pointing to an
     *            empty file. The application should record the final backup
     *            state here after writing the requested data to dataFd.
     */
    public abstract void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
             ParcelFileDescriptor newState) throws IOException;

    /**
     * The application is being restored from backup and should replace any
     * existing data with the contents of the backup. The backup data is
     * provided in the file descriptor pointed to by the
     * {@link android.app.backup.BackupDataInput} instance <code>data</code>. Once
     * the restore is finished, the application should write a representation of
     * the final state to the <code>newState</code> file descriptor.
     * <p>
     * The application is responsible for properly erasing its old data and
     * replacing it with the data supplied to this method. No "clear user data"
     * operation will be performed automatically by the operating system. The
     * exception to this is in the case of a failed restore attempt: if
     * onRestore() throws an exception, the OS will assume that the
     * application's data may now be in an incoherent state, and will clear it
     * before proceeding.
     * 
     * @param data A structured wrapper around an open, read-only
     *            ParcelFileDescriptor pointing to a full snapshot of the
     *            application's data. Typically the application will use helper
     *            classes to read this data.
     * @param appVersionCode The android:versionCode value of the application
     *            that backed up this particular data set. This makes it easier
     *            for an application's agent to distinguish among several
     *            possible older data versions when asked to perform the restore
     *            operation.
     * @param newState An open, read/write ParcelFileDescriptor pointing to an
     *            empty file. The application should record the final backup
     *            state here after restoring its data from dataFd.
     */
    public abstract void onRestore(BackupDataInput data, int appVersionCode,
            ParcelFileDescriptor newState)
            throws IOException;


    // ----- Core implementation -----

    /** @hide */
    public final IBinder onBind() {
        return mBinder;
    }

    private final IBinder mBinder = new BackupServiceBinder().asBinder();

    /** @hide */
    public void attach(Context context) {
        attachBaseContext(context);
    }

    // ----- IBackupService binder interface -----
    private class BackupServiceBinder extends IBackupAgent.Stub {
        private static final String TAG = "BackupServiceBinder";

        public void doBackup(ParcelFileDescriptor oldState,
                ParcelFileDescriptor data,
                ParcelFileDescriptor newState,
                int token, IBackupManager callbackBinder) throws RemoteException {
            // Ensure that we're running with the app's normal permission level
            long ident = Binder.clearCallingIdentity();

            if (DEBUG) Log.v(TAG, "doBackup() invoked");
            BackupDataOutput output = new BackupDataOutput(data.getFileDescriptor());
            try {
                BackupAgent.this.onBackup(oldState, output, newState);
            } catch (IOException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opComplete(token);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }
            }
        }

        public void doRestore(ParcelFileDescriptor data, int appVersionCode,
                ParcelFileDescriptor newState,
                int token, IBackupManager callbackBinder) throws RemoteException {
            // Ensure that we're running with the app's normal permission level
            long ident = Binder.clearCallingIdentity();

            if (DEBUG) Log.v(TAG, "doRestore() invoked");
            BackupDataInput input = new BackupDataInput(data.getFileDescriptor());
            try {
                BackupAgent.this.onRestore(input, appVersionCode, newState);
            } catch (IOException ex) {
                Log.d(TAG, "onRestore (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onRestore (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opComplete(token);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }
            }
        }
    }
}
