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

import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_NONE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsSourceConsumer.ShowResult.IME_SHOW_DELAYED;
import static android.view.InsetsSourceConsumer.ShowResult.SHOW_IMMEDIATELY;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type;
import android.view.WindowInsetsController.OnControllableInsetsChangedListener;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.view.animation.LinearInterpolator;
import android.view.test.InsetsModeSession;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

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
    private ViewRootImpl mViewRoot;
    private TestHandler mTestHandler;
    private OffsettableClock mTestClock;
    private static InsetsModeSession sInsetsModeSession;

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
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getTargetContext();
            // cannot mock ViewRootImpl since it's final.
            mViewRoot = new ViewRootImpl(context, context.getDisplayNoVerify());
            try {
                mViewRoot.setView(new TextView(context), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mTestClock = new OffsettableClock();
            mTestHandler = new TestHandler(null, mTestClock);
            mController = new InsetsController(mViewRoot, (controller, type) -> {
                if (type == ITYPE_IME) {
                    return new InsetsSourceConsumer(type, controller.getState(),
                            Transaction::new, controller) {

                        private boolean mImeRequestedShow;

                        @Override
                        public void show(boolean fromIme) {
                            super.show(fromIme);
                            if (fromIme) {
                                mImeRequestedShow = true;
                            }
                        }

                        @Override
                        public int requestShow(boolean fromController) {
                            if (fromController || mImeRequestedShow) {
                                return SHOW_IMMEDIATELY;
                            } else {
                                return IME_SHOW_DELAYED;
                            }
                        }
                    };
                } else {
                    return new InsetsSourceConsumer(type, controller.getState(), Transaction::new,
                            controller);
                }
            }, mTestHandler);
            final Rect rect = new Rect(5, 5, 5, 5);
            mController.getState().getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 10));
            mController.getState().getSource(ITYPE_NAVIGATION_BAR).setFrame(
                    new Rect(0, 90, 100, 100));
            mController.getState().getSource(ITYPE_IME).setFrame(new Rect(0, 50, 100, 100));
            mController.getState().setDisplayFrame(new Rect(0, 0, 100, 100));
            mController.calculateInsets(
                    false,
                    false,
                    new DisplayCutout(
                            Insets.of(10, 10, 10, 10), rect, rect, rect, rect),
                    rect, rect, SOFT_INPUT_ADJUST_RESIZE, 0);
            mController.onFrameChanged(new Rect(0, 0, 100, 100));
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
        mController.addOnControllableInsetsChangedListener(
                ((controller, typeMask) -> assertEquals(statusBars(), typeMask)));
    }

    @Test
    public void testControlsRevoked() {
        OnControllableInsetsChangedListener listener
                = mock(OnControllableInsetsChangedListener.class);
        mController.addOnControllableInsetsChangedListener(listener);
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        mController.onControlsChanged(new InsetsSourceControl[0]);
        assertNull(mController.getSourceConsumer(ITYPE_STATUS_BAR).getControl());
        InOrder inOrder = Mockito.inOrder(listener);
        inOrder.verify(listener).onControllableInsetsChanged(eq(mController), eq(0));
        inOrder.verify(listener).onControllableInsetsChanged(eq(mController), eq(statusBars()));
        inOrder.verify(listener).onControllableInsetsChanged(eq(mController), eq(0));
    }

    @Test
    public void testControlsRevoked_duringAnim() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            InsetsSourceControl control =
                    new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
            mController.onControlsChanged(new InsetsSourceControl[] { control });

            WindowInsetsAnimationControlListener mockListener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(statusBars(), 10 /* durationMs */,
                    new LinearInterpolator(), new CancellationSignal(), mockListener);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();
            verify(mockListener).onReady(any(), anyInt());
            mController.onControlsChanged(new InsetsSourceControl[0]);
            verify(mockListener).onCancelled();
        });
    }

    @Test
    public void testFrameDoesntMatchDisplay() {
        mController.onFrameChanged(new Rect(0, 0, 100, 100));
        mController.getState().setDisplayFrame(new Rect(0, 0, 200, 200));
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        WindowInsetsAnimationControlListener controlListener =
                mock(WindowInsetsAnimationControlListener.class);
        mController.controlWindowInsetsAnimation(0, 0 /* durationMs */, new LinearInterpolator(),
                new CancellationSignal(), controlListener);
        mController.addOnControllableInsetsChangedListener(
                (controller, typeMask) -> assertEquals(0, typeMask));
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
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.applyImeVisibility(false /* setVisible */);
            mController.hide(Type.all());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
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
            assertTrue(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
            mController.applyImeVisibility(false);
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
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
            // test hide select types.
            mController.hide(types);
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimation();
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_STATUS_BAR));
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test hide all
            mController.show(types);
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
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
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test hide all
            mController.hide(Type.all());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test single show
            mController.show(Type.navigationBars());
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test single hide
            mController.hide(Type.navigationBars());
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

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
            mController.hide(Type.navigationBars());
            mController.hide(Type.systemBars());
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.show(Type.navigationBars());
            mController.show(Type.systemBars());
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            int types = Type.navigationBars() | Type.systemBars();
            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(Type.navigationBars());
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.hide(Type.systemBars());
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
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
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.hide(Type.systemBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testRestoreStartsAnimation() {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[]{control});

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.hide(Type.statusBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(ITYPE_STATUS_BAR).isRequestedVisible());
            assertFalse(mController.getState().getSource(ITYPE_STATUS_BAR).isVisible());

            // Loosing control
            InsetsState state = new InsetsState(mController.getState());
            state.setSourceVisible(ITYPE_STATUS_BAR, true);
            mController.onStateChanged(state);
            mController.onControlsChanged(new InsetsSourceControl[0]);
            assertFalse(mController.getSourceConsumer(ITYPE_STATUS_BAR).isRequestedVisible());
            assertTrue(mController.getState().getSource(ITYPE_STATUS_BAR).isVisible());

            // Gaining control
            mController.onControlsChanged(new InsetsSourceControl[]{control});
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(ITYPE_STATUS_BAR).isRequestedVisible());
            assertFalse(mController.getState().getSource(ITYPE_STATUS_BAR).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testStartImeAnimationAfterGettingControl() {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_IME, mLeash, new Point());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            mController.show(ime());
            assertFalse(mController.getState().getSource(ITYPE_IME).isVisible());

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */);

            // Gaining control shortly after
            mController.onControlsChanged(new InsetsSourceControl[]{control});

            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_IME));
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(ITYPE_IME).isRequestedVisible());
            assertTrue(mController.getState().getSource(ITYPE_IME).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testStartImeAnimationAfterGettingControl_imeLater() {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_IME, mLeash, new Point());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            mController.show(ime());
            assertFalse(mController.getState().getSource(ITYPE_IME).isVisible());

            // Gaining control shortly after
            mController.onControlsChanged(new InsetsSourceControl[]{control});

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */);

            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_IME));
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(ITYPE_IME).isRequestedVisible());
            assertTrue(mController.getState().getSource(ITYPE_IME).isVisible());
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
                    new LinearInterpolator(), new CancellationSignal(), mockListener);

            ArgumentCaptor<WindowInsetsAnimationController> controllerCaptor =
                    ArgumentCaptor.forClass(WindowInsetsAnimationController.class);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(mockListener).onReady(controllerCaptor.capture(), anyInt());
            controllerCaptor.getValue().finish(false /* shown */);
        });
        waitUntilNextFrame();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(mController.getSourceConsumer(ITYPE_STATUS_BAR).isRequestedVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testCancellation_afterGainingControl() throws Exception {
        InsetsSourceControl control =
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowInsetsAnimationControlListener mockListener =
                    mock(WindowInsetsAnimationControlListener.class);
            CancellationSignal cancellationSignal = new CancellationSignal();
            mController.controlWindowInsetsAnimation(
                    statusBars(), 0 /* durationMs */,
                    new LinearInterpolator(), cancellationSignal, mockListener);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(mockListener).onReady(any(), anyInt());

            cancellationSignal.cancel();
            verify(mockListener).onCancelled();
        });
        waitUntilNextFrame();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(mController.getSourceConsumer(ITYPE_STATUS_BAR).isRequestedVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testControlImeNotReady() {
        prepareControls();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowInsetsAnimationControlListener listener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(ime(), 0, new LinearInterpolator(),
                    null /* cancellationSignal */, listener);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(listener, never()).onReady(any(), anyInt());

            // Pretend that IME is calling.
            mController.show(ime(), true);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(listener).onReady(notNull(), eq(ime()));

        });
    }

    @Test
    public void testControlImeNotReady_controlRevoked() {
        prepareControls();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowInsetsAnimationControlListener listener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(ime(), 0, new LinearInterpolator(),
                    null /* cancellationSignal */, listener);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(listener, never()).onReady(any(), anyInt());

            // Pretend that we are losing control
            mController.onControlsChanged(new InsetsSourceControl[0]);

            verify(listener).onCancelled();
        });
    }

    @Test
    public void testControlImeNotReady_timeout() {
        prepareControls();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowInsetsAnimationControlListener listener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(ime(), 0, new LinearInterpolator(),
                    null /* cancellationSignal */, listener);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(listener, never()).onReady(any(), anyInt());

            // Pretend that timeout is happening
            mTestClock.fastForward(2500);
            mTestHandler.timeAdvance();

            verify(listener).onCancelled();
        });
    }

    @Test
    public void testControlImeNotReady_cancel() {
        prepareControls();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowInsetsAnimationControlListener listener =
                    mock(WindowInsetsAnimationControlListener.class);
            CancellationSignal cancellationSignal = new CancellationSignal();
            mController.controlWindowInsetsAnimation(ime(), 0, new LinearInterpolator(),
                    cancellationSignal, listener);
            cancellationSignal.cancel();

            verify(listener).onCancelled();

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(listener, never()).onReady(any(), anyInt());

            // Pretend that timeout is happening
            mTestClock.fastForward(2500);
            mTestHandler.timeAdvance();
        });
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
