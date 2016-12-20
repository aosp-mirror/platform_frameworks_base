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

import android.content.pm.PackageStats;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.Log;

import com.android.server.storage.FileCollector.MeasurementResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * DiskStatsFileLogger logs collected storage information to a file in a JSON format.
 *
 * The following information is cached in the file:
 * 1. Size of images on disk.
 * 2. Size of videos on disk.
 * 3. Size of audio on disk.
 * 4. Size of the downloads folder.
 * 5. System size.
 * 6. Aggregate and individual app and app cache sizes.
 * 7. How much storage couldn't be categorized in one of the above categories.
 */
public class DiskStatsFileLogger {
    private static final String TAG = "DiskStatsLogger";

    public static final String PHOTOS_KEY = "photosSize";
    public static final String VIDEOS_KEY = "videosSize";
    public static final String AUDIO_KEY = "audioSize";
    public static final String DOWNLOADS_KEY = "downloadsSize";
    public static final String SYSTEM_KEY = "systemSize";
    public static final String MISC_KEY = "otherSize";
    public static final String APP_SIZE_AGG_KEY = "appSize";
    public static final String APP_CACHE_AGG_KEY = "cacheSize";
    public static final String PACKAGE_NAMES_KEY = "packageNames";
    public static final String APP_SIZES_KEY = "appSizes";
    public static final String APP_CACHES_KEY = "cacheSizes";
    public static final String LAST_QUERY_TIMESTAMP_KEY = "queryTime";

    private MeasurementResult mResult;
    private long mDownloadsSize;
    private long mSystemSize;
    private List<PackageStats> mPackageStats;

    /**
     * Constructs a DiskStatsFileLogger with calculated measurement results.
     */
    public DiskStatsFileLogger(MeasurementResult result, MeasurementResult downloadsResult,
            List<PackageStats> stats, long systemSize) {
        mResult = result;
        mDownloadsSize = downloadsResult.totalAccountedSize();
        mSystemSize = systemSize;
        mPackageStats = stats;
    }

    /**
     * Dumps the storage collection output to a file.
     * @param file File to write the output into.
     * @throws FileNotFoundException
     */
    public void dumpToFile(File file) throws FileNotFoundException {
        PrintWriter pw = new PrintWriter(file);
        JSONObject representation = getJsonRepresentation();
        if (representation != null) {
            pw.println(representation);
        }
        pw.close();
    }

    private JSONObject getJsonRepresentation() {
        JSONObject json = new JSONObject();
        try {
            json.put(LAST_QUERY_TIMESTAMP_KEY, System.currentTimeMillis());
            json.put(PHOTOS_KEY, mResult.imagesSize);
            json.put(VIDEOS_KEY, mResult.videosSize);
            json.put(AUDIO_KEY, mResult.audioSize);
            json.put(DOWNLOADS_KEY, mDownloadsSize);
            json.put(SYSTEM_KEY, mSystemSize);
            json.put(MISC_KEY, mResult.miscSize);
            addAppsToJson(json);
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            return null;
        }

        return json;
    }

    private void addAppsToJson(JSONObject json) throws JSONException {
        JSONArray names = new JSONArray();
        JSONArray appSizeList = new JSONArray();
        JSONArray cacheSizeList = new JSONArray();

        long appSizeSum = 0L;
        long cacheSizeSum = 0L;
        boolean isExternal = Environment.isExternalStorageEmulated();
        for (Map.Entry<String, PackageStats> entry : mergePackagesAcrossUsers().entrySet()) {
            PackageStats stat = entry.getValue();
            long appSize = stat.codeSize + stat.dataSize;
            long cacheSize = stat.cacheSize;
            if (isExternal) {
                appSize += stat.externalCodeSize + stat.externalDataSize;
                cacheSize += stat.externalCacheSize;
            }
            appSizeSum += appSize;
            cacheSizeSum += cacheSize;

            names.put(stat.packageName);
            appSizeList.put(appSize);
            cacheSizeList.put(cacheSize);
        }
        json.put(PACKAGE_NAMES_KEY, names);
        json.put(APP_SIZES_KEY, appSizeList);
        json.put(APP_CACHES_KEY, cacheSizeList);
        json.put(APP_SIZE_AGG_KEY, appSizeSum);
        json.put(APP_CACHE_AGG_KEY, cacheSizeSum);
    }

    /**
     * A given package may exist for multiple users with distinct sizes. This function merges
     * the duplicated packages together and sums up their sizes to get the actual totals for the
     * package.
     * @return A mapping of package name to merged package stats.
     */
    private ArrayMap<String, PackageStats> mergePackagesAcrossUsers() {
        ArrayMap<String, PackageStats> packageMap = new ArrayMap<>();
        for (PackageStats stat : mPackageStats) {
            PackageStats existingStats = packageMap.get(stat.packageName);
            if (existingStats != null) {
                existingStats.cacheSize += stat.cacheSize;
                existingStats.codeSize += stat.codeSize;
                existingStats.dataSize += stat.dataSize;
                existingStats.externalCacheSize += stat.externalCacheSize;
                existingStats.externalCodeSize += stat.externalCodeSize;
                existingStats.externalDataSize += stat.externalDataSize;
            } else {
                packageMap.put(stat.packageName, new PackageStats(stat));
            }
        }
        return packageMap;
    }
}