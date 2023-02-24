/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsets.Type.systemBars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;
import android.view.SurfaceControl.Transaction;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.animation.LinearInterpolator;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Tests for {@link InsetsAnimationControlImpl}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsAnimationControlImplTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsAnimationControlImplTest {

    private InsetsAnimationControlImpl mController;

    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mTopLeash;
    private SurfaceControl mNavLeash;
    private InsetsState mInsetsState;

    @Mock Transaction mMockTransaction;
    @Mock InsetsController mMockController;
    @Mock WindowInsetsAnimationControlListener mMockListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTopLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        mNavLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        mInsetsState = new InsetsState();
        mInsetsState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 500, 100));
        mInsetsState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(400, 0, 500, 500));
        doNothing().when(mMockController).onRequestedVisibilityChanged(any());
        InsetsSourceConsumer topConsumer = new InsetsSourceConsumer(ITYPE_STATUS_BAR, mInsetsState,
                () -> mMockTransaction, mMockController);
        topConsumer.setControl(
                new InsetsSourceControl(
                        ITYPE_STATUS_BAR, mTopLeash, true, new Point(0, 0),
                        Insets.of(0, 100, 0, 0)),
                new int[1], new int[1]);

        InsetsSourceConsumer navConsumer = new InsetsSourceConsumer(ITYPE_NAVIGATION_BAR,
                mInsetsState, () -> mMockTransaction, mMockController);
        navConsumer.setControl(new InsetsSourceControl(ITYPE_NAVIGATION_BAR, mNavLeash, true,
                new Point(400, 0), Insets.of(0, 0, 100, 0)), new int[1], new int[1]);
        navConsumer.hide();

        SparseArray<InsetsSourceControl> controls = new SparseArray<>();
        controls.put(ITYPE_STATUS_BAR, topConsumer.getControl());
        controls.put(ITYPE_NAVIGATION_BAR, navConsumer.getControl());
        mController = new InsetsAnimationControlImpl(controls,
                new Rect(0, 0, 500, 500), mInsetsState, mMockListener, systemBars(),
                mMockController, 10 /* durationMs */, new LinearInterpolator(),
                0 /* animationType */, 0 /* layoutInsetsDuringAnimation */, null /* translator */);
        mController.setReadyDispatched(true);
    }

    @Test
    public void testGetters() {
        assertEquals(Insets.of(0, 100, 100, 0), mController.getShownStateInsets());
        assertEquals(Insets.of(0, 0, 0, 0), mController.getHiddenStateInsets());
        assertEquals(Insets.of(0, 100, 0, 0), mController.getCurrentInsets());
        assertEquals(systemBars(), mController.getTypes());
    }

    @Test
    public void testReady() {
        assertTrue(mController.isReady());
        assertFalse(mController.isFinished());
        assertFalse(mController.isCancelled());
    }

    @Test
    public void testChangeInsets() {
        mController.setInsetsAndAlpha(Insets.of(0, 30, 40, 0), 1f /* alpha */,
                0f /* fraction */);
        mController.applyChangeInsets(null /* outState */);
        assertEquals(Insets.of(0, 30, 40, 0), mController.getCurrentInsets());
        assertEquals(1f, mController.getCurrentAlpha(), 1f - mController.getCurrentAlpha());

        ArgumentCaptor<SurfaceParams> captor = ArgumentCaptor.forClass(SurfaceParams.class);
        verify(mMockController).applySurfaceParams(captor.capture());
        List<SurfaceParams> params = captor.getAllValues();
        assertEquals(2, params.size());
        SurfaceParams first = params.get(0);
        SurfaceParams second = params.get(1);
        SurfaceParams topParams = first.surface == mTopLeash ? first : second;
        SurfaceParams navParams = first.surface == mNavLeash ? first : second;
        assertPosition(topParams.matrix, new Rect(0, 0, 500, 100), new Rect(0, -70, 500, 30));
        assertPosition(navParams.matrix, new Rect(400, 0, 500, 500), new Rect(460, 0, 560, 500));
    }

    @Test
    public void testChangeAlphaNoInsets() {
        Insets initialInsets = mController.getCurrentInsets();
        mController.setInsetsAndAlpha(null, 0.5f, 0f /* fraction*/);
        mController.applyChangeInsets(null /* outState */);
        assertEquals(0.5f, mController.getCurrentAlpha(), 0.5f - mController.getCurrentAlpha());
        assertEquals(initialInsets, mController.getCurrentInsets());
    }

    @Test
    public void testChangeInsetsAndAlpha() {
        mController.setInsetsAndAlpha(Insets.of(0, 30, 40, 0), 0.5f, 1f);
        mController.applyChangeInsets(null /* outState */);
        assertEquals(0.5f, mController.getCurrentAlpha(), 0.5f - mController.getCurrentAlpha());
        assertEquals(Insets.of(0, 30, 40, 0), mController.getCurrentInsets());
    }

    @Test
    public void testFinishing() {
        when(mMockController.getState()).thenReturn(mInsetsState);
        mController.finish(true /* shown */);
        mController.applyChangeInsets(mInsetsState);
        assertEquals(Insets.of(0, 100, 100, 0), mController.getCurrentInsets());
        verify(mMockController).notifyFinished(eq(mController), eq(true /* shown */));
        assertFalse(mController.isReady());
        assertTrue(mController.isFinished());
        assertFalse(mController.isCancelled());
        verify(mMockListener).onFinished(mController);
    }

    @Test
    public void testCancelled() {
        mController.cancel();
        try {
            mController.setInsetsAndAlpha(Insets.NONE, 1f /*alpha */, 0f /* fraction */);
            fail("Expected exception to be thrown");
        } catch (IllegalStateException ignored) {
        }
        assertFalse(mController.isReady());
        assertFalse(mController.isFinished());
        assertTrue(mController.isCancelled());
        verify(mMockListener).onCancelled(mController);
        mController.finish(true /* shown */);
        verify(mMockListener, never()).onFinished(any());
    }

    @Test
    public void testCancelled_beforeReadyDispatched() {
        mController.setReadyDispatched(false);
        mController.cancel();
        assertFalse(mController.isReady());
        assertFalse(mController.isFinished());
        assertTrue(mController.isCancelled());
        verify(mMockListener).onCancelled(null);
        verify(mMockListener, never()).onFinished(any());
    }

    @Test
    public void testFinish_immediately() {
        when(mMockController.getState()).thenReturn(mInsetsState);
        doAnswer(invocation -> {
            mController.applyChangeInsets(mInsetsState);
            return null;
        }).when(mMockController).scheduleApplyChangeInsets(any());
        mController.finish(true /* shown */);
        assertEquals(Insets.of(0, 100, 100, 0), mController.getCurrentInsets());
        verify(mMockController).notifyFinished(eq(mController), eq(true /* shown */));
        assertFalse(mController.isReady());
        assertTrue(mController.isFinished());
        assertFalse(mController.isCancelled());
        verify(mMockListener).onFinished(mController);
    }

    @Test
    public void testPerceptible_insets() {
        mController.setInsetsAndAlpha(mController.getHiddenStateInsets(), 1f, 1f);
        verify(mMockController).reportPerceptible(systemBars(), false);

        mController.setInsetsAndAlpha(mController.getShownStateInsets(), 1f, 1f);
        verify(mMockController).reportPerceptible(systemBars(), true);
    }

    @Test
    public void testPerceptible_alpha() {
        mController.setInsetsAndAlpha(mController.getShownStateInsets(), 0f, 1f);
        verify(mMockController).reportPerceptible(systemBars(), false);

        mController.setInsetsAndAlpha(mController.getShownStateInsets(), 1f, 1f);
        verify(mMockController).reportPerceptible(systemBars(), true);
    }

    private void assertPosition(Matrix m, Rect original, Rect transformed) {
        RectF rect = new RectF(original);
        rect.offsetTo(0, 0);
        m.mapRect(rect);
        rect.round(original);
        assertEquals(original, transformed);
    }
}
