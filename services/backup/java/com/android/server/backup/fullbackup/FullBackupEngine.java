/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.fullbackup;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.BACKUP_MANIFEST_FILENAME;
import static com.android.server.backup.UserBackupManagerService.BACKUP_METADATA_FILENAME;
import static com.android.server.backup.UserBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;

import android.annotation.UserIdInt;
import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.BackupTransport;
import android.app.backup.FullBackupDataOutput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.AppWidgetBackupBridge;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.OperationStorage.OpType;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.remote.RemoteCall;
import com.android.server.backup.utils.BackupEligibilityRules;
import com.android.server.backup.utils.FullBackupUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Core logic for performing one package's full backup, gathering the tarball from the application
 * and emitting it to the designated OutputStream.
 */
public class FullBackupEngine {
    private UserBackupManagerService backupManagerService;
    private OutputStream mOutput;
    private FullBackupPreflight mPreflightHook;
    private BackupRestoreTask mTimeoutMonitor;
    private IBackupAgent mAgent;
    private boolean mIncludeApks;
    private PackageInfo mPkg;
    private final long mQuota;
    private final int mOpToken;
    private final int mTransportFlags;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private final BackupEligibilityRules mBackupEligibilityRules;

    class FullBackupRunner implements Runnable {
        private final @UserIdInt int mUserId;
        private final PackageManager mPackageManager;
        private final PackageInfo mPackage;
        private final IBackupAgent mAgent;
        private final ParcelFileDescriptor mPipe;
        private final int mToken;
        private final boolean mIncludeApks;
        private final File mFilesDir;

        FullBackupRunner(
                UserBackupManagerService userBackupManagerService,
                PackageInfo packageInfo,
                IBackupAgent agent,
                ParcelFileDescriptor pipe,
                int token,
                boolean includeApks)
                throws IOException {
            mUserId = userBackupManagerService.getUserId();
            mPackageManager = backupManagerService.getPackageManager();
            mPackage = packageInfo;
            mAgent = agent;
            mPipe = ParcelFileDescriptor.dup(pipe.getFileDescriptor());
            mToken = token;
            mIncludeApks = includeApks;
            mFilesDir = userBackupManagerService.getDataDir();
        }

        @Override
        public void run() {
            try {
                FullBackupDataOutput output =
                        new FullBackupDataOutput(mPipe, /* quota */ -1, mTransportFlags);
                AppMetadataBackupWriter appMetadataBackupWriter =
                        new AppMetadataBackupWriter(output, mPackageManager);

                String packageName = mPackage.packageName;
                boolean isSharedStorage = SHARED_BACKUP_AGENT_PACKAGE.equals(packageName);
                boolean writeApk =
                        shouldWriteApk(mPackage.applicationInfo, mIncludeApks, isSharedStorage);

                if (!isSharedStorage) {
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "Writing manifest for " + packageName);
                    }

                    File manifestFile = new File(mFilesDir, BACKUP_MANIFEST_FILENAME);
                    appMetadataBackupWriter.backupManifest(
                            mPackage, manifestFile, mFilesDir, writeApk);
                    manifestFile.delete();

                    // Write widget data.
                    byte[] widgetData =
                            AppWidgetBackupBridge.getWidgetState(packageName, mUserId);
                    if (widgetData != null && widgetData.length > 0) {
                        File metadataFile = new File(mFilesDir, BACKUP_METADATA_FILENAME);
                        appMetadataBackupWriter.backupWidget(
                                mPackage, metadataFile, mFilesDir, widgetData);
                        metadataFile.delete();
                    }
                }

                // TODO(b/113807190): Look into removing, only used for 'adb backup'.
                if (writeApk) {
                    appMetadataBackupWriter.backupApk(mPackage);
                    appMetadataBackupWriter.backupObb(mUserId, mPackage);
                }

                if (DEBUG) {
                    Slog.d(TAG, "Calling doFullBackup() on " + packageName);
                }

                long timeout =
                        isSharedStorage
                                ? mAgentTimeoutParameters.getSharedBackupAgentTimeoutMillis()
                                : mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
                backupManagerService.prepareOperationTimeout(
                        mToken,
                        timeout,
                        mTimeoutMonitor /* in parent class */,
                        OpType.BACKUP_WAIT);
                mAgent.doFullBackup(
                        mPipe,
                        mQuota,
                        mToken,
                        backupManagerService.getBackupManagerBinder(),
                        mTransportFlags);
            } catch (IOException e) {
                Slog.e(TAG, "Error running full backup for " + mPackage.packageName, e);
            } catch (RemoteException e) {
                Slog.e(
                        TAG,
                        "Remote agent vanished during full backup of " + mPackage.packageName,
                        e);
            } finally {
                try {
                    mPipe.close();
                } catch (IOException e) {
                }
            }
        }

        /**
         * Don't write apks for system-bundled apps that are not upgraded.
         */
        private boolean shouldWriteApk(
                ApplicationInfo applicationInfo, boolean includeApks, boolean isSharedStorage) {
            boolean isSystemApp = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystemApp =
                    (applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            return includeApks
                    && !isSharedStorage
                    && (!isSystemApp || isUpdatedSystemApp);
        }
    }

    public FullBackupEngine(
            UserBackupManagerService backupManagerService,
            OutputStream output,
            FullBackupPreflight preflightHook,
            PackageInfo pkg,
            boolean alsoApks,
            BackupRestoreTask timeoutMonitor,
            long quota,
            int opToken,
            int transportFlags,
            BackupEligibilityRules backupEligibilityRules) {
        this.backupManagerService = backupManagerService;
        mOutput = output;
        mPreflightHook = preflightHook;
        mPkg = pkg;
        mIncludeApks = alsoApks;
        mTimeoutMonitor = timeoutMonitor;
        mQuota = quota;
        mOpToken = opToken;
        mTransportFlags = transportFlags;
        mAgentTimeoutParameters =
                Objects.requireNonNull(
                        backupManagerService.getAgentTimeoutParameters(),
                        "Timeout parameters cannot be null");
        mBackupEligibilityRules = backupEligibilityRules;
    }

    public int preflightCheck() throws RemoteException {
        if (mPreflightHook == null) {
            if (MORE_DEBUG) {
                Slog.v(TAG, "No preflight check");
            }
            return BackupTransport.TRANSPORT_OK;
        }
        if (initializeAgent()) {
            int result = mPreflightHook.preflightFullBackup(mPkg, mAgent);
            if (MORE_DEBUG) {
                Slog.v(TAG, "preflight returned " + result);
            }
            return result;
        } else {
            Slog.w(TAG, "Unable to bind to full agent for " + mPkg.packageName);
            return BackupTransport.AGENT_ERROR;
        }
    }

    public int backupOnePackage() throws RemoteException {
        int result = BackupTransport.AGENT_ERROR;

        if (initializeAgent()) {
            ParcelFileDescriptor[] pipes = null;
            try {
                pipes = ParcelFileDescriptor.createPipe();

                FullBackupRunner runner =
                        new FullBackupRunner(
                                backupManagerService,
                                mPkg,
                                mAgent,
                                pipes[1],
                                mOpToken,
                                mIncludeApks);
                pipes[1].close(); // the runner has dup'd it
                pipes[1] = null;
                Thread t = new Thread(runner, "app-data-runner");
                t.start();

                FullBackupUtils.routeSocketDataToOutput(pipes[0], mOutput);

                if (!backupManagerService.waitUntilOperationComplete(mOpToken)) {
                    Slog.e(TAG, "Full backup failed on package " + mPkg.packageName);
                } else {
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "Full package backup success: " + mPkg.packageName);
                    }
                    result = BackupTransport.TRANSPORT_OK;
                }
            } catch (IOException e) {
                Slog.e(TAG, "Error backing up " + mPkg.packageName + ": " + e.getMessage());
                result = BackupTransport.AGENT_ERROR;
            } finally {
                try {
                    // flush after every package
                    mOutput.flush();
                    if (pipes != null) {
                        if (pipes[0] != null) {
                            pipes[0].close();
                        }
                        if (pipes[1] != null) {
                            pipes[1].close();
                        }
                    }
                } catch (IOException e) {
                    Slog.w(TAG, "Error bringing down backup stack");
                    result = BackupTransport.TRANSPORT_ERROR;
                }
            }
        } else {
            Slog.w(TAG, "Unable to bind to full agent for " + mPkg.packageName);
        }
        tearDown();
        return result;
    }

    public void sendQuotaExceeded(long backupDataBytes, long quotaBytes) {
        if (initializeAgent()) {
            try {
                RemoteCall.execute(
                        callback -> mAgent.doQuotaExceeded(backupDataBytes, quotaBytes, callback),
                        mAgentTimeoutParameters.getQuotaExceededTimeoutMillis());
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception while telling agent about quota exceeded");
            }
        }
    }

    private boolean initializeAgent() {
        if (mAgent == null) {
            if (MORE_DEBUG) {
                Slog.d(TAG, "Binding to full backup agent : " + mPkg.packageName);
            }
            mAgent =
                    backupManagerService.bindToAgentSynchronous(
                            mPkg.applicationInfo, ApplicationThreadConstants.BACKUP_MODE_FULL,
                            mBackupEligibilityRules.getOperationType());
        }
        return mAgent != null;
    }

    private void tearDown() {
        if (mPkg != null) {
            backupManagerService.tearDownAgentAndKill(mPkg.applicationInfo);
        }
    }
}
