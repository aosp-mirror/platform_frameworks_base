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

import android.app.backup.RestoreSession;
import android.app.backup.IBackupManager;
import android.app.backup.IRestoreSession;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * BackupManager is the interface to the system's backup service. Applications
 * simply instantiate one, and then use that instance to communicate with the
 * backup infrastructure.
 * <p>
 * When an application has made changes to data which should be backed up, a
 * call to {@link #dataChanged()} will notify the backup service. The system
 * will then schedule a backup operation to occur in the near future. Repeated
 * calls to {@link #dataChanged()} have no further effect until the backup
 * operation actually occurs.
 * <p>
 * The backup operation itself begins with the system launching the
 * {@link android.app.backup.BackupAgent} subclass declared in your manifest. See the
 * documentation for {@link android.app.backup.BackupAgent} for a detailed description
 * of how the backup then proceeds.
 * <p>
 * A simple implementation of a BackupAgent useful for backing up Preferences
 * and files is available by using {@link android.app.backup.BackupHelperAgent}.
 * <p>
 * STOPSHIP: more documentation!
 * <p>
 * <b>XML attributes</b>
 * <p>
 * See {@link android.R.styleable#AndroidManifestApplication 
 * AndroidManifest.xml's application attributes}
 * 
 * @attr ref android.R.styleable#AndroidManifestApplication_allowBackup
 * @attr ref android.R.styleable#AndroidManifestApplication_backupAgent
 * @attr ref
 *       android.R.styleable#AndroidManifestApplication_restoreNeedsApplication
 * @attr ref android.R.styleable#AndroidManifestApplication_killAfterRestore
 */
public class BackupManager {
    private static final String TAG = "BackupManager";

    private Context mContext;
    private static IBackupManager sService;

    private static void checkServiceBinder() {
        if (sService == null) {
            sService = IBackupManager.Stub.asInterface(
                    ServiceManager.getService(Context.BACKUP_SERVICE));
        }
    }

    /**
     * Constructs a BackupManager object through which the application can
     * communicate with the Android backup system.
     *
     * @param context The {@link android.content.Context} that was provided when
     *                one of your application's {@link android.app.Activity Activities}
     *                was created.
     */
    public BackupManager(Context context) {
        mContext = context;
    }

    /**
     * Notifies the Android backup system that your application wishes to back up
     * new changes to its data.  A backup operation using your application's
     * {@link android.app.backup.BackupAgent} subclass will be scheduled when you call this method.
     */
    public void dataChanged() {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.dataChanged(mContext.getPackageName());
            } catch (RemoteException e) {
                Log.d(TAG, "dataChanged() couldn't connect");
            }
        }
    }

    /**
     * Convenience method for callers who need to indicate that some other package
     * needs a backup pass.  This can be relevant in the case of groups of packages
     * that share a uid, for example.
     *
     * This method requires that the application hold the "android.permission.BACKUP"
     * permission if the package named in the argument is not the caller's own.
     */
    public static void dataChanged(String packageName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.dataChanged(packageName);
            } catch (RemoteException e) {
                Log.d(TAG, "dataChanged(pkg) couldn't connect");
            }
        }
    }

    /**
     * Begin the process of restoring data from backup.  See the
     * {@link android.app.backup.RestoreSession} class for documentation on that process.
     */
    public RestoreSession beginRestoreSession() {
        RestoreSession session = null;
        checkServiceBinder();
        if (sService != null) {
            try {
                String transport = sService.getCurrentTransport();
                IRestoreSession binder = sService.beginRestoreSession(transport);
                session = new RestoreSession(mContext, binder);
            } catch (RemoteException e) {
                Log.w(TAG, "beginRestoreSession() couldn't connect");
            }
        }
        return session;
    }
}
