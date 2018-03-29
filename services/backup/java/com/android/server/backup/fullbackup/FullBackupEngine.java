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

import static com.android.server.backup.BackupManagerService.BACKUP_MANIFEST_FILENAME;
import static com.android.server.backup.BackupManagerService.BACKUP_METADATA_FILENAME;
import static com.android.server.backup.BackupManagerService.BACKUP_METADATA_VERSION;
import static com.android.server.backup.BackupManagerService.BACKUP_WIDGET_METADATA_TOKEN;
import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.OP_TYPE_BACKUP_WAIT;
import static com.android.server.backup.BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;
import static com.android.server.backup.BackupManagerService.TAG;

import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.BackupTransport;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Environment.UserEnvironment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.util.StringBuilderPrinter;

import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.utils.FullBackupUtils;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Core logic for performing one package's full backup, gathering the tarball from the
 * application and emitting it to the designated OutputStream.
 */
public class FullBackupEngine {

    private BackupManagerService backupManagerService;
    OutputStream mOutput;
    FullBackupPreflight mPreflightHook;
    BackupRestoreTask mTimeoutMonitor;
    IBackupAgent mAgent;
    File mFilesDir;
    File mManifestFile;
    File mMetadataFile;
    boolean mIncludeApks;
    PackageInfo mPkg;
    private final long mQuota;
    private final int mOpToken;
    private final int mTransportFlags;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;

    class FullBackupRunner implements Runnable {

        PackageInfo mPackage;
        byte[] mWidgetData;
        IBackupAgent mAgent;
        ParcelFileDescriptor mPipe;
        int mToken;
        boolean mSendApk;
        boolean mWriteManifest;

        FullBackupRunner(PackageInfo pack, IBackupAgent agent, ParcelFileDescriptor pipe,
                int token, boolean sendApk, boolean writeManifest, byte[] widgetData)
                throws IOException {
            mPackage = pack;
            mWidgetData = widgetData;
            mAgent = agent;
            mPipe = ParcelFileDescriptor.dup(pipe.getFileDescriptor());
            mToken = token;
            mSendApk = sendApk;
            mWriteManifest = writeManifest;
        }

        @Override
        public void run() {
            try {
                FullBackupDataOutput output = new FullBackupDataOutput(
                        mPipe, -1, mTransportFlags);

                if (mWriteManifest) {
                    final boolean writeWidgetData = mWidgetData != null;
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "Writing manifest for " + mPackage.packageName);
                    }
                    FullBackupUtils
                            .writeAppManifest(mPackage, backupManagerService.getPackageManager(),
                                    mManifestFile, mSendApk,
                                    writeWidgetData);
                    FullBackup.backupToTar(mPackage.packageName, null, null,
                            mFilesDir.getAbsolutePath(),
                            mManifestFile.getAbsolutePath(),
                            output);
                    mManifestFile.delete();

                    // We only need to write a metadata file if we have widget data to stash
                    if (writeWidgetData) {
                        writeMetadata(mPackage, mMetadataFile, mWidgetData);
                        FullBackup.backupToTar(mPackage.packageName, null, null,
                                mFilesDir.getAbsolutePath(),
                                mMetadataFile.getAbsolutePath(),
                                output);
                        mMetadataFile.delete();
                    }
                }

                if (mSendApk) {
                    writeApkToBackup(mPackage, output);
                }

                final boolean isSharedStorage =
                        mPackage.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE);
                final long timeout = isSharedStorage ?
                        mAgentTimeoutParameters.getSharedBackupAgentTimeoutMillis() :
                        mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();

                if (DEBUG) {
                    Slog.d(TAG, "Calling doFullBackup() on " + mPackage.packageName);
                }
                backupManagerService
                        .prepareOperationTimeout(mToken,
                                timeout,
                                mTimeoutMonitor /* in parent class */,
                                OP_TYPE_BACKUP_WAIT);
                mAgent.doFullBackup(mPipe, mQuota, mToken,
                        backupManagerService.getBackupManagerBinder(), mTransportFlags);
            } catch (IOException e) {
                Slog.e(TAG, "Error running full backup for " + mPackage.packageName);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote agent vanished during full backup of " + mPackage.packageName);
            } finally {
                try {
                    mPipe.close();
                } catch (IOException e) {
                }
            }
        }
    }

    public FullBackupEngine(BackupManagerService backupManagerService,
            OutputStream output,
            FullBackupPreflight preflightHook, PackageInfo pkg,
            boolean alsoApks, BackupRestoreTask timeoutMonitor, long quota, int opToken,
            int transportFlags) {
        this.backupManagerService = backupManagerService;
        mOutput = output;
        mPreflightHook = preflightHook;
        mPkg = pkg;
        mIncludeApks = alsoApks;
        mTimeoutMonitor = timeoutMonitor;
        mFilesDir = new File("/data/system");
        mManifestFile = new File(mFilesDir, BACKUP_MANIFEST_FILENAME);
        mMetadataFile = new File(mFilesDir, BACKUP_METADATA_FILENAME);
        mQuota = quota;
        mOpToken = opToken;
        mTransportFlags = transportFlags;
        mAgentTimeoutParameters = Preconditions.checkNotNull(
                backupManagerService.getAgentTimeoutParameters(),
                "Timeout parameters cannot be null");
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

                ApplicationInfo app = mPkg.applicationInfo;
                final boolean isSharedStorage =
                        mPkg.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE);
                final boolean sendApk = mIncludeApks
                        && !isSharedStorage
                        && ((app.privateFlags & ApplicationInfo.PRIVATE_FLAG_FORWARD_LOCK) == 0)
                        && ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);

                // TODO: http://b/22388012
                byte[] widgetBlob = AppWidgetBackupBridge.getWidgetState(mPkg.packageName,
                        UserHandle.USER_SYSTEM);

                FullBackupRunner runner = new FullBackupRunner(mPkg, mAgent, pipes[1],
                        mOpToken, sendApk, !isSharedStorage, widgetBlob);
                pipes[1].close();   // the runner has dup'd it
                pipes[1] = null;
                Thread t = new Thread(runner, "app-data-runner");
                t.start();

                // Now pull data from the app and stuff it into the output
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

    public void sendQuotaExceeded(final long backupDataBytes, final long quotaBytes) {
        if (initializeAgent()) {
            try {
                mAgent.doQuotaExceeded(backupDataBytes, quotaBytes);
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
            mAgent = backupManagerService.bindToAgentSynchronous(mPkg.applicationInfo,
                    ApplicationThreadConstants.BACKUP_MODE_FULL);
        }
        return mAgent != null;
    }

    private void writeApkToBackup(PackageInfo pkg, FullBackupDataOutput output) {
        // Forward-locked apps, system-bundled .apks, etc are filtered out before we get here
        // TODO: handle backing up split APKs
        final String appSourceDir = pkg.applicationInfo.getBaseCodePath();
        final String apkDir = new File(appSourceDir).getParent();
        FullBackup.backupToTar(pkg.packageName, FullBackup.APK_TREE_TOKEN, null,
                apkDir, appSourceDir, output);

        // TODO: migrate this to SharedStorageBackup, since AID_SYSTEM
        // doesn't have access to external storage.

        // Save associated .obb content if it exists and we did save the apk
        // check for .obb and save those too
        // TODO: http://b/22388012
        final UserEnvironment userEnv = new UserEnvironment(UserHandle.USER_SYSTEM);
        final File obbDir = userEnv.buildExternalStorageAppObbDirs(pkg.packageName)[0];
        if (obbDir != null) {
            if (MORE_DEBUG) {
                Log.i(TAG, "obb dir: " + obbDir.getAbsolutePath());
            }
            File[] obbFiles = obbDir.listFiles();
            if (obbFiles != null) {
                final String obbDirName = obbDir.getAbsolutePath();
                for (File obb : obbFiles) {
                    FullBackup.backupToTar(pkg.packageName, FullBackup.OBB_TREE_TOKEN, null,
                            obbDirName, obb.getAbsolutePath(), output);
                }
            }
        }
    }

    // Widget metadata format. All header entries are strings ending in LF:
    //
    // Version 1 header:
    //     BACKUP_METADATA_VERSION, currently "1"
    //     package name
    //
    // File data (all integers are binary in network byte order)
    // *N: 4 : integer token identifying which metadata blob
    //     4 : integer size of this blob = N
    //     N : raw bytes of this metadata blob
    //
    // Currently understood blobs (always in network byte order):
    //
    //     widgets : metadata token = 0x01FFED01 (BACKUP_WIDGET_METADATA_TOKEN)
    //
    // Unrecognized blobs are *ignored*, not errors.
    private void writeMetadata(PackageInfo pkg, File destination, byte[] widgetData)
            throws IOException {
        StringBuilder b = new StringBuilder(512);
        StringBuilderPrinter printer = new StringBuilderPrinter(b);
        printer.println(Integer.toString(BACKUP_METADATA_VERSION));
        printer.println(pkg.packageName);

        FileOutputStream fout = new FileOutputStream(destination);
        BufferedOutputStream bout = new BufferedOutputStream(fout);
        DataOutputStream out = new DataOutputStream(bout);
        bout.write(b.toString().getBytes());    // bypassing DataOutputStream

        if (widgetData != null && widgetData.length > 0) {
            out.writeInt(BACKUP_WIDGET_METADATA_TOKEN);
            out.writeInt(widgetData.length);
            out.write(widgetData);
        }
        bout.flush();
        out.close();

        // As with the manifest file, guarantee idempotence of the archive metadata
        // for the widget block by using a fixed mtime on the transient file.
        destination.setLastModified(0);
    }

    private void tearDown() {
        if (mPkg != null) {
            backupManagerService.tearDownAgentAndKill(mPkg.applicationInfo);
        }
    }
}
