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

package com.android.systemui.pip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.util.Size;
import android.view.DisplayInfo;
import android.view.Gravity;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests against {@link PipBoundsHandler}, including but not limited to:
 * - default/movement bounds
 * - save/restore PiP position on application lifecycle
 * - save/restore PiP position on screen rotation
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class PipBoundsHandlerTest extends SysuiTestCase {
    private static final int ROUNDING_ERROR_MARGIN = 16;
    private static final float ASPECT_RATIO_ERROR_MARGIN = 0.01f;
    private static final float DEFAULT_ASPECT_RATIO = 1f;
    private static final float MIN_ASPECT_RATIO = 0.5f;
    private static final float MAX_ASPECT_RATIO = 2f;
    private static final Rect EMPTY_CURRENT_BOUNDS = null;
    private static final Size EMPTY_MINIMAL_SIZE = null;

    private PipBoundsHandler mPipBoundsHandler;
    private DisplayInfo mDefaultDisplayInfo;

    @Before
    public void setUp() throws Exception {
        initializeMockResources();
        mPipBoundsHandler = new PipBoundsHandler(mContext, new PipSnapAlgorithm(mContext));

        mPipBoundsHandler.onDisplayInfoChanged(mDefaultDisplayInfo);
    }

    private void initializeMockResources() {
        final TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                com.android.internal.R.dimen.config_pictureInPictureDefaultAspectRatio,
                DEFAULT_ASPECT_RATIO);
        res.addOverride(
                com.android.internal.R.integer.config_defaultPictureInPictureGravity,
                Gravity.END | Gravity.BOTTOM);
        res.addOverride(
                com.android.internal.R.dimen.default_minimal_size_pip_resizable_task, 100);
        res.addOverride(
                com.android.internal.R.string.config_defaultPictureInPictureScreenEdgeInsets,
                "16x16");
        res.addOverride(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio,
                MIN_ASPECT_RATIO);
        res.addOverride(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio,
                MAX_ASPECT_RATIO);

        mDefaultDisplayInfo = new DisplayInfo();
        mDefaultDisplayInfo.displayId = 1;
        mDefaultDisplayInfo.logicalWidth = 1000;
        mDefaultDisplayInfo.logicalHeight = 1500;
    }

    @Test
    public void getDefaultAspectRatio() {
        assertEquals("Default aspect ratio matches resources",
                DEFAULT_ASPECT_RATIO, mPipBoundsHandler.getDefaultAspectRatio(),
                ASPECT_RATIO_ERROR_MARGIN);
    }

    @Test
    public void onConfigurationChanged_reloadResources() {
        final float newDefaultAspectRatio = (DEFAULT_ASPECT_RATIO + MAX_ASPECT_RATIO) / 2;
        final TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(com.android.internal.R.dimen.config_pictureInPictureDefaultAspectRatio,
                newDefaultAspectRatio);

        mPipBoundsHandler.onConfigurationChanged();

        assertEquals("Default aspect ratio should be reloaded",
                mPipBoundsHandler.getDefaultAspectRatio(), newDefaultAspectRatio,
                ASPECT_RATIO_ERROR_MARGIN);
    }

    @Test
    public void getDestinationBounds_returnBoundsMatchesAspectRatio() {
        final float[] aspectRatios = new float[] {
                (MIN_ASPECT_RATIO + DEFAULT_ASPECT_RATIO) / 2,
                DEFAULT_ASPECT_RATIO,
                (MAX_ASPECT_RATIO + DEFAULT_ASPECT_RATIO) / 2
        };
        for (float aspectRatio : aspectRatios) {
            final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                    aspectRatio, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);
            final float actualAspectRatio =
                    destinationBounds.width() / (destinationBounds.height() * 1f);
            assertEquals("Destination bounds matches the given aspect ratio",
                    aspectRatio, actualAspectRatio, ASPECT_RATIO_ERROR_MARGIN);
        }
    }

    @Test
    public void getDestinationBounds_invalidAspectRatio_returnsDefaultAspectRatio() {
        final float[] invalidAspectRatios = new float[] {
                MIN_ASPECT_RATIO / 2,
                MAX_ASPECT_RATIO * 2
        };
        for (float aspectRatio : invalidAspectRatios) {
            final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                    aspectRatio, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);
            final float actualAspectRatio =
                    destinationBounds.width() / (destinationBounds.height() * 1f);
            assertEquals("Destination bounds fallbacks to default aspect ratio",
                    mPipBoundsHandler.getDefaultAspectRatio(), actualAspectRatio,
                    ASPECT_RATIO_ERROR_MARGIN);
        }
    }

    @Test
    public void  getDestinationBounds_withCurrentBounds_returnBoundsMatchesAspectRatio() {
        final float aspectRatio = (DEFAULT_ASPECT_RATIO + MAX_ASPECT_RATIO) / 2;
        final Rect currentBounds = new Rect(0, 0, 0, 100);
        currentBounds.right = (int) (currentBounds.height() * aspectRatio) + currentBounds.left;

        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                aspectRatio, currentBounds, EMPTY_MINIMAL_SIZE);

        final float actualAspectRatio =
                destinationBounds.width() / (destinationBounds.height() * 1f);
        assertEquals("Destination bounds matches the given aspect ratio",
                aspectRatio, actualAspectRatio, ASPECT_RATIO_ERROR_MARGIN);
    }

    @Test
    public void getDestinationBounds_withMinSize_returnMinBounds() {
        final float[] aspectRatios = new float[] {
                (MIN_ASPECT_RATIO + DEFAULT_ASPECT_RATIO) / 2,
                DEFAULT_ASPECT_RATIO,
                (MAX_ASPECT_RATIO + DEFAULT_ASPECT_RATIO) / 2
        };
        final Size[] minimalSizes = new Size[] {
                new Size((int) (100 * aspectRatios[0]), 100),
                new Size((int) (100 * aspectRatios[1]), 100),
                new Size((int) (100 * aspectRatios[2]), 100)
        };
        for (int i = 0; i < aspectRatios.length; i++) {
            final float aspectRatio = aspectRatios[i];
            final Size minimalSize = minimalSizes[i];
            final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                    aspectRatio, EMPTY_CURRENT_BOUNDS, minimalSize);
            assertTrue("Destination bounds is no smaller than minimal requirement",
                    (destinationBounds.width() == minimalSize.getWidth()
                            && destinationBounds.height() >= minimalSize.getHeight())
                            || (destinationBounds.height() == minimalSize.getHeight()
                            && destinationBounds.width() >= minimalSize.getWidth()));
            final float actualAspectRatio =
                    destinationBounds.width() / (destinationBounds.height() * 1f);
            assertEquals("Destination bounds matches the given aspect ratio",
                    aspectRatio, actualAspectRatio, ASPECT_RATIO_ERROR_MARGIN);
        }
    }

    @Test
    public void getDestinationBounds_withCurrentBounds_ignoreMinBounds() {
        final float aspectRatio = (DEFAULT_ASPECT_RATIO + MAX_ASPECT_RATIO) / 2;
        final Rect currentBounds = new Rect(0, 0, 0, 100);
        currentBounds.right = (int) (currentBounds.height() * aspectRatio) + currentBounds.left;
        final Size minSize = new Size(currentBounds.width() / 2, currentBounds.height() / 2);

        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                aspectRatio, currentBounds, minSize);

        assertTrue("Destination bounds ignores minimal size",
                destinationBounds.width() > minSize.getWidth()
                        && destinationBounds.height() > minSize.getHeight());
    }

    @Test
    public void setShelfHeight_offsetBounds() {
        final int shelfHeight = 100;
        final Rect oldPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        mPipBoundsHandler.setShelfHeight(true, shelfHeight);
        final Rect newPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        oldPosition.offset(0, -shelfHeight);
        assertBoundsWithMargin("offsetBounds by shelf", oldPosition, newPosition);
    }

    @Test
    public void onImeVisibilityChanged_offsetBounds() {
        final int imeHeight = 100;
        final Rect oldPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        mPipBoundsHandler.onImeVisibilityChanged(true, imeHeight);
        final Rect newPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        oldPosition.offset(0, -imeHeight);
        assertBoundsWithMargin("offsetBounds by IME", oldPosition, newPosition);
    }

    @Test
    public void onSaveReentryBounds_restoreLastPosition() {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final Rect oldPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        oldPosition.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, oldPosition);

        final Rect newPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        assertBoundsWithMargin("restoreLastPosition", oldPosition, newPosition);
    }

    @Test
    public void onResetReentryBounds_useDefaultBounds() {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final Rect defaultBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);
        final Rect newBounds = new Rect(defaultBounds);
        newBounds.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, newBounds);

        mPipBoundsHandler.onResetReentryBounds(componentName);
        final Rect actualBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        assertBoundsWithMargin("useDefaultBounds", defaultBounds, actualBounds);
    }

    @Test
    public void onResetReentryBounds_componentMismatch_restoreLastPosition() {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final Rect defaultBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);
        final Rect newBounds = new Rect(defaultBounds);
        newBounds.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, newBounds);

        mPipBoundsHandler.onResetReentryBounds(new ComponentName(mContext, "component2"));
        final Rect actualBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS, EMPTY_MINIMAL_SIZE);

        assertBoundsWithMargin("restoreLastPosition", newBounds, actualBounds);
    }

    private void assertBoundsWithMargin(String from, Rect expected, Rect actual) {
        final Rect expectedWithMargin = new Rect(expected);
        expectedWithMargin.inset(-ROUNDING_ERROR_MARGIN, -ROUNDING_ERROR_MARGIN);
        assertTrue(from + ": expect " + expected
                + " contains " + actual
                + " with error margin " + ROUNDING_ERROR_MARGIN,
                expectedWithMargin.contains(actual));
    }
}
