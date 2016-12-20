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

package com.android.server.storage;

import android.content.pm.PackageStats;
import android.test.AndroidTestCase;
import android.util.ArraySet;
import libcore.io.IoUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.ArrayList;

import static com.google.common.truth.Truth.assertThat;

@RunWith(JUnit4.class)
public class DiskStatsFileLoggerTest extends AndroidTestCase {
    @Rule public TemporaryFolder temporaryFolder;
    public FileCollector.MeasurementResult mMainResult;
    public FileCollector.MeasurementResult mDownloadsResult;
    private ArrayList<PackageStats> mPackages;
    private File mOutputFile;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        temporaryFolder = new TemporaryFolder();
        temporaryFolder.create();
        mOutputFile = temporaryFolder.newFile();
        mMainResult = new FileCollector.MeasurementResult();
        mDownloadsResult = new FileCollector.MeasurementResult();
        mPackages = new ArrayList<>();
    }

    @Test
    public void testEmptyStorage() throws Exception {
        DiskStatsFileLogger logger = new DiskStatsFileLogger(
                mMainResult, mDownloadsResult,mPackages, 0L);

        logger.dumpToFile(mOutputFile);

        JSONObject output = getOutputFileAsJson();
        assertThat(output.getLong(DiskStatsFileLogger.PHOTOS_KEY)).isEqualTo(0L);
        assertThat(output.getLong(DiskStatsFileLogger.VIDEOS_KEY)).isEqualTo(0L);
        assertThat(output.getLong(DiskStatsFileLogger.AUDIO_KEY)).isEqualTo(0L);
        assertThat(output.getLong(DiskStatsFileLogger.DOWNLOADS_KEY)).isEqualTo(0L);
        assertThat(output.getLong(DiskStatsFileLogger.SYSTEM_KEY)).isEqualTo(0L);
        assertThat(output.getLong(DiskStatsFileLogger.MISC_KEY)).isEqualTo(0L);
        assertThat(output.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY)).isEqualTo(0L);
        assertThat(output.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY)).isEqualTo(0L);
        assertThat(
                output.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY).length()).isEqualTo(0L);
        assertThat(output.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY).length()).isEqualTo(0L);
        assertThat(output.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY).length()).isEqualTo(0L);
    }

    @Test
    public void testMeasurementResultsReported() throws Exception {
        mMainResult.audioSize = 1;
        mMainResult.imagesSize = 10;
        mMainResult.miscSize = 100;
        mDownloadsResult.miscSize = 1000;
        DiskStatsFileLogger logger = new DiskStatsFileLogger(
                mMainResult, mDownloadsResult,mPackages, 3L);

        logger.dumpToFile(mOutputFile);

        JSONObject output = getOutputFileAsJson();
        assertThat(output.getLong(DiskStatsFileLogger.AUDIO_KEY)).isEqualTo(1L);
        assertThat(output.getLong(DiskStatsFileLogger.PHOTOS_KEY)).isEqualTo(10L);
        assertThat(output.getLong(DiskStatsFileLogger.MISC_KEY)).isEqualTo(100L);
        assertThat(output.getLong(DiskStatsFileLogger.DOWNLOADS_KEY)).isEqualTo(1000L);
        assertThat(output.getLong(DiskStatsFileLogger.SYSTEM_KEY)).isEqualTo(3L);
    }

    @Test
    public void testAppsReported() throws Exception {
        PackageStats firstPackage = new PackageStats("com.test.app");
        firstPackage.codeSize = 100;
        firstPackage.dataSize = 1000;
        firstPackage.cacheSize = 20;
        mPackages.add(firstPackage);

        PackageStats secondPackage = new PackageStats("com.test.app2");
        secondPackage.codeSize = 10;
        secondPackage.dataSize = 1;
        secondPackage.cacheSize = 2;
        mPackages.add(secondPackage);

        DiskStatsFileLogger logger = new DiskStatsFileLogger(
                mMainResult, mDownloadsResult, mPackages, 0L);
        logger.dumpToFile(mOutputFile);

        JSONObject output = getOutputFileAsJson();
        assertThat(output.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY)).isEqualTo(1111);
        assertThat(output.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY)).isEqualTo(22);

        JSONArray packageNames = output.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
        assertThat(packageNames.length()).isEqualTo(2);
        JSONArray appSizes = output.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
        assertThat(appSizes.length()).isEqualTo(2);
        JSONArray cacheSizes = output.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
        assertThat(cacheSizes.length()).isEqualTo(2);

        // We need to do this crazy Set over this because the DiskStatsFileLogger provides no
        // guarantee of the ordering of the apps in its output. By using a set, we avoid any order
        // problems.
        ArraySet<AppSizeGrouping> apps = new ArraySet<>();
        for (int i = 0; i < packageNames.length(); i++) {
            AppSizeGrouping app = new AppSizeGrouping(packageNames.getString(i),
                    appSizes.getLong(i), cacheSizes.getLong(i));
            apps.add(app);
        }
        assertThat(apps).containsAllOf(new AppSizeGrouping("com.test.app", 1100, 20),
                new AppSizeGrouping("com.test.app2", 11, 2));
    }

    @Test
    public void testEmulatedExternalStorageCounted() throws Exception {
        PackageStats app = new PackageStats("com.test.app");
        app.dataSize = 1000;
        app.externalDataSize = 1000;
        app.cacheSize = 20;
        mPackages.add(app);

        DiskStatsFileLogger logger = new DiskStatsFileLogger(
                mMainResult, mDownloadsResult, mPackages, 0L);
        logger.dumpToFile(mOutputFile);

        JSONObject output = getOutputFileAsJson();
        JSONArray appSizes = output.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
        assertThat(appSizes.length()).isEqualTo(1);
        assertThat(appSizes.getLong(0)).isEqualTo(2000);
    }

    @Test
    public void testDuplicatePackageNameIsMergedAcrossMultipleUsers() throws Exception {
        PackageStats app = new PackageStats("com.test.app");
        app.dataSize = 1000;
        app.externalDataSize = 1000;
        app.cacheSize = 20;
        app.userHandle = 0;
        mPackages.add(app);

        PackageStats secondApp = new PackageStats("com.test.app");
        secondApp.dataSize = 100;
        secondApp.externalDataSize = 100;
        secondApp.cacheSize = 2;
        secondApp.userHandle = 1;
        mPackages.add(secondApp);

        DiskStatsFileLogger logger = new DiskStatsFileLogger(
                mMainResult, mDownloadsResult, mPackages, 0L);
        logger.dumpToFile(mOutputFile);

        JSONObject output = getOutputFileAsJson();
        assertThat(output.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY)).isEqualTo(2200);
        assertThat(output.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY)).isEqualTo(22);
        JSONArray packageNames = output.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
        assertThat(packageNames.length()).isEqualTo(1);
        assertThat(packageNames.getString(0)).isEqualTo("com.test.app");

        JSONArray appSizes = output.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
        assertThat(appSizes.length()).isEqualTo(1);
        assertThat(appSizes.getLong(0)).isEqualTo(2200);

        JSONArray cacheSizes = output.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
        assertThat(cacheSizes.length()).isEqualTo(1);
        assertThat(cacheSizes.getLong(0)).isEqualTo(22);
    }

    private JSONObject getOutputFileAsJson() throws Exception {
        return new JSONObject(IoUtils.readFileAsString(mOutputFile.getAbsolutePath()));
    }

    /**
     * This class exists for putting zipped app size information arrays into a set for comparison
     * purposes.
     */
    private class AppSizeGrouping {
        public String packageName;
        public long appSize;
        public long cacheSize;

        public AppSizeGrouping(String packageName, long appSize, long cacheSize) {
            this.packageName = packageName;
            this.appSize = appSize;
            this.cacheSize = cacheSize;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 37 * result + (int)(appSize ^ (appSize >>> 32));
            result = 37 * result + (int)(cacheSize ^ (cacheSize >>> 32));
            result = 37 * result + packageName.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AppSizeGrouping)) {
                return false;
            }
            if (this == o) {
                return true;
            }
            AppSizeGrouping grouping = (AppSizeGrouping) o;
            return packageName.equals(grouping.packageName) && appSize == grouping.appSize &&
                    cacheSize == grouping.cacheSize;
        }

        @Override
        public String toString() {
            return packageName + " " + appSize + " " + cacheSize;
        }
    }
}