package com.android.server.backup;

import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;

import android.app.IBackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackup;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Used by BackupManagerService to perform adb restore for key-value packages. At the moment this
 * class resembles what is done in the standard key-value code paths in BackupManagerService, and
 * should be unified later.
 *
 * TODO: We should create unified backup/restore engines that can be used for both transport and
 * adb backup/restore, and for fullbackup and key-value backup.
 */
public class KeyValueAdbRestoreEngine implements Runnable {
    private static final String TAG = "KeyValueAdbRestoreEngine";
    private static final boolean DEBUG = false;

    private final UserBackupManagerService mBackupManagerService;
    private final File mDataDir;

    private final FileMetadata mInfo;
    private final ParcelFileDescriptor mInFD;
    private final IBackupAgent mAgent;
    private final int mToken;

    public KeyValueAdbRestoreEngine(UserBackupManagerService backupManagerService,
            File dataDir, FileMetadata info, ParcelFileDescriptor inFD, IBackupAgent agent,
            int token) {
        mBackupManagerService = backupManagerService;
        mDataDir = dataDir;
        mInfo = info;
        mInFD = inFD;
        mAgent = agent;
        mToken = token;
    }

    @Override
    public void run() {
        try {
            File restoreData = prepareRestoreData(mInfo, mInFD);

            invokeAgentForAdbRestore(mAgent, mInfo, restoreData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File prepareRestoreData(FileMetadata info, ParcelFileDescriptor inFD) throws IOException {
        String pkg = info.packageName;
        File restoreDataName = new File(mDataDir, pkg + ".restore");
        File sortedDataName = new File(mDataDir, pkg + ".sorted");

        FullBackup.restoreFile(inFD, info.size, info.type, info.mode, info.mtime, restoreDataName);

        // Sort the keys, as the BackupAgent expect them to come in lexicographical order
        sortKeyValueData(restoreDataName, sortedDataName);
        return sortedDataName;
    }

    private void invokeAgentForAdbRestore(IBackupAgent agent, FileMetadata info, File restoreData)
            throws IOException {
        String pkg = info.packageName;
        File newStateName = new File(mDataDir, pkg + ".new");
        try {
            ParcelFileDescriptor backupData =
                    ParcelFileDescriptor.open(restoreData, MODE_READ_ONLY);
            ParcelFileDescriptor newState = ParcelFileDescriptor.open(newStateName,
                    MODE_READ_WRITE | MODE_CREATE | MODE_TRUNCATE);

            if (DEBUG) {
                Slog.i(TAG, "Starting restore of package " + pkg + " for version code "
                        + info.version);
            }
            agent.doRestore(backupData, info.version, newState, mToken,
                    mBackupManagerService.getBackupManagerBinder());
        } catch (IOException e) {
            Slog.e(TAG, "Exception opening file. " + e);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception calling doRestore on agent: " + e);
        }
    }

    private void sortKeyValueData (File restoreData, File sortedData) throws IOException {
        FileInputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            inputStream = new FileInputStream(restoreData);
            outputStream = new FileOutputStream(sortedData);
            BackupDataInput reader = new BackupDataInput(inputStream.getFD());
            BackupDataOutput writer = new BackupDataOutput(outputStream.getFD());
            copyKeysInLexicalOrder(reader, writer);
        } finally {
            if (inputStream != null) {
                IoUtils.closeQuietly(inputStream);
            }
            if (outputStream != null) {
                IoUtils.closeQuietly(outputStream);
            }
        }
    }

    private void copyKeysInLexicalOrder(BackupDataInput in, BackupDataOutput out)
            throws IOException {
        Map<String, byte[]> data = new HashMap<>();
        while (in.readNextHeader()) {
            String key = in.getKey();
            int size = in.getDataSize();
            if (size < 0) {
                in.skipEntityData();
                continue;
            }
            byte[] value = new byte[size];
            in.readEntityData(value, 0, size);
            data.put(key, value);
        }
        List<String> keys = new ArrayList<>(data.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            byte[] value = data.get(key);
            out.writeEntityHeader(key, value.length);
            out.writeEntityData(value, value.length);
        }
    }
}
