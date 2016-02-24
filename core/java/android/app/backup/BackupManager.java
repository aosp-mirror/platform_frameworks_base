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

import android.annotation.SystemApi;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Pair;

/**
 * The interface through which an application interacts with the Android backup service to
 * request backup and restore operations.
 * Applications instantiate it using the constructor and issue calls through that instance.
 * <p>
 * When an application has made changes to data which should be backed up, a
 * call to {@link #dataChanged()} will notify the backup service. The system
 * will then schedule a backup operation to occur in the near future. Repeated
 * calls to {@link #dataChanged()} have no further effect until the backup
 * operation actually occurs.
 * <p>
 * A backup or restore operation for your application begins when the system launches the
 * {@link android.app.backup.BackupAgent} subclass you've declared in your manifest. See the
 * documentation for {@link android.app.backup.BackupAgent} for a detailed description
 * of how the operation then proceeds.
 * <p>
 * Several attributes affecting the operation of the backup and restore mechanism
 * can be set on the <code>
 * <a href="{@docRoot}guide/topics/manifest/application-element.html">&lt;application&gt;</a></code>
 * tag in your application's AndroidManifest.xml file.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using BackupManager, read the
 * <a href="{@docRoot}guide/topics/data/backup.html">Data Backup</a> developer guide.</p></div>
 *
 * @attr ref android.R.styleable#AndroidManifestApplication_allowBackup
 * @attr ref android.R.styleable#AndroidManifestApplication_backupAgent
 * @attr ref android.R.styleable#AndroidManifestApplication_killAfterRestore
 * @attr ref android.R.styleable#AndroidManifestApplication_restoreAnyVersion
 */
public class BackupManager {
    private static final String TAG = "BackupManager";

    // BackupObserver status codes
    /**
     * Indicates that backup succeeded.
     *
     * @hide
     */
    @SystemApi
    public static final int SUCCESS = 0;

    /**
     * Indicates that backup is either not enabled at all or
     * backup for the package was rejected by backup service
     * or backup transport,
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_BACKUP_NOT_ALLOWED = -2001;

    /**
     * The requested app is not installed on the device.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_PACKAGE_NOT_FOUND = -2002;

    /**
     * The transport for some reason was not in a good state and
     * aborted the entire backup request. This is a transient
     * failure and should not be retried immediately.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_TRANSPORT_ABORTED = BackupTransport.TRANSPORT_ERROR;

    /**
     * Returned when the transport was unable to process the
     * backup request for a given package, for example if the
     * transport hit a transient network failure. The remaining
     * packages provided to {@link #requestBackup(String[], BackupObserver)}
     * will still be attempted.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_TRANSPORT_PACKAGE_REJECTED =
            BackupTransport.TRANSPORT_PACKAGE_REJECTED;

    /**
     * Returned when the transport reject the attempt to backup because
     * backup data size exceeded current quota limit for this package.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_TRANSPORT_QUOTA_EXCEEDED =
            BackupTransport.TRANSPORT_QUOTA_EXCEEDED;

    /**
     * The {@link BackupAgent} for the requested package failed for some reason
     * and didn't provide appropriate backup data.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_AGENT_FAILURE = BackupTransport.AGENT_ERROR;

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
     * {@link android.app.backup.BackupAgent} subclass will be scheduled when you
     * call this method.
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
     * needs a backup pass.  This can be useful in the case of groups of packages
     * that share a uid.
     * <p>
     * This method requires that the application hold the "android.permission.BACKUP"
     * permission if the package named in the argument does not run under the same uid
     * as the caller.
     *
     * @param packageName The package name identifying the application to back up.
     */
    public static void dataChanged(String packageName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.dataChanged(packageName);
            } catch (RemoteException e) {
                Log.e(TAG, "dataChanged(pkg) couldn't connect");
            }
        }
    }

    /**
     * Restore the calling application from backup.  The data will be restored from the
     * current backup dataset if the application has stored data there, or from
     * the dataset used during the last full device setup operation if the current
     * backup dataset has no matching data.  If no backup data exists for this application
     * in either source, a nonzero value will be returned.
     *
     * <p>If this method returns zero (meaning success), the OS will attempt to retrieve
     * a backed-up dataset from the remote transport, instantiate the application's
     * backup agent, and pass the dataset to the agent's
     * {@link android.app.backup.BackupAgent#onRestore(BackupDataInput, int, android.os.ParcelFileDescriptor) onRestore()}
     * method.
     *
     * @param observer The {@link RestoreObserver} to receive callbacks during the restore
     * operation. This must not be null.
     *
     * @return Zero on success; nonzero on error.
     */
    public int requestRestore(RestoreObserver observer) {
        int result = -1;
        checkServiceBinder();
        if (sService != null) {
            RestoreSession session = null;
            try {
                IRestoreSession binder = sService.beginRestoreSession(mContext.getPackageName(),
                        null);
                if (binder != null) {
                    session = new RestoreSession(mContext, binder);
                    result = session.restorePackage(mContext.getPackageName(), observer);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "restoreSelf() unable to contact service");
            } finally {
                if (session != null) {
                    session.endRestoreSession();
                }
            }
        }
        return result;
    }

    // system APIs start here

    /**
     * Begin the process of restoring data from backup.  See the
     * {@link android.app.backup.RestoreSession} class for documentation on that process.
     * @hide
     */
    @SystemApi
    public RestoreSession beginRestoreSession() {
        RestoreSession session = null;
        checkServiceBinder();
        if (sService != null) {
            try {
                // All packages, current transport
                IRestoreSession binder = sService.beginRestoreSession(null, null);
                if (binder != null) {
                    session = new RestoreSession(mContext, binder);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "beginRestoreSession() couldn't connect");
            }
        }
        return session;
    }

    /**
     * Enable/disable the backup service entirely.  When disabled, no backup
     * or restore operations will take place.  Data-changed notifications will
     * still be observed and collected, however, so that changes made while the
     * mechanism was disabled will still be backed up properly if it is enabled
     * at some point in the future.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     *
     * @hide
     */
    @SystemApi
    public void setBackupEnabled(boolean isEnabled) {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.setBackupEnabled(isEnabled);
            } catch (RemoteException e) {
                Log.e(TAG, "setBackupEnabled() couldn't connect");
            }
        }
    }

    /**
     * Report whether the backup mechanism is currently enabled.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     *
     * @hide
     */
    @SystemApi
    public boolean isBackupEnabled() {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.isBackupEnabled();
            } catch (RemoteException e) {
                Log.e(TAG, "isBackupEnabled() couldn't connect");
            }
        }
        return false;
    }

    /**
     * Enable/disable data restore at application install time.  When enabled, app
     * installation will include an attempt to fetch the app's historical data from
     * the archival restore dataset (if any).  When disabled, no such attempt will
     * be made.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     *
     * @hide
     */
    @SystemApi
    public void setAutoRestore(boolean isEnabled) {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.setAutoRestore(isEnabled);
            } catch (RemoteException e) {
                Log.e(TAG, "setAutoRestore() couldn't connect");
            }
        }
    }

    /**
     * Identify the currently selected transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     * @return The name of the currently active backup transport.  In case of
     *   failure or if no transport is currently active, this method returns {@code null}.
     *
     * @hide
     */
    @SystemApi
    public String getCurrentTransport() {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getCurrentTransport();
            } catch (RemoteException e) {
                Log.e(TAG, "getCurrentTransport() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Request a list of all available backup transports' names.  Callers must
     * hold the android.permission.BACKUP permission to use this method.
     *
     * @hide
     */
    @SystemApi
    public String[] listAllTransports() {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.listAllTransports();
            } catch (RemoteException e) {
                Log.e(TAG, "listAllTransports() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Specify the current backup transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     *
     * @param transport The name of the transport to select.  This should be one
     *   of the names returned by {@link #listAllTransports()}.
     * @return The name of the previously selected transport.  If the given transport
     *   name is not one of the currently available transports, no change is made to
     *   the current transport setting and the method returns null.
     *
     * @hide
     */
    @SystemApi
    public String selectBackupTransport(String transport) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.selectBackupTransport(transport);
            } catch (RemoteException e) {
                Log.e(TAG, "selectBackupTransport() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Schedule an immediate backup attempt for all pending key/value updates.  This
     * is primarily intended for transports to use when they detect a suitable
     * opportunity for doing a backup pass.  If there are no pending updates to
     * be sent, no action will be taken.  Even if some updates are pending, the
     * transport will still be asked to confirm via the usual requestBackupTime()
     * method.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     *
     * @hide
     */
    @SystemApi
    public void backupNow() {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.backupNow();
            } catch (RemoteException e) {
                Log.e(TAG, "backupNow() couldn't connect");
            }
        }
    }

    /**
     * Ask the framework which dataset, if any, the given package's data would be
     * restored from if we were to install it right now.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     *
     * @param packageName The name of the package whose most-suitable dataset we
     *     wish to look up
     * @return The dataset token from which a restore should be attempted, or zero if
     *     no suitable data is available.
     *
     * @hide
     */
    @SystemApi
    public long getAvailableRestoreToken(String packageName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getAvailableRestoreToken(packageName);
            } catch (RemoteException e) {
                Log.e(TAG, "getAvailableRestoreToken() couldn't connect");
            }
        }
        return 0;
    }

    /**
     * Ask the framework whether this app is eligible for backup.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     *
     * @param packageName The name of the package.
     * @return Whether this app is eligible for backup.
     *
     * @hide
     */
    @SystemApi
    public boolean isAppEligibleForBackup(String packageName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.isAppEligibleForBackup(packageName);
            } catch (RemoteException e) {
                Log.e(TAG, "isAppEligibleForBackup(pkg) couldn't connect");
            }
        }
        return false;
    }
    
    /**
     * Request an immediate backup, providing an observer to which results of the backup operation
     * will be published. The Android backup system will decide for each package whether it will
     * be full app data backup or key/value-pair-based backup.
     *
     * <p>If this method returns {@link BackupManager#SUCCESS}, the OS will attempt to backup all
     * provided packages using the remote transport.
     *
     * @param packages List of package names to backup.
     * @param observer The {@link BackupObserver} to receive callbacks during the backup
     * operation. Could be {@code null}.
     * @return {@link BackupManager#SUCCESS} on success; nonzero on error.
     * @exception  IllegalArgumentException on null or empty {@code packages} param.
     *
     * @hide
     */
    @SystemApi
    public int requestBackup(String[] packages, BackupObserver observer) {
        checkServiceBinder();
        if (sService != null) {
            try {
                BackupObserverWrapper observerWrapper = observer == null
                        ? null
                        : new BackupObserverWrapper(mContext, observer);
                return sService.requestBackup(packages, observerWrapper);
            } catch (RemoteException e) {
                Log.e(TAG, "requestBackup() couldn't connect");
            }
        }
        return -1;
    }

    /*
     * We wrap incoming binder calls with a private class implementation that
     * redirects them into main-thread actions.  This serializes the backup
     * progress callbacks nicely within the usual main-thread lifecycle pattern.
     */
    @SystemApi
    private class BackupObserverWrapper extends IBackupObserver.Stub {
        final Handler mHandler;
        final BackupObserver mObserver;

        static final int MSG_UPDATE = 1;
        static final int MSG_RESULT = 2;
        static final int MSG_FINISHED = 3;

        BackupObserverWrapper(Context context, BackupObserver observer) {
            mHandler = new Handler(context.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_UPDATE:
                            Pair<String, BackupProgress> obj =
                                (Pair<String, BackupProgress>) msg.obj;
                            mObserver.onUpdate(obj.first, obj.second);
                            break;
                        case MSG_RESULT:
                            mObserver.onResult((String)msg.obj, msg.arg1);
                            break;
                        case MSG_FINISHED:
                            mObserver.backupFinished(msg.arg1);
                            break;
                        default:
                            Log.w(TAG, "Unknown message: " + msg);
                            break;
                    }
                }
            };
            mObserver = observer;
        }

        // Binder calls into this object just enqueue on the main-thread handler
        @Override
        public void onUpdate(String currentPackage, BackupProgress backupProgress) {
            mHandler.sendMessage(
                mHandler.obtainMessage(MSG_UPDATE, Pair.create(currentPackage, backupProgress)));
        }

        @Override
        public void onResult(String currentPackage, int status) {
            mHandler.sendMessage(
                mHandler.obtainMessage(MSG_RESULT, status, 0, currentPackage));
        }

        @Override
        public void backupFinished(int status) {
            mHandler.sendMessage(
                mHandler.obtainMessage(MSG_FINISHED, status, 0));
        }
    }
}
