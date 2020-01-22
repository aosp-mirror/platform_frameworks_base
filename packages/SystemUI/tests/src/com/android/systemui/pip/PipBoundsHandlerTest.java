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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IPinnedStackController;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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

    private PipBoundsHandler mPipBoundsHandler;
    private DisplayInfo mDefaultDisplayInfo;
    private Rect mDefaultDisplayRect;

    @Mock
    private IPinnedStackController mPinnedStackController;

    @Before
    public void setUp() throws Exception {
        mPipBoundsHandler = new PipBoundsHandler(mContext);
        MockitoAnnotations.initMocks(this);
        initializeMockResources();

        mPipBoundsHandler.onDisplayInfoChanged(mDefaultDisplayInfo);
        mPipBoundsHandler.setPinnedStackController(mPinnedStackController);
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
        mDefaultDisplayRect = new Rect(0, 0,
                mDefaultDisplayInfo.logicalWidth, mDefaultDisplayInfo.logicalHeight);
    }

    @Test
    public void setShelfHeight_offsetBounds() throws Exception {
        final ArgumentCaptor<Rect> destinationBounds = ArgumentCaptor.forClass(Rect.class);
        final int shelfHeight = 100;

        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        final Rect lastPosition = destinationBounds.getValue();
        // Reset the pinned stack controller since we will do another verify later on
        reset(mPinnedStackController);

        mPipBoundsHandler.setShelfHeight(true, shelfHeight);
        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        lastPosition.offset(0, -shelfHeight);
        assertBoundsWithMargin("PiP bounds offset by shelf height",
                lastPosition, destinationBounds.getValue());
    }

    @Test
    public void onImeVisibilityChanged_offsetBounds() throws Exception {
        final ArgumentCaptor<Rect> destinationBounds = ArgumentCaptor.forClass(Rect.class);
        final int imeHeight = 100;

        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        final Rect lastPosition = destinationBounds.getValue();
        // Reset the pinned stack controller since we will do another verify later on
        reset(mPinnedStackController);

        mPipBoundsHandler.onImeVisibilityChanged(true, imeHeight);
        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        lastPosition.offset(0, -imeHeight);
        assertBoundsWithMargin("PiP bounds offset by IME height",
                lastPosition, destinationBounds.getValue());
    }

    @Test
    public void onPrepareAnimation_startAnimation() throws Exception {
        final Rect sourceRectHint = new Rect(100, 100, 200, 200);
        final ArgumentCaptor<Rect> destinationBounds = ArgumentCaptor.forClass(Rect.class);

        mPipBoundsHandler.onPrepareAnimation(sourceRectHint, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), eq(sourceRectHint), anyInt());
        final Rect capturedDestinationBounds = destinationBounds.getValue();
        assertFalse("Destination bounds is not empty",
                capturedDestinationBounds.isEmpty());
        assertBoundsWithMargin("Destination bounds within Display",
                mDefaultDisplayRect, capturedDestinationBounds);
    }

    @Test
    public void onSaveReentryBounds_restoreLastPosition() throws Exception {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final ArgumentCaptor<Rect> destinationBounds = ArgumentCaptor.forClass(Rect.class);

        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        final Rect lastPosition = destinationBounds.getValue();
        lastPosition.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, lastPosition);
        // Reset the pinned stack controller since we will do another verify later on
        reset(mPinnedStackController);

        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        assertBoundsWithMargin("Last position is restored",
                lastPosition, destinationBounds.getValue());
    }

    @Test
    public void onResetReentryBounds_componentMatch_useDefaultBounds() throws Exception {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final ArgumentCaptor<Rect> destinationBounds = ArgumentCaptor.forClass(Rect.class);

        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        final Rect defaultBounds = new Rect(destinationBounds.getValue());
        final Rect newBounds = new Rect(defaultBounds);
        newBounds.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, newBounds);
        // Reset the pinned stack controller since we will do another verify later on
        reset(mPinnedStackController);

        mPipBoundsHandler.onResetReentryBounds(componentName);
        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        final Rect actualBounds = destinationBounds.getValue();
        assertBoundsWithMargin("Use default bounds", defaultBounds, actualBounds);
    }

    @Test
    public void onResetReentryBounds_componentMismatch_restoreLastPosition() throws Exception {
        final ComponentName componentName = new ComponentName(mContext, "component1");
        final ArgumentCaptor<Rect> destinationBounds = ArgumentCaptor.forClass(Rect.class);

        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        final Rect defaultBounds = new Rect(destinationBounds.getValue());
        final Rect newBounds = new Rect(defaultBounds);
        newBounds.offset(0, -100);
        mPipBoundsHandler.onSaveReentryBounds(componentName, newBounds);
        // Reset the pinned stack controller since we will do another verify later on
        reset(mPinnedStackController);

        mPipBoundsHandler.onResetReentryBounds(new ComponentName(mContext, "component2"));
        mPipBoundsHandler.onPrepareAnimation(null, 1f, null);

        verify(mPinnedStackController).startAnimation(
                destinationBounds.capture(), isNull(), anyInt());
        final Rect actualBounds = destinationBounds.getValue();
        assertBoundsWithMargin("Last position is restored", newBounds, actualBounds);
    }

    private void assertBoundsWithMargin(String msg, Rect expected, Rect actual) {
        expected.inset(-ROUNDING_ERROR_MARGIN, -ROUNDING_ERROR_MARGIN);
        assertTrue(msg, expected.contains(actual));
    }
}
