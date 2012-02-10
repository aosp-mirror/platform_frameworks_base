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
import android.graphics.Point;
import android.os.ParcelFileDescriptor;
import android.util.Slog;
import android.view.Display;
import android.view.WindowManager;

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
    // TODO: Will need to change if backing up non-primary user's wallpaper
    public static final String WALLPAPER_IMAGE = "/data/system/users/0/wallpaper";
    public static final String WALLPAPER_INFO = "/data/system/users/0/wallpaper_info.xml";
    // Use old keys to keep legacy data compatibility and avoid writing two wallpapers
    public static final String WALLPAPER_IMAGE_KEY =
            "/data/data/com.android.settings/files/wallpaper";
    public static final String WALLPAPER_INFO_KEY = "/data/system/wallpaper_info.xml";

    // Stage file - should be adjacent to the WALLPAPER_IMAGE location.  The wallpapers
    // will be saved to this file from the restore stream, then renamed to the proper
    // location if it's deemed suitable.
    // TODO: Will need to change if backing up non-primary user's wallpaper
    private static final String STAGE_FILE = "/data/system/users/0/wallpaper-tmp";

    Context mContext;
    String[] mFiles;
    String[] mKeys;
    double mDesiredMinWidth;
    double mDesiredMinHeight;

    /**
     * Construct a helper for backing up / restoring the files at the given absolute locations
     * within the file system.
     *
     * @param context
     * @param files
     */
    public WallpaperBackupHelper(Context context, String[] files, String[] keys) {
        super(context);

        mContext = context;
        mFiles = files;
        mKeys = keys;

        WallpaperManager wpm;
        wpm = (WallpaperManager) context.getSystemService(Context.WALLPAPER_SERVICE);
        mDesiredMinWidth = (double) wpm.getDesiredMinimumWidth();
        mDesiredMinHeight = (double) wpm.getDesiredMinimumHeight();

        if (mDesiredMinWidth <= 0 || mDesiredMinHeight <= 0) {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            Point size = new Point();
            d.getSize(size);
            mDesiredMinWidth = size.x;
            mDesiredMinHeight = size.y;
        }

        if (DEBUG) {
            Slog.d(TAG, "dmW=" + mDesiredMinWidth + " dmH=" + mDesiredMinHeight);
        }
    }

    /**
     * Based on oldState, determine which of the files from the application's data directory
     * need to be backed up, write them to the data stream, and fill in newState with the
     * state as it exists now.
     */
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        performBackup_checked(oldState, data, newState, mFiles, mKeys);
    }

    /**
     * Restore one absolute file entity from the restore stream.  If we're restoring the
     * magic wallpaper file, take specific action to determine whether it is suitable for
     * the current device.
     */
    public void restoreEntity(BackupDataInputStream data) {
        final String key = data.getKey();
        if (isKeyInList(key, mKeys)) {
            if (key.equals(WALLPAPER_IMAGE_KEY)) {
                // restore the file to the stage for inspection
                File f = new File(STAGE_FILE);
                if (writeFile(f, data)) {

                    // Preflight the restored image's dimensions without loading it
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(STAGE_FILE, options);

                    if (DEBUG) Slog.d(TAG, "Restoring wallpaper image w=" + options.outWidth
                            + " h=" + options.outHeight);

                    // How much does the image differ from our preference?  The threshold
                    // here is set to accept any image larger than our target, because
                    // scaling down is acceptable; but to reject images that are deemed
                    // "too small" to scale up attractively.  The value 1.33 is just barely
                    // too low to pass Nexus 1 or Droid wallpapers for use on a Xoom, but
                    // will pass anything relatively larger.
                    double widthRatio = mDesiredMinWidth / options.outWidth;
                    double heightRatio = mDesiredMinHeight / options.outHeight;
                    if (widthRatio > 0 && widthRatio < 1.33
                            && heightRatio > 0 && heightRatio < 1.33) {
                        // sufficiently close to our resolution; go ahead and use it
                        if (DEBUG) Slog.d(TAG, "wallpaper dimension match; using");
                        f.renameTo(new File(WALLPAPER_IMAGE));
                        // TODO: spin a service to copy the restored image to sd/usb storage,
                        // since it does not exist anywhere other than the private wallpaper
                        // file.
                    } else {
                        if (DEBUG) Slog.d(TAG, "dimensions too far off: wr=" + widthRatio
                                + " hr=" + heightRatio);
                        f.delete();
                    }
                }
            } else if (key.equals(WALLPAPER_INFO_KEY)) {
                // XML file containing wallpaper info
                File f = new File(WALLPAPER_INFO);
                writeFile(f, data);
            }
        }
    }
}
