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

import android.app.WallpaperManager;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.content.Context;
import android.graphics.Rect;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.system.Os;
import android.util.Slog;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WallpaperBackupAgent extends BackupAgent {
    private static final String TAG = "WallpaperBackup";
    private static final boolean DEBUG = true;

    // NB: must be kept in sync with WallpaperManagerService but has no
    // compile-time visibility.

    // Target filenames within the system's wallpaper directory
    static final String WALLPAPER = "wallpaper_orig";
    static final String WALLPAPER_LOCK = "wallpaper_lock_orig";
    static final String WALLPAPER_INFO = "wallpaper_info.xml";

    // Names of our local-data stage files/links
    static final String IMAGE_STAGE = "wallpaper-stage";
    static final String LOCK_IMAGE_STAGE = "wallpaper-lock-stage";
    static final String INFO_STAGE = "wallpaper-info-stage";
    static final String EMPTY_SENTINEL = "empty";
    static final String QUOTA_SENTINEL = "quota";

    private File mWallpaperInfo;        // wallpaper metadata file
    private File mWallpaperFile;        // primary wallpaper image file
    private File mLockWallpaperFile;    // lock wallpaper image file

    // If this file exists, it means we exceeded our quota last time
    private File mQuotaFile;
    private boolean mQuotaExceeded;

    private WallpaperManager mWm;

    @Override
    public void onCreate() {
        if (DEBUG) {
            Slog.v(TAG, "onCreate()");
        }

        File wallpaperDir = Environment.getUserSystemDirectory(UserHandle.USER_SYSTEM);
        mWallpaperInfo = new File(wallpaperDir, WALLPAPER_INFO);
        mWallpaperFile = new File(wallpaperDir, WALLPAPER);
        mLockWallpaperFile = new File(wallpaperDir, WALLPAPER_LOCK);
        mWm = (WallpaperManager) getSystemService(Context.WALLPAPER_SERVICE);

        mQuotaFile = new File(getFilesDir(), QUOTA_SENTINEL);
        mQuotaExceeded = mQuotaFile.exists();
        if (DEBUG) {
            Slog.v(TAG, "quota file " + mQuotaFile.getPath() + " exists=" + mQuotaExceeded);
        }
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        // To avoid data duplication and disk churn, use links as the stage.
        final File filesDir = getFilesDir();
        final File infoStage = new File(filesDir, INFO_STAGE);
        final File imageStage = new File (filesDir, IMAGE_STAGE);
        final File lockImageStage = new File (filesDir, LOCK_IMAGE_STAGE);
        final File empty = new File (filesDir, EMPTY_SENTINEL);

        try {
            // We always back up this 'empty' file to ensure that the absence of
            // storable wallpaper imagery still produces a non-empty backup data
            // stream, otherwise it'd simply be ignored in preflight.
            FileOutputStream touch = new FileOutputStream(empty);
            touch.close();
            fullBackupFile(empty, data);

            // only back up the wallpaper if we've been told it's allowed
            if (mWm.isWallpaperBackupEligible()) {
                if (DEBUG) {
                    Slog.v(TAG, "Wallpaper is backup-eligible; linking & writing");
                }

                // In case of prior muddled state
                infoStage.delete();
                imageStage.delete();
                lockImageStage.delete();

                Os.link(mWallpaperInfo.getCanonicalPath(), infoStage.getCanonicalPath());
                fullBackupFile(infoStage, data);
                Os.link(mWallpaperFile.getCanonicalPath(), imageStage.getCanonicalPath());
                fullBackupFile(imageStage, data);

                // Don't try to store the lock image if we overran our quota last time
                if (!mQuotaExceeded) {
                    Os.link(mLockWallpaperFile.getCanonicalPath(), lockImageStage.getCanonicalPath());
                    fullBackupFile(lockImageStage, data);
                }
            } else {
                if (DEBUG) {
                    Slog.v(TAG, "Wallpaper not backup-eligible; writing no data");
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to back up wallpaper: " + e.getMessage());
        } finally {
            if (DEBUG) {
                Slog.v(TAG, "Removing backup stage links");
            }
            infoStage.delete();
            imageStage.delete();
            lockImageStage.delete();

            // Even if this time we had to back off on attempting to store the lock image
            // due to exceeding the data quota, try again next time.  This will alternate
            // between "try both" and "only store the primary image" until either there
            // is no lock image to store, or the quota is raised, or both fit under the
            // quota.
            mQuotaFile.delete();
        }
    }

    @Override
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        if (DEBUG) {
            Slog.i(TAG, "Quota exceeded (" + backupDataBytes + " vs " + quotaBytes + ')');
        }
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
        if (DEBUG) {
            Slog.v(TAG, "onRestoreFinished()");
        }
        final File filesDir = getFilesDir();
        final File infoStage = new File(filesDir, INFO_STAGE);
        final File imageStage = new File (filesDir, IMAGE_STAGE);
        final File lockImageStage = new File (filesDir, LOCK_IMAGE_STAGE);

        try {
            // It is valid for the imagery to be absent; it means that we were not permitted
            // to back up the original image on the source device, or there was no user-supplied
            // wallpaper image present.
            restoreFromStage(imageStage, infoStage, "wp", WallpaperManager.FLAG_SYSTEM);
            restoreFromStage(lockImageStage, infoStage, "kwp", WallpaperManager.FLAG_LOCK);
        } catch (Exception e) {
            Slog.e(TAG, "Unable to restore wallpaper: " + e.getMessage());
        } finally {
            if (DEBUG) {
                Slog.v(TAG, "Removing restore stage files");
            }
            infoStage.delete();
            imageStage.delete();
            lockImageStage.delete();
        }
    }

    private void restoreFromStage(File stage, File info, String hintTag, int which)
            throws IOException {
        if (stage.exists()) {
            if (DEBUG) {
                Slog.v(TAG, "Got restored wallpaper; applying which=" + which);
            }
            // Parse the restored info file to find the crop hint.  Note that this currently
            // relies on a priori knowledge of the wallpaper info file schema.
            Rect cropHint = parseCropHint(info, hintTag);
            if (cropHint != null) {
                if (DEBUG) {
                    Slog.v(TAG, "Restored crop hint " + cropHint + "; now writing data");
                }
                try (FileInputStream in = new FileInputStream(stage)) {
                    mWm.setStream(in, cropHint, true, which);
                } finally {} // auto-closes 'in'
            }
        }
    }

    private Rect parseCropHint(File wallpaperInfo, String sectionTag) {
        Rect cropHint = new Rect();
        try (FileInputStream stream = new FileInputStream(wallpaperInfo)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());

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
            Slog.w(TAG, "Failed to parse restored metadata: " + e.getMessage());
            return null;
        }

        return cropHint;
    }

    private int getAttributeInt(XmlPullParser parser, String name, int defValue) {
        final String value = parser.getAttributeValue(null, name);
        return (value == null) ? defValue : Integer.parseInt(value);
    }

    //
    // Key/value API: abstract, therefore required; but not used
    //

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {
        // Intentionally blank
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        // Intentionally blank
    }
}