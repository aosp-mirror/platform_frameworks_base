/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app.backup;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.util.Slog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * We no longer back up wallpapers with this helper, but we do need to process restores
 * of legacy backup payloads.  We just take the restored image as-is and apply it as the
 * system wallpaper using the public "set the wallpaper" API.
 *
 * @hide
 */
public class WallpaperBackupHelper extends FileBackupHelperBase implements BackupHelper {
    private static final String TAG = "WallpaperBackupHelper";
    private static final boolean DEBUG = false;

    // Key that legacy wallpaper imagery was stored under
    public static final String WALLPAPER_IMAGE_KEY =
            "/data/data/com.android.settings/files/wallpaper";
    public static final String WALLPAPER_INFO_KEY = "/data/system/wallpaper_info.xml";

    // Stage file that the restored imagery is stored to prior to being applied
    // as the system wallpaper.
    private static final String STAGE_FILE =
            new File(Environment.getUserSystemDirectory(UserHandle.USER_SYSTEM),
                    "wallpaper-tmp").getAbsolutePath();

    private final String[] mKeys;
    private final WallpaperManager mWpm;

    /**
     * Legacy wallpaper restores, from back when the imagery was stored under the
     * "android" system package as file key/value entities.
     *
     * @param context
     * @param files
     */
    public WallpaperBackupHelper(Context context, String[] keys) {
        super(context);

        mContext = context;
        mKeys = keys;

        mWpm = (WallpaperManager) context.getSystemService(Context.WALLPAPER_SERVICE);
    }

    /**
     * Based on oldState, determine which of the files from the application's data directory
     * need to be backed up, write them to the data stream, and fill in newState with the
     * state as it exists now.
     */
    @Override
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        // Intentionally no-op; we don't back up the wallpaper this way any more.
    }

    /**
     * Restore one absolute file entity from the restore stream.  If we're restoring the
     * magic wallpaper file, apply it as the system wallpaper.
     */
    @Override
    public void restoreEntity(BackupDataInputStream data) {
        final String key = data.getKey();
        if (isKeyInList(key, mKeys)) {
            if (key.equals(WALLPAPER_IMAGE_KEY)) {
                // restore the file to the stage for inspection
                File stage = new File(STAGE_FILE);
                try {
                    if (writeFile(stage, data)) {
                        try (FileInputStream in = new FileInputStream(stage)) {
                            mWpm.setStream(in);
                        } catch (IOException e) {
                            Slog.e(TAG, "Unable to set restored wallpaper: " + e.getMessage());
                        }
                    } else {
                        Slog.e(TAG, "Unable to save restored wallpaper");
                    }
                } finally {
                    stage.delete();
                }
            }
        }
    }
}
