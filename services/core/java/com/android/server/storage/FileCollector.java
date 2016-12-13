/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.storage;

import android.annotation.IntDef;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.ArrayMap;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

/**
 * FileCollector walks over a directory and categorizes storage usage by their type.
 */
public class FileCollector {
    private static final int UNRECOGNIZED = -1;
    private static final int IMAGES = 0;
    private static final int VIDEO = 1;
    private static final int AUDIO = 2;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UNRECOGNIZED,
            IMAGES,
            VIDEO,
            AUDIO })
    private @interface FileTypes {}


    private static final Map<String, Integer> EXTENSION_MAP = new ArrayMap<String, Integer>();
    static {
        // Audio
        EXTENSION_MAP.put("aac", AUDIO);
        EXTENSION_MAP.put("amr", AUDIO);
        EXTENSION_MAP.put("awb", AUDIO);
        EXTENSION_MAP.put("snd", AUDIO);
        EXTENSION_MAP.put("flac", AUDIO);
        EXTENSION_MAP.put("mp3", AUDIO);
        EXTENSION_MAP.put("mpga", AUDIO);
        EXTENSION_MAP.put("mpega", AUDIO);
        EXTENSION_MAP.put("mp2", AUDIO);
        EXTENSION_MAP.put("m4a", AUDIO);
        EXTENSION_MAP.put("aif", AUDIO);
        EXTENSION_MAP.put("aiff", AUDIO);
        EXTENSION_MAP.put("aifc", AUDIO);
        EXTENSION_MAP.put("gsm", AUDIO);
        EXTENSION_MAP.put("mka", AUDIO);
        EXTENSION_MAP.put("m3u", AUDIO);
        EXTENSION_MAP.put("wma", AUDIO);
        EXTENSION_MAP.put("wax", AUDIO);
        EXTENSION_MAP.put("ra", AUDIO);
        EXTENSION_MAP.put("rm", AUDIO);
        EXTENSION_MAP.put("ram", AUDIO);
        EXTENSION_MAP.put("pls", AUDIO);
        EXTENSION_MAP.put("sd2", AUDIO);
        EXTENSION_MAP.put("wav", AUDIO);
        EXTENSION_MAP.put("ogg", AUDIO);
        EXTENSION_MAP.put("oga", AUDIO);
        // Video
        EXTENSION_MAP.put("3gpp", VIDEO);
        EXTENSION_MAP.put("3gp", VIDEO);
        EXTENSION_MAP.put("3gpp2", VIDEO);
        EXTENSION_MAP.put("3g2", VIDEO);
        EXTENSION_MAP.put("avi", VIDEO);
        EXTENSION_MAP.put("dl", VIDEO);
        EXTENSION_MAP.put("dif", VIDEO);
        EXTENSION_MAP.put("dv", VIDEO);
        EXTENSION_MAP.put("fli", VIDEO);
        EXTENSION_MAP.put("m4v", VIDEO);
        EXTENSION_MAP.put("ts", VIDEO);
        EXTENSION_MAP.put("mpeg", VIDEO);
        EXTENSION_MAP.put("mpg", VIDEO);
        EXTENSION_MAP.put("mpe", VIDEO);
        EXTENSION_MAP.put("mp4", VIDEO);
        EXTENSION_MAP.put("vob", VIDEO);
        EXTENSION_MAP.put("qt", VIDEO);
        EXTENSION_MAP.put("mov", VIDEO);
        EXTENSION_MAP.put("mxu", VIDEO);
        EXTENSION_MAP.put("webm", VIDEO);
        EXTENSION_MAP.put("lsf", VIDEO);
        EXTENSION_MAP.put("lsx", VIDEO);
        EXTENSION_MAP.put("mkv", VIDEO);
        EXTENSION_MAP.put("mng", VIDEO);
        EXTENSION_MAP.put("asf", VIDEO);
        EXTENSION_MAP.put("asx", VIDEO);
        EXTENSION_MAP.put("wm", VIDEO);
        EXTENSION_MAP.put("wmv", VIDEO);
        EXTENSION_MAP.put("wmx", VIDEO);
        EXTENSION_MAP.put("wvx", VIDEO);
        EXTENSION_MAP.put("movie", VIDEO);
        EXTENSION_MAP.put("wrf", VIDEO);
        // Images
        EXTENSION_MAP.put("bmp", IMAGES);
        EXTENSION_MAP.put("gif", IMAGES);
        EXTENSION_MAP.put("jpg", IMAGES);
        EXTENSION_MAP.put("jpeg", IMAGES);
        EXTENSION_MAP.put("jpe", IMAGES);
        EXTENSION_MAP.put("pcx", IMAGES);
        EXTENSION_MAP.put("png", IMAGES);
        EXTENSION_MAP.put("svg", IMAGES);
        EXTENSION_MAP.put("svgz", IMAGES);
        EXTENSION_MAP.put("tiff", IMAGES);
        EXTENSION_MAP.put("tif", IMAGES);
        EXTENSION_MAP.put("wbmp", IMAGES);
        EXTENSION_MAP.put("webp", IMAGES);
        EXTENSION_MAP.put("dng", IMAGES);
        EXTENSION_MAP.put("cr2", IMAGES);
        EXTENSION_MAP.put("ras", IMAGES);
        EXTENSION_MAP.put("art", IMAGES);
        EXTENSION_MAP.put("jng", IMAGES);
        EXTENSION_MAP.put("nef", IMAGES);
        EXTENSION_MAP.put("nrw", IMAGES);
        EXTENSION_MAP.put("orf", IMAGES);
        EXTENSION_MAP.put("rw2", IMAGES);
        EXTENSION_MAP.put("pef", IMAGES);
        EXTENSION_MAP.put("psd", IMAGES);
        EXTENSION_MAP.put("pnm", IMAGES);
        EXTENSION_MAP.put("pbm", IMAGES);
        EXTENSION_MAP.put("pgm", IMAGES);
        EXTENSION_MAP.put("ppm", IMAGES);
        EXTENSION_MAP.put("srw", IMAGES);
        EXTENSION_MAP.put("arw", IMAGES);
        EXTENSION_MAP.put("rgb", IMAGES);
        EXTENSION_MAP.put("xbm", IMAGES);
        EXTENSION_MAP.put("xpm", IMAGES);
        EXTENSION_MAP.put("xwd", IMAGES);
    }

    /**
     * Returns the file categorization measurement result.
     * @param path Directory to collect and categorize storage in.
     */
    public static MeasurementResult getMeasurementResult(File path) {
        return collectFiles(StorageManager.maybeTranslateEmulatedPathToInternal(path),
                new MeasurementResult());
    }

    /**
     * Returns the size of a system for a given context. This is done by finding the difference
     * between the shared data and the total primary storage size.
     * @param context Context to use to get storage information.
     */
    public static long getSystemSize(Context context) {
        PackageManager pm = context.getPackageManager();
        VolumeInfo primaryVolume = pm.getPrimaryStorageCurrentVolume();

        StorageManager sm = context.getSystemService(StorageManager.class);
        VolumeInfo shared = sm.findEmulatedForPrivate(primaryVolume);
        if (shared == null) {
            return 0;
        }

        final long sharedDataSize = shared.getPath().getTotalSpace();
        long systemSize = sm.getPrimaryStorageSize() - sharedDataSize;

        // This case is not exceptional -- we just fallback to the shared data volume in this case.
        if (systemSize <= 0) {
            return 0;
        }

        return systemSize;
    }

    private static MeasurementResult collectFiles(File file, MeasurementResult result) {
        File[] files = file.listFiles();

        if (files == null) {
            return result;
        }

        for (File f : files) {
            if (f.isDirectory()) {
                try {
                    collectFiles(f, result);
                } catch (StackOverflowError e) {
                    return result;
                }
            } else {
                handleFile(result, f);
            }
        }

        return result;
    }

    private static void handleFile(MeasurementResult result, File f) {
        long fileSize = f.length();
        int fileType = EXTENSION_MAP.getOrDefault(getExtensionForFile(f), UNRECOGNIZED);
        switch (fileType) {
            case AUDIO:
                result.audioSize += fileSize;
                break;
            case VIDEO:
                result.videosSize += fileSize;
                break;
            case IMAGES:
                result.imagesSize += fileSize;
                break;
            default:
                result.miscSize += fileSize;
        }
    }

    private static String getExtensionForFile(File file) {
        String fileName = file.getName();
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase();
    }

    /**
     * MeasurementResult contains a storage categorization result.
     */
    public static class MeasurementResult {
        public long imagesSize;
        public long videosSize;
        public long miscSize;
        public long audioSize;

        /**
         * Sums up the storage taken by all of the categorizable sizes in the measurement.
         */
        public long totalAccountedSize() {
            return imagesSize + videosSize + miscSize + audioSize;
        }
    }
}
