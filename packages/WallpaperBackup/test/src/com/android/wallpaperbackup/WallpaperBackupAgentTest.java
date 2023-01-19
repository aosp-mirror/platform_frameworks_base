/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wallpaperbackup;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import static com.android.wallpaperbackup.WallpaperBackupAgent.LOCK_WALLPAPER_STAGE;
import static com.android.wallpaperbackup.WallpaperBackupAgent.SYSTEM_WALLPAPER_STAGE;
import static com.android.wallpaperbackup.WallpaperBackupAgent.WALLPAPER_INFO_STAGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WallpaperManager;
import android.app.backup.FullBackupDataOutput;
import android.content.ComponentName;
import android.content.Context;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.PackageMonitor;
import com.android.wallpaperbackup.utils.ContextWithServiceOverrides;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public class WallpaperBackupAgentTest {
    private static final String TEST_WALLPAPER_PACKAGE = "wallpaper_package";

    private static final int TEST_SYSTEM_WALLPAPER_ID = 1;
    private static final int TEST_LOCK_WALLPAPER_ID = 2;
    private static final int NO_LOCK_WALLPAPER_ID = -1;

    @Mock private FullBackupDataOutput mOutput;
    @Mock private WallpaperManager mWallpaperManager;
    @Mock private Context mMockContext;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private ContextWithServiceOverrides mContext;
    private IsolatedWallpaperBackupAgent mWallpaperBackupAgent;
    private ComponentName mWallpaperComponent;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_SYSTEM))).thenReturn(true);
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_LOCK))).thenReturn(true);

        mContext = new ContextWithServiceOverrides(ApplicationProvider.getApplicationContext());
        mContext.injectSystemService(WallpaperManager.class, mWallpaperManager);

        mWallpaperBackupAgent = new IsolatedWallpaperBackupAgent(mTemporaryFolder.getRoot());
        mWallpaperBackupAgent.attach(mContext);
        mWallpaperBackupAgent.onCreate();

        mWallpaperComponent = new ComponentName(TEST_WALLPAPER_PACKAGE, "");
    }

    @After
    public void tearDown() {
        FileUtils.deleteContents(mContext.getFilesDir());
    }

    @Test
    public void testOnFullBackup_backsUpEmptyFile() throws IOException {
        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertThat(getBackedUpFileOptional("empty").isPresent()).isTrue();
    }

    @Test
    public void testOnFullBackup_noExistingInfoStage_backsUpInfoFile() throws Exception {
        mockWallpaperInfoFileWithContents("fake info file");

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(WALLPAPER_INFO_STAGE).get(),
                "fake info file");
    }

    @Test
    public void testOnFullBackup_existingInfoStage_noChange_backsUpAlreadyStagedInfoFile()
            throws Exception {
        // Do a backup first so the info file is staged.
        mockWallpaperInfoFileWithContents("old info file");
        // Provide system and lock wallpapers but don't change them in between backups.
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockLockWallpaperFileWithContents("lock wallpaper");
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // This new wallpaper should be ignored since the ID of neither wallpaper changed.
        mockWallpaperInfoFileWithContents("new info file");

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(WALLPAPER_INFO_STAGE).get(),
                "old info file");
    }

    @Test
    public void testOnFullBackup_existingInfoStage_sysChanged_backsUpNewInfoFile()
            throws Exception {
        // Do a backup first so the backed up system wallpaper ID is persisted to disk.
        mockWallpaperInfoFileWithContents("old info file");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // Mock that the user changed the system wallpaper.
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID + 1, TEST_LOCK_WALLPAPER_ID);
        mockWallpaperInfoFileWithContents("new info file");

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(WALLPAPER_INFO_STAGE).get(),
                "new info file");
    }

    @Test
    public void testOnFullBackup_existingInfoStage_lockChanged_backsUpNewInfoFile()
            throws Exception {
        // Do a backup first so the backed up lock wallpaper ID is persisted to disk.
        mockWallpaperInfoFileWithContents("old info file");
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // Mock that the user changed the system wallpaper.
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID + 1);
        mockWallpaperInfoFileWithContents("new info file");

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(WALLPAPER_INFO_STAGE).get(),
                "new info file");
    }

    @Test
    public void testOnFullBackup_systemWallpaperNotEligible_doesNotBackUpSystemWallpaper()
            throws Exception {
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_SYSTEM))).thenReturn(false);
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, NO_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertThat(getBackedUpFileOptional(SYSTEM_WALLPAPER_STAGE).isPresent()).isFalse();
    }

    @Test
    public void testOnFullBackup_existingSystemStage_noSysChange_backsUpAlreadyStagedFile()
            throws Exception {
        // Do a backup first so that a stage file is created.
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, NO_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // This new file should be ignored since the ID of the wallpaper did not change.
        mockSystemWallpaperFileWithContents("new system wallpaper");

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(SYSTEM_WALLPAPER_STAGE).get(),
                "system wallpaper");
    }

    @Test
    public void testOnFullBackup_existingSystemStage_sysChanged_backsUpNewSystemWallpaper()
            throws Exception {
        // Do a backup first so that a stage file is created.
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, NO_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // Mock that the system wallpaper was changed by the user.
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID + 1, NO_LOCK_WALLPAPER_ID);
        mockSystemWallpaperFileWithContents("new system wallpaper");

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(SYSTEM_WALLPAPER_STAGE).get(),
                "new system wallpaper");
    }

    @Test
    public void testOnFullBackup_noExistingSystemStage_backsUpSystemWallpaper()
            throws Exception {
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, NO_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(SYSTEM_WALLPAPER_STAGE).get(),
                "system wallpaper");
    }

    @Test
    public void testOnFullBackup_lockWallpaperNotEligible_doesNotBackUpLockWallpaper()
            throws Exception {
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_LOCK))).thenReturn(false);
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertThat(getBackedUpFileOptional(LOCK_WALLPAPER_STAGE).isPresent()).isFalse();
    }

    @Test
    public void testOnFullBackup_existingLockStage_lockWallpaperRemovedByUser_NotBackUpOldStage()
            throws Exception {
        // Do a backup first so that a stage file is created.
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // Mock the ID of the lock wallpaper to indicate it's not set.
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, NO_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertThat(getBackedUpFileOptional(LOCK_WALLPAPER_STAGE).isPresent()).isFalse();
    }

    @Test
    public void testOnFullBackup_existingLockStage_lockWallpaperRemovedByUser_deletesExistingStage()
            throws Exception {
        // Do a backup first so that a stage file is created.
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // Mock the ID of the lock wallpaper to indicate it's not set.
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, NO_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertThat(new File(mContext.getFilesDir(), LOCK_WALLPAPER_STAGE).exists()).isFalse();
    }

    @Test
    public void testOnFullBackup_existingLockStage_noLockChange_backsUpAlreadyStagedFile()
            throws Exception {
        // Do a backup first so that a stage file is created.
        mockLockWallpaperFileWithContents("old lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // This new file should be ignored since the ID of the wallpaper did not change.
        mockLockWallpaperFileWithContents("new lock wallpaper");

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(LOCK_WALLPAPER_STAGE).get(),
                "old lock wallpaper");
    }

    @Test
    public void testOnFullBackup_existingLockStage_lockChanged_backsUpNewLockWallpaper()
            throws Exception {
        // Do a backup first so that a stage file is created.
        mockLockWallpaperFileWithContents("old lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        mWallpaperBackupAgent.onFullBackup(mOutput);
        mWallpaperBackupAgent.mBackedUpFiles.clear();
        // Mock that the lock wallpaper was changed by the user.
        mockLockWallpaperFileWithContents("new lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID + 1);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(LOCK_WALLPAPER_STAGE).get(),
                "new lock wallpaper");
    }

    @Test
    public void testOnFullBackup_noExistingLockStage_backsUpLockWallpaper()
            throws Exception {
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertFileContentEquals(getBackedUpFileOptional(LOCK_WALLPAPER_STAGE).get(),
                "lock wallpaper");
    }

    @Test
    public void testUpdateWallpaperComponent_doesApplyLater() throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = true;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ true);

        // Imitate wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(TEST_WALLPAPER_PACKAGE,
                /* uid */0);

        verify(mWallpaperManager, times(1)).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, times(1)).clear(eq(FLAG_LOCK));
    }

    @Test
    public void testUpdateWallpaperComponent_applyToLockFalse_doesApplyLaterOnlyToMainScreen()
            throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = true;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ false);

        // Imitate wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(TEST_WALLPAPER_PACKAGE,
                /* uid */0);

        verify(mWallpaperManager, times(1)).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, never()).clear(eq(FLAG_LOCK));
    }

    @Test
    public void testUpdateWallpaperComponent_deviceNotInRestore_doesNotApply()
            throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = false;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ true);

        // Imitate wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(TEST_WALLPAPER_PACKAGE,
                /* uid */0);

        verify(mWallpaperManager, never()).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, never()).clear(eq(FLAG_LOCK));
    }

    @Test
    public void testUpdateWallpaperComponent_differentPackageInstalled_doesNotApply()
            throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = false;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ true);

        // Imitate "wrong" wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(/* packageName */"",
                /* uid */0);

        verify(mWallpaperManager, never()).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, never()).clear(eq(FLAG_LOCK));
    }

    private void mockCurrentWallpaperIds(int systemWallpaperId, int lockWallpaperId) {
        when(mWallpaperManager.getWallpaperId(eq(FLAG_SYSTEM))).thenReturn(systemWallpaperId);
        when(mWallpaperManager.getWallpaperId(eq(FLAG_LOCK))).thenReturn(lockWallpaperId);
    }

    private File createTemporaryFileWithContentString(String contents) throws Exception {
        File file = mTemporaryFolder.newFile();
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(contents.getBytes());
        }
        return file;
    }

    private void assertFileContentEquals(File file, String expected) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            assertThat(new String(inputStream.readAllBytes())).isEqualTo(expected);
        }
    }

    private Optional<File> getBackedUpFileOptional(String fileName) {
        return mWallpaperBackupAgent.mBackedUpFiles.stream().filter(
                file -> file.getName().equals(fileName)).findFirst();
    }

    private void mockWallpaperInfoFileWithContents(String contents) throws Exception {
        File fakeInfoFile = createTemporaryFileWithContentString(contents);
        when(mWallpaperManager.getWallpaperInfoFile()).thenReturn(
                ParcelFileDescriptor.open(fakeInfoFile, MODE_READ_ONLY));
    }

    private void mockSystemWallpaperFileWithContents(String contents) throws Exception {
        File fakeSystemWallpaperFile = createTemporaryFileWithContentString(contents);
        when(mWallpaperManager.getWallpaperFile(eq(FLAG_SYSTEM), /* cropped = */
                eq(false))).thenReturn(
                ParcelFileDescriptor.open(fakeSystemWallpaperFile, MODE_READ_ONLY));
    }

    private void mockLockWallpaperFileWithContents(String contents) throws Exception {
        File fakeLockWallpaperFile = createTemporaryFileWithContentString(contents);
        when(mWallpaperManager.getWallpaperFile(eq(FLAG_LOCK), /* cropped = */
                eq(false))).thenReturn(
                ParcelFileDescriptor.open(fakeLockWallpaperFile, MODE_READ_ONLY));
    }

    private class IsolatedWallpaperBackupAgent extends WallpaperBackupAgent {
        File mWallpaperBaseDirectory;
        List<File> mBackedUpFiles = new ArrayList<>();
        PackageMonitor mWallpaperPackageMonitor;
        boolean mIsDeviceInRestore = false;

        IsolatedWallpaperBackupAgent(File wallpaperBaseDirectory) {
            mWallpaperBaseDirectory = wallpaperBaseDirectory;
        }

        @Override
        protected void backupFile(File file, FullBackupDataOutput data) {
            mBackedUpFiles.add(file);
        }

        @Override
        boolean servicePackageExists(ComponentName comp) {
            return false;
        }

        @Override
        boolean isDeviceInRestore() {
            return mIsDeviceInRestore;
        }

        @Override
        PackageMonitor getWallpaperPackageMonitor(ComponentName componentName,
                boolean applyToLock) {
            mWallpaperPackageMonitor = super.getWallpaperPackageMonitor(componentName, applyToLock);
            return mWallpaperPackageMonitor;
        }

        @Override
        public Context getBaseContext() {
            return mMockContext;
        }
    }
}
