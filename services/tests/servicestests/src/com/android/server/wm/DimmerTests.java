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
 * limitations under the License
 */

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.runner.AndroidJUnit4;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 * atest FrameworksServicesTests:com.android.server.wm.DimmerTests;
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DimmerTests extends WindowTestsBase {

    private class TestWindowContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceControl mControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mTransaction = mock(SurfaceControl.Transaction.class);

        TestWindowContainer() {
            super(sWm);
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

    private class MockSurfaceBuildingContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceSession mSession = new SurfaceSession();
        final SurfaceControl mHostControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mHostTransaction = mock(SurfaceControl.Transaction.class);

        MockSurfaceBuildingContainer() {
            super(sWm);
        }

        class MockSurfaceBuilder extends SurfaceControl.Builder {
            MockSurfaceBuilder(SurfaceSession ss) {
                super(ss);
            }

            @Override
            public SurfaceControl build() {
                return mock(SurfaceControl.class);
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

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mHost = new MockSurfaceBuildingContainer();

        mTransaction = mock(SurfaceControl.Transaction.class);
        mDimmer = new Dimmer(mHost,
                (surfaceAnimator, t, anim, hidden) -> surfaceAnimator.mAnimationFinishedCallback
                        .run());
    }

    @Test
    public void testDimAboveNoChildCreatesSurface() throws Exception {
        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);

        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mTransaction).setAlpha(dimLayer, alpha);
        verify(mTransaction).setLayer(dimLayer, Integer.MAX_VALUE);
    }

    @Test
    public void testDimAboveNoChildRedundantlyUpdatesAlphaOnExistingSurface() throws Exception {
        float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);
        final SurfaceControl firstSurface = getDimLayer();

        alpha = 0.9f;
        mDimmer.dimAbove(mTransaction, alpha);

        assertEquals(firstSurface, getDimLayer());
        verify(mTransaction).setAlpha(firstSurface, 0.9f);
    }

    @Test
    public void testUpdateDimsAppliesSize() throws Exception {
        mDimmer.dimAbove(mTransaction, 0.8f);

        int width = 100;
        int height = 300;
        Rect bounds = new Rect(0, 0, width, height);
        mDimmer.updateDims(mTransaction, bounds);

        verify(mTransaction).setSize(getDimLayer(), width, height);
        verify(mTransaction).show(getDimLayer());
    }

    @Test
    public void testDimAboveNoChildNotReset() throws Exception {
        mDimmer.dimAbove(mTransaction, 0.8f);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mTransaction).show(getDimLayer());
        verify(dimLayer, never()).destroy();
    }

    @Test
    public void testDimAboveWithChildCreatesSurfaceAboveChild() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mTransaction).setAlpha(dimLayer, alpha);
        verify(mTransaction).setRelativeLayer(dimLayer, child.mControl, 1);
    }

    @Test
    public void testDimBelowWithChildSurfaceCreatesSurfaceBelowChild() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimBelow(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mTransaction).setAlpha(dimLayer, alpha);
        verify(mTransaction).setRelativeLayer(dimLayer, child.mControl, -1);
    }

    @Test
    public void testDimBelowWithChildSurfaceDestroyedWhenReset() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction, new Rect());
        verify(dimLayer).destroy();
    }

    @Test
    public void testDimBelowWithChildSurfaceNotDestroyedWhenPersisted() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer();
        mDimmer.resetDimStates();
        mDimmer.dimAbove(mTransaction, child, alpha);

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mTransaction).show(dimLayer);
        verify(dimLayer, never()).destroy();
    }

    @Test
    public void testDimUpdateWhileDimming() throws Exception {
        Rect bounds = new Rect();
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);

        SurfaceControl dimLayer = getDimLayer();
        bounds.set(0, 0, 10, 10);
        mDimmer.updateDims(mTransaction, bounds);
        verify(mTransaction, times(1)).show(dimLayer);
        verify(mTransaction).setSize(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction).setPosition(dimLayer, 0, 0);

        bounds.set(10, 10, 30, 30);
        mDimmer.updateDims(mTransaction, bounds);
        verify(mTransaction).setSize(dimLayer, bounds.width(), bounds.height());
        verify(mTransaction).setPosition(dimLayer, 10, 10);
    }

    private SurfaceControl getDimLayer() {
        return mDimmer.mDimState.mDimLayer;
    }
}
