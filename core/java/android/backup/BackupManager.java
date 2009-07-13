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

package android.backup;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * BackupManager is the interface to the system's backup service.
 * Applications simply instantiate one, and then use that instance
 * to communicate with the backup infrastructure.
 *
 * <p>When your application has made changes to data it wishes to have
 * backed up, call {@link #dataChanged()} to notify the backup service.
 * The system will then schedule a backup operation to occur in the near
 * future.  Repeated calls to {@link #dataChanged()} have no further effect
 * until the backup operation actually occurs.
 *
 * <p>The backup operation itself begins with the system launching the
 * {@link android.app.BackupAgent} subclass declared in your manifest.  See the
 * documentation for {@link android.app.BackupAgent} for a detailed description
 * of how the backup then proceeds.
 *
 * @hide pending API solidification
 */
public class BackupManager {
    private static final String TAG = "BackupManager";

    /** @hide TODO: REMOVE THIS */
    public static final boolean EVEN_THINK_ABOUT_DOING_RESTORE = false;

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
     * {@link android.app.BackupAgent} subclass will be scheduled when you call this method.
     */
    public void dataChanged() {
        if (!EVEN_THINK_ABOUT_DOING_RESTORE) {
            return;
        }
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
        if (!EVEN_THINK_ABOUT_DOING_RESTORE) {
            return;
        }
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
     * Begin the process of restoring system data from backup.  This method requires
     * that the application hold the "android.permission.BACKUP" permission, and is
     * not public.
     *
     * {@hide}
     */
    public IRestoreSession beginRestoreSession(String transport) {
        if (!EVEN_THINK_ABOUT_DOING_RESTORE) {
            return null;
        }
        IRestoreSession binder = null;
        checkServiceBinder();
        if (sService != null) {
            try {
                binder = sService.beginRestoreSession(transport);
            } catch (RemoteException e) {
                Log.d(TAG, "beginRestoreSession() couldn't connect");
            }
        }
        return binder;
    }
}
