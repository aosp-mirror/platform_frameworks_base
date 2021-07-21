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

package android.view;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable.Aggregator;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable.BlurRegion;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BlurAggregatorTest {
    private static final int TEST_BLUR_RADIUS = 30;
    private static final int TEST_FRAME_NUMBER = 1;

    private Context mContext;

    private Aggregator mAggregator;
    private BackgroundBlurDrawable mDrawable;

    private ViewRootImpl mViewRoot;

    @Before
    public void setUp() {
        mContext = getInstrumentation().getTargetContext();
        getInstrumentation().runOnMainSync(() -> {
            mViewRoot = new ViewRootImpl(mContext, mContext.getDisplayNoVerify());
        });
        mAggregator = new Aggregator(mViewRoot);
        mDrawable = createTestBackgroundBlurDrawable();
    }

    private BackgroundBlurDrawable createTestBackgroundBlurDrawable() {
        final BackgroundBlurDrawable drawable = mAggregator.createBackgroundBlurDrawable(mContext);
        drawable.setBlurRadius(TEST_BLUR_RADIUS);
        final boolean hasUpdates = mAggregator.hasUpdates();
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        mAggregator.getBlurRegionsToDispatchToSf(TEST_FRAME_NUMBER, blurRegions, hasUpdates);
        return drawable;
    }

    @Test
    public void testBlurRadiusUpdatePropagatesToRenderThreadIfNeeded() {
        mDrawable.setBlurRadius(TEST_BLUR_RADIUS);
        assertFalse(mAggregator.hasUpdates());

        mDrawable.setBlurRadius(0);
        assertTrue(mAggregator.hasUpdates());
        BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(0, blurRegions.length);
        assertFalse(mAggregator.hasUpdates());

        mDrawable.setBlurRadius(TEST_BLUR_RADIUS);
        assertTrue(mAggregator.hasUpdates());
        blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);
        assertEquals(TEST_BLUR_RADIUS, blurRegions[0].blurRadius);
        assertFalse(mAggregator.hasUpdates());

    }

    @Test
    public void testAlphaUpdatePropagatesToRenderThreadIfNeeded() {
        mDrawable.setAlpha(20);
        assertTrue(mAggregator.hasUpdates());
        BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);
        assertEquals(20 / 255f, blurRegions[0].alpha);
        assertFalse(mAggregator.hasUpdates());

        mDrawable.setAlpha(20);
        assertFalse(mAggregator.hasUpdates());

        mDrawable.setAlpha(0);
        assertTrue(mAggregator.hasUpdates());
        blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(0, blurRegions.length);
        assertFalse(mAggregator.hasUpdates());
    }

    @Test
    public void testCornerRadiusUpdatePropagatesToRenderThreadIfNeeded() {
        mDrawable.setCornerRadius(1f, 2f, 3f, 4f);
        assertTrue(mAggregator.hasUpdates());
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);
        assertEquals(1f, blurRegions[0].cornerRadiusTL);
        assertEquals(2f, blurRegions[0].cornerRadiusTR);
        assertEquals(3f, blurRegions[0].cornerRadiusBL);
        assertEquals(4f, blurRegions[0].cornerRadiusBR);
        assertFalse(mAggregator.hasUpdates());
    }

    @Test
    public void testVisibleUpdatePropagatesToRenderThreadIfNeeded() {
        mDrawable.setVisible(false, /* restart= */false);
        assertTrue(mAggregator.hasUpdates());
        BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(0, blurRegions.length);
        assertFalse(mAggregator.hasUpdates());

        mDrawable.setVisible(true, /* restart= */ false);
        assertTrue(mAggregator.hasUpdates());
        blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);
        assertEquals(TEST_BLUR_RADIUS, blurRegions[0].blurRadius);
        assertFalse(mAggregator.hasUpdates());
    }

    @Test
    public void testBlurRegionCopyForRtIsSameIfNoUiUpdates() {
        mDrawable.setBlurRadius(30);
        BlurRegion[] blurRegions1 = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions1.length);
        assertEquals(30, blurRegions1[0].blurRadius);

        BlurRegion[] blurRegions2 = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(blurRegions1, blurRegions2);
    }

    @Test
    public void testPositionUpdateAppearsInBlurRegion() {
        BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);

        mDrawable.mPositionUpdateListener.positionChanged(TEST_FRAME_NUMBER, 1, 2, 3, 4);
        mAggregator.getBlurRegionsToDispatchToSf(TEST_FRAME_NUMBER, blurRegions,
                mAggregator.hasUpdates());
        assertEquals(1, blurRegions[0].rect.left);
        assertEquals(2, blurRegions[0].rect.top);
        assertEquals(3, blurRegions[0].rect.right);
        assertEquals(4, blurRegions[0].rect.bottom);
    }

    @Test
    public void testNoBlurRegionsDispatchedWhenNoUpdates() {
        final boolean hasUpdates = mAggregator.hasUpdates();
        assertFalse(hasUpdates);
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);

        float[][] blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER, blurRegions, hasUpdates);
        assertNull(blurRegionsForSf);
    }

    @Test
    public void testBlurRegionDispatchedIfOnlyDrawableUpdated() {
        mDrawable.setBlurRadius(50);
        final boolean hasUpdates = mAggregator.hasUpdates();
        assertTrue(hasUpdates);
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);

        float[][] blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER, blurRegions, hasUpdates);
        assertNotNull(blurRegionsForSf);
        assertEquals(1, blurRegionsForSf.length);
        assertEquals(50f, blurRegionsForSf[0][0]);
    }

    @Test
    public void testBlurRegionDispatchedIfOnlyPositionUpdated() {
        final boolean hasUpdates = mAggregator.hasUpdates();
        assertFalse(hasUpdates);
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);

        mDrawable.mPositionUpdateListener.positionChanged(TEST_FRAME_NUMBER, 1, 2, 3, 4);
        float[][] blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER, blurRegions, hasUpdates);
        assertNotNull(blurRegionsForSf);
        assertEquals(1, blurRegionsForSf.length);
        assertEquals((float) TEST_BLUR_RADIUS, blurRegionsForSf[0][0]);
        assertEquals(1f, blurRegionsForSf[0][2]);
        assertEquals(2f, blurRegionsForSf[0][3]);
        assertEquals(3f, blurRegionsForSf[0][4]);
        assertEquals(4f, blurRegionsForSf[0][5]);
    }

    @Test
    public void testPositionUpdateIsAppliedInNextFrameIfMissed() {
        final boolean hasUpdates = mAggregator.hasUpdates();
        assertFalse(hasUpdates);
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);

        mDrawable.mPositionUpdateListener.positionChanged(TEST_FRAME_NUMBER, 1, 2, 3, 4);
        float[][] blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER + 1, blurRegions, hasUpdates);
        assertNotNull(blurRegionsForSf);
        assertEquals(1, blurRegionsForSf.length);
        assertEquals((float) TEST_BLUR_RADIUS, blurRegionsForSf[0][0]);
        assertEquals(1f, blurRegionsForSf[0][2]);
        assertEquals(2f, blurRegionsForSf[0][3]);
        assertEquals(3f, blurRegionsForSf[0][4]);
        assertEquals(4f, blurRegionsForSf[0][5]);
    }

    @Test
    public void testMultipleDrawablesDispatchedToSfIfOneIsUpdated() {
        final BackgroundBlurDrawable drawable2 = createTestBackgroundBlurDrawable();
        drawable2.setBlurRadius(50);
        final boolean hasUpdates = mAggregator.hasUpdates();
        assertTrue(hasUpdates);
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(2, blurRegions.length);

        // Check that an update in one of the drawables triggers a dispatch of all blur regions
        float[][] blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER, blurRegions, hasUpdates);
        assertNotNull(blurRegionsForSf);
        assertEquals(2, blurRegionsForSf.length);

        // Check that the Aggregator deleted all position updates for frame TEST_FRAME_NUMBER
        blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER, blurRegions, /* hasUiUpdates= */ false);
        assertNull(blurRegionsForSf);

        // Check that a position update triggers a dispatch of all blur regions
        drawable2.mPositionUpdateListener.positionChanged(TEST_FRAME_NUMBER, 1, 2, 3, 4);
        blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER + 1, blurRegions, hasUpdates);
        assertNotNull(blurRegionsForSf);
        assertEquals(2, blurRegionsForSf.length);
    }

    @Test
    public void testUiThreadUpdatesDoNotChangeStateOnRenderThread() {
        // Updates for frame N
        mDrawable.setBlurRadius(50);
        mDrawable.setCornerRadius(1, 2, 3, 4);
        mDrawable.setAlpha(20);

        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();
        assertEquals(1, blurRegions.length);
        assertEquals(50, blurRegions[0].blurRadius);
        assertEquals(20 / 255f, blurRegions[0].alpha);
        assertEquals(1f, blurRegions[0].cornerRadiusTL);
        assertEquals(2f, blurRegions[0].cornerRadiusTR);
        assertEquals(3f, blurRegions[0].cornerRadiusBL);
        assertEquals(4f, blurRegions[0].cornerRadiusBR);

        // Updates for frame N+1
        mDrawable.setBlurRadius(60);
        mDrawable.setCornerRadius(10, 20, 30, 40);
        mDrawable.setAlpha(40);

        // Assert state for frame N is untouched
        assertEquals(50, blurRegions[0].blurRadius);
        assertEquals(20 / 255f, blurRegions[0].alpha);
        assertEquals(1f, blurRegions[0].cornerRadiusTL);
        assertEquals(2f, blurRegions[0].cornerRadiusTR);
        assertEquals(3f, blurRegions[0].cornerRadiusBL);
        assertEquals(4f, blurRegions[0].cornerRadiusBR);
    }

    @Test
    public void testPositionUpdatesForFutureFramesAreNotAppliedForCurrentFrame() {
        final BlurRegion[] blurRegions = mAggregator.getBlurRegionsCopyForRT();

        mDrawable.mPositionUpdateListener.positionChanged(TEST_FRAME_NUMBER, 1, 2, 3, 4);
        mDrawable.mPositionUpdateListener.positionChanged(TEST_FRAME_NUMBER + 1, 5, 6, 7, 8);

        final float[][] blurRegionsForSf = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER, blurRegions, /* hasUiUpdates= */ false);
        assertNotNull(blurRegionsForSf);
        assertEquals(1, blurRegionsForSf.length);
        // Assert state for first frame is not affected by update for second frame
        assertEquals((float) TEST_BLUR_RADIUS, blurRegionsForSf[0][0]);
        assertEquals(1f, blurRegionsForSf[0][2]);
        assertEquals(2f, blurRegionsForSf[0][3]);
        assertEquals(3f, blurRegionsForSf[0][4]);
        assertEquals(4f, blurRegionsForSf[0][5]);

        final float[][] blurRegionsForSfForNextFrame = mAggregator.getBlurRegionsToDispatchToSf(
                TEST_FRAME_NUMBER + 1, blurRegions, /* hasUiUpdates= */ false);
        assertNotNull(blurRegionsForSfForNextFrame);
        assertEquals(1, blurRegionsForSfForNextFrame.length);
        // Assert second frame updates are applied normally
        assertEquals((float) TEST_BLUR_RADIUS, blurRegionsForSfForNextFrame[0][0]);
        assertEquals(5f, blurRegionsForSfForNextFrame[0][2]);
        assertEquals(6f, blurRegionsForSfForNextFrame[0][3]);
        assertEquals(7f, blurRegionsForSfForNextFrame[0][4]);
        assertEquals(8f, blurRegionsForSfForNextFrame[0][5]);
    }

}
