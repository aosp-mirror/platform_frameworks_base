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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Benchmarks for {@link android.content.res.Resources.Theme}.
 */
@LargeTest
public class ResourcesThemePerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Context mContext;
    private int mThemeResId;
    private Resources.Theme mTheme;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mThemeResId = com.android.perftests.core.R.style.Base_V7_Theme_AppCompat;
        mTheme = mContext.getResources().newTheme();
        mTheme.applyStyle(mThemeResId, true /* force */);
    }

    @Test
    public void applyStyle() {
        Resources.Theme destTheme = mContext.getResources().newTheme();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            destTheme.applyStyle(mThemeResId, true /* force */);
        }
    }

    @Test
    public void rebase() {
        Resources.Theme destTheme = mContext.getResources().newTheme();
        destTheme.applyStyle(mThemeResId, true /* force */);
        destTheme.applyStyle(android.R.style.Theme_Material, true /* force */);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            destTheme.rebase();
        }
    }

    @Test
    public void setToSameAssetManager() {
        Resources.Theme destTheme = mContext.getResources().newTheme();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            destTheme.setTo(mTheme);
        }
    }

    @Test
    public void setToDifferentAssetManager() throws Exception {
        // Create a new Resources object with the same asset paths but a different AssetManager
        PackageManager packageManager = mContext.getApplicationContext().getPackageManager();
        ApplicationInfo ai = packageManager.getApplicationInfo(mContext.getPackageName(),
                UserHandle.myUserId());

        ResourcesManager resourcesManager = ResourcesManager.getInstance();
        Configuration c = resourcesManager.getConfiguration();
        c.orientation = (c.orientation == Configuration.ORIENTATION_PORTRAIT)
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;

        Resources destResources = resourcesManager.getResources(null, ai.sourceDir,
                ai.splitSourceDirs, ai.resourceDirs, ai.overlayPaths, ai.sharedLibraryFiles,
                Display.DEFAULT_DISPLAY, c, mContext.getResources().getCompatibilityInfo(),
                null, null);
        Assert.assertNotEquals(destResources.getAssets(), mContext.getAssets());

        Resources.Theme destTheme = destResources.newTheme();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            destTheme.setTo(mTheme);
        }
    }

    @Test
    public void obtainStyledAttributesForViewFromMaterial() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTheme.obtainStyledAttributes(android.R.style.Theme_Material, android.R.styleable.View);
        }
    }
}