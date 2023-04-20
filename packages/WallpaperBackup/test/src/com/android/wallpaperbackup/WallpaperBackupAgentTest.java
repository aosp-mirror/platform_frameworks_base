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
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_INELIGIBLE;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_NO_METADATA;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_NO_WALLPAPER;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_QUOTA_EXCEEDED;
import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_IMG_LOCK;
import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_IMG_SYSTEM;
import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_LIVE_LOCK;
import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_LIVE_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.backup.BackupAnnotations;
import android.app.backup.BackupRestoreEventLogger.DataTypeResult;
import android.app.backup.FullBackupDataOutput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.service.wallpaper.WallpaperService;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.PackageMonitor;
import com.android.modules.utils.TypedXmlSerializer;
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
    // An arbitrary user.
    private static final UserHandle USER_HANDLE = new UserHandle(15);

    @Mock
    private FullBackupDataOutput mOutput;
    @Mock
    private WallpaperManager mWallpaperManager;
    @Mock
    private Context mMockContext;

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

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

        mWallpaperBackupAgent = new IsolatedWallpaperBackupAgent();
        mWallpaperBackupAgent.attach(mContext);
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.BACKUP);

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

    @Test
    public void testOnFullBackup_systemWallpaperImgSuccess_logsSuccess() throws Exception {
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, NO_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void testOnFullBackup_systemWallpaperImgIneligible_logsFailure() throws Exception {
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_SYSTEM))).thenReturn(false);
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_INELIGIBLE);
    }

    @Test
    public void testOnFullBackup_systemWallpaperImgMissing_logsFailure() throws Exception {
        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_NO_WALLPAPER);
    }

    @Test
    public void testOnFullBackup_systemWallpaperImgMissingButHasLiveComponent_logsLiveSuccess()
            throws Exception {
        mockWallpaperInfoFileWithContents("info file");
        when(mWallpaperManager.getWallpaperInfo(anyInt())).thenReturn(getFakeWallpaperInfo());

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_LIVE_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getMetadataHash()).isNotNull();
    }

    @Test
    public void testOnFullBackup_systemWallpaperImgMissingButHasLiveComponent_logsNothingForImg()
            throws Exception {
        mockWallpaperInfoFileWithContents("info file");
        when(mWallpaperManager.getWallpaperInfo(anyInt())).thenReturn(getFakeWallpaperInfo());

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNull();
    }

    @Test
    public void testOnFullBackup_lockWallpaperImgSuccess_logsSuccess() throws Exception {
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void testOnFullBackup_lockWallpaperImgIneligible_logsFailure() throws Exception {
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_LOCK))).thenReturn(false);
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_INELIGIBLE);
    }

    @Test
    public void testOnFullBackup_lockWallpaperImgMissing_logsFailure() throws Exception {
        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_NO_WALLPAPER);
    }

    @Test
    public void testOnFullBackup_lockWallpaperImgMissingButHasLiveComponent_logsLiveSuccess()
            throws Exception {
        mockWallpaperInfoFileWithContents("info file");
        when(mWallpaperManager.getWallpaperInfo(anyInt())).thenReturn(getFakeWallpaperInfo());

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_LIVE_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getMetadataHash()).isNotNull();
    }

    @Test
    public void testOnFullBackup_lockWallpaperImgMissingButHasLiveComponent_logsNothingForImg()
            throws Exception {
        mockWallpaperInfoFileWithContents("info file");
        when(mWallpaperManager.getWallpaperInfo(anyInt())).thenReturn(getFakeWallpaperInfo());

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNull();
    }


    @Test
    public void testOnFullBackup_exceptionThrown_logsException() throws Exception {
        when(mWallpaperManager.isWallpaperBackupEligible(anyInt())).thenThrow(
                new RuntimeException());
        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(RuntimeException.class.getName());
    }

    @Test
    public void testOnFullBackup_lastBackupOverQuota_logsLockFailure() throws Exception {
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        markAgentAsOverQuota();

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_QUOTA_EXCEEDED);
    }

    @Test
    public void testOnFullBackup_lastBackupOverQuota_logsSystemSuccess() throws Exception {
        mockSystemWallpaperFileWithContents("system wallpaper");
        mockLockWallpaperFileWithContents("lock wallpaper");
        mockCurrentWallpaperIds(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        markAgentAsOverQuota();

        mWallpaperBackupAgent.onFullBackup(mOutput);

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void testOnRestore_systemWallpaperImgSuccess_logsSuccess() throws Exception {
        mockStagedWallpaperFile(WALLPAPER_INFO_STAGE);
        mockStagedWallpaperFile(SYSTEM_WALLPAPER_STAGE);
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.RESTORE);

        mWallpaperBackupAgent.onRestoreFinished();

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void testOnRestore_lockWallpaperImgSuccess_logsSuccess() throws Exception {
        mockStagedWallpaperFile(WALLPAPER_INFO_STAGE);
        mockStagedWallpaperFile(LOCK_WALLPAPER_STAGE);
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.RESTORE);

        mWallpaperBackupAgent.onRestoreFinished();

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void testOnRestore_systemWallpaperImgMissingAndNoLive_logsFailure() throws Exception {
        mockStagedWallpaperFile(WALLPAPER_INFO_STAGE);
        mockStagedWallpaperFile(LOCK_WALLPAPER_STAGE);
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.RESTORE);

        mWallpaperBackupAgent.onRestoreFinished();

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_NO_WALLPAPER);

    }

    @Test
    public void testOnRestore_lockWallpaperImgMissingAndNoLive_logsFailure() throws Exception {
        mockStagedWallpaperFile(WALLPAPER_INFO_STAGE);
        mockStagedWallpaperFile(SYSTEM_WALLPAPER_STAGE);
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.RESTORE);

        mWallpaperBackupAgent.onRestoreFinished();

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_NO_WALLPAPER);
    }

    @Test
    public void testOnRestore_wallpaperInfoMissing_logsFailure() throws Exception {
        mockStagedWallpaperFile(SYSTEM_WALLPAPER_STAGE);
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.RESTORE);

        mWallpaperBackupAgent.onRestoreFinished();

        DataTypeResult result = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(ERROR_NO_METADATA);
    }

    @Test
    public void testOnRestore_imgMissingButWallpaperInfoHasLive_doesNotLogImg() throws Exception {
        mockRestoredLiveWallpaperFile();
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.RESTORE);

        mWallpaperBackupAgent.onRestoreFinished();

        DataTypeResult system = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        DataTypeResult lock = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(system).isNull();
        assertThat(lock).isNull();
    }

    @Test
    public void testOnRestore_throwsException_logsErrors() throws Exception {
        when(mWallpaperManager.setStream(any(), any(), anyBoolean(), anyInt())).thenThrow(
                new RuntimeException());
        mockStagedWallpaperFile(SYSTEM_WALLPAPER_STAGE);
        mockStagedWallpaperFile(WALLPAPER_INFO_STAGE);
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.RESTORE);

        mWallpaperBackupAgent.onRestoreFinished();

        DataTypeResult system = getLoggingResult(WALLPAPER_IMG_SYSTEM,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        DataTypeResult lock = getLoggingResult(WALLPAPER_IMG_LOCK,
                mWallpaperBackupAgent.getBackupRestoreEventLogger().getLoggingResults());
        assertThat(system).isNotNull();
        assertThat(system.getFailCount()).isEqualTo(1);
        assertThat(system.getErrors()).containsKey(RuntimeException.class.getName());
        assertThat(lock).isNotNull();
        assertThat(lock.getFailCount()).isEqualTo(1);
        assertThat(lock.getErrors()).containsKey(RuntimeException.class.getName());
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

    private void mockStagedWallpaperFile(String location) throws Exception {
        File wallpaperFile = new File(mContext.getFilesDir(), location);
        wallpaperFile.createNewFile();
    }

    private void mockRestoredLiveWallpaperFile() throws Exception {
        File wallpaperFile = new File(mContext.getFilesDir(), WALLPAPER_INFO_STAGE);
        wallpaperFile.createNewFile();
        FileOutputStream fstream = new FileOutputStream(wallpaperFile, false);
        TypedXmlSerializer out = Xml.resolveSerializer(fstream);
        out.startDocument(null, true);
        out.startTag(null, "wp");
        out.attribute(null, "component",
                getFakeWallpaperInfo().getComponent().flattenToShortString());
        out.endTag(null, "wp");
        out.endDocument();
        fstream.flush();
        FileUtils.sync(fstream);
        fstream.close();
    }

    private WallpaperInfo getFakeWallpaperInfo() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        intent.setPackage("com.android.wallpaperbackup.tests");
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> result = pm.queryIntentServices(intent, PackageManager.GET_META_DATA);
        assertEquals(1, result.size());
        ResolveInfo info = result.get(0);
        return new WallpaperInfo(context, info);
    }

    private void markAgentAsOverQuota() throws Exception {
        // Create over quota file to indicate the last backup was over quota
        File quotaFile = new File(mContext.getFilesDir(), WallpaperBackupAgent.QUOTA_SENTINEL);
        quotaFile.createNewFile();

        // Now redo the setup of the agent to pick up the over quota
        mWallpaperBackupAgent.onCreate(USER_HANDLE, BackupAnnotations.BackupDestination.CLOUD,
                BackupAnnotations.OperationType.BACKUP);
    }

    private static DataTypeResult getLoggingResult(String dataType, List<DataTypeResult> results) {
        for (DataTypeResult result : results) {
            if ((result.getDataType()).equals(dataType)) {
                return result;
            }
        }
        return null;
    }

    private class IsolatedWallpaperBackupAgent extends WallpaperBackupAgent {
        List<File> mBackedUpFiles = new ArrayList<>();
        PackageMonitor mWallpaperPackageMonitor;
        boolean mIsDeviceInRestore = false;

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
