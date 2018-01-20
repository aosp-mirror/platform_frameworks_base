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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.runner.AndroidJUnit4;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;

/**
 * Build/Install/Run:
 * atest FrameworksServicesTests:com.android.server.wm.DimmerTests;
 */
@Presubmit
@Ignore("b/72450130")
@RunWith(AndroidJUnit4.class)
public class DimmerTests extends WindowTestsBase {

    public DimmerTests() {
        super(spy(new SurfaceAnimationRunner()));
    }

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
        final SurfaceControl.Transaction mHostTransaction = mock(SurfaceControl.Transaction.class);

        MockSurfaceBuildingContainer() {
            super(sWm);
            mSurfaceControl = sWm.makeSurfaceBuilder(mSession)
                    .setName("test surface")
                    .setSize(1, 1)
                    .build();
        }

        class MockSurfaceBuilder extends SurfaceControl.Builder {
            MockSurfaceBuilder(SurfaceSession ss) {
                super(ss);
            }

            @Override
            public SurfaceControl build() {
                return spy(sWm.makeSurfaceBuilder(mSession)
                        .setName("test surface")
                        .setSize(1, 1)
                        .build());
            }
        }

        @Override
        SurfaceControl.Builder makeSurface() {
            return sWm.makeSurfaceBuilder(mSession)
                    .setName("test surface")
                    .setSize(1, 1);
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceBuilder(mSession);
        }

        @Override
        public SurfaceControl.Transaction getPendingTransaction() {
            return mHostTransaction;
        }
    }

    MockSurfaceBuildingContainer mHost;
    Dimmer mDimmer;
    SurfaceControl.Transaction mTransaction;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mHost = new MockSurfaceBuildingContainer();

        mTransaction = mock(SurfaceControl.Transaction.class);
        mDimmer = new Dimmer(mHost);

        doAnswer((Answer<Void>) invocation -> {
            Runnable runnable = invocation.getArgument(3);
            runnable.run();
            return null;
        }).when(sWm.mSurfaceAnimationRunner).startAnimation(any(), any(), any(), any());
    }

    @Test
    public void testDimAboveNoChildCreatesSurface() throws Exception {
        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);

        SurfaceControl dimLayer = getDimLayer(null);

        assertNotNull("Dimmer should have created a surface", dimLayer);

        verify(mTransaction).setAlpha(dimLayer, alpha);
        verify(mTransaction).setLayer(dimLayer, Integer.MAX_VALUE);
    }

    @Test
    public void testDimAboveNoChildRedundantlyUpdatesAlphaOnExistingSurface() throws Exception {
        float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);
        final SurfaceControl firstSurface = getDimLayer(null);

        alpha = 0.9f;
        mDimmer.dimAbove(mTransaction, alpha);

        assertEquals(firstSurface, getDimLayer(null));
        verify(mTransaction).setAlpha(firstSurface, 0.9f);
    }

    @Test
    public void testUpdateDimsAppliesSize() throws Exception {
        mDimmer.dimAbove(mTransaction, 0.8f);

        int width = 100;
        int height = 300;
        Rect bounds = new Rect(0, 0, width, height);
        mDimmer.updateDims(mTransaction, bounds);

        verify(mTransaction).setSize(getDimLayer(null), width, height);
        verify(mTransaction).show(getDimLayer(null));
    }

    @Test
    public void testDimAboveNoChildNotReset() throws Exception {
        mDimmer.dimAbove(mTransaction, 0.8f);
        SurfaceControl dimLayer = getDimLayer(null);
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mTransaction).show(getDimLayer(null));
        verify(dimLayer, never()).destroy();
    }

    @Test
    public void testDimAboveWithChildCreatesSurfaceAboveChild() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl mDimLayer = getDimLayer(child);

        assertNotNull("Dimmer should have created a surface", mDimLayer);

        verify(mTransaction).setAlpha(mDimLayer, alpha);
        verify(mTransaction).setRelativeLayer(mDimLayer, child.mControl, 1);
    }

    @Test
    public void testDimBelowWithChildSurfaceCreatesSurfaceBelowChild() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimBelow(mTransaction, child, alpha);
        SurfaceControl mDimLayer = getDimLayer(child);

        assertNotNull("Dimmer should have created a surface", mDimLayer);

        verify(mTransaction).setAlpha(mDimLayer, alpha);
        verify(mTransaction).setRelativeLayer(mDimLayer, child.mControl, -1);
    }

    @Test
    public void testDimBelowWithChildSurfaceDestroyedWhenReset() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        SurfaceControl dimLayer = getDimLayer(child);
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
        SurfaceControl dimLayer = getDimLayer(child);
        mDimmer.resetDimStates();
        mDimmer.dimAbove(mTransaction, child, alpha);

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mTransaction).show(dimLayer);
        verify(dimLayer, never()).destroy();
    }

    private SurfaceControl getDimLayer(WindowContainer windowContainer) {
        return mDimmer.mDimLayerUsers.get(windowContainer).mDimLayer;
    }
}
