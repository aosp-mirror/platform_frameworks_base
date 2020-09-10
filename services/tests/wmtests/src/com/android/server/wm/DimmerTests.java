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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_DIMMER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import com.android.server.wm.SurfaceAnimator.AnimationType;

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
        final SurfaceControl.Transaction mTransaction = spy(StubTransaction.class);

        TestWindowContainer(WindowManagerService wm) {
            super(wm);
        }

        @Override
        public SurfaceControl getSurfaceControl() {
            return mControl;
        }

        @Override
        public SurfaceControl.Transaction getPendingTransaction() {
            return mTransaction;
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
        public SurfaceControl.Transaction getPendingTransaction() {
            return mHostTransaction;
        }
    }

    private MockSurfaceBuildingContainer mHost;
    private Dimmer mDimmer;
    private SurfaceControl.Transaction mTransaction;
    private Dimmer.SurfaceAnimatorStarter mSurfaceAnimatorStarter;

    private static class SurfaceAnimatorStarterImpl implements Dimmer.SurfaceAnimatorStarter {
        @Override
        public void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction t,
                AnimationAdapter anim, boolean hidden, @AnimationType int type) {
            surfaceAnimator.mStaticAnimationFinishedCallback.onAnimationFinished(type, anim);
        }
    }

    @Before
    public void setUp() throws Exception {
        mHost = new MockSurfaceBuildingContainer(mWm);
        mSurfaceAnimatorStarter = spy(new SurfaceAnimatorStarterImpl());
        mTransaction = spy(StubTransaction.class);
        mDimmer = new Dimmer(mHost, mSurfaceAnimatorStarter);
    }

    @Test
    public void testDimAboveNoChildCreatesSurface() {
        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);

        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mTransaction).setAlpha(dimLayer, alpha);
        verify(mTransaction).setLayer(dimLayer, Integer.MAX_VALUE);
    }

    @Test
    public void testDimAboveNoChildRedundantlyUpdatesAlphaOnExistingSurface() {
        float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);
        final SurfaceControl firstSurface = getDimLayer();

        alpha = 0.9f;
        mDimmer.dimAbove(mTransaction, alpha);

        assertEquals(firstSurface, getDimLayer());
        verify(mTransaction).setAlpha(firstSurface, 0.9f);
    }

    @Test
    public void testUpdateDimsAppliesCrop() {
        mDimmer.dimAbove(mTransaction, 0.8f);

        int width = 100;
        int height = 300;
        Rect bounds = new Rect(0, 0, width, height);
        mDimmer.updateDims(mTransaction, bounds);

        verify(mTransaction).setWindowCrop(getDimLayer(), width, height);
        verify(mTransaction).show(getDimLayer());
    }

    @Test
    public void testDimAboveNoChildNotReset() {
        mDimmer.dimAbove(mTransaction, 0.8f);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mTransaction).show(getDimLayer());
        verify(mTransaction, never()).remove(dimLayer);
    }

    @Test
    public void testDimAboveWithChildCreatesSurfaceAboveChild() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mTransaction).setAlpha(dimLayer, alpha);
        verify(mTransaction).setRelativeLayer(dimLayer, child.mControl, 1);
    }

    @Test
    public void testDimBelowWithChildSurfaceCreatesSurfaceBelowChild() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimBelow(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mTransaction).setAlpha(dimLayer, alpha);
        verify(mTransaction).setRelativeLayer(dimLayer, child.mControl, -1);
    }

    @Test
    public void testDimBelowWithChildSurfaceDestroyedWhenReset() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mSurfaceAnimatorStarter).startAnimation(any(SurfaceAnimator.class), any(
                SurfaceControl.Transaction.class), any(AnimationAdapter.class), anyBoolean(),
                eq(ANIMATION_TYPE_DIMMER));
        verify(mHost.getPendingTransaction()).remove(dimLayer);
    }

    @Test
    public void testDimBelowWithChildSurfaceNotDestroyedWhenPersisted() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();
        mDimmer.dimAbove(mTransaction, child, alpha);

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mTransaction).show(dimLayer);
        verify(mTransaction, never()).remove(dimLayer);
    }

    @Test
    public void testDimUpdateWhileDimming() {
        Rect bounds = new Rect();
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);

        SurfaceControl dimLayer = getDimLayer();
        bounds.set(0, 0, 10, 10);
        mDimmer.updateDims(mTransaction, bounds);
        verify(mTransaction).setWindowCrop(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction, times(1)).show(dimLayer);
        verify(mTransaction).setPosition(dimLayer, 0, 0);

        bounds.set(10, 10, 30, 30);
        mDimmer.updateDims(mTransaction, bounds);
        verify(mTransaction).setWindowCrop(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction).setPosition(dimLayer, 10, 10);
    }

    @Test
    public void testRemoveDimImmediately() {
        TestWindowContainer child = new TestWindowContainer(mWm);
        mHost.addChild(child, 0);

        mDimmer.dimAbove(mTransaction, child, 1);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.updateDims(mTransaction, new Rect());
        verify(mTransaction, times(1)).show(dimLayer);

        reset(mSurfaceAnimatorStarter);
        mDimmer.dontAnimateExit();
        mDimmer.resetDimStates();
        mDimmer.updateDims(mTransaction, new Rect());
        verify(mSurfaceAnimatorStarter, never()).startAnimation(any(SurfaceAnimator.class), any(
                SurfaceControl.Transaction.class), any(AnimationAdapter.class), anyBoolean(),
                eq(ANIMATION_TYPE_DIMMER));
        verify(mTransaction).remove(dimLayer);
    }

    private SurfaceControl getDimLayer() {
        return mDimmer.mDimState.mDimLayer;
    }
}
