/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_IMG_LOCK;
import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_IMG_SYSTEM;
import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_LIVE_LOCK;
import static com.android.wallpaperbackup.WallpaperEventLogger.WALLPAPER_LIVE_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.WallpaperInfo;
import android.app.backup.BackupAnnotations;
import android.app.backup.BackupManager;
import android.app.backup.BackupRestoreEventLogger;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.service.wallpaper.WallpaperService;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class WallpaperEventLoggerTest {

    private BackupRestoreEventLogger mEventLogger;

    @Mock
    private BackupManager mMockBackupManager;

    @Mock
    private WallpaperBackupAgent mMockBackupAgent;

    private static final String WALLPAPER_ERROR = "some_error";

    private WallpaperEventLogger mWallpaperEventLogger;
    private WallpaperInfo mWallpaperInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockBackupAgent.getBackupRestoreEventLogger()).thenReturn(mEventLogger);
        when(mMockBackupManager.getBackupRestoreEventLogger(any())).thenReturn(mEventLogger);

        mWallpaperInfo = getWallpaperInfo();
        mWallpaperEventLogger = new WallpaperEventLogger(mMockBackupManager, mMockBackupAgent);
    }

    @Test
    public void onSystemImgWallpaperBackedUp_logsSuccess() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onLockImgWallpaperBackedUp_logsSuccess() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onLockImageWallpaperBackedUp();
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onSystemLiveWallpaperBackedUp_logsSuccess() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onSystemLiveWallpaperBackedUp(mWallpaperInfo);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onLockLiveWallpaperBackedUp_logsSuccess() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onLockLiveWallpaperBackedUp(mWallpaperInfo);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onImgWallpaperBackedUp_nullInfo_doesNotLogMetadata() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getMetadataHash()).isNull();
    }


    @Test
    public void onLiveWallpaperBackedUp_logsMetadata() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onSystemLiveWallpaperBackedUp(mWallpaperInfo);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getMetadataHash()).isNotNull();
    }


    @Test
    public void onSystemImgWallpaperBackupFailed_logsFail() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onSystemImageWallpaperBackupFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }

    @Test
    public void onLockImgWallpaperBackupFailed_logsFail() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onLockImageWallpaperBackupFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }


    @Test
    public void onSystemLiveWallpaperBackupFailed_logsFail() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onSystemLiveWallpaperBackupFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }

    @Test
    public void onLockLiveWallpaperBackupFailed_logsFail() {
        setUpLoggerForBackup();

        mWallpaperEventLogger.onLockLiveWallpaperBackupFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }


    @Test
    public void onWallpaperBackupException_someProcessed_doesNotLogErrorForProcessedType() {
        setUpLoggerForBackup();
        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();

        mWallpaperEventLogger.onBackupException(new Exception());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(0);
    }


    @Test
    public void onWallpaperBackupException_someProcessed_logsErrorForUnprocessedType() {
        setUpLoggerForBackup();
        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();

        mWallpaperEventLogger.onBackupException(new Exception());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
    }

    @Test
    public void onWallpaperBackupException_liveTypeProcessed_doesNotLogForSameImgType() {
        setUpLoggerForBackup();
        mWallpaperEventLogger.onSystemLiveWallpaperBackedUp(mWallpaperInfo);

        mWallpaperEventLogger.onBackupException(new Exception());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNull();
    }

    @Test
    public void onSystemImgWallpaperRestored_logsSuccess() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onSystemImageWallpaperRestored();
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onLockImgWallpaperRestored_logsSuccess() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onLockImageWallpaperRestored();
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onSystemLiveWallpaperRestored_logsSuccess() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onSystemLiveWallpaperRestored(mWallpaperInfo.getComponent());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onLockLiveWallpaperRestored_logsSuccess() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onLockLiveWallpaperRestored(mWallpaperInfo.getComponent());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getSuccessCount()).isEqualTo(1);
    }

    @Test
    public void onImgWallpaperRestored_nullInfo_doesNotLogMetadata() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onSystemImageWallpaperRestored();
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getMetadataHash()).isNull();
    }


    @Test
    public void onLiveWallpaperRestored_logsMetadata() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onSystemLiveWallpaperRestored(mWallpaperInfo.getComponent());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getMetadataHash()).isNotNull();
    }


    @Test
    public void onSystemImgWallpaperRestoreFailed_logsFail() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onSystemImageWallpaperRestoreFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }

    @Test
    public void onLockImgWallpaperRestoreFailed_logsFail() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onLockImageWallpaperRestoreFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }


    @Test
    public void onSystemLiveWallpaperRestoreFailed_logsFail() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onSystemLiveWallpaperRestoreFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }

    @Test
    public void onLockLiveWallpaperRestoreFailed_logsFail() {
        setUpLoggerForRestore();

        mWallpaperEventLogger.onLockLiveWallpaperRestoreFailed(WALLPAPER_ERROR);
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_LIVE_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getErrors()).containsKey(WALLPAPER_ERROR);
    }


    @Test
    public void onWallpaperRestoreException_someProcessed_doesNotLogErrorForProcessedType() {
        setUpLoggerForRestore();
        mWallpaperEventLogger.onSystemImageWallpaperRestored();

        mWallpaperEventLogger.onRestoreException(new Exception());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(0);
    }


    @Test
    public void onWallpaperRestoreException_someProcessed_logsErrorForUnprocessedType() {
        setUpLoggerForRestore();
        mWallpaperEventLogger.onSystemImageWallpaperRestored();

        mWallpaperEventLogger.onRestoreException(new Exception());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_LOCK);

        assertThat(result).isNotNull();
        assertThat(result.getFailCount()).isEqualTo(1);
    }

    @Test
    public void onWallpaperRestoreException_liveTypeProcessed_doesNotLogForSameImgType() {
        setUpLoggerForRestore();
        mWallpaperEventLogger.onSystemLiveWallpaperRestored(mWallpaperInfo.getComponent());

        mWallpaperEventLogger.onRestoreException(new Exception());
        BackupRestoreEventLogger.DataTypeResult result = getLogsForType(WALLPAPER_IMG_SYSTEM);

        assertThat(result).isNull();
    }

    private BackupRestoreEventLogger.DataTypeResult getLogsForType(String dataType) {
        for (BackupRestoreEventLogger.DataTypeResult result :  mEventLogger.getLoggingResults()) {
            if ((result.getDataType()).equals(dataType)) {
                return result;
            }
        }
        return null;
    }

    private void setUpLoggerForBackup() {
        mEventLogger = new BackupRestoreEventLogger(BackupAnnotations.OperationType.BACKUP);
        createEventLogger();
    }

    private void setUpLoggerForRestore() {
        mEventLogger = new BackupRestoreEventLogger(BackupAnnotations.OperationType.RESTORE);
        createEventLogger();
    }

    private void createEventLogger() {
        when(mMockBackupAgent.getBackupRestoreEventLogger()).thenReturn(mEventLogger);
        when(mMockBackupManager.getBackupRestoreEventLogger(any())).thenReturn(mEventLogger);

        mWallpaperEventLogger = new WallpaperEventLogger(mMockBackupManager, mMockBackupAgent);
    }


    private WallpaperInfo getWallpaperInfo() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        intent.setPackage("com.android.wallpaperbackup.tests");
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> result = pm.queryIntentServices(intent, PackageManager.GET_META_DATA);
        assertEquals(1, result.size());
        ResolveInfo info = result.get(0);
        return new WallpaperInfo(context, info);
    }
}
