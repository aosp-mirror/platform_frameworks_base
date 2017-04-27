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

package com.android.server.backup.restore;

import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.FullBackup;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.KeyValueAdbRestoreEngine;
import com.android.server.backup.RefactoredBackupManagerService;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
import com.android.server.backup.utils.BytesReadListener;
import com.android.server.backup.utils.FullBackupRestoreObserverUtils;
import com.android.server.backup.utils.RestoreUtils;
import com.android.server.backup.utils.TarBackupReader;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Full restore engine, used by both adb restore and transport-based full restore.
 */
public class FullRestoreEngine extends RestoreEngine {

    private RefactoredBackupManagerService backupManagerService;
    // Task in charge of monitoring timeouts
    BackupRestoreTask mMonitorTask;

    // Dedicated observer, if any
    IFullBackupRestoreObserver mObserver;

    IBackupManagerMonitor mMonitor;

    // Where we're delivering the file data as we go
    IBackupAgent mAgent;

    // Are we permitted to only deliver a specific package's metadata?
    PackageInfo mOnlyPackage;

    boolean mAllowApks;
    boolean mAllowObbs;

    // Which package are we currently handling data for?
    String mAgentPackage;

    // Info for working with the target app process
    ApplicationInfo mTargetApp;

    // Machinery for restoring OBBs
    FullBackupObbConnection mObbConnection = null;

    // possible handling states for a given package in the restore dataset
    final HashMap<String, RestorePolicy> mPackagePolicies
            = new HashMap<>();

    // installer package names for each encountered app, derived from the manifests
    final HashMap<String, String> mPackageInstallers = new HashMap<>();

    // Signatures for a given package found in its manifest file
    final HashMap<String, Signature[]> mManifestSignatures
            = new HashMap<>();

    // Packages we've already wiped data on when restoring their first file
    final HashSet<String> mClearedPackages = new HashSet<>();

    // How much data have we moved?
    long mBytes;

    // Working buffer
    byte[] mBuffer;

    // Pipes for moving data
    ParcelFileDescriptor[] mPipes = null;

    // Widget blob to be restored out-of-band
    byte[] mWidgetData = null;

    final int mEphemeralOpToken;

    public FullRestoreEngine(RefactoredBackupManagerService backupManagerService,
            BackupRestoreTask monitorTask, IFullBackupRestoreObserver observer,
            IBackupManagerMonitor monitor, PackageInfo onlyPackage, boolean allowApks,
            boolean allowObbs, int ephemeralOpToken) {
        this.backupManagerService = backupManagerService;
        mEphemeralOpToken = ephemeralOpToken;
        mMonitorTask = monitorTask;
        mObserver = observer;
        mMonitor = monitor;
        mOnlyPackage = onlyPackage;
        mAllowApks = allowApks;
        mAllowObbs = allowObbs;
        mBuffer = new byte[32 * 1024];
        mBytes = 0;
    }

    public IBackupAgent getAgent() {
        return mAgent;
    }

    public byte[] getWidgetData() {
        return mWidgetData;
    }

    public boolean restoreOneFile(InputStream instream, boolean mustKillAgent, byte[] buffer,
            PackageInfo onlyPackage, boolean allowApks, int token, IBackupManagerMonitor monitor) {
        if (!isRunning()) {
            Slog.w(RefactoredBackupManagerService.TAG, "Restore engine used after halting");
            return false;
        }

        BytesReadListener bytesReadListener = new BytesReadListener() {
            @Override
            public void onBytesRead(long bytesRead) {
                mBytes += bytesRead;
            }
        };

        TarBackupReader tarBackupReader = new TarBackupReader(instream,
                bytesReadListener, monitor);

        FileMetadata info;
        try {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.v(RefactoredBackupManagerService.TAG, "Reading tar header for restoring file");
            }
            info = tarBackupReader.readTarHeaders();
            if (info != null) {
                if (RefactoredBackupManagerService.MORE_DEBUG) {
                    info.dump();
                }

                final String pkg = info.packageName;
                if (!pkg.equals(mAgentPackage)) {
                    // In the single-package case, it's a semantic error to expect
                    // one app's data but see a different app's on the wire
                    if (onlyPackage != null) {
                        if (!pkg.equals(onlyPackage.packageName)) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Expected data for " + onlyPackage
                                            + " but saw " + pkg);
                            setResult(RestoreEngine.TRANSPORT_FAILURE);
                            setRunning(false);
                            return false;
                        }
                    }

                    // okay, change in package; set up our various
                    // bookkeeping if we haven't seen it yet
                    if (!mPackagePolicies.containsKey(pkg)) {
                        mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                    }

                    // Clean up the previous agent relationship if necessary,
                    // and let the observer know we're considering a new app.
                    if (mAgent != null) {
                        if (RefactoredBackupManagerService.DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Saw new package; finalizing old one");
                        }
                        // Now we're really done
                        tearDownPipes();
                        tearDownAgent(mTargetApp);
                        mTargetApp = null;
                        mAgentPackage = null;
                    }
                }

                if (info.path.equals(RefactoredBackupManagerService.BACKUP_MANIFEST_FILENAME)) {
                    RestorePolicy appManifest = tarBackupReader.readAppManifest(
                            backupManagerService.getPackageManager(), allowApks,
                            mManifestSignatures, info);
                    mPackagePolicies.put(pkg, appManifest);
                    mPackageInstallers.put(pkg, info.installerPackageName);
                    // We've read only the manifest content itself at this point,
                    // so consume the footer before looping around to the next
                    // input file
                    tarBackupReader.skipTarPadding(info.size);
                    mObserver = FullBackupRestoreObserverUtils.sendOnRestorePackage(mObserver, pkg);
                } else if (info.path.equals(
                        RefactoredBackupManagerService.BACKUP_METADATA_FILENAME)) {
                    // Metadata blobs!
                    tarBackupReader.readMetadata(info);

                    // The following only exist because we want to keep refactoring as safe as
                    // possible, without changing too much.
                    // TODO: Refactor, so that there are no funny things like this.
                    // This is read during TarBackupReader.readMetadata().
                    mWidgetData = tarBackupReader.getWidgetData();
                    // This can be nulled during TarBackupReader.readMetadata().
                    monitor = tarBackupReader.getMonitor();

                    tarBackupReader.skipTarPadding(info.size);
                } else {
                    // Non-manifest, so it's actual file data.  Is this a package
                    // we're ignoring?
                    boolean okay = true;
                    RestorePolicy policy = mPackagePolicies.get(pkg);
                    switch (policy) {
                        case IGNORE:
                            okay = false;
                            break;

                        case ACCEPT_IF_APK:
                            // If we're in accept-if-apk state, then the first file we
                            // see MUST be the apk.
                            if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "APK file; installing");
                                }
                                // Try to install the app.
                                String installerName = mPackageInstallers.get(pkg);
                                boolean isSuccessfullyInstalled = RestoreUtils.installApk(
                                        instream, backupManagerService.getPackageManager(),
                                        mInstallObserver, mDeleteObserver, mManifestSignatures,
                                        mPackagePolicies, info, installerName,
                                        bytesReadListener, backupManagerService.getDataDir()
                                                              );
                                // good to go; promote to ACCEPT
                                mPackagePolicies.put(pkg, isSuccessfullyInstalled
                                        ? RestorePolicy.ACCEPT
                                        : RestorePolicy.IGNORE);
                                // At this point we've consumed this file entry
                                // ourselves, so just strip the tar footer and
                                // go on to the next file in the input stream
                                tarBackupReader.skipTarPadding(info.size);
                                return true;
                            } else {
                                // File data before (or without) the apk.  We can't
                                // handle it coherently in this case so ignore it.
                                mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                                okay = false;
                            }
                            break;

                        case ACCEPT:
                            if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "apk present but ACCEPT");
                                }
                                // we can take the data without the apk, so we
                                // *want* to do so.  skip the apk by declaring this
                                // one file not-okay without changing the restore
                                // policy for the package.
                                okay = false;
                            }
                            break;

                        default:
                            // Something has gone dreadfully wrong when determining
                            // the restore policy from the manifest.  Ignore the
                            // rest of this package's data.
                            Slog.e(RefactoredBackupManagerService.TAG,
                                    "Invalid policy from manifest");
                            okay = false;
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                            break;
                    }

                    // Is it a *file* we need to drop or is it not a canonical path?
                    if (!isRestorableFile(info) || !isCanonicalFilePath(info.path)) {
                        okay = false;
                    }

                    // If the policy is satisfied, go ahead and set up to pipe the
                    // data to the agent.
                    if (RefactoredBackupManagerService.MORE_DEBUG && okay && mAgent != null) {
                        Slog.i(RefactoredBackupManagerService.TAG,
                                "Reusing existing agent instance");
                    }
                    if (okay && mAgent == null) {
                        if (RefactoredBackupManagerService.MORE_DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Need to launch agent for " + pkg);
                        }

                        try {
                            mTargetApp =
                                    backupManagerService.getPackageManager().getApplicationInfo(
                                            pkg, 0);

                            // If we haven't sent any data to this app yet, we probably
                            // need to clear it first.  Check that.
                            if (!mClearedPackages.contains(pkg)) {
                                // apps with their own backup agents are
                                // responsible for coherently managing a full
                                // restore.
                                if (mTargetApp.backupAgentName == null) {
                                    if (RefactoredBackupManagerService.DEBUG) {
                                        Slog.d(RefactoredBackupManagerService.TAG,
                                                "Clearing app data preparatory to full restore");
                                    }
                                    backupManagerService.clearApplicationDataSynchronous(pkg);
                                } else {
                                    if (RefactoredBackupManagerService.MORE_DEBUG) {
                                        Slog.d(RefactoredBackupManagerService.TAG, "backup agent ("
                                                + mTargetApp.backupAgentName + ") => no clear");
                                    }
                                }
                                mClearedPackages.add(pkg);
                            } else {
                                if (RefactoredBackupManagerService.MORE_DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "We've initialized this app already; no clear "
                                                    + "required");
                                }
                            }

                            // All set; now set up the IPC and launch the agent
                            setUpPipes();
                            mAgent = backupManagerService.bindToAgentSynchronous(mTargetApp,
                                    ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL);
                            mAgentPackage = pkg;
                        } catch (IOException e) {
                            // fall through to error handling
                        } catch (NameNotFoundException e) {
                            // fall through to error handling
                        }

                        if (mAgent == null) {
                            Slog.e(
                                    RefactoredBackupManagerService.TAG,
                                    "Unable to create agent for " + pkg);
                            okay = false;
                            tearDownPipes();
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }
                    }

                    // Sanity check: make sure we never give data to the wrong app.  This
                    // should never happen but a little paranoia here won't go amiss.
                    if (okay && !pkg.equals(mAgentPackage)) {
                        Slog.e(RefactoredBackupManagerService.TAG, "Restoring data for " + pkg
                                + " but agent is for " + mAgentPackage);
                        okay = false;
                    }

                    // At this point we have an agent ready to handle the full
                    // restore data as well as a pipe for sending data to
                    // that agent.  Tell the agent to start reading from the
                    // pipe.
                    if (okay) {
                        boolean agentSuccess = true;
                        long toCopy = info.size;
                        try {
                            backupManagerService.prepareOperationTimeout(token,
                                    RefactoredBackupManagerService.TIMEOUT_FULL_BACKUP_INTERVAL,
                                    mMonitorTask,
                                    RefactoredBackupManagerService.OP_TYPE_RESTORE_WAIT);

                            if (FullBackup.OBB_TREE_TOKEN.equals(info.domain)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Restoring OBB file for " + pkg
                                                    + " : " + info.path);
                                }
                                mObbConnection.restoreObbFile(pkg, mPipes[0],
                                        info.size, info.type, info.path, info.mode,
                                        info.mtime, token,
                                        backupManagerService.getBackupManagerBinder());
                            } else if (FullBackup.KEY_VALUE_DATA_TOKEN.equals(info.domain)) {
                                // This is only possible during adb restore.
                                // TODO: Refactor to clearly separate the flows.
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Restoring key-value file for " + pkg
                                                    + " : " + info.path);
                                }
                                KeyValueAdbRestoreEngine restoreEngine =
                                        new KeyValueAdbRestoreEngine(
                                                backupManagerService,
                                                backupManagerService.getDataDir(), info, mPipes[0],
                                                mAgent, token);
                                new Thread(restoreEngine, "restore-key-value-runner").start();
                            } else {
                                if (RefactoredBackupManagerService.MORE_DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Invoking agent to restore file "
                                                    + info.path);
                                }
                                // fire up the app's agent listening on the socket.  If
                                // the agent is running in the system process we can't
                                // just invoke it asynchronously, so we provide a thread
                                // for it here.
                                if (mTargetApp.processName.equals("system")) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "system process agent - spinning a thread");
                                    RestoreFileRunnable runner = new RestoreFileRunnable(
                                            backupManagerService, mAgent, info, mPipes[0], token);
                                    new Thread(runner, "restore-sys-runner").start();
                                } else {
                                    mAgent.doRestoreFile(mPipes[0], info.size, info.type,
                                            info.domain, info.path, info.mode, info.mtime,
                                            token, backupManagerService.getBackupManagerBinder());
                                }
                            }
                        } catch (IOException e) {
                            // couldn't dup the socket for a process-local restore
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Couldn't establish restore");
                            agentSuccess = false;
                            okay = false;
                        } catch (RemoteException e) {
                            // whoops, remote entity went away.  We'll eat the content
                            // ourselves, then, and not copy it over.
                            Slog.e(RefactoredBackupManagerService.TAG,
                                    "Agent crashed during full restore");
                            agentSuccess = false;
                            okay = false;
                        }

                        // Copy over the data if the agent is still good
                        if (okay) {
                            if (RefactoredBackupManagerService.MORE_DEBUG) {
                                Slog.v(RefactoredBackupManagerService.TAG,
                                        "  copying to restore agent: "
                                                + toCopy + " bytes");
                            }
                            boolean pipeOkay = true;
                            FileOutputStream pipe = new FileOutputStream(
                                    mPipes[1].getFileDescriptor());
                            while (toCopy > 0) {
                                int toRead = (toCopy > buffer.length)
                                        ? buffer.length : (int) toCopy;
                                int nRead = instream.read(buffer, 0, toRead);
                                if (nRead >= 0) {
                                    mBytes += nRead;
                                }
                                if (nRead <= 0) {
                                    break;
                                }
                                toCopy -= nRead;

                                // send it to the output pipe as long as things
                                // are still good
                                if (pipeOkay) {
                                    try {
                                        pipe.write(buffer, 0, nRead);
                                    } catch (IOException e) {
                                        Slog.e(RefactoredBackupManagerService.TAG,
                                                "Failed to write to restore pipe: "
                                                        + e.getMessage());
                                        pipeOkay = false;
                                    }
                                }
                            }

                            // done sending that file!  Now we just need to consume
                            // the delta from info.size to the end of block.
                            tarBackupReader.skipTarPadding(info.size);

                            // and now that we've sent it all, wait for the remote
                            // side to acknowledge receipt
                            agentSuccess = backupManagerService.waitUntilOperationComplete(token);
                        }

                        // okay, if the remote end failed at any point, deal with
                        // it by ignoring the rest of the restore on it
                        if (!agentSuccess) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Agent failure restoring " + pkg + "; ending restore");
                            backupManagerService.getBackupHandler().removeMessages(
                                    RefactoredBackupManagerService.MSG_RESTORE_OPERATION_TIMEOUT);
                            tearDownPipes();
                            tearDownAgent(mTargetApp);
                            mAgent = null;
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);

                            // If this was a single-package restore, we halt immediately
                            // with an agent error under these circumstances
                            if (onlyPackage != null) {
                                setResult(RestoreEngine.TARGET_FAILURE);
                                setRunning(false);
                                return false;
                            }
                        }
                    }

                    // Problems setting up the agent communication, an explicitly
                    // dropped file, or an already-ignored package: skip to the
                    // next stream entry by reading and discarding this file.
                    if (!okay) {
                        if (RefactoredBackupManagerService.MORE_DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG, "[discarding file content]");
                        }
                        long bytesToConsume = (info.size + 511) & ~511;
                        while (bytesToConsume > 0) {
                            int toRead = (bytesToConsume > buffer.length)
                                    ? buffer.length : (int) bytesToConsume;
                            long nRead = instream.read(buffer, 0, toRead);
                            if (nRead >= 0) {
                                mBytes += nRead;
                            }
                            if (nRead <= 0) {
                                break;
                            }
                            bytesToConsume -= nRead;
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (RefactoredBackupManagerService.DEBUG) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "io exception on restore socket read: " + e.getMessage());
            }
            setResult(RestoreEngine.TRANSPORT_FAILURE);
            info = null;
        }

        // If we got here we're either running smoothly or we've finished
        if (info == null) {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.i(RefactoredBackupManagerService.TAG,
                        "No [more] data for this package; tearing down");
            }
            tearDownPipes();
            setRunning(false);
            if (mustKillAgent) {
                tearDownAgent(mTargetApp);
            }
        }
        return (info != null);
    }

    void setUpPipes() throws IOException {
        mPipes = ParcelFileDescriptor.createPipe();
    }

    void tearDownPipes() {
        // Teardown might arise from the inline restore processing or from the asynchronous
        // timeout mechanism, and these might race.  Make sure we don't try to close and
        // null out the pipes twice.
        synchronized (this) {
            if (mPipes != null) {
                try {
                    mPipes[0].close();
                    mPipes[0] = null;
                    mPipes[1].close();
                    mPipes[1] = null;
                } catch (IOException e) {
                    Slog.w(RefactoredBackupManagerService.TAG, "Couldn't close agent pipes", e);
                }
                mPipes = null;
            }
        }
    }

    void tearDownAgent(ApplicationInfo app) {
        if (mAgent != null) {
            backupManagerService.tearDownAgentAndKill(app);
            mAgent = null;
        }
    }

    void handleTimeout() {
        tearDownPipes();
        setResult(RestoreEngine.TARGET_FAILURE);
        setRunning(false);
    }

    final RestoreInstallObserver mInstallObserver = new RestoreInstallObserver();
    final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();

    private static boolean isRestorableFile(FileMetadata info) {
        if (FullBackup.CACHE_TREE_TOKEN.equals(info.domain)) {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.i(RefactoredBackupManagerService.TAG, "Dropping cache file path " + info.path);
            }
            return false;
        }

        if (FullBackup.ROOT_TREE_TOKEN.equals(info.domain)) {
            // It's possible this is "no-backup" dir contents in an archive stream
            // produced on a device running a version of the OS that predates that
            // API.  Respect the no-backup intention and don't let the data get to
            // the app.
            if (info.path.startsWith("no_backup/")) {
                if (RefactoredBackupManagerService.MORE_DEBUG) {
                    Slog.i(RefactoredBackupManagerService.TAG,
                            "Dropping no_backup file path " + info.path);
                }
                return false;
            }
        }

        // Otherwise we think this file is good to go
        return true;
    }

    private static boolean isCanonicalFilePath(String path) {
        if (path.contains("..") || path.contains("//")) {
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.w(RefactoredBackupManagerService.TAG, "Dropping invalid path " + path);
            }
            return false;
        }

        return true;
    }

    void sendOnRestorePackage(String name) {
        if (mObserver != null) {
            try {
                // TODO: use a more user-friendly name string
                mObserver.onRestorePackage(name);
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: restorePackage");
                mObserver = null;
            }
        }
    }
}
