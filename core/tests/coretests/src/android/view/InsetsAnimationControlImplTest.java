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
 * limitations under the License
 */

package android.view;

import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.WindowInsets.Type.sideBars;
import static android.view.WindowInsets.Type.systemBars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
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
import android.view.test.InsetsModeSession;

import androidx.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
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
    private static InsetsModeSession sInsetsModeSession;

    @Mock Transaction mMockTransaction;
    @Mock InsetsController mMockController;
    @Mock WindowInsetsAnimationControlListener mMockListener;
    @Mock SyncRtSurfaceTransactionApplier mMockTransactionApplier;

    @BeforeClass
    public static void setupOnce() {
        sInsetsModeSession = new InsetsModeSession(NEW_INSETS_MODE_FULL);
    }

    @AfterClass
    public static void tearDownOnce() {
        sInsetsModeSession.close();
    }

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
        mInsetsState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 500, 100));
        mInsetsState.getSource(TYPE_NAVIGATION_BAR).setFrame(new Rect(400, 0, 500, 500));
        InsetsSourceConsumer topConsumer = new InsetsSourceConsumer(TYPE_TOP_BAR, mInsetsState,
                () -> mMockTransaction, mMockController);
        topConsumer.setControl(new InsetsSourceControl(TYPE_TOP_BAR, mTopLeash, new Point(0, 0)));

        InsetsSourceConsumer navConsumer = new InsetsSourceConsumer(TYPE_NAVIGATION_BAR,
                mInsetsState, () -> mMockTransaction, mMockController);
        navConsumer.hide();
        navConsumer.setControl(new InsetsSourceControl(TYPE_NAVIGATION_BAR, mNavLeash,
                new Point(400, 0)));

        SparseArray<InsetsSourceConsumer> consumers = new SparseArray<>();
        consumers.put(TYPE_TOP_BAR, topConsumer);
        consumers.put(TYPE_NAVIGATION_BAR, navConsumer);
        mController = new InsetsAnimationControlImpl(consumers,
                new Rect(0, 0, 500, 500), mInsetsState, mMockListener, systemBars(),
                () -> mMockTransactionApplier, mMockController);
    }

    @Test
    public void testGetters() {
        assertEquals(Insets.of(0, 100, 100, 0), mController.getShownStateInsets());
        assertEquals(Insets.of(0, 0, 0, 0), mController.getHiddenStateInsets());
        assertEquals(Insets.of(0, 100, 0, 0), mController.getCurrentInsets());
        assertEquals(systemBars(), mController.getTypes());
    }

    @Test
    public void testChangeInsets() {
        mController.changeInsets(Insets.of(0, 30, 40, 0));
        mController.applyChangeInsets(new InsetsState());
        assertEquals(Insets.of(0, 30, 40, 0), mController.getCurrentInsets());

        ArgumentCaptor<SurfaceParams> captor = ArgumentCaptor.forClass(SurfaceParams.class);
        verify(mMockTransactionApplier).scheduleApply(captor.capture());
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
    public void testFinishing() {
        when(mMockController.getState()).thenReturn(mInsetsState);
        mController.finish(sideBars());
        mController.applyChangeInsets(mInsetsState);
        assertFalse(mInsetsState.getSource(TYPE_TOP_BAR).isVisible());
        assertTrue(mInsetsState.getSource(TYPE_NAVIGATION_BAR).isVisible());
        assertEquals(Insets.of(0, 0, 100, 0), mController.getCurrentInsets());
        verify(mMockController).notifyFinished(eq(mController), eq(sideBars()));
    }

    @Test
    public void testCancelled() {
        mController.onCancelled();
        try {
            mController.changeInsets(Insets.NONE);
            fail("Expected exception to be thrown");
        } catch (IllegalStateException ignored) {
        }
        verify(mMockListener).onCancelled();
        mController.finish(sideBars());
    }

    private void assertPosition(Matrix m, Rect original, Rect transformed) {
        RectF rect = new RectF(original);
        rect.offsetTo(0, 0);
        m.mapRect(rect);
        rect.round(original);
        assertEquals(original, transformed);
    }
}
