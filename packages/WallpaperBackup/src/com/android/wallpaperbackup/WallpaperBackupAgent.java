/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_INELIGIBLE;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_NO_METADATA;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_NO_WALLPAPER;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_QUOTA_EXCEEDED;

import android.app.AppGlobals;
import android.app.WallpaperManager;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManager;
import android.app.backup.BackupRestoreEventLogger.BackupRestoreError;
import android.app.backup.FullBackupDataOutput;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Backs up and restores wallpaper and metadata related to it.
 *
 * This agent has its own package because it does full backup as opposed to SystemBackupAgent
 * which does key/value backup.
 *
 * This class stages wallpaper files for backup by copying them into its own directory because of
 * the following reasons:
 *
 * <ul>
 *     <li>Non-system users don't have permission to read the directory that the system stores
 *     the wallpaper files in</li>
 *     <li>{@link BackupAgent} enforces that backed up files must live inside the package's
 *     {@link Context#getFilesDir()}</li>
 * </ul>
 *
 * There are 3 files to back up:
 * <ul>
 *     <li>The "wallpaper info"  file which contains metadata like the crop applied to the
 *     wallpaper or the live wallpaper component name.</li>
 *     <li>The "system" wallpaper file.</li>
 *     <li>An optional "lock" wallpaper, which is shown on the lockscreen instead of the system
 *     wallpaper if set.</li>
 * </ul>
 *
 * On restore, the metadata file is parsed and {@link WallpaperManager} APIs are used to set the
 * wallpaper. Note that if there's a live wallpaper, the live wallpaper package name will be
 * part of the metadata file and the wallpaper will be applied when the package it's installed.
 */
public class WallpaperBackupAgent extends BackupAgent {
    private static final String TAG = "WallpaperBackup";
    private static final boolean DEBUG = false;

    // Names of our local-data stage files
    @VisibleForTesting
    static final String SYSTEM_WALLPAPER_STAGE = "wallpaper-stage";
    @VisibleForTesting
    static final String LOCK_WALLPAPER_STAGE = "wallpaper-lock-stage";
    @VisibleForTesting
    static final String WALLPAPER_INFO_STAGE = "wallpaper-info-stage";

    static final String EMPTY_SENTINEL = "empty";
    static final String QUOTA_SENTINEL = "quota";

    // Shared preferences constants.
    static final String PREFS_NAME = "wbprefs.xml";
    static final String SYSTEM_GENERATION = "system_gen";
    static final String LOCK_GENERATION = "lock_gen";

    // If this file exists, it means we exceeded our quota last time
    private File mQuotaFile;
    private boolean mQuotaExceeded;

    private WallpaperManager mWallpaperManager;
    private WallpaperEventLogger mEventLogger;

    private boolean mSystemHasLiveComponent;
    private boolean mLockHasLiveComponent;

    @Override
    public void onCreate() {
        if (DEBUG) {
            Slog.v(TAG, "onCreate()");
        }

        mWallpaperManager = getSystemService(WallpaperManager.class);

        mQuotaFile = new File(getFilesDir(), QUOTA_SENTINEL);
        mQuotaExceeded = mQuotaFile.exists();
        if (DEBUG) {
            Slog.v(TAG, "quota file " + mQuotaFile.getPath() + " exists=" + mQuotaExceeded);
        }

        BackupManager backupManager = new BackupManager(getApplicationContext());
        mEventLogger = new WallpaperEventLogger(backupManager, /* wallpaperAgent */ this);
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        try {
            // We always back up this 'empty' file to ensure that the absence of
            // storable wallpaper imagery still produces a non-empty backup data
            // stream, otherwise it'd simply be ignored in preflight.
            final File empty = new File(getFilesDir(), EMPTY_SENTINEL);
            if (!empty.exists()) {
                FileOutputStream touch = new FileOutputStream(empty);
                touch.close();
            }
            backupFile(empty, data);

            SharedPreferences sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Check the IDs of the wallpapers that we backed up last time. If they haven't
            // changed, we won't re-stage them for backup and use the old staged versions to avoid
            // disk churn.
            final int lastSysGeneration = sharedPrefs.getInt(SYSTEM_GENERATION, /* defValue= */ -1);
            final int lastLockGeneration = sharedPrefs.getInt(LOCK_GENERATION, /* defValue= */ -1);
            final int sysGeneration = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            final int lockGeneration = mWallpaperManager.getWallpaperId(FLAG_LOCK);
            final boolean sysChanged = (sysGeneration != lastSysGeneration);
            final boolean lockChanged = (lockGeneration != lastLockGeneration);

            if (DEBUG) {
                Slog.v(TAG, "sysGen=" + sysGeneration + " : sysChanged=" + sysChanged);
                Slog.v(TAG, "lockGen=" + lockGeneration + " : lockChanged=" + lockChanged);
            }

            // Due to the way image vs live wallpaper backup logic is intermingled, for logging
            // purposes first check if we have live components for each wallpaper to avoid
            // over-reporting errors.
            mSystemHasLiveComponent = mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM) != null;
            mLockHasLiveComponent = mWallpaperManager.getWallpaperInfo(FLAG_LOCK) != null;

            backupWallpaperInfoFile(/* sysOrLockChanged= */ sysChanged || lockChanged, data);
            backupSystemWallpaperFile(sharedPrefs, sysChanged, sysGeneration, data);
            backupLockWallpaperFileIfItExists(sharedPrefs, lockChanged, lockGeneration, data);
        } catch (Exception e) {
            Slog.e(TAG, "Unable to back up wallpaper", e);
            mEventLogger.onBackupException(e);
        } finally {
            // Even if this time we had to back off on attempting to store the lock image
            // due to exceeding the data quota, try again next time.  This will alternate
            // between "try both" and "only store the primary image" until either there
            // is no lock image to store, or the quota is raised, or both fit under the
            // quota.
            mQuotaFile.delete();
        }
    }

    private void backupWallpaperInfoFile(boolean sysOrLockChanged, FullBackupDataOutput data)
            throws IOException {
        final ParcelFileDescriptor wallpaperInfoFd = mWallpaperManager.getWallpaperInfoFile();

        if (wallpaperInfoFd == null) {
            Slog.w(TAG, "Wallpaper metadata file doesn't exist");
            // If we have live components, getting the file to back up somehow failed, so log it
            // as an error.
            if (mSystemHasLiveComponent) {
                mEventLogger.onSystemLiveWallpaperBackupFailed(ERROR_NO_METADATA);
            }
            if (mLockHasLiveComponent) {
                mEventLogger.onLockLiveWallpaperBackupFailed(ERROR_NO_METADATA);
            }
            return;
        }

        final File infoStage = new File(getFilesDir(), WALLPAPER_INFO_STAGE);

        if (sysOrLockChanged || !infoStage.exists()) {
            if (DEBUG) Slog.v(TAG, "New wallpaper configuration; copying");
            copyFromPfdToFileAndClosePfd(wallpaperInfoFd, infoStage);
        }

        if (DEBUG) Slog.v(TAG, "Storing wallpaper metadata");
        backupFile(infoStage, data);

        // We've backed up the info file which contains the live component, so log it as success
        if (mSystemHasLiveComponent) {
            mEventLogger.onSystemLiveWallpaperBackedUp(
                    mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM));
        }
        if (mLockHasLiveComponent) {
            mEventLogger.onLockLiveWallpaperBackedUp(mWallpaperManager.getWallpaperInfo(FLAG_LOCK));
        }
    }

    private void backupSystemWallpaperFile(SharedPreferences sharedPrefs,
            boolean sysChanged, int sysGeneration, FullBackupDataOutput data) throws IOException {
        if (!mWallpaperManager.isWallpaperBackupEligible(FLAG_SYSTEM)) {
            Slog.d(TAG, "System wallpaper ineligible for backup");
            logSystemImageErrorIfNoLiveComponent(ERROR_INELIGIBLE);
            return;
        }

        final ParcelFileDescriptor systemWallpaperImageFd = mWallpaperManager.getWallpaperFile(
                FLAG_SYSTEM,
                /* getCropped= */ false);

        if (systemWallpaperImageFd == null) {
            Slog.w(TAG, "System wallpaper doesn't exist");
            logSystemImageErrorIfNoLiveComponent(ERROR_NO_WALLPAPER);
            return;
        }

        final File imageStage = new File(getFilesDir(), SYSTEM_WALLPAPER_STAGE);

        if (sysChanged || !imageStage.exists()) {
            if (DEBUG) Slog.v(TAG, "New system wallpaper; copying");
            copyFromPfdToFileAndClosePfd(systemWallpaperImageFd, imageStage);
        }

        if (DEBUG) Slog.v(TAG, "Storing system wallpaper image");
        backupFile(imageStage, data);
        sharedPrefs.edit().putInt(SYSTEM_GENERATION, sysGeneration).apply();
        mEventLogger.onSystemImageWallpaperBackedUp();
    }

    private void logSystemImageErrorIfNoLiveComponent(@BackupRestoreError String error) {
        if (mSystemHasLiveComponent) {
            return;
        }
        mEventLogger.onSystemImageWallpaperBackupFailed(error);
    }


    private void backupLockWallpaperFileIfItExists(SharedPreferences sharedPrefs,
            boolean lockChanged, int lockGeneration, FullBackupDataOutput data) throws IOException {
        final File lockImageStage = new File(getFilesDir(), LOCK_WALLPAPER_STAGE);

        // This means there's no lock wallpaper set by the user.
        if (lockGeneration == -1) {
            if (lockChanged && lockImageStage.exists()) {
                if (DEBUG) Slog.v(TAG, "Removed lock wallpaper; deleting");
                lockImageStage.delete();
            }
            Slog.d(TAG, "No lockscreen wallpaper set, add nothing to backup");
            sharedPrefs.edit().putInt(LOCK_GENERATION, lockGeneration).apply();
            logLockImageErrorIfNoLiveComponent(ERROR_NO_WALLPAPER);
            return;
        }

        if (!mWallpaperManager.isWallpaperBackupEligible(FLAG_LOCK)) {
            Slog.d(TAG, "Lock screen wallpaper ineligible for backup");
            logLockImageErrorIfNoLiveComponent(ERROR_INELIGIBLE);
            return;
        }

        final ParcelFileDescriptor lockWallpaperFd = mWallpaperManager.getWallpaperFile(
                FLAG_LOCK, /* getCropped= */ false);

        // If we get to this point, that means lockGeneration != -1 so there's a lock wallpaper
        // set, but we can't find it.
        if (lockWallpaperFd == null) {
            Slog.w(TAG, "Lock wallpaper doesn't exist");
            logLockImageErrorIfNoLiveComponent(ERROR_NO_WALLPAPER);
            return;
        }

        if (mQuotaExceeded) {
            Slog.w(TAG, "Not backing up lock screen wallpaper. Quota was exceeded last time");
            logLockImageErrorIfNoLiveComponent(ERROR_QUOTA_EXCEEDED);
            return;
        }

        if (lockChanged || !lockImageStage.exists()) {
            if (DEBUG) Slog.v(TAG, "New lock wallpaper; copying");
            copyFromPfdToFileAndClosePfd(lockWallpaperFd, lockImageStage);
        }

        if (DEBUG) Slog.v(TAG, "Storing lock wallpaper image");
        backupFile(lockImageStage, data);
        sharedPrefs.edit().putInt(LOCK_GENERATION, lockGeneration).apply();
        mEventLogger.onLockImageWallpaperBackedUp();
    }

    private void logLockImageErrorIfNoLiveComponent(@BackupRestoreError String error) {
        if (mLockHasLiveComponent) {
            return;
        }
        mEventLogger.onLockImageWallpaperBackupFailed(error);
    }

    /**
     * Copies the contents of the given {@code pfd} to the given {@code file}.
     *
     * All resources used in the process including the {@code pfd} will be closed.
     */
    private static void copyFromPfdToFileAndClosePfd(ParcelFileDescriptor pfd, File file)
            throws IOException {
        try (ParcelFileDescriptor.AutoCloseInputStream inputStream =
                     new ParcelFileDescriptor.AutoCloseInputStream(pfd);
             FileOutputStream outputStream = new FileOutputStream(file)
        ) {
            FileUtils.copy(inputStream, outputStream);
        }
    }

    @VisibleForTesting
    // fullBackupFile is final, so we intercept backups here in tests.
    protected void backupFile(File file, FullBackupDataOutput data) {
        fullBackupFile(file, data);
    }

    @Override
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        Slog.i(TAG, "Quota exceeded (" + backupDataBytes + " vs " + quotaBytes + ')');
        try (FileOutputStream f = new FileOutputStream(mQuotaFile)) {
            f.write(0);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to record quota-exceeded: " + e.getMessage());
        }
    }

    // We use the default onRestoreFile() implementation that will recreate our stage files,
    // then post-process in onRestoreFinished() to apply the new wallpaper.
    @Override
    public void onRestoreFinished() {
        Slog.v(TAG, "onRestoreFinished()");
        final File filesDir = getFilesDir();
        final File infoStage = new File(filesDir, WALLPAPER_INFO_STAGE);
        final File imageStage = new File(filesDir, SYSTEM_WALLPAPER_STAGE);
        final File lockImageStage = new File(filesDir, LOCK_WALLPAPER_STAGE);

        // If we restored separate lock imagery, the system wallpaper should be
        // applied as system-only; but if there's no separate lock image, make
        // sure to apply the restored system wallpaper as both.
        final int sysWhich = FLAG_SYSTEM | (lockImageStage.exists() ? 0 : FLAG_LOCK);

        try {
            // First parse the live component name so that we know for logging if we care about
            // logging errors with the image restore.
            ComponentName wpService = parseWallpaperComponent(infoStage, "wp");
            mSystemHasLiveComponent = wpService != null;

            // It is valid for the imagery to be absent; it means that we were not permitted
            // to back up the original image on the source device, or there was no user-supplied
            // wallpaper image present.
            restoreFromStage(imageStage, infoStage, "wp", sysWhich);
            restoreFromStage(lockImageStage, infoStage, "kwp", FLAG_LOCK);

            // And reset to the wallpaper service we should be using
            updateWallpaperComponent(wpService, !lockImageStage.exists());
        } catch (Exception e) {
            Slog.e(TAG, "Unable to restore wallpaper: " + e.getMessage());
            mEventLogger.onRestoreException(e);
        } finally {
            Slog.v(TAG, "Restore finished; clearing backup bookkeeping");
            infoStage.delete();
            imageStage.delete();
            lockImageStage.delete();

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(SYSTEM_GENERATION, -1)
                    .putInt(LOCK_GENERATION, -1)
                    .commit();
        }
    }

    @VisibleForTesting
    void updateWallpaperComponent(ComponentName wpService, boolean applyToLock) throws IOException {
        if (servicePackageExists(wpService)) {
            Slog.i(TAG, "Using wallpaper service " + wpService);
            mWallpaperManager.setWallpaperComponent(wpService);
            if (applyToLock) {
                // We have a live wallpaper and no static lock image,
                // allow live wallpaper to show "through" on lock screen.
                mWallpaperManager.clear(FLAG_LOCK);
                mEventLogger.onLockLiveWallpaperRestored(wpService);
            }
            mEventLogger.onSystemLiveWallpaperRestored(wpService);
        } else {
            // If we've restored a live wallpaper, but the component doesn't exist,
            // we should log it as an error so we can easily identify the problem
            // in reports from users
            if (wpService != null) {
                // TODO(b/268471749): Handle delayed case
                applyComponentAtInstall(wpService, applyToLock);
                Slog.w(TAG, "Wallpaper service " + wpService + " isn't available. "
                        + " Will try to apply later");
            }
        }
    }

    private void restoreFromStage(File stage, File info, String hintTag, int which)
            throws IOException {
        if (stage.exists()) {
            // Parse the restored info file to find the crop hint.  Note that this currently
            // relies on a priori knowledge of the wallpaper info file schema.
            Rect cropHint = parseCropHint(info, hintTag);
            if (cropHint != null) {
                Slog.i(TAG, "Got restored wallpaper; applying which=" + which
                        + "; cropHint = " + cropHint);
                try (FileInputStream in = new FileInputStream(stage)) {
                    mWallpaperManager.setStream(in, cropHint.isEmpty() ? null : cropHint, true,
                            which);

                    // And log the success
                    if ((which & FLAG_SYSTEM) > 0) {
                        mEventLogger.onSystemImageWallpaperRestored();
                    } else {
                        mEventLogger.onLockImageWallpaperRestored();
                    }
                }
            } else {
                logRestoreError(which, ERROR_NO_METADATA);
            }
        } else {
            Slog.d(TAG, "Restore data doesn't exist for file " + stage.getPath());
            logRestoreErrorIfNoLiveComponent(which, ERROR_NO_WALLPAPER);
        }
    }

    private void logRestoreErrorIfNoLiveComponent(int which, String error) {
        if (mSystemHasLiveComponent) {
            return;
        }
        logRestoreError(which, error);
    }

    private void logRestoreError(int which, String error) {
        if ((which & FLAG_SYSTEM) == FLAG_SYSTEM) {
            mEventLogger.onSystemImageWallpaperRestoreFailed(error);
        } else if ((which & FLAG_LOCK) == FLAG_LOCK) {
            mEventLogger.onLockImageWallpaperRestoreFailed(error);
        }
    }
    private Rect parseCropHint(File wallpaperInfo, String sectionTag) {
        Rect cropHint = new Rect();
        try (FileInputStream stream = new FileInputStream(wallpaperInfo)) {
            XmlPullParser parser = Xml.resolvePullParser(stream);

            int type;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if (sectionTag.equals(tag)) {
                        cropHint.left = getAttributeInt(parser, "cropLeft", 0);
                        cropHint.top = getAttributeInt(parser, "cropTop", 0);
                        cropHint.right = getAttributeInt(parser, "cropRight", 0);
                        cropHint.bottom = getAttributeInt(parser, "cropBottom", 0);
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
        } catch (Exception e) {
            // Whoops; can't process the info file at all.  Report failure.
            Slog.w(TAG, "Failed to parse restored crop: " + e.getMessage());
            return null;
        }

        return cropHint;
    }

    private ComponentName parseWallpaperComponent(File wallpaperInfo, String sectionTag) {
        ComponentName name = null;
        try (FileInputStream stream = new FileInputStream(wallpaperInfo)) {
            final XmlPullParser parser = Xml.resolvePullParser(stream);

            int type;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if (sectionTag.equals(tag)) {
                        final String parsedName = parser.getAttributeValue(null, "component");
                        name = (parsedName != null)
                                ? ComponentName.unflattenFromString(parsedName)
                                : null;
                        break;
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
        } catch (Exception e) {
            // Whoops; can't process the info file at all.  Report failure.
            Slog.w(TAG, "Failed to parse restored component: " + e.getMessage());
            return null;
        }
        return name;
    }

    private int getAttributeInt(XmlPullParser parser, String name, int defValue) {
        final String value = parser.getAttributeValue(null, name);
        return (value == null) ? defValue : Integer.parseInt(value);
    }

    @VisibleForTesting
    boolean servicePackageExists(ComponentName comp) {
        try {
            if (comp != null) {
                final IPackageManager pm = AppGlobals.getPackageManager();
                final PackageInfo info = pm.getPackageInfo(comp.getPackageName(),
                        0, getUserId());
                return (info != null);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to contact package manager");
        }
        return false;
    }

    /** Unused Key/Value API. */
    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {
        // Intentionally blank
    }

    /** Unused Key/Value API. */
    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        // Intentionally blank
    }

    private void applyComponentAtInstall(ComponentName componentName, boolean applyToLock) {
        PackageMonitor packageMonitor = getWallpaperPackageMonitor(componentName, applyToLock);
        packageMonitor.register(getBaseContext(), null, UserHandle.ALL, true);
    }

    @VisibleForTesting
    PackageMonitor getWallpaperPackageMonitor(ComponentName componentName, boolean applyToLock) {
        return new PackageMonitor() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                if (!isDeviceInRestore()) {
                    // We don't want to reapply the wallpaper outside a restore.
                    unregister();
                    return;
                }

                if (componentName.getPackageName().equals(packageName)) {
                    Slog.d(TAG, "Applying component " + componentName);
                    mWallpaperManager.setWallpaperComponent(componentName);
                    if (applyToLock) {
                        try {
                            mWallpaperManager.clear(FLAG_LOCK);
                        } catch (IOException e) {
                            Slog.w(TAG, "Failed to apply live wallpaper to lock screen: " + e);
                        }
                    }
                    // We're only expecting to restore the wallpaper component once.
                    unregister();
                }
            }
        };
    }

    @VisibleForTesting
    boolean isDeviceInRestore() {
        try {
            boolean isInSetup = Settings.Secure.getInt(getBaseContext().getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE) == 0;
            boolean isInDeferredSetup = Settings.Secure.getInt(getBaseContext()
                            .getContentResolver(),
                    Settings.Secure.USER_SETUP_PERSONALIZATION_STATE) ==
                    Settings.Secure.USER_SETUP_PERSONALIZATION_STARTED;
            return isInSetup || isInDeferredSetup;
        } catch (Settings.SettingNotFoundException e) {
            Slog.w(TAG, "Failed to check if the user is in restore: " + e);
            return false;
        }
    }
}