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

import java.util.HashMap;
import java.util.Map;

public class ResourcesManagerTest extends TestCase {
    private static final int SECONDARY_DISPLAY_ID = 1;
    private static final String APP_ONE_RES_DIR = "app_one.apk";
    private static final String APP_ONE_RES_SPLIT_DIR = "app_one_split.apk";
    private static final String APP_TWO_RES_DIR = "app_two.apk";
    private static final String LIB_RES_DIR = "lib.apk";

    private ResourcesManager mResourcesManager;
    private Map<Integer, DisplayMetrics> mDisplayMetricsMap;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mDisplayMetricsMap = new HashMap<>();

        DisplayMetrics defaultDisplayMetrics = new DisplayMetrics();
        defaultDisplayMetrics.setToDefaults();

        // Override defaults (which take device specific properties).
        defaultDisplayMetrics.density = 1.0f;
        defaultDisplayMetrics.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        defaultDisplayMetrics.xdpi = DisplayMetrics.DENSITY_DEFAULT;
        defaultDisplayMetrics.ydpi = DisplayMetrics.DENSITY_DEFAULT;
        defaultDisplayMetrics.widthPixels = 1440;
        defaultDisplayMetrics.heightPixels = 2960;
        defaultDisplayMetrics.noncompatDensity = defaultDisplayMetrics.density;
        defaultDisplayMetrics.noncompatDensityDpi = defaultDisplayMetrics.densityDpi;
        defaultDisplayMetrics.noncompatXdpi = DisplayMetrics.DENSITY_DEFAULT;
        defaultDisplayMetrics.noncompatYdpi = DisplayMetrics.DENSITY_DEFAULT;
        defaultDisplayMetrics.noncompatWidthPixels = defaultDisplayMetrics.widthPixels;
        defaultDisplayMetrics.noncompatHeightPixels = defaultDisplayMetrics.heightPixels;
        mDisplayMetricsMap.put(Display.DEFAULT_DISPLAY, defaultDisplayMetrics);

        DisplayMetrics secondaryDisplayMetrics = new DisplayMetrics();
        secondaryDisplayMetrics.setTo(defaultDisplayMetrics);
        secondaryDisplayMetrics.widthPixels = 50;
        secondaryDisplayMetrics.heightPixels = 100;
        secondaryDisplayMetrics.noncompatWidthPixels = secondaryDisplayMetrics.widthPixels;
        secondaryDisplayMetrics.noncompatHeightPixels = secondaryDisplayMetrics.heightPixels;
        mDisplayMetricsMap.put(SECONDARY_DISPLAY_ID, secondaryDisplayMetrics);

        mResourcesManager = new ResourcesManager() {
            @Override
            protected AssetManager createAssetManager(@NonNull ResourcesKey key) {
                return new AssetManager();
            }

            @Override
            protected DisplayMetrics getDisplayMetrics(int displayId, DisplayAdjustments daj) {
                return mDisplayMetricsMap.get(displayId);
            }
        };
    }

    @SmallTest
    public void testMultipleCallsWithIdenticalParametersCacheReference() {
        Resources resources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, null, null, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources);

        Resources newResources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, null, null, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(newResources);
        assertSame(resources, newResources);
    }

    @SmallTest
    public void testMultipleCallsWithDifferentParametersReturnDifferentReferences() {
        Resources resources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, null, null, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources);

        Configuration overrideConfig = new Configuration();
        overrideConfig.smallestScreenWidthDp = 200;
        Resources newResources = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, null, null, overrideConfig,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(newResources);
        assertNotSame(resources, newResources);
    }

    @SmallTest
    public void testAddingASplitCreatesANewImpl() {
        Resources resources1 = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, null, null, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        Resources resources2 = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, new String[] { APP_ONE_RES_SPLIT_DIR }, null, null, null,
                null, null, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null,
                null);
        assertNotNull(resources2);

        assertNotSame(resources1, resources2);
        assertNotSame(resources1.getImpl(), resources2.getImpl());
    }

    @SmallTest
    public void testUpdateConfigurationUpdatesAllAssetManagers() {
        Resources resources1 = mResourcesManager.getResources(
                null, APP_ONE_RES_DIR, null, null, null, null, null, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        Resources resources2 = mResourcesManager.getResources(
                null, APP_TWO_RES_DIR, null, null, null, null, null, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources2);

        Binder activity = new Binder();
        final Configuration overrideConfig = new Configuration();
        overrideConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;
        Resources resources3 = mResourcesManager.getResources(
                activity, APP_ONE_RES_DIR, null, null, null, null, null,
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
        mResourcesManager.applyConfigurationToResources(newConfig, null);

        final Configuration expectedConfig = new Configuration();
        expectedConfig.setToDefaults();
        expectedConfig.setLocales(LocaleList.getAdjustedDefault());
        expectedConfig.densityDpi = mDisplayMetricsMap.get(Display.DEFAULT_DISPLAY).densityDpi;
        expectedConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;

        assertEquals(expectedConfig, resources1.getConfiguration());
        assertEquals(expectedConfig, resources2.getConfiguration());
        assertEquals(expectedConfig, resources3.getConfiguration());
    }

    @SmallTest
    public void testTwoActivitiesWithIdenticalParametersShareImpl() {
        Binder activity1 = new Binder();
        Resources resources1 = mResourcesManager.getResources(
                activity1, APP_ONE_RES_DIR, null, null, null, null, null, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        Binder activity2 = new Binder();
        Resources resources2 = mResourcesManager.getResources(
                activity2, APP_ONE_RES_DIR, null, null, null, null, null, null,
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
                activity1, APP_ONE_RES_DIR, null, null, null, null, Display.DEFAULT_DISPLAY, null,
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
                Display.DEFAULT_DISPLAY);
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
                activity1, APP_ONE_RES_DIR, null, null, null, null, Display.DEFAULT_DISPLAY,
                config1, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        assertNotNull(resources1);

        // Create a Resources based on the Activity.
        Configuration config2 = new Configuration();
        config2.screenLayout |= Configuration.SCREENLAYOUT_ROUND_YES;
        Resources resources2 = mResourcesManager.getResources(
                activity1, APP_ONE_RES_DIR, null, null, null, null, null, config2,
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
        mResourcesManager.updateResourcesForActivity(activity1, config1, Display.DEFAULT_DISPLAY);

        expectedConfig1.orientation = Configuration.ORIENTATION_LANDSCAPE;
        assertEquals(expectedConfig1, resources1.getConfiguration());

        expectedConfig2.orientation = Configuration.ORIENTATION_LANDSCAPE;
        assertEquals(expectedConfig2, resources2.getConfiguration());
    }

    @SmallTest
    public void testOverrideDisplayAdjustments() {
        final int originalOverrideDensity = 200;
        final int overrideDisplayDensity = 400;
        final Binder token = new Binder();
        final Configuration overrideConfig = new Configuration();
        overrideConfig.densityDpi = originalOverrideDensity;
        final Resources resources = mResourcesManager.createBaseTokenResources(
                token, APP_ONE_RES_DIR, null /* splitResDirs */, null /* legacyOverlayDirs */,
                null /* overlayDirs */,null /* libDirs */, Display.DEFAULT_DISPLAY, overrideConfig,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null /* classLoader */,
                null /* loaders */);

        // Update the override.
        boolean handled = mResourcesManager.overrideTokenDisplayAdjustments(token,
                adjustments -> adjustments.getConfiguration().densityDpi = overrideDisplayDensity);

        assertTrue(handled);
        assertTrue(resources.hasOverrideDisplayAdjustments());
        assertEquals(overrideDisplayDensity,
                resources.getDisplayAdjustments().getConfiguration().densityDpi);

        // Clear the override.
        handled = mResourcesManager.overrideTokenDisplayAdjustments(token, null /* override */);

        assertTrue(handled);
        assertFalse(resources.hasOverrideDisplayAdjustments());
        assertEquals(originalOverrideDensity,
                resources.getDisplayAdjustments().getConfiguration().densityDpi);
    }

    @SmallTest
    public void testChangingActivityDisplayDoesntOverrideDisplayRequestedByResources() {
        Binder activity = new Binder();

        // Create a base token resources that are based on the default display.
        Resources activityResources = mResourcesManager.createBaseTokenResources(
                activity, APP_ONE_RES_DIR, null, null, null,null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);
        // Create another resources that explicitly override the display of the base token above
        // and set it to DEFAULT_DISPLAY.
        Resources defaultDisplayResources = mResourcesManager.getResources(
                activity, APP_ONE_RES_DIR, null, null, null, null, Display.DEFAULT_DISPLAY, null,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null, null);

        assertEquals(mDisplayMetricsMap.get(Display.DEFAULT_DISPLAY).widthPixels,
                activityResources.getDisplayMetrics().widthPixels);
        assertEquals(mDisplayMetricsMap.get(Display.DEFAULT_DISPLAY).heightPixels,
                activityResources.getDisplayMetrics().heightPixels);
        assertEquals(mDisplayMetricsMap.get(Display.DEFAULT_DISPLAY).widthPixels,
                defaultDisplayResources.getDisplayMetrics().widthPixels);
        assertEquals(mDisplayMetricsMap.get(Display.DEFAULT_DISPLAY).widthPixels,
                defaultDisplayResources.getDisplayMetrics().widthPixels);

        // Now change the display of the activity and ensure the activity's display metrics match
        // the new display, but the other resources remain based on the default display.
        mResourcesManager.updateResourcesForActivity(activity, null, SECONDARY_DISPLAY_ID);

        assertEquals(mDisplayMetricsMap.get(SECONDARY_DISPLAY_ID).widthPixels,
                activityResources.getDisplayMetrics().widthPixels);
        assertEquals(mDisplayMetricsMap.get(SECONDARY_DISPLAY_ID).heightPixels,
                activityResources.getDisplayMetrics().heightPixels);
        assertEquals(mDisplayMetricsMap.get(Display.DEFAULT_DISPLAY).widthPixels,
                defaultDisplayResources.getDisplayMetrics().widthPixels);
        assertEquals(mDisplayMetricsMap.get(Display.DEFAULT_DISPLAY).widthPixels,
                defaultDisplayResources.getDisplayMetrics().widthPixels);
    }
}
