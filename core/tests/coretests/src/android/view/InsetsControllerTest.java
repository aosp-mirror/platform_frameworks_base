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

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_NONE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsSourceConsumer.ShowResult.IME_SHOW_DELAYED;
import static android.view.InsetsSourceConsumer.ShowResult.SHOW_IMMEDIATELY;
import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsState.InternalInsetsType;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type;
import android.view.WindowInsetsController.OnControllableInsetsChangedListener;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
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
    private TestHost mTestHost;
    private TestHandler mTestHandler;
    private OffsettableClock mTestClock;

    @Before
    public void setup() {
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            // cannot mock ViewRootImpl since it's final.
            mViewRoot = new ViewRootImpl(context, context.getDisplayNoVerify());
            try {
                mViewRoot.setView(new TextView(context), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mTestClock = new OffsettableClock();
            mTestHandler = new TestHandler(null, mTestClock);
            mTestHost = spy(new TestHost(mViewRoot));
            mController = new InsetsController(mTestHost, (controller, type) -> {
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
            mController.getState().setDisplayCutout(new DisplayCutout(
                    Insets.of(10, 10, 10, 10), rect, rect, rect, rect));
            mController.calculateInsets(
                    false,
                    false,
                    TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                    SOFT_INPUT_ADJUST_RESIZE, 0, 0);
            mController.onFrameChanged(new Rect(0, 0, 100, 100));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testControlsChanged() {
        mController.onControlsChanged(createSingletonControl(ITYPE_STATUS_BAR));
        assertNotNull(mController.getSourceConsumer(ITYPE_STATUS_BAR).getControl().getLeash());
        mController.addOnControllableInsetsChangedListener(
                ((controller, typeMask) -> assertEquals(statusBars(), typeMask)));
    }

    @Test
    public void testControlsRevoked() {
        OnControllableInsetsChangedListener listener
                = mock(OnControllableInsetsChangedListener.class);
        mController.addOnControllableInsetsChangedListener(listener);
        mController.onControlsChanged(createSingletonControl(ITYPE_STATUS_BAR));
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
            mController.onControlsChanged(createSingletonControl(ITYPE_STATUS_BAR));

            ArgumentCaptor<WindowInsetsAnimationController> animationController =
                    ArgumentCaptor.forClass(WindowInsetsAnimationController.class);

            WindowInsetsAnimationControlListener mockListener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(statusBars(), 10 /* durationMs */,
                    new LinearInterpolator(), new CancellationSignal(), mockListener);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();
            verify(mockListener).onReady(animationController.capture(), anyInt());
            mController.onControlsChanged(new InsetsSourceControl[0]);
            verify(mockListener).onCancelled(notNull());
            assertTrue(animationController.getValue().isCancelled());
        });
    }

    @Test
    public void testFrameDoesntMatchDisplay() {
        mController.onFrameChanged(new Rect(0, 0, 100, 100));
        mController.getState().setDisplayFrame(new Rect(0, 0, 200, 200));
        InsetsSourceControl control =
                new InsetsSourceControl(
                        ITYPE_STATUS_BAR, mLeash, new Point(), Insets.of(0, 10, 0, 0));
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        WindowInsetsAnimationControlListener controlListener =
                mock(WindowInsetsAnimationControlListener.class);
        mController.controlWindowInsetsAnimation(0, 0 /* durationMs */, new LinearInterpolator(),
                new CancellationSignal(), controlListener);
        mController.addOnControllableInsetsChangedListener(
                (controller, typeMask) -> assertEquals(0, typeMask));
        verify(controlListener).onCancelled(null);
        verify(controlListener, never()).onReady(any(), anyInt());
    }

    @Test
    public void testAnimationEndState() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl statusBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.getSourceConsumer(ITYPE_IME).onWindowFocusGained(true);
            // since there is no focused view, forcefully make IME visible.
            mController.show(Type.ime(), true /* fromIme */);
            mController.show(Type.all());
            // quickly jump to final state by cancelling it.
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.hide(Type.ime(), true /* fromIme */);
            mController.hide(Type.all());
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
            mController.getSourceConsumer(ITYPE_IME).onWindowFocusLost();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testApplyImeVisibility() {
        InsetsSourceControl ime = createControl(ITYPE_IME);
        mController.onControlsChanged(new InsetsSourceControl[] { ime });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.getSourceConsumer(ITYPE_IME).onWindowFocusGained(true);
            mController.show(Type.ime(), true /* fromIme */);
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
            mController.hide(Type.ime(), true /* fromIme */);
            mController.cancelExistingAnimations();
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
            mController.cancelExistingAnimations();
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_STATUS_BAR));
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test hide all
            mController.show(types);
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimations();
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
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test hide all
            mController.hide(Type.all());
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test single show
            mController.show(Type.navigationBars());
            mController.cancelExistingAnimations();
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
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.show(Type.navigationBars());
            mController.show(Type.systemBars());
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            int types = Type.navigationBars() | Type.systemBars();
            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(Type.navigationBars());
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.hide(Type.systemBars());
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(ITYPE_NAVIGATION_BAR));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimations();
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
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertTrue(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            mController.hide(Type.systemBars());
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(statusBar.getType()).isRequestedVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testRestoreStartsAnimation() {
        mController.onControlsChanged(createSingletonControl(ITYPE_STATUS_BAR));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.hide(Type.statusBars());
            mController.cancelExistingAnimations();
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
            mController.onControlsChanged(createSingletonControl(ITYPE_STATUS_BAR));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(ITYPE_STATUS_BAR));
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(ITYPE_STATUS_BAR).isRequestedVisible());
            assertFalse(mController.getState().getSource(ITYPE_STATUS_BAR).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testStartImeAnimationAfterGettingControl() {

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            mController.show(ime());
            assertFalse(mController.getState().getSource(ITYPE_IME).isVisible());

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */);

            // Gaining control shortly after
            mController.onControlsChanged(createSingletonControl(ITYPE_IME));

            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_IME));
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(ITYPE_IME).isRequestedVisible());
            assertTrue(mController.getState().getSource(ITYPE_IME).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testStartImeAnimationAfterGettingControl_imeLater() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            mController.show(ime());
            assertFalse(mController.getState().getSource(ITYPE_IME).isVisible());

            // Gaining control shortly after
            mController.onControlsChanged(createSingletonControl(ITYPE_IME));

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */);

            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ITYPE_IME));
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(ITYPE_IME).isRequestedVisible());
            assertTrue(mController.getState().getSource(ITYPE_IME).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testAnimationEndState_controller() throws Exception {
        mController.onControlsChanged(createSingletonControl(ITYPE_STATUS_BAR));

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
        mController.onControlsChanged(createSingletonControl(ITYPE_STATUS_BAR));

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
            verify(mockListener).onCancelled(notNull());
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

            verify(listener).onCancelled(null);
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

            verify(listener).onCancelled(null);
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

            verify(listener).onCancelled(null);

            // Ready gets deferred until next predraw
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();

            verify(listener, never()).onReady(any(), anyInt());

            // Pretend that timeout is happening
            mTestClock.fastForward(2500);
            mTestHandler.timeAdvance();
        });
    }

    @Test
    public void testFrameUpdateDuringAnimation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            mController.onControlsChanged(createSingletonControl(ITYPE_IME));

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */);

            InsetsState copy = new InsetsState(mController.getState(), true /* copySources */);
            copy.getSource(ITYPE_IME).setFrame(0, 1, 2, 3);
            copy.getSource(ITYPE_IME).setVisibleFrame(new Rect(4, 5, 6, 7));
            mController.onStateChanged(copy);
            assertNotEquals(new Rect(0, 1, 2, 3),
                    mController.getState().getSource(ITYPE_IME).getFrame());
            assertNotEquals(new Rect(4, 5, 6, 7),
                    mController.getState().getSource(ITYPE_IME).getVisibleFrame());
            mController.cancelExistingAnimations();
            assertEquals(new Rect(0, 1, 2, 3),
                    mController.getState().getSource(ITYPE_IME).getFrame());
            assertEquals(new Rect(4, 5, 6, 7),
                    mController.getState().getSource(ITYPE_IME).getVisibleFrame());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testCaptionInsetsStateAssemble() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.onFrameChanged(new Rect(0, 0, 100, 300));
            final InsetsState state = new InsetsState(mController.getState(), true);
            final Rect captionFrame = new Rect(0, 0, 100, 100);
            mController.setCaptionInsetsHeight(100);
            mController.onStateChanged(state);
            final InsetsState currentState = new InsetsState(mController.getState());
            // The caption bar source should be synced with the info in mAttachInfo.
            assertEquals(captionFrame, currentState.peekSource(ITYPE_CAPTION_BAR).getFrame());
            assertTrue(currentState.equals(state, true /* excludingCaptionInsets*/,
                    true /* excludeInvisibleIme */));
            mController.setCaptionInsetsHeight(0);
            mController.onStateChanged(state);
            // The caption bar source should not be there at all, because we don't add empty
            // caption to the state from the server.
            assertNull(mController.getState().peekSource(ITYPE_CAPTION_BAR));
        });
    }

    @Test
    public void testNotifyCaptionInsetsOnlyChange() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final InsetsState state = new InsetsState(mController.getState(), true);
            reset(mTestHost);
            mController.setCaptionInsetsHeight(100);
            verify(mTestHost).notifyInsetsChanged();
            reset(mTestHost);
            mController.setCaptionInsetsHeight(0);
            verify(mTestHost).notifyInsetsChanged();
        });
    }

    @Test
    public void testRequestedState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final InsetsState state = mTestHost.getRequestedState();

            mController.hide(statusBars() | navigationBars());
            assertFalse(state.getSourceOrDefaultVisibility(ITYPE_STATUS_BAR));
            assertFalse(state.getSourceOrDefaultVisibility(ITYPE_NAVIGATION_BAR));

            mController.show(statusBars() | navigationBars());
            assertTrue(state.getSourceOrDefaultVisibility(ITYPE_STATUS_BAR));
            assertTrue(state.getSourceOrDefaultVisibility(ITYPE_NAVIGATION_BAR));
        });
    }

    @Test
    public void testInsetsChangedCount_controlSystemBars() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            prepareControls();

            // Hiding visible system bars should only causes insets change once for each bar.
            clearInvocations(mTestHost);
            mController.hide(statusBars() | navigationBars());
            verify(mTestHost, times(2)).notifyInsetsChanged();

            // Sending the same insets state should not cause insets change.
            // This simulates the callback from server after hiding system bars.
            clearInvocations(mTestHost);
            mController.onStateChanged(mController.getState());
            verify(mTestHost, never()).notifyInsetsChanged();

            // Showing invisible system bars should only causes insets change once for each bar.
            clearInvocations(mTestHost);
            mController.show(statusBars() | navigationBars());
            verify(mTestHost, times(2)).notifyInsetsChanged();

            // Sending the same insets state should not cause insets change.
            // This simulates the callback from server after showing system bars.
            clearInvocations(mTestHost);
            mController.onStateChanged(mController.getState());
            verify(mTestHost, never()).notifyInsetsChanged();
        });
    }

    @Test
    public void testInsetsChangedCount_controlIme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            prepareControls();

            // Showing invisible ime should only causes insets change once.
            clearInvocations(mTestHost);
            mController.show(ime(), true /* fromIme */);
            verify(mTestHost, times(1)).notifyInsetsChanged();

            // Sending the same insets state should not cause insets change.
            // This simulates the callback from server after showing ime.
            clearInvocations(mTestHost);
            mController.onStateChanged(mController.getState());
            verify(mTestHost, never()).notifyInsetsChanged();

            // Hiding visible ime should only causes insets change once.
            clearInvocations(mTestHost);
            mController.hide(ime());
            verify(mTestHost, times(1)).notifyInsetsChanged();

            // Sending the same insets state should not cause insets change.
            // This simulates the callback from server after hiding ime.
            clearInvocations(mTestHost);
            mController.onStateChanged(mController.getState());
            verify(mTestHost, never()).notifyInsetsChanged();
        });
    }

    @Test
    public void testInsetsChangedCount_onStateChanged() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final InsetsState localState = mController.getState();

            // Changing status bar frame should cause notifyInsetsChanged.
            clearInvocations(mTestHost);
            InsetsState newState = new InsetsState(localState, true /* copySources */);
            newState.getSource(ITYPE_STATUS_BAR).getFrame().bottom++;
            mController.onStateChanged(newState);
            verify(mTestHost, times(1)).notifyInsetsChanged();

            // Changing status bar visibility should cause notifyInsetsChanged.
            clearInvocations(mTestHost);
            newState = new InsetsState(localState, true /* copySources */);
            newState.getSource(ITYPE_STATUS_BAR).setVisible(false);
            mController.onStateChanged(newState);
            verify(mTestHost, times(1)).notifyInsetsChanged();

            // Changing invisible IME frame should not cause notifyInsetsChanged.
            clearInvocations(mTestHost);
            newState = new InsetsState(localState, true /* copySources */);
            newState.getSource(ITYPE_IME).getFrame().top--;
            mController.onStateChanged(newState);
            verify(mTestHost, never()).notifyInsetsChanged();

            // Changing IME visibility should cause notifyInsetsChanged.
            clearInvocations(mTestHost);
            newState = new InsetsState(localState, true /* copySources */);
            newState.getSource(ITYPE_IME).setVisible(true);
            mController.onStateChanged(newState);
            verify(mTestHost, times(1)).notifyInsetsChanged();
        });
    }

    private void waitUntilNextFrame() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        Choreographer.getMainThreadInstance().postCallback(Choreographer.CALLBACK_COMMIT,
                latch::countDown, null /* token */);
        latch.await();
    }

    private InsetsSourceControl createControl(@InternalInsetsType int type) {

        // Simulate binder behavior by copying SurfaceControl. Otherwise, InsetsController will
        // attempt to release mLeash directly.
        SurfaceControl copy = new SurfaceControl(mLeash, "InsetsControllerTest.createControl");
        return new InsetsSourceControl(type, copy, new Point(), Insets.NONE);
    }

    private InsetsSourceControl[] createSingletonControl(@InternalInsetsType int type) {
        return new InsetsSourceControl[] { createControl(type) };
    }

    private InsetsSourceControl[] prepareControls() {
        final InsetsSourceControl navBar = createControl(ITYPE_NAVIGATION_BAR);
        final InsetsSourceControl statusBar = createControl(ITYPE_STATUS_BAR);
        final InsetsSourceControl ime = createControl(ITYPE_IME);

        InsetsSourceControl[] controls = new InsetsSourceControl[3];
        controls[0] = navBar;
        controls[1] = statusBar;
        controls[2] = ime;
        mController.onControlsChanged(controls);
        return controls;
    }

    public static class TestHost extends ViewRootInsetsControllerHost {

        private final InsetsState mRequestedState = new InsetsState();

        TestHost(ViewRootImpl viewRoot) {
            super(viewRoot);
        }

        @Override
        public void onInsetsModified(InsetsState insetsState) {
            mRequestedState.set(insetsState, true);
            super.onInsetsModified(insetsState);
        }

        public InsetsState getRequestedState() {
            return mRequestedState;
        }
    }
}
