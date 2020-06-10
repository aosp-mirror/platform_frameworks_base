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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
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
     * The backup operation was cancelled.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_BACKUP_CANCELLED = -2003;

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

    /**
     * Intent extra when any subsidiary backup-related UI is launched from Settings:  does
     * device policy or configuration permit backup operations to run at all?
     *
     * @hide
     */
    public static final String EXTRA_BACKUP_SERVICES_AVAILABLE = "backup_services_available";

    /**
     * If this flag is passed to {@link #requestBackup(String[], BackupObserver, int)},
     * BackupManager will pass a blank old state to BackupAgents of requested packages.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_NON_INCREMENTAL_BACKUP = 1;

    /**
     * Use with {@link #requestBackup} to force backup of
     * package meta data. Typically you do not need to explicitly request this be backed up as it is
     * handled internally by the BackupManager. If you are requesting backups with
     * FLAG_NON_INCREMENTAL, this package won't automatically be backed up and you have to
     * explicitly request for its backup.
     *
     * @hide
     */
    @SystemApi
    public static final String PACKAGE_MANAGER_SENTINEL = "@pm@";


    /**
     * This error code is passed to {@link SelectBackupTransportCallback#onFailure(int)}
     * if the requested transport is unavailable.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_TRANSPORT_UNAVAILABLE = -1;

    /**
     * This error code is passed to {@link SelectBackupTransportCallback#onFailure(int)} if the
     * requested transport is not a valid BackupTransport.
     *
     * @hide
     */
    @SystemApi
    public static final int ERROR_TRANSPORT_INVALID = -2;

    private Context mContext;
    @UnsupportedAppUsage
    private static IBackupManager sService;

    @UnsupportedAppUsage
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
     * @deprecated Applications shouldn't request a restore operation using this method. In Android
     * P and later, this method is a no-op.
     *
     * <p>Restore the calling application from backup. The data will be restored from the
     * current backup dataset if the application has stored data there, or from
     * the dataset used during the last full device setup operation if the current
     * backup dataset has no matching data.  If no backup data exists for this application
     * in either source, a non-zero value is returned.
     *
     * <p>If this method returns zero (meaning success), the OS attempts to retrieve a backed-up
     * dataset from the remote transport, instantiate the application's backup agent, and pass the
     * dataset to the agent's
     * {@link android.app.backup.BackupAgent#onRestore(BackupDataInput, int, android.os.ParcelFileDescriptor) onRestore()}
     * method.
     *
     * <p class="caution">Unlike other restore operations, this method doesn't terminate the
     * application after the restore. The application continues running to receive the
     * {@link RestoreObserver} callbacks on the {@code observer} argument. Full backups use an
     * {@link android.app.Application Application} base class while key-value backups use the
     * application subclass declared in the AndroidManifest.xml {@code <application>} tag.
     *
     * @param observer The {@link RestoreObserver} to receive callbacks during the restore
     * operation. This must not be null.
     *
     * @return Zero on success; nonzero on error.
     */
    @Deprecated
    public int requestRestore(RestoreObserver observer) {
        return requestRestore(observer, null);
    }

    // system APIs start here

    /**
     * @deprecated Since Android P app can no longer request restoring of its backup.
     *
     * <p>Restore the calling application from backup.  The data will be restored from the
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
     * @param monitor the {@link BackupManagerMonitor} to receive callbacks during the restore
     * operation.
     *
     * @return Zero on success; nonzero on error.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    public int requestRestore(RestoreObserver observer, BackupManagerMonitor monitor) {
        Log.w(TAG, "requestRestore(): Since Android P app can no longer request restoring"
                + " of its backup.");
        return -1;
    }

    /**
     * Begin the process of restoring data from backup.  See the
     * {@link android.app.backup.RestoreSession} class for documentation on that process.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public RestoreSession beginRestoreSession() {
        RestoreSession session = null;
        checkServiceBinder();
        if (sService != null) {
            try {
                // All packages, current transport
                IRestoreSession binder =
                        sService.beginRestoreSessionForUser(mContext.getUserId(), null, null);
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
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
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
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
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
     * Report whether the backup mechanism is currently active.
     * When it is inactive, the device will not perform any backup operations, nor will it
     * deliver data for restore, although clients can still safely call BackupManager methods.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public boolean isBackupServiceActive(UserHandle user) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "isBackupServiceActive");
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.isBackupServiceActive(user.getIdentifier());
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
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
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
     * Identify the currently selected transport.
     * @return The name of the currently active backup transport.  In case of
     *   failure or if no transport is currently active, this method returns {@code null}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
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
     * Returns the {@link ComponentName} of the host service of the selected transport or {@code
     * null} if no transport selected or if the transport selected is not registered.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    @Nullable
    public ComponentName getCurrentTransportComponent() {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getCurrentTransportComponentForUser(mContext.getUserId());
            } catch (RemoteException e) {
                Log.e(TAG, "getCurrentTransportComponent() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Request a list of all available backup transports' names.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
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
     * Update the attributes of the transport identified by {@code transportComponent}. If the
     * specified transport has not been bound at least once (for registration), this call will be
     * ignored. Only the host process of the transport can change its description, otherwise a
     * {@link SecurityException} will be thrown.
     *
     * @param transportComponent The identity of the transport being described.
     * @param name A {@link String} with the new name for the transport. This is NOT for
     *     identification. MUST NOT be {@code null}.
     * @param configurationIntent An {@link Intent} that can be passed to {@link
     *     Context#startActivity} in order to launch the transport's configuration UI. It may be
     *     {@code null} if the transport does not offer any user-facing configuration UI.
     * @param currentDestinationString A {@link String} describing the destination to which the
     *     transport is currently sending data. MUST NOT be {@code null}.
     * @param dataManagementIntent An {@link Intent} that can be passed to {@link
     *     Context#startActivity} in order to launch the transport's data-management UI. It may be
     *     {@code null} if the transport does not offer any user-facing data management UI.
     * @param dataManagementLabel A {@link String} to be used as the label for the transport's data
     *     management affordance. This MUST be {@code null} when dataManagementIntent is {@code
     *     null} and MUST NOT be {@code null} when dataManagementIntent is not {@code null}.
     * @throws SecurityException If the UID of the calling process differs from the package UID of
     *     {@code transportComponent} or if the caller does NOT have BACKUP permission.
     * @deprecated Since Android Q, please use the variant {@link
     *     #updateTransportAttributes(ComponentName, String, Intent, String, Intent, CharSequence)}
     *     instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public void updateTransportAttributes(
            @NonNull ComponentName transportComponent,
            @NonNull String name,
            @Nullable Intent configurationIntent,
            @NonNull String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            @Nullable String dataManagementLabel) {
        updateTransportAttributes(
                transportComponent,
                name,
                configurationIntent,
                currentDestinationString,
                dataManagementIntent,
                (CharSequence) dataManagementLabel);
    }

    /**
     * Update the attributes of the transport identified by {@code transportComponent}. If the
     * specified transport has not been bound at least once (for registration), this call will be
     * ignored. Only the host process of the transport can change its description, otherwise a
     * {@link SecurityException} will be thrown.
     *
     * @param transportComponent The identity of the transport being described.
     * @param name A {@link String} with the new name for the transport. This is NOT for
     *     identification. MUST NOT be {@code null}.
     * @param configurationIntent An {@link Intent} that can be passed to {@link
     *     Context#startActivity} in order to launch the transport's configuration UI. It may be
     *     {@code null} if the transport does not offer any user-facing configuration UI.
     * @param currentDestinationString A {@link String} describing the destination to which the
     *     transport is currently sending data. MUST NOT be {@code null}.
     * @param dataManagementIntent An {@link Intent} that can be passed to {@link
     *     Context#startActivity} in order to launch the transport's data-management UI. It may be
     *     {@code null} if the transport does not offer any user-facing data management UI.
     * @param dataManagementLabel A {@link CharSequence} to be used as the label for the transport's
     *     data management affordance. This MUST be {@code null} when dataManagementIntent is {@code
     *     null} and MUST NOT be {@code null} when dataManagementIntent is not {@code null}.
     * @throws SecurityException If the UID of the calling process differs from the package UID of
     *     {@code transportComponent} or if the caller does NOT have BACKUP permission.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public void updateTransportAttributes(
            @NonNull ComponentName transportComponent,
            @NonNull String name,
            @Nullable Intent configurationIntent,
            @NonNull String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            @Nullable CharSequence dataManagementLabel) {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.updateTransportAttributesForUser(
                        mContext.getUserId(),
                        transportComponent,
                        name,
                        configurationIntent,
                        currentDestinationString,
                        dataManagementIntent,
                        dataManagementLabel);
            } catch (RemoteException e) {
                Log.e(TAG, "describeTransport() couldn't connect");
            }
        }
    }

    /**
     * Specify the current backup transport.
     *
     * @param transport The name of the transport to select.  This should be one
     *   of the names returned by {@link #listAllTransports()}. This is the String returned by
     *   {@link BackupTransport#name()} for the particular transport.
     * @return The name of the previously selected transport.  If the given transport
     *   name is not one of the currently available transports, no change is made to
     *   the current transport setting and the method returns null.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
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
     * Specify the current backup transport and get notified when the transport is ready to be used.
     * This method is async because BackupManager might need to bind to the specified transport
     * which is in a separate process.
     *
     * @param transport ComponentName of the service hosting the transport. This is different from
     *                  the transport's name that is returned by {@link BackupTransport#name()}.
     * @param listener A listener object to get a callback on the transport being selected.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public void selectBackupTransport(ComponentName transport,
            SelectBackupTransportCallback listener) {
        checkServiceBinder();
        if (sService != null) {
            try {
                SelectTransportListenerWrapper wrapper = listener == null ?
                        null : new SelectTransportListenerWrapper(mContext, listener);
                sService.selectBackupTransportAsyncForUser(
                        mContext.getUserId(), transport, wrapper);
            } catch (RemoteException e) {
                Log.e(TAG, "selectBackupTransportAsync() couldn't connect");
            }
        }
    }

    /**
     * Schedule an immediate backup attempt for all pending key/value updates.  This
     * is primarily intended for transports to use when they detect a suitable
     * opportunity for doing a backup pass.  If there are no pending updates to
     * be sent, no action will be taken.  Even if some updates are pending, the
     * transport will still be asked to confirm via the usual requestBackupTime()
     * method.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
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
     * @param packageName The name of the package whose most-suitable dataset we
     *     wish to look up
     * @return The dataset token from which a restore should be attempted, or zero if
     *     no suitable data is available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public long getAvailableRestoreToken(String packageName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getAvailableRestoreTokenForUser(mContext.getUserId(), packageName);
            } catch (RemoteException e) {
                Log.e(TAG, "getAvailableRestoreToken() couldn't connect");
            }
        }
        return 0;
    }

    /**
     * Ask the framework whether this app is eligible for backup.
     *
     * @param packageName The name of the package.
     * @return Whether this app is eligible for backup.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public boolean isAppEligibleForBackup(String packageName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.isAppEligibleForBackupForUser(mContext.getUserId(), packageName);
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
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public int requestBackup(String[] packages, BackupObserver observer) {
        return requestBackup(packages, observer, null, 0);
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
     *                 operation. Could be {@code null}.
     * @param monitor  The {@link BackupManagerMonitorWrapper} to receive callbacks of important
     *                 events during the backup operation. Could be {@code null}.
     * @param flags    {@link #FLAG_NON_INCREMENTAL_BACKUP}.
     * @return {@link BackupManager#SUCCESS} on success; nonzero on error.
     * @throws IllegalArgumentException on null or empty {@code packages} param.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public int requestBackup(String[] packages, BackupObserver observer,
            BackupManagerMonitor monitor, int flags) {
        checkServiceBinder();
        if (sService != null) {
            try {
                BackupObserverWrapper observerWrapper = observer == null
                        ? null
                        : new BackupObserverWrapper(mContext, observer);
                BackupManagerMonitorWrapper monitorWrapper = monitor == null
                        ? null
                        : new BackupManagerMonitorWrapper(monitor);
                return sService.requestBackup(packages, observerWrapper, monitorWrapper, flags);
            } catch (RemoteException e) {
                Log.e(TAG, "requestBackup() couldn't connect");
            }
        }
        return -1;
    }

    /**
     * Cancel all running backups. After this call returns, no currently running backups will
     * interact with the selected transport.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public void cancelBackups() {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.cancelBackups();
            } catch (RemoteException e) {
                Log.e(TAG, "cancelBackups() couldn't connect.");
            }
        }
    }

    /**
     * Returns a {@link UserHandle} for the user that has {@code ancestralSerialNumber} as the
     * serial number of the its ancestral work profile or {@code null} if there is none.
     *
     * <p> The ancestral serial number will have a corresponding {@link UserHandle} if the device
     * has a work profile that was restored from another work profile with serial number
     * {@code ancestralSerialNumber}.
     *
     * @see android.os.UserManager#getSerialNumberForUser(UserHandle)
     */
    @Nullable
    public UserHandle getUserForAncestralSerialNumber(long ancestralSerialNumber) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getUserForAncestralSerialNumber(ancestralSerialNumber);
            } catch (RemoteException e) {
                Log.e(TAG, "getUserForAncestralSerialNumber() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Sets the ancestral work profile for the calling user.
     *
     * <p> The ancestral work profile corresponds to the profile that was used to restore to the
     * callers profile.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public void setAncestralSerialNumber(long ancestralSerialNumber) {
        checkServiceBinder();
        if (sService != null) {
            try {
                sService.setAncestralSerialNumber(ancestralSerialNumber);
            } catch (RemoteException e) {
                Log.e(TAG, "setAncestralSerialNumber() couldn't connect");
            }
        }
    }

    /**
     * Returns an {@link Intent} for the specified transport's configuration UI.
     * This value is set by {@link #updateTransportAttributes(ComponentName, String, Intent, String,
     * Intent, CharSequence)}.
     * @param transportName The name of the registered transport.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public Intent getConfigurationIntent(String transportName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getConfigurationIntentForUser(mContext.getUserId(), transportName);
            } catch (RemoteException e) {
                Log.e(TAG, "getConfigurationIntent() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Returns a {@link String} describing where the specified transport is sending data.
     * This value is set by {@link #updateTransportAttributes(ComponentName, String, Intent, String,
     * Intent, CharSequence)}.
     * @param transportName The name of the registered transport.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public String getDestinationString(String transportName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getDestinationStringForUser(mContext.getUserId(), transportName);
            } catch (RemoteException e) {
                Log.e(TAG, "getDestinationString() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Returns an {@link Intent} for the specified transport's data management UI.
     * This value is set by {@link #updateTransportAttributes(ComponentName, String, Intent, String,
     * Intent, CharSequence)}.
     * @param transportName The name of the registered transport.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    public Intent getDataManagementIntent(String transportName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getDataManagementIntentForUser(mContext.getUserId(), transportName);
            } catch (RemoteException e) {
                Log.e(TAG, "getDataManagementIntent() couldn't connect");
            }
        }
        return null;
    }

    /**
     * Returns a {@link String} describing what the specified transport's data management intent is
     * used for. This value is set by {@link #updateTransportAttributes(ComponentName, String,
     * Intent, String, Intent, CharSequence)}.
     *
     * @param transportName The name of the registered transport.
     * @deprecated Since Android Q, please use the variant {@link
     *     #getDataManagementIntentLabel(String)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    @Nullable
    public String getDataManagementLabel(@NonNull String transportName) {
        CharSequence label = getDataManagementIntentLabel(transportName);
        return label == null ? null : label.toString();
    }

    /**
     * Returns a {@link CharSequence} describing what the specified transport's data management
     * intent is used for. This value is set by {@link #updateTransportAttributes(ComponentName,
     * String, Intent, String, Intent, CharSequence)}.
     *
     * @param transportName The name of the registered transport.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.BACKUP)
    @Nullable
    public CharSequence getDataManagementIntentLabel(@NonNull String transportName) {
        checkServiceBinder();
        if (sService != null) {
            try {
                return sService.getDataManagementLabelForUser(mContext.getUserId(), transportName);
            } catch (RemoteException e) {
                Log.e(TAG, "getDataManagementIntentLabel() couldn't connect");
            }
        }
        return null;
    }

    /*
     * We wrap incoming binder calls with a private class implementation that
     * redirects them into main-thread actions.  This serializes the backup
     * progress callbacks nicely within the usual main-thread lifecycle pattern.
     */
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

    private class SelectTransportListenerWrapper extends ISelectBackupTransportCallback.Stub {

        private final Handler mHandler;
        private final SelectBackupTransportCallback mListener;

        SelectTransportListenerWrapper(Context context, SelectBackupTransportCallback listener) {
            mHandler = new Handler(context.getMainLooper());
            mListener = listener;
        }

        @Override
        public void onSuccess(final String transportName) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onSuccess(transportName);
                }
            });
        }

        @Override
        public void onFailure(final int reason) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onFailure(reason);
                }
            });
        }
    }

    private class BackupManagerMonitorWrapper extends IBackupManagerMonitor.Stub {
        final BackupManagerMonitor mMonitor;

        BackupManagerMonitorWrapper(BackupManagerMonitor monitor) {
            mMonitor = monitor;
        }

        @Override
        public void onEvent(final Bundle event) throws RemoteException {
            mMonitor.onEvent(event);
        }
    }

}
