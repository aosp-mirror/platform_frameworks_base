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
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

import java.io.File;

/**
 * Helper for backing up / restoring wallpapers.  Basically an AbsoluteFileBackupHelper,
 * but with logic for deciding what to do with restored wallpaper images.
 *
 * @hide
 */
public class WallpaperBackupHelper extends FileBackupHelperBase implements BackupHelper {
    private static final String TAG = "WallpaperBackupHelper";
    private static final boolean DEBUG = false;

    // This path must match what the WallpaperManagerService uses
    private static final String WALLPAPER_IMAGE = "/data/data/com.android.settings/files/wallpaper";

    // Stage file - should be adjacent to the WALLPAPER_IMAGE location.  The wallpapers
    // will be saved to this file from the restore stream, then renamed to the proper
    // location if it's deemed suitable.
    private static final String STAGE_FILE = "/data/data/com.android.settings/files/wallpaper-tmp";

    Context mContext;
    String[] mFiles;
    double mDesiredMinWidth;
    double mDesiredMinHeight;

    /**
     * Construct a helper for backing up / restoring the files at the given absolute locations
     * within the file system.
     *
     * @param context
     * @param files
     */
    public WallpaperBackupHelper(Context context, String... files) {
        super(context);

        mContext = context;
        mFiles = files;

        WallpaperManager wpm;
        wpm = (WallpaperManager) context.getSystemService(Context.WALLPAPER_SERVICE);
        mDesiredMinWidth = (double) wpm.getDesiredMinimumWidth();
        mDesiredMinHeight = (double) wpm.getDesiredMinimumHeight();
    }

    /**
     * Based on oldState, determine which of the files from the application's data directory
     * need to be backed up, write them to the data stream, and fill in newState with the
     * state as it exists now.
     */
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        performBackup_checked(oldState, data, newState, mFiles, mFiles);
    }

    /**
     * Restore one absolute file entity from the restore stream.  If we're restoring the
     * magic wallpaper file, take specific action to determine whether it is suitable for
     * the current device.
     */
    public void restoreEntity(BackupDataInputStream data) {
        final String key = data.getKey();
        if (isKeyInList(key, mFiles)) {
            if (key.equals(WALLPAPER_IMAGE)) {
                // restore the file to the stage for inspection
                File f = new File(STAGE_FILE);
                if (writeFile(f, data)) {

                    // Preflight the restored image's dimensions without loading it
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(STAGE_FILE, options);

                    if (DEBUG) Slog.v(TAG, "Restoring wallpaper image w=" + options.outWidth
                            + " h=" + options.outHeight);

                    // how much does the image differ from our preference?
                    double widthRatio = mDesiredMinWidth / options.outWidth;
                    double heightRatio = mDesiredMinHeight / options.outHeight;
                    if (widthRatio > 0.8 && widthRatio < 1.25
                            && heightRatio > 0.8 && heightRatio < 1.25) {
                        // sufficiently close to our resolution; go ahead and use it
                        if (DEBUG) Slog.v(TAG, "wallpaper dimension match; using");
                        f.renameTo(new File(WALLPAPER_IMAGE));
                        // TODO: spin a service to copy the restored image to sd/usb storage,
                        // since it does not exist anywhere other than the private wallpaper
                        // file.
                    } else {
                        if (DEBUG) Slog.v(TAG, "dimensions too far off: wr=" + widthRatio
                                + " hr=" + heightRatio);
                        f.delete();
                    }
                }
            } else {
                // Some other normal file; just decode it to its destination
                File f = new File(key);
                writeFile(f, data);
            }
        }
    }
}
