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

import java.util.HashMap;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.runner.AndroidJUnit4;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;

/**
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.DimmerTests;
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DimmerTests extends WindowTestsBase {
    private class TestWindowContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceControl mControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mTransaction = mock(SurfaceControl.Transaction.class);

        @Override
        SurfaceControl getSurfaceControl() {
            return mControl;
        }

        @Override
        SurfaceControl.Transaction getPendingTransaction() {
            return mTransaction;
        }
    }

    private class MockSurfaceBuildingContainer extends WindowContainer<TestWindowContainer> {
        final SurfaceSession mSession = new SurfaceSession();
        SurfaceControl mBuiltSurface = null;
        final SurfaceControl mHostControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction mHostTransaction = mock(SurfaceControl.Transaction.class);

        class MockSurfaceBuilder extends SurfaceControl.Builder {
            MockSurfaceBuilder(SurfaceSession ss) {
                super(ss);
            }

            @Override
            public SurfaceControl build() {
                SurfaceControl sc = mock(SurfaceControl.class);
                mBuiltSurface = sc;
                return sc;
            }
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceBuilder(mSession);
        }

        @Override
        SurfaceControl getSurfaceControl() {
            return mHostControl;
        }

        @Override
        SurfaceControl.Transaction getPendingTransaction() {
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
    }

    @Test
    public void testDimAboveNoChildCreatesSurface() throws Exception {
        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);
        assertNotNull("Dimmer should have created a surface", mHost.mBuiltSurface);

        verify(mTransaction).setAlpha(mHost.mBuiltSurface, alpha);
        verify(mTransaction).show(mHost.mBuiltSurface);
        verify(mTransaction).setLayer(mHost.mBuiltSurface, Integer.MAX_VALUE);
    }

    @Test
    public void testDimAboveNoChildRedundantlyUpdatesAlphaOnExistingSurface() throws Exception {
        float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, alpha);
        final SurfaceControl firstSurface = mHost.mBuiltSurface;

        alpha = 0.9f;
        mDimmer.dimAbove(mTransaction, alpha);

        assertEquals(firstSurface, mHost.mBuiltSurface);
        verify(mTransaction).setAlpha(firstSurface, 0.9f);
    }

    @Test
    public void testUpdateDimsAppliesSize() throws Exception {
        mDimmer.dimAbove(mTransaction, 0.8f);

        int width = 100;
        int height = 300;
        Rect bounds = new Rect(0, 0, width, height);
        mDimmer.updateDims(mTransaction, bounds);
        verify(mTransaction).setSize(mHost.mBuiltSurface, width, height);
    }

    @Test
    public void testDimAboveNoChildNotReset() throws Exception {
        mDimmer.dimAbove(mTransaction, 0.8f);
        mDimmer.resetDimStates();

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mHost.mBuiltSurface, never()).destroy();
    }

    @Test
    public void testDimAboveWithChildCreatesSurfaceAboveChild() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        assertNotNull("Dimmer should have created a surface", mHost.mBuiltSurface);

        verify(mTransaction).setAlpha(mHost.mBuiltSurface, alpha);
        verify(mTransaction).show(mHost.mBuiltSurface);
        verify(mTransaction).setRelativeLayer(mHost.mBuiltSurface, child.mControl, 1);
    }

    @Test
    public void testDimBelowWithChildSurfaceCreatesSurfaceBelowChild() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimBelow(mTransaction, child, alpha);
        assertNotNull("Dimmer should have created a surface", mHost.mBuiltSurface);

        verify(mTransaction).setAlpha(mHost.mBuiltSurface, alpha);
        verify(mTransaction).show(mHost.mBuiltSurface);
        verify(mTransaction).setRelativeLayer(mHost.mBuiltSurface, child.mControl, -1);
    }

    @Test
    public void testDimBelowWithChildSurfaceDestroyedWhenReset() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        mDimmer.resetDimStates();
        mDimmer.updateDims(mTransaction, new Rect());
        verify(mHost.mBuiltSurface).destroy();
    }

    @Test
    public void testDimBelowWithChildSurfaceNotDestroyedWhenPersisted() throws Exception {
        TestWindowContainer child = new TestWindowContainer();
        mHost.addChild(child, 0);

        final float alpha = 0.8f;
        mDimmer.dimAbove(mTransaction, child, alpha);
        mDimmer.resetDimStates();
        mDimmer.dimAbove(mTransaction, child, alpha);

        mDimmer.updateDims(mTransaction, new Rect());
        verify(mHost.mBuiltSurface, never()).destroy();
    }
}
