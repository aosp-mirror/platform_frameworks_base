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

import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsets.Type;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;

/**
 * Tests for {@link InsetsController}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsControllerTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsControllerTest {

    private InsetsController mController;
    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mLeash;

    @Before
    public void setup() {
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getTargetContext();
            // cannot mock ViewRootImpl since it's final.
            ViewRootImpl viewRootImpl = new ViewRootImpl(context, context.getDisplay());
            try {
                viewRootImpl.setView(new TextView(context), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mController = new InsetsController(viewRootImpl);
            final Rect rect = new Rect(5, 5, 5, 5);
            mController.calculateInsets(
                    false,
                    false,
                    new DisplayCutout(
                            Insets.of(10, 10, 10, 10), rect, rect, rect, rect),
                    rect, rect, SOFT_INPUT_ADJUST_RESIZE);
            mController.onFrameChanged(new Rect(0, 0, 100, 100));
            mController.getState().setDisplayFrame(new Rect(0, 0, 100, 100));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testControlsChanged() {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        assertEquals(mLeash,
                mController.getSourceConsumer(ITYPE_STATUS_BAR).getControl().getLeash());
    }

    @Test
    public void testControlsRevoked() {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        mController.onControlsChanged(new InsetsSourceControl[0]);
        assertNull(mController.getSourceConsumer(ITYPE_STATUS_BAR).getControl());
    }

    @Test
    public void testControlsRevoked_duringAnim() {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });

        WindowInsetsAnimationControlListener mockListener =
                mock(WindowInsetsAnimationControlListener.class);
        mController.controlWindowInsetsAnimation(statusBars(), 10 /* durationMs */, mockListener);
        verify(mockListener).onReady(any(), anyInt());
        mController.onControlsChanged(new InsetsSourceControl[0]);
        verify(mockListener).onCancelled();
    }

    @Test
    public void testFrameDoesntMatchDisplay() {
        mController.onFrameChanged(new Rect(0, 0, 100, 100));
        mController.getState().setDisplayFrame(new Rect(0, 0, 200, 200));
        WindowInsetsAnimationControlListener controlListener =
                mock(WindowInsetsAnimationControlListener.class);
        mController.controlWindowInsetsAnimation(0, 0 /* durationMs */, controlListener);
        verify(controlListener).onCancelled();
        verify(controlListener, never()).onReady(any(), anyInt());
    }

    @Test
    public void testAnimationEndState() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl statusBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.getSourceConsumer(ITYPE_IME).onWindowFocusGained();
            // since there is no focused view, forcefully make IME visible.
            mController.applyImeVisibility(true /* setVisible */);
            mController.show(Type.all());
            // quickly jump to final state by cancelling it.
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.applyImeVisibility(false /* setVisible */);
            mController.hide(Type.all());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
            mController.getSourceConsumer(ITYPE_IME).onWindowFocusLost();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testApplyImeVisibility() {
        final InsetsSourceControl ime = new InsetsSourceControl(ITYPE_IME, mLeash, new Point());

        InsetsSourceControl[] controls = new InsetsSourceControl[3];
        controls[0] = ime;
        mController.onControlsChanged(controls);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.getSourceConsumer(ITYPE_IME).onWindowFocusGained();
            mController.applyImeVisibility(true);
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(ime.getType()).isVisible());
            mController.applyImeVisibility(false);
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
            mController.getSourceConsumer(ITYPE_IME).onWindowFocusLost();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowHideSelectively() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl statusBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int types = Type.navigationBars() | Type.systemBars();
            // test show select types.
            mController.show(types);
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test hide all
            mController.hide(types);
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowHideSingle() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl statusBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int types = Type.navigationBars() | Type.systemBars();
            // test show select types.
            mController.show(types);
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test hide all
            mController.hide(Type.all());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test single show
            mController.show(Type.navigationBars());
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test single hide
            mController.hide(Type.navigationBars());
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowHideMultiple() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl statusBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // start two animations and see if previous is cancelled and final state is reached.
            mController.show(Type.navigationBars());
            mController.show(Type.systemBars());
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.navigationBars());
            mController.hide(Type.systemBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            int types = Type.navigationBars() | Type.systemBars();
            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(Type.navigationBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.systemBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowMultipleHideOneByOne() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl statusBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int types = Type.navigationBars() | Type.systemBars();
            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(Type.navigationBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.systemBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testAnimationEndState_controller() throws Exception {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowInsetsAnimationControlListener mockListener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(statusBars(), 0 /* durationMs */,
                    mockListener);

            ArgumentCaptor<WindowInsetsAnimationController> controllerCaptor =
                    ArgumentCaptor.forClass(WindowInsetsAnimationController.class);
            verify(mockListener).onReady(controllerCaptor.capture(), anyInt());
            controllerCaptor.getValue().finish(false /* shown */);
        });
        waitUntilNextFrame();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(mController.getSourceConsumer(ITYPE_STATUS_BAR).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void waitUntilNextFrame() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        Choreographer.getMainThreadInstance().postCallback(Choreographer.CALLBACK_COMMIT,
                latch::countDown, null /* token */);
        latch.await();
    }

    private InsetsSourceControl[] prepareControls() {
        final InsetsSourceControl navBar = new InsetsSourceControl(ITYPE_NAVIGATION_BAR, mLeash,
                new Point());
        final InsetsSourceControl statusBar = new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash,
                new Point());
        final InsetsSourceControl ime = new InsetsSourceControl(ITYPE_IME, mLeash, new Point());

        InsetsSourceControl[] controls = new InsetsSourceControl[3];
        controls[0] = navBar;
        controls[1] = statusBar;
        controls[2] = ime;
        mController.onControlsChanged(controls);
        return controls;
    }
}
