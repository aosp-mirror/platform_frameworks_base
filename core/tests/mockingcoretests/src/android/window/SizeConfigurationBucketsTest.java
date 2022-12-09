/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import static android.content.pm.ActivityInfo.CONFIG_LOCALE;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.res.Configuration.SCREENLAYOUT_COMPAT_NEEDED;
import static android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_LTR;
import static android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_RTL;
import static android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_UNDEFINED;
import static android.content.res.Configuration.SCREENLAYOUT_LONG_NO;
import static android.content.res.Configuration.SCREENLAYOUT_LONG_YES;
import static android.content.res.Configuration.SCREENLAYOUT_ROUND_NO;
import static android.content.res.Configuration.SCREENLAYOUT_ROUND_UNDEFINED;
import static android.content.res.Configuration.SCREENLAYOUT_ROUND_YES;
import static android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE;
import static android.content.res.Configuration.SCREENLAYOUT_SIZE_NORMAL;
import static android.content.res.Configuration.SCREENLAYOUT_SIZE_SMALL;
import static android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Tests for {@link SizeConfigurationBuckets}
 *
 * Build/Install/Run:
 *  atest FrameworksMockingCoreTests:SizeConfigurationBucketsTest
 */

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SizeConfigurationBucketsTest {

    /**
     * Tests that a change in any of the non-size-related screen layout fields results in
     * {@link SizeConfigurationBuckets#areNonSizeLayoutFieldsUnchanged} returning false.
     */
    @Test
    public void testNonSizeRelatedScreenLayoutFields() {
        // Test layout direction
        assertEquals(true, SizeConfigurationBuckets
                .areNonSizeLayoutFieldsUnchanged(0, SCREENLAYOUT_LAYOUTDIR_UNDEFINED));
        assertEquals(false, SizeConfigurationBuckets
                .areNonSizeLayoutFieldsUnchanged(0, SCREENLAYOUT_LAYOUTDIR_LTR));
        assertEquals(false, SizeConfigurationBuckets
                .areNonSizeLayoutFieldsUnchanged(0, SCREENLAYOUT_LAYOUTDIR_RTL));

        // Test layout roundness
        assertEquals(true, SizeConfigurationBuckets
                .areNonSizeLayoutFieldsUnchanged(0, SCREENLAYOUT_ROUND_UNDEFINED));
        assertEquals(false, SizeConfigurationBuckets
                .areNonSizeLayoutFieldsUnchanged(0, SCREENLAYOUT_ROUND_NO));
        assertEquals(false, SizeConfigurationBuckets
                .areNonSizeLayoutFieldsUnchanged(0, SCREENLAYOUT_ROUND_YES));

        // Test layout compat needed
        assertEquals(false, SizeConfigurationBuckets
                .areNonSizeLayoutFieldsUnchanged(0, SCREENLAYOUT_COMPAT_NEEDED));
    }

    /**
     * Tests that {@code null} size configuration buckets do not unflip the configuration flags.
     */
    @Test
    public void testNullSizeConfigurationBuckets() {
        final int diff = CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE | CONFIG_SCREEN_LAYOUT
                | CONFIG_LOCALE;
        final int filteredDiffNonSizeLayoutUnchanged = SizeConfigurationBuckets.filterDiff(diff,
                Configuration.EMPTY, Configuration.EMPTY, null);
        assertEquals(diff, filteredDiffNonSizeLayoutUnchanged);
    }

    /**
     * Tests that {@link SizeConfigurationBuckets.crossesSizeThreshold()} correctly checks whether
     * the bucket thresholds have or have not been crossed. This test includes boundary checks
     * to ensure that arithmetic is inclusive and exclusive in the right places.
     */
    @Test
    public void testCrossesSizeThreshold() {
        final int[] thresholds = new int[] { 360, 600 };
        final int nThresholds = thresholds.length;
        for (int i = -1; i < nThresholds; i++) {
            final int minValueInBucket = i < 0 ? 0 : thresholds[i];
            final int maxValueInBucket = i < nThresholds - 1
                    ? thresholds[i + 1] - 1 : Integer.MAX_VALUE;
            final int bucketRange = maxValueInBucket - minValueInBucket;
            // Set old value to 1/4 in between the two thresholds.
            final int oldValue = (int) (minValueInBucket + bucketRange * 0.25);
            // Test 3 values of new value spread across bucket range: minValueInBucket, bucket
            // midpoint, and max value in bucket. In all 3 cases, the bucket has not changed so
            // {@link SizeConfigurationBuckets#crossedSizeThreshold()} should return false.
            checkCrossesSizeThreshold(thresholds, oldValue, minValueInBucket, false);
            checkCrossesSizeThreshold(thresholds, oldValue,
                    (int) (minValueInBucket + bucketRange * 0.5), false);
            checkCrossesSizeThreshold(thresholds, oldValue, maxValueInBucket, false);
            // Test 4 values of size spread outside of bucket range: more than 1 less than min
            // value, 1 less than min value, 1 more than max value, and more than 1 more than max
            // value. In all 4 cases, the bucket has changed so
            // {@link SizeConfigurationBuckets#crossedSizeThreshold()} should return true.
            // Only test less than min value if min value > 0.
            if (minValueInBucket > 0) {
                checkCrossesSizeThreshold(thresholds, oldValue, minValueInBucket - 20, true);
                checkCrossesSizeThreshold(thresholds, oldValue, minValueInBucket - 1, true);
            }
            // Only test greater than max value if not in highest bucket.
            if (i < nThresholds - 1) {
                checkCrossesSizeThreshold(thresholds, oldValue, maxValueInBucket + 1, true);
                checkCrossesSizeThreshold(thresholds, oldValue, maxValueInBucket + 20, true);
            }
        }
    }

    /**
     * Tests that if screen layout size changed but did not cross a threshold, the filtered diff
     * does not include screen layout.
     */
    @Test
    public void testScreenLayoutFilteredIfSizeDidNotCrossThreshold() {
        // Set only small and large sizes
        final Configuration[] sizeConfigs = new Configuration[2];
        sizeConfigs[0] = new Configuration();
        sizeConfigs[0].screenLayout |= SCREENLAYOUT_SIZE_SMALL;
        sizeConfigs[1] = new Configuration();
        sizeConfigs[1].screenLayout |= SCREENLAYOUT_SIZE_LARGE;
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(sizeConfigs);

        // Change screen layout size from small to normal and check that screen layout flag is
        // not part of the diff because a threshold was not crossed.
        final int diff = CONFIG_SCREEN_LAYOUT;
        final Configuration oldConfig = new Configuration();
        oldConfig.screenLayout |= SCREENLAYOUT_SIZE_SMALL;
        final Configuration newConfig = new Configuration();
        newConfig.screenLayout |= SCREENLAYOUT_SIZE_NORMAL;
        final int filteredDiff = SizeConfigurationBuckets.filterDiff(diff, oldConfig, newConfig,
                sizeBuckets);
        assertEquals(0, filteredDiff);

        // If a non-size attribute of screen layout changed, then screen layout should not be
        // filtered from the diff.
        newConfig.screenLayout |= SCREENLAYOUT_ROUND_YES;
        final int filteredDiffNonSizeLayoutChanged = SizeConfigurationBuckets.filterDiff(diff,
                oldConfig, newConfig, sizeBuckets);
        assertEquals(CONFIG_SCREEN_LAYOUT, filteredDiffNonSizeLayoutChanged);
    }

    /**
     * Tests that if screen layout size changed and did cross a threshold, the filtered diff
     * includes screen layout.
     */
    @Test
    public void testScreenLayoutNotFilteredIfSizeCrossedThreshold() {
        // Set only small and normal sizes
        final Configuration[] sizeConfigs = new Configuration[2];
        sizeConfigs[0] = new Configuration();
        sizeConfigs[0].screenLayout |= SCREENLAYOUT_SIZE_SMALL;
        sizeConfigs[1] = new Configuration();
        sizeConfigs[1].screenLayout |= SCREENLAYOUT_SIZE_NORMAL;
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(sizeConfigs);

        // Change screen layout size from small to normal and check that screen layout flag is
        // still part of the diff because a threshold was crossed.
        final int diff = CONFIG_SCREEN_LAYOUT;
        final Configuration oldConfig = new Configuration();
        oldConfig.screenLayout |= SCREENLAYOUT_SIZE_SMALL;
        final Configuration newConfig = new Configuration();
        newConfig.screenLayout |= SCREENLAYOUT_SIZE_NORMAL;
        final int filteredDiff = SizeConfigurationBuckets.filterDiff(diff, oldConfig, newConfig,
                sizeBuckets);
        assertEquals(CONFIG_SCREEN_LAYOUT, filteredDiff);
    }

    /**
     * Tests that anytime screen layout size is decreased, the filtered diff still includes screen
     * layout.
     */
    @Test
    public void testScreenLayoutNotFilteredIfSizeDecreased() {
        // The size thresholds can be anything, but can't be null
        final int[] horizontalThresholds = new int[] { 360, 600 };
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(
                horizontalThresholds, null /* vertical */, null /* smallest */,
                null /* screenLayoutSize */, false /* screenLayoutLongSet */);
        final int[] sizeValuesInOrder = new int[] {
                SCREENLAYOUT_SIZE_SMALL, SCREENLAYOUT_SIZE_NORMAL, SCREENLAYOUT_SIZE_LARGE,
                SCREENLAYOUT_SIZE_XLARGE
        };
        final int nSizes = sizeValuesInOrder.length;
        for (int larger = nSizes - 1; larger > 0; larger--) {
            for (int smaller = larger - 1; smaller >= 0; smaller--) {
                final Configuration oldConfig = new Configuration();
                oldConfig.screenLayout |= sizeValuesInOrder[larger];
                final Configuration newConfig = new Configuration();
                newConfig.screenLayout |= sizeValuesInOrder[smaller];
                assertTrue(String.format("oldSize=%d, newSize=%d", oldConfig.screenLayout,
                        newConfig.screenLayout),
                        sizeBuckets.crossesScreenLayoutSizeThreshold(oldConfig, newConfig));
            }
        }
    }

    /**
     * Tests that if screen layout long changed but did not cross a threshold, the filtered diff
     * does not include screen layout.
     */
    @Test
    public void testScreenLayoutFilteredIfLongDidNotCrossThreshold() {
        // Do not set any long threshold
        final Configuration[] sizeConfigs = new Configuration[1];
        sizeConfigs[0] = Configuration.EMPTY;
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(sizeConfigs);

        // Change screen layout long from not long to long and check that screen layout flag is
        // not part of the diff because a threshold was not crossed.
        final int diff = CONFIG_SCREEN_LAYOUT;
        final Configuration oldConfig = new Configuration();
        oldConfig.screenLayout |= SCREENLAYOUT_LONG_NO;
        final Configuration newConfig = new Configuration();
        newConfig.screenLayout |= SCREENLAYOUT_LONG_YES;
        final int filteredDiff = SizeConfigurationBuckets.filterDiff(diff, oldConfig, newConfig,
                sizeBuckets);
        assertEquals(0, filteredDiff);

        // If a non-size attribute of screen layout changed, then screen layout should not be
        // filtered from the diff.
        newConfig.screenLayout |= SCREENLAYOUT_ROUND_YES;
        final int filteredDiffNonSizeLayoutChanged = SizeConfigurationBuckets.filterDiff(diff,
                oldConfig, newConfig, sizeBuckets);
        assertEquals(CONFIG_SCREEN_LAYOUT, filteredDiffNonSizeLayoutChanged);
    }

    /**
     * Tests that if screen layout long changed and did cross a threshold, the filtered diff
     * includes screen layout.
     */
    @Test
    public void testScreenLayoutNotFilteredIfLongCrossedThreshold() {
        // Set only small and normal sizes
        final Configuration[] sizeConfigs = new Configuration[1];
        sizeConfigs[0] = new Configuration();
        sizeConfigs[0].screenLayout |= SCREENLAYOUT_LONG_NO;
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(sizeConfigs);

        // Change screen layout long from not long to long and check that screen layout flag is
        // still part of the diff because a threshold was crossed.
        final int diff = CONFIG_SCREEN_LAYOUT;
        final Configuration oldConfig = new Configuration();
        oldConfig.screenLayout |= SCREENLAYOUT_LONG_NO;
        final Configuration newConfig = new Configuration();
        newConfig.screenLayout |= SCREENLAYOUT_LONG_YES;
        final int filteredDiff = SizeConfigurationBuckets.filterDiff(diff, oldConfig, newConfig,
                sizeBuckets);
        assertEquals(CONFIG_SCREEN_LAYOUT, filteredDiff);
    }

    /**
     * Tests that horizontal buckets are correctly checked in
     * {@link SizeConfigurationBuckets#filterDiff()}.
     */
    @Test
    public void testHorizontalSizeThresholds() {
        final int[] horizontalThresholds = new int[] { 360, 600 };
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(
                horizontalThresholds, null /* vertical */, null /* smallest */,
                null /* screenLayoutSize */, false /* screenLayoutLongSet */);

        final Configuration oldConfig = new Configuration();
        final Configuration newConfig = new Configuration();

        oldConfig.screenWidthDp = 480;
        // Test that value within bucket filters out screen size config
        newConfig.screenWidthDp = 520;
        assertEquals(0, SizeConfigurationBuckets.filterDiff(CONFIG_SCREEN_SIZE, oldConfig,
                newConfig, sizeBuckets));
        // Test that value outside bucket does not filter out screen size config
        newConfig.screenWidthDp = 640;
        assertEquals(CONFIG_SCREEN_SIZE, SizeConfigurationBuckets.filterDiff(CONFIG_SCREEN_SIZE,
                oldConfig, newConfig, sizeBuckets));
    }

    /**
     * Tests that vertical buckets are correctly checked in
     * {@link SizeConfigurationBuckets#filterDiff()}.
     */
    @Test
    public void testVerticalSizeThresholds() {
        final int[] verticalThresholds = new int[] { 360, 600 };
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(
                null, verticalThresholds /* vertical */, null /* smallest */,
                null /* screenLayoutSize */, false /* screenLayoutLongSet */);

        final Configuration oldConfig = new Configuration();
        final Configuration newConfig = new Configuration();

        oldConfig.screenHeightDp = 480;
        // Test that value within bucket filters out screen size config
        newConfig.screenHeightDp = 520;
        assertEquals(0, SizeConfigurationBuckets.filterDiff(CONFIG_SCREEN_SIZE, oldConfig,
                newConfig, sizeBuckets));
        // Test that value outside bucket does not filter out screen size config
        newConfig.screenHeightDp = 640;
        assertEquals(CONFIG_SCREEN_SIZE, SizeConfigurationBuckets.filterDiff(CONFIG_SCREEN_SIZE,
                oldConfig, newConfig, sizeBuckets));
    }

    /**
     * Tests that smallest width buckets are correctly checked in
     * {@link SizeConfigurationBuckets#filterDiff()}.
     */
    @Test
    public void testSmallestWidthSizeThresholds() {
        final int[] smallestWidthThresholds = new int[] { 360, 600 };
        final SizeConfigurationBuckets sizeBuckets = new SizeConfigurationBuckets(
                null, null /* vertical */, smallestWidthThresholds /* smallest */,
                null /* screenLayoutSize */, false /* screenLayoutLongSet */);

        final Configuration oldConfig = new Configuration();
        final Configuration newConfig = new Configuration();

        oldConfig.smallestScreenWidthDp = 480;
        // Test that value within bucket filters out smallest screen size config
        newConfig.smallestScreenWidthDp = 520;
        assertEquals(0, SizeConfigurationBuckets.filterDiff(CONFIG_SMALLEST_SCREEN_SIZE, oldConfig,
                newConfig, sizeBuckets));
        // Test that value outside bucket does not filter out smallest screen size config
        newConfig.smallestScreenWidthDp = 640;
        assertEquals(CONFIG_SMALLEST_SCREEN_SIZE, SizeConfigurationBuckets.filterDiff(
                CONFIG_SMALLEST_SCREEN_SIZE, oldConfig, newConfig, sizeBuckets));
    }

    private void checkCrossesSizeThreshold(int[] thresholds, int oldValue, int newValue,
            boolean expected) {
        final String errorString = String.format(
                "thresholds=%s, oldValue=%d, newValue=%d, expected=%b", Arrays.toString(thresholds),
                oldValue, newValue, expected);
        final boolean actual = SizeConfigurationBuckets.crossesSizeThreshold(thresholds, oldValue,
                newValue);
        assertEquals(errorString, expected, actual);
    }
}
