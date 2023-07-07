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

package com.android.server.wallpaper;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

class WallpaperUtils {

    static final String WALLPAPER = "wallpaper_orig";
    static final String WALLPAPER_CROP = "wallpaper";
    static final String WALLPAPER_LOCK_ORIG = "wallpaper_lock_orig";
    static final String WALLPAPER_LOCK_CROP = "wallpaper_lock";
    static final String WALLPAPER_INFO = "wallpaper_info.xml";
    static final String RECORD_FILE = "decode_record";
    static final String RECORD_LOCK_FILE = "decode_lock_record";

    // All the various per-user state files we need to be aware of
    private static final String[] sPerUserFiles = new String[] {
            WALLPAPER, WALLPAPER_CROP,
            WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP,
            WALLPAPER_INFO
    };

    /**
     * ID of the current wallpaper, incremented every time anything sets a wallpaper.
     * This is used for external detection of wallpaper update activity.
     */
    private static int sWallpaperId;

    static File getWallpaperDir(int userId) {
        return Environment.getUserSystemDirectory(userId);
    }

    /**
     * generate a new wallpaper id
     * should be called with the {@link WallpaperManagerService} lock held
     */
    static int makeWallpaperIdLocked() {
        do {
            ++sWallpaperId;
        } while (sWallpaperId == 0);
        return sWallpaperId;
    }

    /**
     * returns the id of the current wallpaper (the last one that has been set)
     */
    static int getCurrentWallpaperId() {
        return sWallpaperId;
    }

    /**
     * sets the id of the current wallpaper
     * used when a wallpaper with higher id than current is loaded from settings
     */
    static void setCurrentWallpaperId(int id) {
        sWallpaperId = id;
    }

    static List<File> getWallpaperFiles(int userId) {
        File wallpaperDir = getWallpaperDir(userId);
        List<File> result = new ArrayList<File>();
        for (int i = 0; i < sPerUserFiles.length; i++) {
            result.add(new File(wallpaperDir, sPerUserFiles[i]));
        }
        return result;
    }
}
