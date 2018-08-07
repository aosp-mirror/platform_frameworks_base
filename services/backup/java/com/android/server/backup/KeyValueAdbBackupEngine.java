package com.android.server.backup;

import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;

import static com.android.server.backup.BackupManagerService.OP_TYPE_BACKUP_WAIT;

import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SELinux;
import android.util.Slog;

import com.android.internal.util.Preconditions;
import com.android.server.backup.utils.FullBackupUtils;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Used by BackupManagerService to perform adb backup for key-value packages. At the moment this
 * class resembles what is done in the standard key-value code paths in BackupManagerService, and
 * should be unified later.
 *
 * TODO: We should create unified backup/restore engines that can be used for both transport and
 * adb backup/restore, and for fullbackup and key-value backup.
 */
public class KeyValueAdbBackupEngine {
    private static final String TAG = "KeyValueAdbBackupEngine";
    private static final boolean DEBUG = false;

    private static final String BACKUP_KEY_VALUE_DIRECTORY_NAME = "key_value_dir";
    private static final String BACKUP_KEY_VALUE_BLANK_STATE_FILENAME = "blank_state";
    private static final String BACKUP_KEY_VALUE_BACKUP_DATA_FILENAME_SUFFIX = ".data";
    private static final String BACKUP_KEY_VALUE_NEW_STATE_FILENAME_SUFFIX = ".new";

    private BackupManagerServiceInterface mBackupManagerService;
    private final PackageManager mPackageManager;
    private final OutputStream mOutput;
    private final PackageInfo mCurrentPackage;
    private final File mDataDir;
    private final File mStateDir;
    private final File mBlankStateName;
    private final File mBackupDataName;
    private final File mNewStateName;
    private final File mManifestFile;
    private ParcelFileDescriptor mSavedState;
    private ParcelFileDescriptor mBackupData;
    private ParcelFileDescriptor mNewState;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;

    public KeyValueAdbBackupEngine(OutputStream output, PackageInfo packageInfo,
            BackupManagerServiceInterface backupManagerService, PackageManager packageManager,
            File baseStateDir, File dataDir) {
        mOutput = output;
        mCurrentPackage = packageInfo;
        mBackupManagerService = backupManagerService;
        mPackageManager = packageManager;

        mDataDir = dataDir;
        mStateDir = new File(baseStateDir, BACKUP_KEY_VALUE_DIRECTORY_NAME);
        mStateDir.mkdirs();

        String pkg = mCurrentPackage.packageName;

        mBlankStateName = new File(mStateDir, BACKUP_KEY_VALUE_BLANK_STATE_FILENAME);
        mBackupDataName = new File(mDataDir,
                pkg + BACKUP_KEY_VALUE_BACKUP_DATA_FILENAME_SUFFIX);
        mNewStateName = new File(mStateDir,
                pkg + BACKUP_KEY_VALUE_NEW_STATE_FILENAME_SUFFIX);

        mManifestFile = new File(mDataDir, BackupManagerService.BACKUP_MANIFEST_FILENAME);
        mAgentTimeoutParameters = Preconditions.checkNotNull(
                backupManagerService.getAgentTimeoutParameters(),
                "Timeout parameters cannot be null");
    }

    public void backupOnePackage() throws IOException {
        ApplicationInfo targetApp = mCurrentPackage.applicationInfo;

        try {
            prepareBackupFiles(mCurrentPackage.packageName);

            IBackupAgent agent = bindToAgent(targetApp);

            if (agent == null) {
                // We failed binding to the agent, so ignore this package
                Slog.e(TAG, "Failed binding to BackupAgent for package "
                        + mCurrentPackage.packageName);
                return;
            }

            // We are bound to agent, initiate backup.
            if (!invokeAgentForAdbBackup(mCurrentPackage.packageName, agent)) {
                // Backup failed, skip package.
                Slog.e(TAG, "Backup Failed for package " + mCurrentPackage.packageName);
                return;
            }

            // Backup finished successfully. Copy the backup data to the output stream.
            writeBackupData();
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "Failed creating files for package " + mCurrentPackage.packageName
                    + " will ignore package. " + e);
        } finally {
            // We are either done, failed or have timed out, so do cleanup and kill the agent.
            cleanup();
        }
    }

    private void  prepareBackupFiles(String packageName) throws FileNotFoundException {

        // We pass a blank state to make sure we are getting the complete backup, not just an
        // increment
        mSavedState = ParcelFileDescriptor.open(mBlankStateName,
                MODE_READ_ONLY | MODE_CREATE);  // Make an empty file if necessary

        mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                MODE_READ_WRITE | MODE_CREATE | MODE_TRUNCATE);

        if (!SELinux.restorecon(mBackupDataName)) {
            Slog.e(TAG, "SELinux restorecon failed on " + mBackupDataName);
        }

        mNewState = ParcelFileDescriptor.open(mNewStateName,
                MODE_READ_WRITE | MODE_CREATE | MODE_TRUNCATE);
    }

    private IBackupAgent bindToAgent(ApplicationInfo targetApp) {
        try {
            return mBackupManagerService.bindToAgentSynchronous(targetApp,
                    ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL);
        } catch (SecurityException e) {
            Slog.e(TAG, "error in binding to agent for package " + targetApp.packageName
                    + ". " + e);
            return null;
        }
    }

    // Return true on backup success, false otherwise
    private boolean invokeAgentForAdbBackup(String packageName, IBackupAgent agent) {
        int token = mBackupManagerService.generateRandomIntegerToken();
        long kvBackupAgentTimeoutMillis = mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis();
        try {
            mBackupManagerService.prepareOperationTimeout(token, kvBackupAgentTimeoutMillis, null,
                    OP_TYPE_BACKUP_WAIT);

            // Start backup and wait for BackupManagerService to get callback for success or timeout
            agent.doBackup(
                    mSavedState, mBackupData, mNewState, Long.MAX_VALUE, token,
                    mBackupManagerService.getBackupManagerBinder(), /*transportFlags=*/ 0);
            if (!mBackupManagerService.waitUntilOperationComplete(token)) {
                Slog.e(TAG, "Key-value backup failed on package " + packageName);
                return false;
            }
            if (DEBUG) {
                Slog.i(TAG, "Key-value backup success for package " + packageName);
            }
            return true;
        } catch (RemoteException e) {
            Slog.e(TAG, "Error invoking agent for backup on " + packageName + ". " + e);
            return false;
        }
    }

    class KeyValueAdbBackupDataCopier implements Runnable {
        private final PackageInfo mPackage;
        private final ParcelFileDescriptor mPipe;
        private final int mToken;

        KeyValueAdbBackupDataCopier(PackageInfo pack, ParcelFileDescriptor pipe,
                int token)
                throws IOException {
            mPackage = pack;
            mPipe = ParcelFileDescriptor.dup(pipe.getFileDescriptor());
            mToken = token;
        }

        @Override
        public void run() {
            try {
                FullBackupDataOutput output = new FullBackupDataOutput(mPipe);

                if (DEBUG) {
                    Slog.d(TAG, "Writing manifest for " + mPackage.packageName);
                }
                FullBackupUtils.writeAppManifest(
                        mPackage, mPackageManager, mManifestFile, false, false);
                FullBackup.backupToTar(mPackage.packageName, FullBackup.KEY_VALUE_DATA_TOKEN, null,
                        mDataDir.getAbsolutePath(),
                        mManifestFile.getAbsolutePath(),
                        output);
                mManifestFile.delete();

                if (DEBUG) {
                    Slog.d(TAG, "Writing key-value package payload" + mPackage.packageName);
                }
                FullBackup.backupToTar(mPackage.packageName, FullBackup.KEY_VALUE_DATA_TOKEN, null,
                        mDataDir.getAbsolutePath(),
                        mBackupDataName.getAbsolutePath(),
                        output);

                // Write EOD marker
                try {
                    FileOutputStream out = new FileOutputStream(mPipe.getFileDescriptor());
                    byte[] buf = new byte[4];
                    out.write(buf);
                } catch (IOException e) {
                    Slog.e(TAG, "Unable to finalize backup stream!");
                }

                try {
                    mBackupManagerService.getBackupManagerBinder().opComplete(mToken, 0);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }

            } catch (IOException e) {
                Slog.e(TAG, "Error running full backup for " + mPackage.packageName + ". " + e);
            } finally {
                IoUtils.closeQuietly(mPipe);
            }
        }
    }

    private void writeBackupData() throws IOException {
        int token = mBackupManagerService.generateRandomIntegerToken();
        long kvBackupAgentTimeoutMillis = mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis();

        ParcelFileDescriptor[] pipes = null;
        try {
            pipes = ParcelFileDescriptor.createPipe();

            mBackupManagerService.prepareOperationTimeout(token, kvBackupAgentTimeoutMillis, null,
                    OP_TYPE_BACKUP_WAIT);

            // We will have to create a runnable that will read the manifest and backup data we
            // created, such that we can pipe the data into mOutput. The reason we do this is that
            // internally FullBackup.backupToTar is used, which will create the necessary file
            // header, but will also chunk the data. The method routeSocketDataToOutput in
            // BackupManagerService will dechunk the data, and append it to the TAR outputstream.
            KeyValueAdbBackupDataCopier runner = new KeyValueAdbBackupDataCopier(mCurrentPackage, pipes[1],
                    token);
            pipes[1].close();   // the runner has dup'd it
            pipes[1] = null;
            Thread t = new Thread(runner, "key-value-app-data-runner");
            t.start();

            // Now pull data from the app and stuff it into the output
            FullBackupUtils.routeSocketDataToOutput(pipes[0], mOutput);

            if (!mBackupManagerService.waitUntilOperationComplete(token)) {
                Slog.e(TAG, "Full backup failed on package " + mCurrentPackage.packageName);
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Full package backup success: " + mCurrentPackage.packageName);
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Error backing up " + mCurrentPackage.packageName + ": " + e);
        } finally {
            // flush after every package
            mOutput.flush();
            if (pipes != null) {
                IoUtils.closeQuietly(pipes[0]);
                IoUtils.closeQuietly(pipes[1]);
            }
        }
    }

    private void cleanup() {
        mBackupManagerService.tearDownAgentAndKill(mCurrentPackage.applicationInfo);
        mBlankStateName.delete();
        mNewStateName.delete();
        mBackupDataName.delete();
    }
}
