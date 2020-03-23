/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.content.res;

import android.annotation.NonNull;
import android.app.ResourcesManager;
import android.os.Binder;
import android.os.LocaleList;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayAdjustments;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

public class ResourcesManagerTest extends TestCase {
    private static final String APP_ONE_RES_DIR = "app_one.apk";
    private static final String APP_ONE_RES_SPLIT_DIR = "app_one_split.apk";
    private static final String APP_TWO_RES_DIR = "app_two.apk";
    private static final String LIB_RES_DIR = "lib.apk";

    private ResourcesManager mResourcesManager;
    private DisplayMetrics mDisplayMetrics;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mDisplayMetrics = new DisplayMetrics();
        mDisplayMetrics.setToDefaults();

        // Override defaults (which take device specific properties).
        mDisplayMetrics.density = 1.0f;
        mDisplayMetrics.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        mDisplayMetrics.xdpi = DisplayMetrics.DENSITY_DEFAULT;
        mDisplayMetrics.ydpi = DisplayMetrics.DENSITY_DEFAULT;
        mDisplayMetrics.noncompatDensity = mDisplayMetrics.density;
        mDisplayMetrics.noncompatDensityDpi = mDisplayMetrics.densityDpi;
        mDisplayMetrics.noncompatXdpi = DisplayMetrics.DENSITY_DEFAULT;
        mDisplayMetrics.noncompatYdpi = DisplayMetrics.DENSITY_DEFAULT;

        mResourcesManager = new ResourcesManager() {
            @Override
            protected AssetManager createAssetManager(@NonNull ResourcesKey key) {
                return new AssetManager();
            }

            @Override
            protected DisplayMetrics getDisplayMetrics(int displayId, DisplayAdjustments daj) {
                return mDisplayMetrics;
            }
        };
    }

    @SmallTest
    public void testMultipleCallsWithIdenticalParametersCacheReference() {
        Resources resources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources);

        Resources newResources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(newResources);
        assertSame(resources, newResources);
    }

    @SmallTest
    public void testMultipleCallsWithDifferentParametersReturnDifferentReferences() {
        Resources resources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources);

        Configuration overrideConfig = new Configuration();
        overrideConfig.smallestScreenWidthDp = 200;
        Resources newResources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, overrideConfig,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(newResources);
        assertNotSame(resources, newResources);
    }

    @SmallTest
    public void testAddingASplitCreatesANewImpl() {
        Resources resources1 = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        Resources resources2 = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, new String[] { APP_ONE_RES_SPLIT_DIR }, null, null,
                Display.DEFAULT_DISPLAY, null, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO,null,
                null);
        assertNotNull(resources2);

        assertNotSame(resources1, resources2);
        assertNotSame(resources1.getImpl(), resources2.getImpl());
    }

    @SmallTest
    public void testUpdateConfigurationUpdatesAllAssetManagers() {
        Resources resources1 = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        Resources resources2 = mResourcesManager.getResources(
                null, APP_TWO_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources2);

        Binder activity = new Binder();
        final Configuration overrideConfig = new Configuration();
        overrideConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;
        Resources resources3 = mResourcesManager.getResources(
                activity, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY,
                overrideConfig, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources3);

        // No Resources object should be the same.
        assertNotSame(resources1, resources2);
        assertNotSame(resources1, resources3);
        assertNotSame(resources2, resources3);

        // Each ResourcesImpl should be different.
        assertNotSame(resources1.getImpl(), resources2.getImpl());
        assertNotSame(resources1.getImpl(), resources3.getImpl());
        assertNotSame(resources2.getImpl(), resources3.getImpl());

        Configuration newConfig = new Configuration();
        newConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mResourcesManager.applyConfigurationToResourcesLocked(newConfig, null);

        final Configuration expectedConfig = new Configuration();
        expectedConfig.setToDefaults();
        expectedConfig.setLocales(LocaleList.getAdjustedDefault());
        expectedConfig.densityDpi = mDisplayMetrics.densityDpi;
        expectedConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;

        assertEquals(expectedConfig, resources1.getConfiguration());
        assertEquals(expectedConfig, resources2.getConfiguration());
        assertEquals(expectedConfig, resources3.getConfiguration());
    }

    @SmallTest
    public void testTwoActivitiesWithIdenticalParametersShareImpl() {
        Binder activity1 = new Binder();
        Resources resources1 = mResourcesManager.getResources(
                activity1, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        Binder activity2 = new Binder();
        Resources resources2 = mResourcesManager.getResources(
                activity2, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        // The references themselves should be unique.
        assertNotSame(resources1, resources2);

        // The implementations should be the same.
        assertSame(resources1.getImpl(), resources2.getImpl());
    }

    @SmallTest
    public void testThemesGetUpdatedWithNewImpl() {
        Binder activity1 = new Binder();
        Resources resources1 = mResourcesManager.createBaseTokenResources(
                activity1, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        Resources.Theme theme = resources1.newTheme();
        assertSame(resources1, theme.getResources());
        theme.applyStyle(android.R.style.Theme_NoTitleBar, false);

        TypedValue value = new TypedValue();
        assertTrue(theme.resolveAttribute(android.R.attr.windowNoTitle, value, true));
        assertEquals(TypedValue.TYPE_INT_BOOLEAN, value.type);
        assertTrue(value.data != 0);

        final Configuration overrideConfig = new Configuration();
        overrideConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mResourcesManager.updateResourcesForActivity(activity1, overrideConfig,
                Display.DEFAULT_DISPLAY, false /* movedToDifferentDisplay */);
        assertSame(resources1, theme.getResources());

        // Make sure we can still access the data.
        assertTrue(theme.resolveAttribute(android.R.attr.windowNoTitle, value, true));
        assertEquals(TypedValue.TYPE_INT_BOOLEAN, value.type);
        assertTrue(value.data != 0);
    }

    @SmallTest
    public void testMultipleResourcesForOneActivityGetUpdatedWhenActivityBaseUpdates() {
        Binder activity1 = new Binder();

        // Create a Resources for the Activity.
        Configuration config1 = new Configuration();
        config1.densityDpi = 280;
        Resources resources1 = mResourcesManager.createBaseTokenResources(
                activity1, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, config1,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        // Create a Resources based on the Activity.
        Configuration config2 = new Configuration();
        config2.screenLayout |= Configuration.SCREENLAYOUT_ROUND_YES;
        Resources resources2 = mResourcesManager.getResources(
                activity1, APP_ONE_RES_DIR, null, null, null, Display.DEFAULT_DISPLAY, config2,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources2);

        assertNotSame(resources1, resources2);
        assertNotSame(resources1.getImpl(), resources2.getImpl());

        final Configuration expectedConfig1 = new Configuration();
        expectedConfig1.setToDefaults();
        expectedConfig1.setLocales(LocaleList.getAdjustedDefault());
        expectedConfig1.densityDpi = 280;
        assertEquals(expectedConfig1, resources1.getConfiguration());

        // resources2 should be based on the Activity's override config, so the density should
        // be the same as resources1.
        final Configuration expectedConfig2 = new Configuration();
        expectedConfig2.setToDefaults();
        expectedConfig2.setLocales(LocaleList.getAdjustedDefault());
        expectedConfig2.densityDpi = 280;
        expectedConfig2.screenLayout |= Configuration.SCREENLAYOUT_ROUND_YES;
        assertEquals(expectedConfig2, resources2.getConfiguration());

        // Now update the Activity base override, and both resources should update.
        config1.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mResourcesManager.updateResourcesForActivity(activity1, config1, Display.DEFAULT_DISPLAY,
                false /* movedToDifferentDisplay */);

        expectedConfig1.orientation = Configuration.ORIENTATION_LANDSCAPE;
        assertEquals(expectedConfig1, resources1.getConfiguration());

        expectedConfig2.orientation = Configuration.ORIENTATION_LANDSCAPE;
        assertEquals(expectedConfig2, resources2.getConfiguration());
    }
}
