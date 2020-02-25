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

import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
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
    private static final int ROUNDING_ERROR_MARGIN = 10;
    private static final float DEFAULT_ASPECT_RATIO = 1f;
    private static final Rect EMPTY_CURRENT_BOUNDS = null;

    private PipBoundsHandler mPipBoundsHandler;
    private DisplayInfo mDefaultDisplayInfo;

    @Before
    public void setUp() throws Exception {
        mPipBoundsHandler = new PipBoundsHandler(mContext);
        initializeMockResources();

        mPipBoundsHandler.onDisplayInfoChanged(mDefaultDisplayInfo);
    }

    private void initializeMockResources() {
        final TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                com.android.internal.R.dimen.config_pictureInPictureDefaultAspectRatio, 1f);
        res.addOverride(
                com.android.internal.R.integer.config_defaultPictureInPictureGravity,
                Gravity.END | Gravity.BOTTOM);
        res.addOverride(
                com.android.internal.R.dimen.default_minimal_size_pip_resizable_task, 100);
        res.addOverride(
                com.android.internal.R.string.config_defaultPictureInPictureScreenEdgeInsets,
                "16x16");
        res.addOverride(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio, 0.5f);
        res.addOverride(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio, 2f);

        mDefaultDisplayInfo = new DisplayInfo();
        mDefaultDisplayInfo.displayId = 1;
        mDefaultDisplayInfo.logicalWidth = 1000;
        mDefaultDisplayInfo.logicalHeight = 1500;
    }

    @Test
    public void setShelfHeight_offsetBounds() {
        final int shelfHeight = 100;
        final Rect oldPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        mPipBoundsHandler.setShelfHeight(true, shelfHeight);
        final Rect newPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        oldPosition.offset(0, -shelfHeight);
        assertBoundsWithMargin("PiP bounds offset by shelf height", oldPosition, newPosition);
    }

    @Test
    public void onImeVisibilityChanged_offsetBounds() {
        final int imeHeight = 100;
        final Rect oldPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        mPipBoundsHandler.onImeVisibilityChanged(true, imeHeight);
        final Rect newPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        oldPosition.offset(0, -imeHeight);
        assertBoundsWithMargin("PiP bounds offset by IME height", oldPosition, newPosition);
    }

    @Test
    public void onSaveReentryBounds_restoreLastPosition() {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final Rect oldPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        oldPosition.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, oldPosition);

        final Rect newPosition = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        assertBoundsWithMargin("Last position is restored", oldPosition, newPosition);
    }

    @Test
    public void onResetReentryBounds_componentMatch_useDefaultBounds() {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final Rect defaultBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);
        final Rect newBounds = new Rect(defaultBounds);
        newBounds.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, newBounds);

        mPipBoundsHandler.onResetReentryBounds(componentName);
        final Rect actualBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        assertBoundsWithMargin("Use default bounds", defaultBounds, actualBounds);
    }

    @Test
    public void onResetReentryBounds_componentMismatch_restoreLastPosition() {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final Rect defaultBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);
        final Rect newBounds = new Rect(defaultBounds);
        newBounds.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, newBounds);

        mPipBoundsHandler.onResetReentryBounds(new ComponentName(mContext, "component2"));
        final Rect actualBounds = mPipBoundsHandler.getDestinationBounds(
                DEFAULT_ASPECT_RATIO, EMPTY_CURRENT_BOUNDS);

        assertBoundsWithMargin("Last position is restored", newBounds, actualBounds);
    }

    private void assertBoundsWithMargin(String msg, Rect expected, Rect actual) {
        expected.inset(-ROUNDING_ERROR_MARGIN, -ROUNDING_ERROR_MARGIN);
        assertTrue(msg, expected.contains(actual));
    }
}
