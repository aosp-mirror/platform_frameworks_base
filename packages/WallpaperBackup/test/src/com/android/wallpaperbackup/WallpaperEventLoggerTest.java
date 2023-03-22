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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WallpaperInfo;
import android.app.backup.BackupManager;
import android.app.backup.BackupRestoreEventLogger;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.service.wallpaper.WallpaperService;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.wallpaperbackup.utils.TestWallpaperService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class WallpaperEventLoggerTest {

    @Mock
    private BackupRestoreEventLogger mMockLogger;

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

        when(mMockBackupAgent.getBackupRestoreEventLogger()).thenReturn(mMockLogger);
        when(mMockBackupManager.getBackupRestoreEventLogger(any())).thenReturn(mMockLogger);

        mWallpaperInfo = getWallpaperInfo();
        mWallpaperEventLogger = new WallpaperEventLogger(mMockBackupManager, mMockBackupAgent);
    }

    @Test
    public void onSystemImgWallpaperBackedUp_logsSuccess() {
        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();

        verify(mMockLogger).logItemsBackedUp(eq(WALLPAPER_IMG_SYSTEM), eq(1));
    }

    @Test
    public void onLockImgWallpaperBackedUp_logsSuccess() {
        mWallpaperEventLogger.onLockImageWallpaperBackedUp();

        verify(mMockLogger).logItemsBackedUp(eq(WALLPAPER_IMG_LOCK), eq(1));
    }

    @Test
    public void onSystemLiveWallpaperBackedUp_logsSuccess() {
        mWallpaperEventLogger.onSystemLiveWallpaperBackedUp(mWallpaperInfo);

        verify(mMockLogger).logItemsBackedUp(eq(WALLPAPER_LIVE_SYSTEM), eq(1));
    }

    @Test
    public void onLockLiveWallpaperBackedUp_logsSuccess() {
        mWallpaperEventLogger.onLockLiveWallpaperBackedUp(mWallpaperInfo);

        verify(mMockLogger).logItemsBackedUp(eq(WALLPAPER_LIVE_LOCK), eq(1));
    }

    @Test
    public void onImgWallpaperBackedUp_nullInfo_doesNotLogMetadata() {
        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();

        verify(mMockLogger, never()).logBackupMetadata(eq(WALLPAPER_IMG_SYSTEM), anyString());
    }


    @Test
    public void onLiveWallpaperBackedUp_logsMetadata() {
        mWallpaperEventLogger.onSystemLiveWallpaperBackedUp(mWallpaperInfo);

        verify(mMockLogger).logBackupMetadata(eq(WALLPAPER_LIVE_SYSTEM),
                eq(TestWallpaperService.class.getName()));
    }


    @Test
    public void onSystemImgWallpaperBackupFailed_logsFail() {
        mWallpaperEventLogger.onSystemImageWallpaperBackupFailed(WALLPAPER_ERROR);

        verify(mMockLogger).logItemsBackupFailed(eq(WALLPAPER_IMG_SYSTEM), eq(1),
                eq(WALLPAPER_ERROR));
    }

    @Test
    public void onLockImgWallpaperBackupFailed_logsFail() {
        mWallpaperEventLogger.onLockImageWallpaperBackupFailed(WALLPAPER_ERROR);

        verify(mMockLogger).logItemsBackupFailed(eq(WALLPAPER_IMG_LOCK), eq(1),
                eq(WALLPAPER_ERROR));
    }


    @Test
    public void onSystemLiveWallpaperBackupFailed_logsFail() {
        mWallpaperEventLogger.onSystemLiveWallpaperBackupFailed(WALLPAPER_ERROR);

        verify(mMockLogger).logItemsBackupFailed(eq(WALLPAPER_LIVE_SYSTEM), eq(1),
                eq(WALLPAPER_ERROR));
    }

    @Test
    public void onLockLiveWallpaperBackupFailed_logsFail() {
        mWallpaperEventLogger.onLockLiveWallpaperBackupFailed(WALLPAPER_ERROR);

        verify(mMockLogger).logItemsBackupFailed(eq(WALLPAPER_LIVE_LOCK), eq(1),
                eq(WALLPAPER_ERROR));
    }


    @Test
    public void onWallpaperBackupException_someProcessed_doesNotLogErrorForProcessedType() {
        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();

        mWallpaperEventLogger.onBackupException(new Exception());

        verify(mMockLogger, never()).logItemsBackupFailed(eq(WALLPAPER_IMG_SYSTEM), anyInt(),
                anyString());
    }


    @Test
    public void onWallpaperBackupException_someProcessed_logsErrorForUnprocessedType() {
        mWallpaperEventLogger.onSystemImageWallpaperBackedUp();

        mWallpaperEventLogger.onBackupException(new Exception());

        verify(mMockLogger).logItemsBackupFailed(eq(WALLPAPER_IMG_LOCK), eq(1),
                eq(Exception.class.getName()));

    }

    @Test
    public void onWallpaperBackupException_liveTypeProcessed_doesNotLogErrorForSameImgType() {
        mWallpaperEventLogger.onSystemLiveWallpaperBackedUp(mWallpaperInfo);

        mWallpaperEventLogger.onBackupException(new Exception());

        verify(mMockLogger, never()).logItemsBackupFailed(eq(WALLPAPER_IMG_SYSTEM), anyInt(),
                anyString());
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
