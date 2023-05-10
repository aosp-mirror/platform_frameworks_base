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

import android.annotation.Nullable;
import android.app.WallpaperInfo;
import android.app.backup.BackupManager;
import android.app.backup.BackupRestoreEventLogger;
import android.app.backup.BackupRestoreEventLogger.BackupRestoreDataType;
import android.app.backup.BackupRestoreEventLogger.BackupRestoreError;
import android.content.ComponentName;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Set;

/**
 * Log backup / restore related events using {@link BackupRestoreEventLogger}.
 */
public class WallpaperEventLogger {
    /* Static image used as system (or home) screen wallpaper */
    @BackupRestoreDataType
    @VisibleForTesting
    static final String WALLPAPER_IMG_SYSTEM = "wlp_img_system";

    /* Static image used as lock screen wallpaper */
    @BackupRestoreDataType
    @VisibleForTesting
    static final String WALLPAPER_IMG_LOCK = "wlp_img_lock";

    /* Live component used as system (or home) screen wallpaper */
    @BackupRestoreDataType
    @VisibleForTesting
    static final String WALLPAPER_LIVE_SYSTEM = "wlp_live_system";

    /* Live component used as lock screen wallpaper */
    @BackupRestoreDataType
    @VisibleForTesting
    static final String WALLPAPER_LIVE_LOCK = "wlp_live_lock";

    @BackupRestoreError
    static final String ERROR_INELIGIBLE = "ineligible";
    @BackupRestoreError
    static final String ERROR_NO_METADATA = "no_metadata";
    @BackupRestoreError
    static final String ERROR_NO_WALLPAPER = "no_wallpaper";
    @BackupRestoreError
    static final String ERROR_QUOTA_EXCEEDED = "quota_exceeded";

    private final BackupRestoreEventLogger mLogger;

    private final Set<String> mProcessedDataTypes = new HashSet<>();

    WallpaperEventLogger(BackupManager backupManager, WallpaperBackupAgent wallpaperAgent) {
        mLogger = backupManager.getBackupRestoreEventLogger(/* backupAgent */ wallpaperAgent);
    }

    void onSystemImageWallpaperBackedUp() {
        logBackupSuccessInternal(WALLPAPER_IMG_SYSTEM, /* liveComponentWallpaperInfo */ null);
    }

    void onLockImageWallpaperBackedUp() {
        logBackupSuccessInternal(WALLPAPER_IMG_LOCK, /* liveComponentWallpaperInfo */ null);
    }

    void onSystemLiveWallpaperBackedUp(WallpaperInfo wallpaperInfo) {
        logBackupSuccessInternal(WALLPAPER_LIVE_SYSTEM, wallpaperInfo);
    }

    void onLockLiveWallpaperBackedUp(WallpaperInfo wallpaperInfo) {
        logBackupSuccessInternal(WALLPAPER_LIVE_LOCK, wallpaperInfo);
    }

    void onSystemImageWallpaperBackupFailed(@BackupRestoreError String error) {
        logBackupFailureInternal(WALLPAPER_IMG_SYSTEM, error);
    }

    void onLockImageWallpaperBackupFailed(@BackupRestoreError String error) {
        logBackupFailureInternal(WALLPAPER_IMG_LOCK, error);
    }

    void onSystemLiveWallpaperBackupFailed(@BackupRestoreError String error) {
        logBackupFailureInternal(WALLPAPER_LIVE_SYSTEM, error);
    }

    void onLockLiveWallpaperBackupFailed(@BackupRestoreError String error) {
        logBackupFailureInternal(WALLPAPER_LIVE_LOCK, error);
    }

    void onSystemImageWallpaperRestored() {
        logRestoreSuccessInternal(WALLPAPER_IMG_SYSTEM, /* liveComponentWallpaperInfo */ null);
    }

    void onLockImageWallpaperRestored() {
        logRestoreSuccessInternal(WALLPAPER_IMG_LOCK, /* liveComponentWallpaperInfo */ null);
    }

    void onSystemLiveWallpaperRestored(ComponentName wpService) {
        logRestoreSuccessInternal(WALLPAPER_LIVE_SYSTEM, wpService);
    }

    void onLockLiveWallpaperRestored(ComponentName wpService) {
        logRestoreSuccessInternal(WALLPAPER_LIVE_LOCK, wpService);
    }

    void onSystemImageWallpaperRestoreFailed(@BackupRestoreError String error) {
        logRestoreFailureInternal(WALLPAPER_IMG_SYSTEM, error);
    }

    void onLockImageWallpaperRestoreFailed(@BackupRestoreError String error) {
        logRestoreFailureInternal(WALLPAPER_IMG_LOCK, error);
    }

    void onSystemLiveWallpaperRestoreFailed(@BackupRestoreError String error) {
        logRestoreFailureInternal(WALLPAPER_LIVE_SYSTEM, error);
    }

    void onLockLiveWallpaperRestoreFailed(@BackupRestoreError String error) {
        logRestoreFailureInternal(WALLPAPER_LIVE_LOCK, error);
    }



    /**
     * Called when the whole backup flow is interrupted by an exception.
     */
    void onBackupException(Exception exception) {
        String error = exception.getClass().getName();
        if (!mProcessedDataTypes.contains(WALLPAPER_IMG_SYSTEM) && !mProcessedDataTypes.contains(
                WALLPAPER_LIVE_SYSTEM)) {
            mLogger.logItemsBackupFailed(WALLPAPER_IMG_SYSTEM, /* count */ 1, error);
        }
        if (!mProcessedDataTypes.contains(WALLPAPER_IMG_LOCK) && !mProcessedDataTypes.contains(
                WALLPAPER_LIVE_LOCK)) {
            mLogger.logItemsBackupFailed(WALLPAPER_IMG_LOCK, /* count */ 1, error);
        }
    }

    /**
     * Called when the whole restore flow is interrupted by an exception.
     */
    void onRestoreException(Exception exception) {
        String error = exception.getClass().getName();
        if (!mProcessedDataTypes.contains(WALLPAPER_IMG_SYSTEM) && !mProcessedDataTypes.contains(
                WALLPAPER_LIVE_SYSTEM)) {
            mLogger.logItemsRestoreFailed(WALLPAPER_IMG_SYSTEM, /* count */ 1, error);
        }
        if (!mProcessedDataTypes.contains(WALLPAPER_IMG_LOCK) && !mProcessedDataTypes.contains(
                WALLPAPER_LIVE_LOCK)) {
            mLogger.logItemsRestoreFailed(WALLPAPER_IMG_LOCK, /* count */ 1, error);
        }
    }
    private void logBackupSuccessInternal(@BackupRestoreDataType String which,
            @Nullable WallpaperInfo liveComponentWallpaperInfo) {
        mLogger.logItemsBackedUp(which, /* count */ 1);
        logLiveWallpaperNameIfPresent(which, liveComponentWallpaperInfo);
        mProcessedDataTypes.add(which);
    }

    private void logBackupFailureInternal(@BackupRestoreDataType String which,
            @BackupRestoreError String error) {
        mLogger.logItemsBackupFailed(which, /* count */ 1, error);
        mProcessedDataTypes.add(which);
    }

    private void logRestoreSuccessInternal(@BackupRestoreDataType String which,
            @Nullable ComponentName liveComponentWallpaperInfo) {
        mLogger.logItemsRestored(which, /* count */ 1);
        logRestoredLiveWallpaperNameIfPresent(which, liveComponentWallpaperInfo);
        mProcessedDataTypes.add(which);
    }

    private void logRestoreFailureInternal(@BackupRestoreDataType String which,
            @BackupRestoreError String error) {
        mLogger.logItemsRestoreFailed(which, /* count */ 1, error);
        mProcessedDataTypes.add(which);
    }

    private void logLiveWallpaperNameIfPresent(@BackupRestoreDataType String wallpaperType,
            WallpaperInfo wallpaperInfo) {
        if (wallpaperInfo != null) {
            mLogger.logBackupMetadata(wallpaperType, wallpaperInfo.getComponent().getClassName());
        }
    }

    private void logRestoredLiveWallpaperNameIfPresent(@BackupRestoreDataType String wallpaperType,
            ComponentName wpService) {
        if (wpService != null) {
            mLogger.logRestoreMetadata(wallpaperType, wpService.getClassName());
        }
    }
}
