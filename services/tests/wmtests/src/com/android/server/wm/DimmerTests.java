/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_DIMMER;
import static com.android.server.wm.utils.LastCallVerifier.lastCall;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import com.android.server.testutils.StubTransaction;
import com.android.server.wm.utils.MockAnimationAdapter;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:DimmerTests
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class DimmerTests extends WindowTestsBase {

    private static class TestWindowContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceControl mControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mPendingTransaction = spy(StubTransaction.class);
        final SurfaceControl.Transaction mSyncTransaction = spy(StubTransaction.class);

        TestWindowContainer(WindowManagerService wm) {
            super(wm);
        }

        @Override
        public SurfaceControl getSurfaceControl() {
            return mControl;
        }

        @Override
        public SurfaceControl.Transaction getSyncTransaction() {
            return mSyncTransaction;
        }

        @Override
        public SurfaceControl.Transaction getPendingTransaction() {
            return mPendingTransaction;
        }
    }

    private static class MockSurfaceBuildingContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceSession mSession = new SurfaceSession();
        final SurfaceControl mHostControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mHostTransaction = spy(StubTransaction.class);

        MockSurfaceBuildingContainer(WindowManagerService wm) {
            super(wm);
        }

        class MockSurfaceBuilder extends SurfaceControl.Builder {
            MockSurfaceBuilder(SurfaceSession ss) {
                super(ss);
            }

            @Override
            public SurfaceControl build() {
                SurfaceControl mSc = mock(SurfaceControl.class);
                when(mSc.isValid()).thenReturn(true);
                return mSc;
            }
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceBuilder(mSession);
        }

        @Override
        public SurfaceControl getSurfaceControl() {
            return mHostControl;
        }

        @Override
        public SurfaceControl.Transaction getSyncTransaction() {
            return mHostTransaction;
        }

        @Override
        public SurfaceControl.Transaction getPendingTransaction() {
            return mHostTransaction;
        }
    }

    static class MockAnimationAdapterFactory extends DimmerAnimationHelper.AnimationAdapterFactory {
        @Override
        public AnimationAdapter get(LocalAnimationAdapter.AnimationSpec alphaAnimationSpec,
                SurfaceAnimationRunner runner) {
            return sTestAnimation;
        }
    }

    private static class SurfaceAnimatorStarterImpl implements LegacyDimmer.SurfaceAnimatorStarter {
        @Override
        public void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction t,
                AnimationAdapter anim, boolean hidden, @SurfaceAnimator.AnimationType int type) {
            surfaceAnimator.mStaticAnimationFinishedCallback.onAnimationFinished(type, anim);
        }
    }

    private MockSurfaceBuildingContainer mHost;
    private Dimmer mDimmer;
    private SurfaceControl.Transaction mTransaction;
    private TestWindowContainer mChild;
    private static AnimationAdapter sTestAnimation;
    private static LegacyDimmer.SurfaceAnimatorStarter sSurfaceAnimatorStarter;

    @Before
    public void setUp() throws Exception {
        mHost = new MockSurfaceBuildingContainer(mWm);
        mTransaction = spy(StubTransaction.class);
        mChild = new TestWindowContainer(mWm);
        if (Dimmer.DIMMER_REFACTOR) {
            sTestAnimation = spy(new MockAnimationAdapter());
            mDimmer = new SmoothDimmer(mHost, new MockAnimationAdapterFactory());
        } else {
            sSurfaceAnimatorStarter = spy(new SurfaceAnimatorStarterImpl());
            mDimmer = new LegacyDimmer(mHost, sSurfaceAnimatorStarter);
        }
    }

    @Test
    public void testUpdateDimsAppliesCrop() {
        mHost.addChild(mChild, 0);

        mDimmer.adjustAppearance(mChild, 1, 1);
        mDimmer.adjustRelativeLayer(mChild, -1);

        int width = 100;
        int height = 300;
        mDimmer.getDimBounds().set(0, 0, width, height);
        mDimmer.updateDims(mTransaction);

        verify(mTransaction).setWindowCrop(mDimmer.getDimLayer(), width, height);
        verify(mTransaction).show(mDimmer.getDimLayer());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testDimBelowWithChildSurfaceCreatesSurfaceBelowChild_Smooth() {
        final float alpha = 0.7f;
        final int blur = 50;
        mHost.addChild(mChild, 0);
        mDimmer.adjustAppearance(mChild, alpha, blur);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        mDimmer.updateDims(mTransaction);
        verify(sTestAnimation).startAnimation(eq(dimLayer), eq(mTransaction),
                anyInt(), any(SurfaceAnimator.OnAnimationFinishedCallback.class));
        verify(mTransaction).setRelativeLayer(dimLayer, mChild.mControl, -1);
        verify(mTransaction, lastCall()).setAlpha(dimLayer, alpha);
        verify(mTransaction).setBackgroundBlurRadius(dimLayer, blur);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testDimBelowWithChildSurfaceCreatesSurfaceBelowChild_Legacy() {
        final float alpha = 0.7f;
        mHost.addChild(mChild, 0);
        mDimmer.adjustAppearance(mChild, alpha, 20);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mHost.getPendingTransaction()).setAlpha(dimLayer, alpha);
        verify(mHost.getPendingTransaction()).setRelativeLayer(dimLayer, mChild.mControl, -1);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testDimBelowWithChildSurfaceDestroyedWhenReset_Smooth() {
        mHost.addChild(mChild, 0);

        final float alpha = 0.8f;
        final int blur = 50;
        // Dim once
        mDimmer.adjustAppearance(mChild, alpha, blur);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.updateDims(mTransaction);
        // Reset, and don't dim
        mDimmer.resetDimStates();
        mDimmer.adjustRelativeLayer(mChild, -1);
        mDimmer.updateDims(mTransaction);
        verify(mTransaction).show(dimLayer);
        verify(mTransaction).remove(dimLayer);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testDimBelowWithChildSurfaceDestroyedWhenReset_Legacy() {
        mHost.addChild(mChild, 0);

        final float alpha = 0.8f;
        mDimmer.adjustAppearance(mChild, alpha, 20);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction);
        verify(sSurfaceAnimatorStarter).startAnimation(any(SurfaceAnimator.class),
                any(SurfaceControl.Transaction.class), any(AnimationAdapter.class),
                anyBoolean(),
                eq(ANIMATION_TYPE_DIMMER));
        verify(mHost.getPendingTransaction()).remove(dimLayer);
    }

    @Test
    public void testDimBelowWithChildSurfaceNotDestroyedWhenPersisted() {
        mHost.addChild(mChild, 0);

        final float alpha = 0.8f;
        final int blur = 20;
        // Dim once
        mDimmer.adjustAppearance(mChild, alpha, blur);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.updateDims(mTransaction);
        // Reset and dim again
        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(mChild, alpha, blur);
        mDimmer.adjustRelativeLayer(mChild, -1);
        mDimmer.updateDims(mTransaction);
        verify(mTransaction).show(dimLayer);
        verify(mTransaction, never()).remove(dimLayer);
    }

    @Test
    public void testDimUpdateWhileDimming() {
        mHost.addChild(mChild, 0);
        final float alpha = 0.8f;
        mDimmer.adjustAppearance(mChild, alpha, 20);
        mDimmer.adjustRelativeLayer(mChild, -1);
        final Rect bounds = mDimmer.getDimBounds();

        SurfaceControl dimLayer = mDimmer.getDimLayer();
        bounds.set(0, 0, 10, 10);
        mDimmer.updateDims(mTransaction);
        verify(mTransaction).setWindowCrop(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction, times(1)).show(dimLayer);
        verify(mTransaction).setPosition(dimLayer, 0, 0);

        bounds.set(10, 10, 30, 30);
        mDimmer.updateDims(mTransaction);
        verify(mTransaction).setWindowCrop(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction).setPosition(dimLayer, 10, 10);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testRemoveDimImmediately_Smooth() {
        mHost.addChild(mChild, 0);
        mDimmer.adjustAppearance(mChild, 1, 2);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.updateDims(mTransaction);
        verify(mTransaction, times(1)).show(dimLayer);

        reset(sTestAnimation);
        mDimmer.dontAnimateExit();
        mDimmer.resetDimStates();
        mDimmer.updateDims(mTransaction);
        verify(sTestAnimation, never()).startAnimation(
                any(SurfaceControl.class), any(SurfaceControl.Transaction.class),
                anyInt(), any(SurfaceAnimator.OnAnimationFinishedCallback.class));
        verify(mTransaction).remove(dimLayer);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testRemoveDimImmediately_Legacy() {
        mHost.addChild(mChild, 0);
        mDimmer.adjustAppearance(mChild, 1, 0);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.updateDims(mTransaction);
        verify(mTransaction, times(1)).show(dimLayer);

        reset(sSurfaceAnimatorStarter);
        mDimmer.dontAnimateExit();
        mDimmer.resetDimStates();
        mDimmer.updateDims(mTransaction);
        verify(sSurfaceAnimatorStarter, never()).startAnimation(any(SurfaceAnimator.class),
                any(SurfaceControl.Transaction.class), any(AnimationAdapter.class), anyBoolean(),
                eq(ANIMATION_TYPE_DIMMER));
        verify(mTransaction).remove(dimLayer);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testDimmerWithBlurUpdatesTransaction_Legacy() {
        mHost.addChild(mChild, 0);

        final int blurRadius = 50;
        mDimmer.adjustAppearance(mChild, 1, blurRadius);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mHost.getPendingTransaction()).setBackgroundBlurRadius(dimLayer, blurRadius);
        verify(mHost.getPendingTransaction()).setRelativeLayer(dimLayer, mChild.mControl, -1);
    }

    /**
     * mChild is requesting the dim values to be set directly. In this case, dim won't play the
     * standard animation, but directly apply mChild's requests to the dim surface
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testContainerDimsOpeningAnimationByItself() {
        mHost.addChild(mChild, 0);

        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(mChild, 0.1f, 0);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.updateDims(mTransaction);

        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(mChild, 0.2f, 0);
        mDimmer.adjustRelativeLayer(mChild, -1);
        mDimmer.updateDims(mTransaction);

        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(mChild, 0.3f, 0);
        mDimmer.adjustRelativeLayer(mChild, -1);
        mDimmer.updateDims(mTransaction);

        verify(mTransaction).setAlpha(dimLayer, 0.2f);
        verify(mTransaction).setAlpha(dimLayer, 0.3f);
        verify(sTestAnimation, times(1)).startAnimation(
                any(SurfaceControl.class), any(SurfaceControl.Transaction.class),
                anyInt(), any(SurfaceAnimator.OnAnimationFinishedCallback.class));
    }

    /**
     * Same as testContainerDimsOpeningAnimationByItself, but this is a more specific case in which
     * alpha is animated to 0. This corner case is needed to verify that the layer is removed anyway
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testContainerDimsClosingAnimationByItself() {
        mHost.addChild(mChild, 0);

        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(mChild, 0.2f, 0);
        mDimmer.adjustRelativeLayer(mChild, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.updateDims(mTransaction);

        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(mChild, 0.1f, 0);
        mDimmer.adjustRelativeLayer(mChild, -1);
        mDimmer.updateDims(mTransaction);

        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(mChild, 0f, 0);
        mDimmer.adjustRelativeLayer(mChild, -1);
        mDimmer.updateDims(mTransaction);

        mDimmer.resetDimStates();
        mDimmer.updateDims(mTransaction);
        verify(mTransaction).remove(dimLayer);
    }

    /**
     * Check the handover of the dim between two windows and the consequent dim animation in between
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testMultipleContainersDimmingConsecutively() {
        TestWindowContainer first = mChild;
        TestWindowContainer second = new TestWindowContainer(mWm);
        mHost.addChild(first, 0);
        mHost.addChild(second, 1);

        mDimmer.adjustAppearance(first, 0.5f, 0);
        mDimmer.adjustRelativeLayer(first, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.updateDims(mTransaction);

        mDimmer.resetDimStates();
        mDimmer.adjustAppearance(second, 0.9f, 0);
        mDimmer.adjustRelativeLayer(second, -1);
        mDimmer.updateDims(mTransaction);

        verify(sTestAnimation, times(2)).startAnimation(
                any(SurfaceControl.class), any(SurfaceControl.Transaction.class),
                anyInt(), any(SurfaceAnimator.OnAnimationFinishedCallback.class));
        verify(mTransaction).setAlpha(dimLayer, 0.5f);
        verify(mTransaction).setAlpha(dimLayer, 0.9f);
    }

    /**
     * Two windows are trying to modify the dim at the same time, but only the last request before
     * updateDims will be satisfied
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INTRODUCE_SMOOTHER_DIMMER)
    public void testMultipleContainersDimmingAtTheSameTime() {
        TestWindowContainer first = mChild;
        TestWindowContainer second = new TestWindowContainer(mWm);
        mHost.addChild(first, 0);
        mHost.addChild(second, 1);

        mDimmer.adjustAppearance(first, 0.5f, 0);
        mDimmer.adjustRelativeLayer(first, -1);
        SurfaceControl dimLayer = mDimmer.getDimLayer();
        mDimmer.adjustAppearance(second, 0.9f, 0);
        mDimmer.adjustRelativeLayer(second, -1);
        mDimmer.updateDims(mTransaction);

        verify(sTestAnimation, times(1)).startAnimation(
                any(SurfaceControl.class), any(SurfaceControl.Transaction.class),
                anyInt(), any(SurfaceAnimator.OnAnimationFinishedCallback.class));
        verify(mTransaction, never()).setAlpha(dimLayer, 0.5f);
        verify(mTransaction).setAlpha(dimLayer, 0.9f);
    }
}
