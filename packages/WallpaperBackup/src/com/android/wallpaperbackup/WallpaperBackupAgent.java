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
import static android.app.WallpaperManager.ORIENTATION_UNKNOWN;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_INELIGIBLE;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_NO_METADATA;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_NO_WALLPAPER;
import static com.android.wallpaperbackup.WallpaperEventLogger.ERROR_QUOTA_EXCEEDED;
import static com.android.window.flags.Flags.multiCrop;

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
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
    @VisibleForTesting
    static final String WALLPAPER_BACKUP_DEVICE_INFO_STAGE = "wallpaper-backup-device-info-stage";
    static final String EMPTY_SENTINEL = "empty";
    static final String QUOTA_SENTINEL = "quota";
    // Shared preferences constants.
    static final String PREFS_NAME = "wbprefs.xml";
    static final String SYSTEM_GENERATION = "system_gen";
    static final String LOCK_GENERATION = "lock_gen";

    static final float DEFAULT_ACCEPTABLE_PARALLAX = 0.2f;

    // If this file exists, it means we exceeded our quota last time
    private File mQuotaFile;
    private boolean mQuotaExceeded;

    private WallpaperManager mWallpaperManager;
    private WallpaperEventLogger mEventLogger;
    private BackupManager mBackupManager;

    private boolean mSystemHasLiveComponent;
    private boolean mLockHasLiveComponent;

    private DisplayManager mDisplayManager;

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

        mBackupManager = new BackupManager(getBaseContext());
        mEventLogger = new WallpaperEventLogger(mBackupManager, /* wallpaperAgent */ this);

        mDisplayManager = getSystemService(DisplayManager.class);
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

            // performing backup of each file based on order of importance
            backupWallpaperInfoFile(/* sysOrLockChanged= */ sysChanged || lockChanged, data);
            backupSystemWallpaperFile(sharedPrefs, sysChanged, sysGeneration, data);
            backupLockWallpaperFileIfItExists(sharedPrefs, lockChanged, lockGeneration, data);
            backupDeviceInfoFile(data);
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

    /**
     * This method backs up the device dimension information. The device data will always get
     * overwritten when triggering a backup
     */
    private void backupDeviceInfoFile(FullBackupDataOutput data)
            throws IOException {
        final File deviceInfoStage = new File(getFilesDir(), WALLPAPER_BACKUP_DEVICE_INFO_STAGE);

        // save the dimensions of the device with xml formatting
        Point dimensions = getScreenDimensions();
        Display smallerDisplay = getSmallerDisplayIfExists();
        Point secondaryDimensions = smallerDisplay != null ? getRealSize(smallerDisplay) :
                new Point(0, 0);

        deviceInfoStage.createNewFile();
        FileOutputStream fstream = new FileOutputStream(deviceInfoStage, false);
        TypedXmlSerializer out = Xml.resolveSerializer(fstream);
        out.startDocument(null, true);
        out.startTag(null, "dimensions");

        out.startTag(null, "width");
        out.text(String.valueOf(dimensions.x));
        out.endTag(null, "width");

        out.startTag(null, "height");
        out.text(String.valueOf(dimensions.y));
        out.endTag(null, "height");

        if (smallerDisplay != null) {
            out.startTag(null, "secondarywidth");
            out.text(String.valueOf(secondaryDimensions.x));
            out.endTag(null, "secondarywidth");

            out.startTag(null, "secondaryheight");
            out.text(String.valueOf(secondaryDimensions.y));
            out.endTag(null, "secondaryheight");
        }

        out.endTag(null, "dimensions");
        out.endDocument();
        fstream.flush();
        FileUtils.sync(fstream);
        fstream.close();

        if (DEBUG) Slog.v(TAG, "Storing device dimension data");
        backupFile(deviceInfoStage, data);
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

    private static String readText(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        String result = "";
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }
        return result;
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
        final File deviceDimensionsStage = new File(filesDir, WALLPAPER_BACKUP_DEVICE_INFO_STAGE);
        boolean lockImageStageExists = lockImageStage.exists();

        try {
            // Parse the device dimensions of the source device
            Pair<Point, Point> sourceDeviceDimensions = parseDeviceDimensions(
                    deviceDimensionsStage);

            // First parse the live component name so that we know for logging if we care about
            // logging errors with the image restore.
            ComponentName wpService = parseWallpaperComponent(infoStage, "wp");
            mSystemHasLiveComponent = wpService != null;

            ComponentName kwpService = parseWallpaperComponent(infoStage, "kwp");
            mLockHasLiveComponent = kwpService != null;
            boolean separateLockWallpaper = mLockHasLiveComponent || lockImageStage.exists();

            // if there's no separate lock wallpaper, apply the system wallpaper to both screens.
            final int sysWhich = separateLockWallpaper ? FLAG_SYSTEM : FLAG_SYSTEM | FLAG_LOCK;

            // It is valid for the imagery to be absent; it means that we were not permitted
            // to back up the original image on the source device, or there was no user-supplied
            // wallpaper image present.
            if (lockImageStageExists) {
                restoreFromStage(lockImageStage, infoStage, "kwp", FLAG_LOCK,
                        sourceDeviceDimensions);
            }
            restoreFromStage(imageStage, infoStage, "wp", sysWhich, sourceDeviceDimensions);

            // And reset to the wallpaper service we should be using
            if (mLockHasLiveComponent) {
                updateWallpaperComponent(kwpService, FLAG_LOCK);
            }
            updateWallpaperComponent(wpService, sysWhich);
        } catch (Exception e) {
            Slog.e(TAG, "Unable to restore wallpaper: " + e.getMessage());
            mEventLogger.onRestoreException(e);
        } finally {
            Slog.v(TAG, "Restore finished; clearing backup bookkeeping");
            infoStage.delete();
            imageStage.delete();
            lockImageStage.delete();
            deviceDimensionsStage.delete();

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(SYSTEM_GENERATION, -1)
                    .putInt(LOCK_GENERATION, -1)
                    .commit();
        }
    }

    /**
     * This method parses the given file for the backed up device dimensions
     *
     * @param deviceDimensions the file which holds the device dimensions
     * @return the backed up device dimensions
     */
    private Pair<Point, Point> parseDeviceDimensions(File deviceDimensions) {
        int width = 0, height = 0, secondaryHeight = 0, secondaryWidth = 0;
        try {
            TypedXmlPullParser parser = Xml.resolvePullParser(
                    new FileInputStream(deviceDimensions));

            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }

                String name = parser.getName();

                switch (name) {
                    case "width":
                        String widthText = readText(parser);
                        width = Integer.valueOf(widthText);
                        break;

                    case "height":
                        String textHeight = readText(parser);
                        height = Integer.valueOf(textHeight);
                        break;

                    case "secondarywidth":
                        String secondaryWidthText = readText(parser);
                        secondaryWidth = Integer.valueOf(secondaryWidthText);
                        break;

                    case "secondaryheight":
                        String secondaryHeightText = readText(parser);
                        secondaryHeight = Integer.valueOf(secondaryHeightText);
                        break;
                    default:
                        break;
                }
            }
            return new Pair<>(new Point(width, height), new Point(secondaryWidth, secondaryHeight));

        } catch (Exception e) {
            return null;
        }
    }

    @VisibleForTesting
    void updateWallpaperComponent(ComponentName wpService, int which)
            throws IOException {
        if (servicePackageExists(wpService)) {
            Slog.i(TAG, "Using wallpaper service " + wpService);
            mWallpaperManager.setWallpaperComponentWithFlags(wpService, which);
            if ((which & FLAG_LOCK) != 0) {
                mEventLogger.onLockLiveWallpaperRestored(wpService);
            }
            if ((which & FLAG_SYSTEM) != 0) {
                mEventLogger.onSystemLiveWallpaperRestored(wpService);
            }
        } else {
            // If we've restored a live wallpaper, but the component doesn't exist,
            // we should log it as an error so we can easily identify the problem
            // in reports from users
            if (wpService != null) {
                // TODO(b/268471749): Handle delayed case
                applyComponentAtInstall(wpService, which);
                Slog.w(TAG, "Wallpaper service " + wpService + " isn't available. "
                        + " Will try to apply later");
            }
        }
    }

    private void restoreFromStage(File stage, File info, String hintTag, int which,
            Pair<Point, Point> sourceDeviceDimensions)
            throws IOException {
        if (stage.exists()) {
            if (multiCrop()) {
                // TODO(b/332937943): implement offset adjustment by manually adjusting crop to
                //  adhere to device aspect ratio
                SparseArray<Rect> cropHints = parseCropHints(info, hintTag);
                if (cropHints != null) {
                    Slog.i(TAG, "Got restored wallpaper; applying which=" + which
                            + "; cropHints = " + cropHints);
                    try (FileInputStream in = new FileInputStream(stage)) {
                        mWallpaperManager.setStreamWithCrops(in, cropHints, true, which);
                    }
                    // And log the success
                    if ((which & FLAG_SYSTEM) > 0) {
                        mEventLogger.onSystemImageWallpaperRestored();
                    }
                    if ((which & FLAG_LOCK) > 0) {
                        mEventLogger.onLockImageWallpaperRestored();
                    }
                } else {
                    logRestoreError(which, ERROR_NO_METADATA);
                }
                return;
            }
            // Parse the restored info file to find the crop hint.  Note that this currently
            // relies on a priori knowledge of the wallpaper info file schema.
            Rect cropHint = parseCropHint(info, hintTag);
            if (cropHint != null) {
                Slog.i(TAG, "Got restored wallpaper; applying which=" + which
                        + "; cropHint = " + cropHint);
                try (FileInputStream in = new FileInputStream(stage)) {

                    if (sourceDeviceDimensions != null && sourceDeviceDimensions.first != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        ParcelFileDescriptor pdf = ParcelFileDescriptor.open(stage, MODE_READ_ONLY);
                        BitmapFactory.decodeFileDescriptor(pdf.getFileDescriptor(),
                                null, options);
                        Point bitmapSize = new Point(options.outWidth, options.outHeight);
                        Point sourceDeviceSize = new Point(sourceDeviceDimensions.first.x,
                                sourceDeviceDimensions.first.y);
                        Point targetDeviceDimensions = getScreenDimensions();

                        // TODO: for now we handle only the case where the target device has smaller
                        // aspect ratio than the source device i.e. the target device is more narrow
                        // than the source device
                        if (isTargetMoreNarrowThanSource(targetDeviceDimensions,
                                sourceDeviceSize)) {
                            Rect adjustedCrop = findNewCropfromOldCrop(cropHint,
                                    sourceDeviceDimensions.first, true, targetDeviceDimensions,
                                    bitmapSize, true);

                            cropHint.set(adjustedCrop);
                        }
                    }

                    mWallpaperManager.setStream(in, cropHint.isEmpty() ? null : cropHint,
                            true, which);

                    // And log the success
                    if ((which & FLAG_SYSTEM) > 0) {
                        mEventLogger.onSystemImageWallpaperRestored();
                    }
                    if ((which & FLAG_LOCK) > 0) {
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

    /**
     * This method computes the crop of the stored wallpaper to preserve its center point as the
     * user had set it in the previous device.
     *
     * The algorithm involves first computing the original crop of the user (without parallax). Then
     * manually adjusting the user's original crop to respect the current device's aspect ratio
     * (thereby preserving the center point). Then finally, adding any leftover image real-estate
     * (i.e. space left over on the horizontal axis) to add parallax effect. Parallax is only added
     * if was present in the old device's settings.
     *
     */
    private Rect findNewCropfromOldCrop(Rect oldCrop, Point oldDisplaySize, boolean oldRtl,
            Point newDisplaySize, Point bitmapSize, boolean newRtl) {
        Rect cropWithoutParallax = withoutParallax(oldCrop, oldDisplaySize, oldRtl, bitmapSize);
        oldCrop = oldCrop.isEmpty() ? new Rect(0, 0, bitmapSize.x, bitmapSize.y) : oldCrop;
        float oldParallaxAmount = ((float) oldCrop.width() / cropWithoutParallax.width()) - 1;

        Rect newCropWithSameCenterWithoutParallax = sameCenter(newDisplaySize, bitmapSize,
                cropWithoutParallax);

        Rect newCrop = newCropWithSameCenterWithoutParallax;

        // calculate the amount of left-over space there is in the image after adjusting the crop
        // from the above operation i.e. in a rtl configuration, this is the remaining space in the
        // image after subtracting the new crop's right edge coordinate from the image itself, and
        // for ltr, its just the new crop's left edge coordinate (as it's the distance from the
        // beginning of the image)
        int widthAvailableForParallaxOnTheNewDevice =
                (newRtl) ? newCrop.left : bitmapSize.x - newCrop.right;

        // calculate relatively how much this available space is as a fraction of the total cropped
        // image
        float availableParallaxAmount =
                (float) widthAvailableForParallaxOnTheNewDevice / newCrop.width();

        float minAcceptableParallax = Math.min(DEFAULT_ACCEPTABLE_PARALLAX, oldParallaxAmount);

        if (DEBUG) {
            Slog.d(TAG, "- cropWithoutParallax: " + cropWithoutParallax);
            Slog.d(TAG, "- oldParallaxAmount: " + oldParallaxAmount);
            Slog.d(TAG, "- newCropWithSameCenterWithoutParallax: "
                    + newCropWithSameCenterWithoutParallax);
            Slog.d(TAG, "- widthAvailableForParallaxOnTheNewDevice: "
                    + widthAvailableForParallaxOnTheNewDevice);
            Slog.d(TAG, "- availableParallaxAmount: " + availableParallaxAmount);
            Slog.d(TAG, "- minAcceptableParallax: " + minAcceptableParallax);
            Slog.d(TAG, "- oldCrop: " + oldCrop);
            Slog.d(TAG, "- oldDisplaySize: " + oldDisplaySize);
            Slog.d(TAG, "- oldRtl: " + oldRtl);
            Slog.d(TAG, "- newDisplaySize: " + newDisplaySize);
            Slog.d(TAG, "- bitmapSize: " + bitmapSize);
            Slog.d(TAG, "- newRtl: " + newRtl);
        }
        if (availableParallaxAmount >= minAcceptableParallax) {
            // but in any case, don't put more parallax than the amount of the old device
            float parallaxToAdd = Math.min(availableParallaxAmount, oldParallaxAmount);

            int widthToAddForParallax = (int) (newCrop.width() * parallaxToAdd);
            if (DEBUG) {
                Slog.d(TAG, "- parallaxToAdd: " + parallaxToAdd);
                Slog.d(TAG, "- widthToAddForParallax: " + widthToAddForParallax);
            }
            if (newRtl) {
                newCrop.left -= widthToAddForParallax;
            } else {
                newCrop.right += widthToAddForParallax;
            }
        }
        return newCrop;
    }

    /**
     * This method computes the original crop of the user without parallax.
     *
     * NOTE: When the user sets the wallpaper with a specific crop, there may additional image added
     * to the crop to support parallax. In order to determine the user's actual crop the parallax
     * must be removed if it exists.
     */
    Rect withoutParallax(Rect crop, Point displaySize, boolean rtl, Point bitmapSize) {
        // in the case an image's crop is not set, we assume the image itself is cropped
        if (crop.isEmpty()) {
            crop = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
        }

        if (DEBUG) {
            Slog.w(TAG, "- crop: " + crop);
        }

        Rect adjustedCrop = new Rect(crop);
        float suggestedDisplayRatio = (float) displaySize.x / displaySize.y;

        // here we calculate the width of the wallpaper image such that it has the same aspect ratio
        // as the given display i.e. the width of the image on a single page of the device without
        // parallax (i.e. displaySize will correspond to the display the crop was originally set on)
        int wallpaperWidthWithoutParallax = (int) (0.5f + (float) displaySize.x * crop.height()
                / displaySize.y);
        // subtracting wallpaperWidthWithoutParallax from the wallpaper crop gives the amount of
        // parallax added
        int widthToRemove = Math.max(0, crop.width() - wallpaperWidthWithoutParallax);

        if (DEBUG) {
            Slog.d(TAG, "- adjustedCrop: " + adjustedCrop);
            Slog.d(TAG, "- suggestedDisplayRatio: " + suggestedDisplayRatio);
            Slog.d(TAG, "- wallpaperWidthWithoutParallax: " + wallpaperWidthWithoutParallax);
            Slog.d(TAG, "- widthToRemove: " + widthToRemove);
        }
        if (rtl) {
            adjustedCrop.left += widthToRemove;
        } else {
            adjustedCrop.right -= widthToRemove;
        }

        if (DEBUG) {
            Slog.d(TAG, "- adjustedCrop: " + crop);
        }
        return adjustedCrop;
    }

    /**
     * This method computes a new crop based on the given crop in order to preserve the center point
     * of the given crop on the provided displaySize. This is only for the case where the device
     * displaySize has a smaller aspect ratio than the cropped image.
     *
     * NOTE: If the width to height ratio is less in the device display than cropped image
     * this means the aspect ratios are off and there will be distortions in the image
     * if the image is applied to the current display (i.e. the image will be skewed ->
     * pixels in the image will not align correctly with the same pixels in the image that are
     * above them)
     */
    Rect sameCenter(Point displaySize, Point bitmapSize, Rect crop) {

        // in the case an image's crop is not set, we assume the image itself is cropped
        if (crop.isEmpty()) {
            crop = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
        }

        float screenRatio = (float) displaySize.x / displaySize.y;
        float cropRatio = (float) crop.width() / crop.height();

        Rect adjustedCrop = new Rect(crop);

        if (screenRatio < cropRatio) {
            // the screen is more narrow than the image, and as such, the image will need to be
            // zoomed in till it fits in the vertical axis. Due to this, we need to manually adjust
            // the image's crop in order for it to fit into the screen without having the framework
            // do it (since the framework left aligns the image after zooming)

            // Calculate the height of the adjusted wallpaper crop so it respects the aspect ratio
            // of the device. To calculate the height, we will use the width of the current crop.
            // This is so we find the largest height possible which also respects the device aspect
            // ratio.
            int heightToAdd = (int) (0.5f + crop.width() / screenRatio - crop.height());

            // Calculate how much extra image space available that can be used to adjust
            // the crop. If this amount is less than heightToAdd, from above, then that means we
            // can't use heightToAdd. Instead we will need to use the maximum possible height, which
            // is the height of the original bitmap. NOTE: the bitmap height may be different than
            // the crop.
            // since there is no guarantee to have height available on both sides
            // (e.g. the available height might be fully at the bottom), grab the minimum
            int availableHeight = 2 * Math.min(crop.top, bitmapSize.y - crop.bottom);
            int actualHeightToAdd = Math.min(heightToAdd, availableHeight);

            // half of the additional height is added to the top and bottom of the crop
            adjustedCrop.top -= actualHeightToAdd / 2 + actualHeightToAdd % 2;
            adjustedCrop.bottom += actualHeightToAdd / 2;

            // Calculate the width of the adjusted crop. Initially we used the fixed width of the
            // crop to calculate the heightToAdd, but since this height may be invalid (based on
            // the calculation above) we calculate the width again instead of using the fixed width,
            // using the adjustedCrop's updated height.
            int widthToRemove = (int) (0.5f + crop.width() - adjustedCrop.height() * screenRatio);

            // half of the additional width is subtracted from the left and right side of the crop
            int widthToRemoveLeft = widthToRemove / 2;
            int widthToRemoveRight = widthToRemove / 2 + widthToRemove % 2;

            adjustedCrop.left += widthToRemoveLeft;
            adjustedCrop.right -= widthToRemoveRight;

            if (DEBUG) {
                Slog.d(TAG, "cropRatio: " + cropRatio);
                Slog.d(TAG, "screenRatio: " + screenRatio);
                Slog.d(TAG, "heightToAdd: " + heightToAdd);
                Slog.d(TAG, "actualHeightToAdd: " + actualHeightToAdd);
                Slog.d(TAG, "availableHeight: " + availableHeight);
                Slog.d(TAG, "widthToRemove: " + widthToRemove);
                Slog.d(TAG, "adjustedCrop: " + adjustedCrop);
            }

            return adjustedCrop;
        }

        return adjustedCrop;
    }

    private boolean isTargetMoreNarrowThanSource(Point targetDisplaySize, Point srcDisplaySize) {
        float targetScreenRatio = (float) targetDisplaySize.x / targetDisplaySize.y;
        float srcScreenRatio = (float) srcDisplaySize.x / srcDisplaySize.y;

        return (targetScreenRatio < srcScreenRatio);
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
        }
        if ((which & FLAG_LOCK) == FLAG_LOCK) {
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

    private SparseArray<Rect> parseCropHints(File wallpaperInfo, String sectionTag) {
        SparseArray<Rect> cropHints = new SparseArray<>();
        try (FileInputStream stream = new FileInputStream(wallpaperInfo)) {
            XmlPullParser parser = Xml.resolvePullParser(stream);
            int type;
            do {
                type = parser.next();
                if (type != XmlPullParser.START_TAG) continue;
                String tag = parser.getName();
                if (!sectionTag.equals(tag)) continue;
                for (Pair<Integer, String> pair : List.of(
                        new Pair<>(WallpaperManager.PORTRAIT, "Portrait"),
                        new Pair<>(WallpaperManager.LANDSCAPE, "Landscape"),
                        new Pair<>(WallpaperManager.SQUARE_PORTRAIT, "SquarePortrait"),
                        new Pair<>(WallpaperManager.SQUARE_LANDSCAPE, "SquareLandscape"))) {
                    Rect cropHint = new Rect(
                            getAttributeInt(parser, "cropLeft" + pair.second, 0),
                            getAttributeInt(parser, "cropTop" + pair.second, 0),
                            getAttributeInt(parser, "cropRight" + pair.second, 0),
                            getAttributeInt(parser, "cropBottom" + pair.second, 0));
                    if (!cropHint.isEmpty()) cropHints.put(pair.first, cropHint);
                }
                if (cropHints.size() == 0) {
                    // migration case: the crops per screen orientation are not specified.
                    // use the old attributes to restore the crop for one screen orientation.
                    Rect cropHint = new Rect(
                            getAttributeInt(parser, "cropLeft", 0),
                            getAttributeInt(parser, "cropTop", 0),
                            getAttributeInt(parser, "cropRight", 0),
                            getAttributeInt(parser, "cropBottom", 0));
                    if (!cropHint.isEmpty()) cropHints.put(ORIENTATION_UNKNOWN, cropHint);
                }
            } while (type != XmlPullParser.END_DOCUMENT);
        } catch (Exception e) {
            // Whoops; can't process the info file at all.  Report failure.
            Slog.w(TAG, "Failed to parse restored crops: " + e.getMessage());
            return null;
        }
        return cropHints;
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

    private void applyComponentAtInstall(ComponentName componentName, int which) {
        PackageMonitor packageMonitor = getWallpaperPackageMonitor(
                componentName, which);
        packageMonitor.register(getBaseContext(), null, UserHandle.ALL, true);
    }

    @VisibleForTesting
    PackageMonitor getWallpaperPackageMonitor(ComponentName componentName, int which) {
        return new PackageMonitor() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                if (!isDeviceInRestore()) {
                    // We don't want to reapply the wallpaper outside a restore.
                    unregister();

                    // We have finished restore and not succeeded, so let's log that as an error.
                    WallpaperEventLogger logger = new WallpaperEventLogger(
                            mBackupManager.getDelayedRestoreLogger());
                    if ((which & FLAG_SYSTEM) != 0) {
                        logger.onSystemLiveWallpaperRestoreFailed(
                                WallpaperEventLogger.ERROR_LIVE_PACKAGE_NOT_INSTALLED);
                    }
                    if ((which & FLAG_LOCK) != 0) {
                        logger.onLockLiveWallpaperRestoreFailed(
                                WallpaperEventLogger.ERROR_LIVE_PACKAGE_NOT_INSTALLED);
                    }
                    mBackupManager.reportDelayedRestoreResult(logger.getBackupRestoreLogger());

                    return;
                }

                if (componentName.getPackageName().equals(packageName)) {
                    Slog.d(TAG, "Applying component " + componentName);
                    boolean success = mWallpaperManager.setWallpaperComponentWithFlags(
                            componentName, which);
                    WallpaperEventLogger logger = new WallpaperEventLogger(
                            mBackupManager.getDelayedRestoreLogger());
                    if (success) {
                        if ((which & FLAG_SYSTEM) != 0) {
                            logger.onSystemLiveWallpaperRestored(componentName);
                        }
                        if ((which & FLAG_LOCK) != 0) {
                            logger.onLockLiveWallpaperRestored(componentName);
                        }
                    } else {
                        if ((which & FLAG_SYSTEM) != 0) {
                            logger.onSystemLiveWallpaperRestoreFailed(
                                    WallpaperEventLogger.ERROR_SET_COMPONENT_EXCEPTION);
                        }
                        if ((which & FLAG_LOCK) != 0) {
                            logger.onLockLiveWallpaperRestoreFailed(
                                    WallpaperEventLogger.ERROR_SET_COMPONENT_EXCEPTION);
                        }
                    }
                    // We're only expecting to restore the wallpaper component once.
                    unregister();
                    mBackupManager.reportDelayedRestoreResult(logger.getBackupRestoreLogger());
                }
            }
        };
    }

    /**
     * This method retrieves the dimensions of the largest display of the device
     *
     * @return a @{Point} object that contains the dimensions of the largest display on the device
     */
    private Point getScreenDimensions() {
        Point largetDimensions = null;
        int maxArea = 0;

        for (Display display : getInternalDisplays()) {
            Point displaySize = getRealSize(display);

            int width = displaySize.x;
            int height = displaySize.y;
            int area = width * height;

            if (area > maxArea) {
                maxArea = area;
                largetDimensions = displaySize;
            }
        }

        return largetDimensions;
    }

    private Point getRealSize(Display display) {
        DisplayInfo displayInfo = new DisplayInfo();
        display.getDisplayInfo(displayInfo);
        return new Point(displayInfo.logicalWidth, displayInfo.logicalHeight);
    }

    /**
     * This method returns the smaller display on a multi-display device
     *
     * @return Display that corresponds to the smaller display on a device or null if ther is only
     * one Display on a device
     */
    private Display getSmallerDisplayIfExists() {
        List<Display> internalDisplays = getInternalDisplays();
        Point largestDisplaySize = getScreenDimensions();

        // Find the first non-matching internal display
        for (Display display : internalDisplays) {
            Point displaySize = getRealSize(display);
            if (displaySize.x != largestDisplaySize.x || displaySize.y != largestDisplaySize.y) {
                return display;
            }
        }

        // If no smaller display found, return null, as there is only a single display
        return null;
    }

    /**
     * This method retrieves the collection of Display objects available in the device.
     * i.e. non-external displays are ignored
     *
     * @return list of displays corresponding to each display in the device
     */
    private List<Display> getInternalDisplays() {
        Display[] allDisplays = mDisplayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED);

        List<Display> internalDisplays = new ArrayList<>();
        for (Display display : allDisplays) {
            if (display.getType() == Display.TYPE_INTERNAL) {
                internalDisplays.add(display);
            }
        }
        return internalDisplays;
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

    @VisibleForTesting
    void setBackupManagerForTesting(BackupManager backupManager) {
        mBackupManager = backupManager;
    }
}