/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import static org.junit.Assert.assertEquals;

import android.content.res.Configuration;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.DisplayAdjustments.FixedRotationAdjustments;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link DisplayAdjustmentsTests}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:DisplayAdjustmentsTests
 */
@RunWith(AndroidJUnit4.class)
public class DisplayAdjustmentsTests {

    @Test
    public void testDefaultConstructor_hasEmptyConfiguration() {
        DisplayAdjustments emptyAdjustments = new DisplayAdjustments();

        assertEquals(Configuration.EMPTY, emptyAdjustments.getConfiguration());
    }

    @Test
    public void testConfigurationConstructor_nullConfigurationBecomesEmpty() {
        DisplayAdjustments emptyAdjustments = new DisplayAdjustments((Configuration) null);

        assertEquals(Configuration.EMPTY, emptyAdjustments.getConfiguration());
    }

    @Test
    public void testConfigurationConstructor_copiesConfiguration() {
        Configuration configuration = new Configuration();
        configuration.colorMode = 1000;
        DisplayAdjustments adjustments = new DisplayAdjustments(configuration);

        assertEquals(configuration, adjustments.getConfiguration());
    }

    @Test
    public void testDisplayAdjustmentsConstructor_copiesConfiguration() {
        Configuration configuration = new Configuration();
        configuration.colorMode = 1000;
        DisplayAdjustments oldAdjustments = new DisplayAdjustments(configuration);

        DisplayAdjustments newAdjustments = new DisplayAdjustments(oldAdjustments);

        assertEquals(configuration, newAdjustments.getConfiguration());
    }

    @Test
    public void testFixedRotationAdjustments() {
        final DisplayAdjustments mDisplayAdjustments = new DisplayAdjustments();
        final int realRotation = Surface.ROTATION_0;
        final int fixedRotation = Surface.ROTATION_90;

        final int appWidth = 1080;
        final int appHeight = 1920;
        mDisplayAdjustments.setFixedRotationAdjustments(new FixedRotationAdjustments(
                fixedRotation, appWidth, appHeight, null /* cutout */));

        final int w = 1000;
        final int h = 2000;
        final Point size = new Point(w, h);
        mDisplayAdjustments.adjustSize(size, realRotation);

        assertEquals(fixedRotation, mDisplayAdjustments.getRotation(realRotation));
        assertEquals(new Point(h, w), size);

        final DisplayMetrics metrics = new DisplayMetrics();
        metrics.xdpi = metrics.noncompatXdpi = w;
        metrics.widthPixels = metrics.noncompatWidthPixels = w;
        metrics.ydpi = metrics.noncompatYdpi = h;
        metrics.heightPixels = metrics.noncompatHeightPixels = h;

        final DisplayMetrics flippedMetrics = new DisplayMetrics();
        // The physical dpi should not be adjusted.
        flippedMetrics.xdpi = flippedMetrics.noncompatXdpi = w;
        flippedMetrics.widthPixels = flippedMetrics.noncompatWidthPixels = h;
        flippedMetrics.ydpi = flippedMetrics.noncompatYdpi = h;
        flippedMetrics.heightPixels = flippedMetrics.noncompatHeightPixels = w;

        mDisplayAdjustments.adjustMetrics(metrics, realRotation);

        assertEquals(flippedMetrics, metrics);

        mDisplayAdjustments.adjustGlobalAppMetrics(metrics);

        assertEquals(appWidth, metrics.widthPixels);
        assertEquals(appWidth, metrics.noncompatWidthPixels);
        assertEquals(appHeight, metrics.heightPixels);
        assertEquals(appHeight, metrics.noncompatHeightPixels);
    }
}
