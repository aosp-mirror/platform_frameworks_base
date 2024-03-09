/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.backup;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;

import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PackageManagerBackupAgentTest {

    private static final String EXISTING_PACKAGE_NAME = "com.android.wallpaperbackup";
    private static final int USER_ID = 0;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private PackageManagerBackupAgent mPackageManagerBackupAgent;
    private ImmutableList<PackageInfo> mPackages;
    private File mBackupData, mOldState, mNewState;

    @Before
    public void setUp() throws Exception {
        PackageManager packageManager = getApplicationContext().getPackageManager();

        PackageInfo existingPackageInfo =
                packageManager.getPackageInfoAsUser(
                        EXISTING_PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES, USER_ID);
        mPackages = ImmutableList.of(existingPackageInfo);
        mPackageManagerBackupAgent =
                new PackageManagerBackupAgent(packageManager, mPackages, USER_ID);

        mBackupData = folder.newFile("backup_data");
        mOldState = folder.newFile("old_state");
        mNewState = folder.newFile("new_state");
    }

    @Test
    public void onBackup_noState_backsUpEverything() throws Exception {
        // no setup needed

        runBackupAgentOnBackup();

        // key/values should be written to backup data
        ImmutableMap<String, Optional<ByteBuffer>> keyValues = getKeyValues(mBackupData);
        assertThat(keyValues.keySet())
                .containsExactly(
                        PackageManagerBackupAgent.ANCESTRAL_RECORD_KEY,
                        PackageManagerBackupAgent.GLOBAL_METADATA_KEY,
                        EXISTING_PACKAGE_NAME)
                .inOrder();
        // new state must not be empty
        assertThat(mNewState.length()).isGreaterThan(0);
    }

    @Test
    public void onBackup_recentState_backsUpNothing() throws Exception {
        try (ParcelFileDescriptor oldStateDescriptor = openForWriting(mOldState)) {
            PackageManagerBackupAgent.writeStateFile(mPackages, oldStateDescriptor);
        }

        runBackupAgentOnBackup();

        // We shouldn't have written anything, but a known issue is that we always write the
        // ancestral record version.
        ImmutableMap<String, Optional<ByteBuffer>> keyValues = getKeyValues(mBackupData);
        assertThat(keyValues.keySet())
                .containsExactly(PackageManagerBackupAgent.ANCESTRAL_RECORD_KEY);
        assertThat(mNewState.length()).isGreaterThan(0);
        assertThat(mNewState.length()).isEqualTo(mOldState.length());
    }

    @Test
    public void onBackup_oldState_backsUpChanges() throws Exception {
        String uninstalledPackageName = "does.not.exist";
        try (ParcelFileDescriptor oldStateDescriptor = openForWriting(mOldState)) {
            PackageManagerBackupAgent.writeStateFile(
                    ImmutableList.of(createPackage(uninstalledPackageName, 1)), oldStateDescriptor);
        }

        runBackupAgentOnBackup();

        // Note that uninstalledPackageName should not exist, i.e. it did not get deleted.
        ImmutableMap<String, Optional<ByteBuffer>> keyValues = getKeyValues(mBackupData);
        assertThat(keyValues.keySet())
                .containsExactly(
                        PackageManagerBackupAgent.ANCESTRAL_RECORD_KEY, EXISTING_PACKAGE_NAME);
        assertThat(mNewState.length()).isGreaterThan(0);
    }

    @Test
    public void onBackup_legacyState_backsUpEverything() throws Exception {
        String uninstalledPackageName = "does.not.exist";
        writeLegacyStateFile(
                mOldState,
                ImmutableList.of(createPackage(uninstalledPackageName, 1), mPackages.getFirst()));

        runBackupAgentOnBackup();

        ImmutableMap<String, Optional<ByteBuffer>> keyValues = getKeyValues(mBackupData);
        assertThat(keyValues.keySet())
                .containsExactly(
                        PackageManagerBackupAgent.ANCESTRAL_RECORD_KEY,
                        PackageManagerBackupAgent.GLOBAL_METADATA_KEY,
                        EXISTING_PACKAGE_NAME);
        assertThat(mNewState.length()).isGreaterThan(0);
    }

    @Test
    public void onRestore_recentBackup_restoresBackup() throws Exception {
        runBackupAgentOnBackup();

        runBackupAgentOnRestore();

        assertThat(mPackageManagerBackupAgent.getRestoredPackages())
                .containsExactly(EXISTING_PACKAGE_NAME);
        // onRestore does not write to newState
        assertThat(mNewState.length()).isEqualTo(0);
    }

    @Test
    public void onRestore_legacyBackup_restoresBackup() throws Exception {
        // A legacy backup is one without an ancestral record version. Ancestral record versions
        // are always written however, so we'll need to delete it from the backup data before
        // restoring.
        runBackupAgentOnBackup();
        deleteKeyFromBackupData(mBackupData, PackageManagerBackupAgent.ANCESTRAL_RECORD_KEY);

        runBackupAgentOnRestore();

        assertThat(mPackageManagerBackupAgent.getRestoredPackages())
                .containsExactly(EXISTING_PACKAGE_NAME);
        // onRestore does not write to newState
        assertThat(mNewState.length()).isEqualTo(0);
    }

    private void runBackupAgentOnBackup() throws Exception {
        try (ParcelFileDescriptor oldStateDescriptor = openForReading(mOldState);
                ParcelFileDescriptor backupDataDescriptor = openForWriting(mBackupData);
                ParcelFileDescriptor newStateDescriptor = openForWriting(mNewState)) {
            mPackageManagerBackupAgent.onBackup(
                    oldStateDescriptor,
                    new BackupDataOutput(backupDataDescriptor.getFileDescriptor()),
                    newStateDescriptor);
        }
    }

    private void runBackupAgentOnRestore() throws Exception {
        try (ParcelFileDescriptor backupDataDescriptor = openForReading(mBackupData);
                ParcelFileDescriptor newStateDescriptor = openForWriting(mNewState)) {
            mPackageManagerBackupAgent.onRestore(
                    new BackupDataInput(backupDataDescriptor.getFileDescriptor()),
                    /* appVersionCode= */ 0,
                    newStateDescriptor);
        }
    }

    private void deleteKeyFromBackupData(File backupData, String key) throws Exception {
        File temporaryBackupData = folder.newFile("backup_data.tmp");
        try (ParcelFileDescriptor inputDescriptor = openForReading(backupData);
                ParcelFileDescriptor outputDescriptor = openForWriting(temporaryBackupData); ) {
            BackupDataInput input = new BackupDataInput(inputDescriptor.getFileDescriptor());
            BackupDataOutput output = new BackupDataOutput(outputDescriptor.getFileDescriptor());
            while (input.readNextHeader()) {
                if (input.getKey().equals(key)) {
                    if (input.getDataSize() > 0) {
                        input.skipEntityData();
                    }
                    continue;
                }
                output.writeEntityHeader(input.getKey(), input.getDataSize());
                if (input.getDataSize() < 0) {
                    input.skipEntityData();
                } else {
                    byte[] buf = new byte[input.getDataSize()];
                    input.readEntityData(buf, 0, buf.length);
                    output.writeEntityData(buf, buf.length);
                }
            }
        }
        assertThat(temporaryBackupData.renameTo(backupData)).isTrue();
    }

    private static PackageInfo createPackage(String name, int versionCode) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = name;
        packageInfo.versionCodeMajor = versionCode;
        return packageInfo;
    }

    /** This creates a legacy state file in which {@code STATE_FILE_HEADER} was not yet present. */
    private static void writeLegacyStateFile(File stateFile, ImmutableList<PackageInfo> packages)
            throws Exception {
        try (ParcelFileDescriptor stateFileDescriptor = openForWriting(stateFile);
                DataOutputStream out =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        new FileOutputStream(
                                                stateFileDescriptor.getFileDescriptor())))) {
            out.writeUTF(PackageManagerBackupAgent.GLOBAL_METADATA_KEY);
            out.writeInt(Build.VERSION.SDK_INT);
            out.writeUTF(Build.VERSION.INCREMENTAL);

            // now write all the app names + versions
            for (PackageInfo pkg : packages) {
                out.writeUTF(pkg.packageName);
                out.writeInt(pkg.versionCode);
            }
            out.flush();
        }
    }

    /**
     * Reads the given backup data file and returns a map of key-value pairs. The value is a {@link
     * ByteBuffer} wrapped in an {@link Optional}, where the empty {@link Optional} represents a key
     * deletion.
     */
    private static ImmutableMap<String, Optional<ByteBuffer>> getKeyValues(File backupData)
            throws Exception {
        ImmutableMap.Builder<String, Optional<ByteBuffer>> builder = ImmutableMap.builder();
        try (ParcelFileDescriptor backupDataDescriptor = openForReading(backupData)) {
            BackupDataInput backupDataInput =
                    new BackupDataInput(backupDataDescriptor.getFileDescriptor());
            while (backupDataInput.readNextHeader()) {
                ByteBuffer value = null;
                if (backupDataInput.getDataSize() >= 0) {
                    byte[] val = new byte[backupDataInput.getDataSize()];
                    backupDataInput.readEntityData(val, 0, val.length);
                    value = ByteBuffer.wrap(val);
                }
                builder.put(backupDataInput.getKey(), Optional.ofNullable(value));
            }
        }
        return builder.build();
    }

    private static ParcelFileDescriptor openForWriting(File file) throws Exception {
        return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY);
    }

    private static ParcelFileDescriptor openForReading(File file) throws Exception {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
