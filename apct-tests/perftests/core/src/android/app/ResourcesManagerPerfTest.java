/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.app;

import android.content.Context;
import android.content.res.Configuration;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Benchmarks for {@link android.app.ResourcesManager}.
 */
@LargeTest
public class ResourcesManagerPerfTest {
    private static Context sContext;
    private static File sResourcesCompressed;
    private static File sResourcesUncompressed;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @BeforeClass
    public static void setUp() throws Exception {
        sContext = InstrumentationRegistry.getTargetContext();
        sResourcesCompressed = copyApkToTemp("LargeResourcesCompressed.apk",
                "LargeResourcesCompressed.apk");
        sResourcesUncompressed = copyApkToTemp("LargeResourcesUncompressed.apk",
                "LargeResourcesUncompressed.apk");
    }

    @AfterClass
    public static void tearDown() {
        Assert.assertTrue(sResourcesCompressed.delete());
        Assert.assertTrue(sResourcesUncompressed.delete());
    }

    private static File copyApkToTemp(String inputFileName, String fileName) throws Exception {
        File file = File.createTempFile(fileName, null, sContext.getCacheDir());
        try (OutputStream tempOutputStream = new FileOutputStream(file);
             InputStream is = sContext.getResources().getAssets().openNonAsset(inputFileName)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                tempOutputStream.write(buffer, 0, n);
            }
            tempOutputStream.flush();
        }
        return file;
    }

    private void getResourcesForPath(String path) {
        ResourcesManager.getInstance().getResources(null, path, null, null, null, null,
                Display.DEFAULT_DISPLAY, null, sContext.getResources().getCompatibilityInfo(),
                null, null);
    }

    @Test
    public void getResourcesCached() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        getResourcesForPath(sResourcesCompressed.getPath());
        while (state.keepRunning()) {
            getResourcesForPath(sResourcesCompressed.getPath());
        }
    }

    @Test
    public void getResourcesCompressedUncached() {
        ResourcesManager resourcesManager = ResourcesManager.getInstance();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            resourcesManager.invalidatePath(sResourcesCompressed.getPath());
            state.resumeTiming();

            getResourcesForPath(sResourcesCompressed.getPath());
        }
    }

    @Test
    public void getResourcesUncompressedUncached() {
        ResourcesManager resourcesManager = ResourcesManager.getInstance();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            resourcesManager.invalidatePath(sResourcesUncompressed.getPath());
            state.resumeTiming();

            getResourcesForPath(sResourcesUncompressed.getPath());
        }
    }

    @Test
    public void applyConfigurationToResourcesLocked() {
        ResourcesManager resourcesManager = ResourcesManager.getInstance();
        Configuration c = new Configuration(resourcesManager.getConfiguration());
        c.uiMode = Configuration.UI_MODE_TYPE_WATCH;

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            resourcesManager.applyConfigurationToResources(c, null);

            // Alternate configurations to ensure the set configuration is different each iteration
            if (c.uiMode == Configuration.UI_MODE_TYPE_WATCH) {
                c.uiMode = Configuration.UI_MODE_TYPE_TELEVISION;
            } else {
                c.uiMode = Configuration.UI_MODE_TYPE_WATCH;
            }
        }
    }

    @Test
    public void getDisplayMetrics() {
        ResourcesManager resourcesManager = ResourcesManager.getInstance();

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            // Invalidate cache.
            resourcesManager.applyConfigurationToResources(
                    resourcesManager.getConfiguration(), null);
            state.resumeTiming();

            // Invoke twice for testing cache.
            resourcesManager.getDisplayMetrics();
            resourcesManager.getDisplayMetrics();
        }
    }
}
