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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_NONE;
import static android.view.InsetsController.ANIMATION_TYPE_RESIZE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsController.ANIMATION_TYPE_USER;
import static android.view.InsetsSource.FLAG_ANIMATE_RESIZING;
import static android.view.InsetsSource.ID_IME;
import static android.view.InsetsSourceConsumer.ShowResult.IME_SHOW_DELAYED;
import static android.view.InsetsSourceConsumer.ShowResult.SHOW_IMMEDIATELY;
import static android.view.WindowInsets.Type.all;
import static android.view.WindowInsets.Type.defaultVisible;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.systemBars;
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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController.OnControllableInsetsChangedListener;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.ImeTracker;
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

    private static final int ID_STATUS_BAR = InsetsSource.createId(
            null /* owner */, 0 /* index */, statusBars());
    private static final int ID_NAVIGATION_BAR = InsetsSource.createId(
            null /* owner */, 0 /* index */, navigationBars());

    private InsetsSource mStatusSource;
    private InsetsSource mNavSource;
    private InsetsSource mImeSource;
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
            mController = new InsetsController(mTestHost, (controller, id, type) -> {
                if (type == ime()) {
                    return new InsetsSourceConsumer(id, type, controller.getState(),
                            Transaction::new, controller) {

                        private boolean mImeRequestedShow;

                        @Override
                        public int requestShow(boolean fromController,
                                ImeTracker.Token statsToken) {
                            if (fromController || mImeRequestedShow) {
                                mImeRequestedShow = true;
                                return SHOW_IMMEDIATELY;
                            } else {
                                return IME_SHOW_DELAYED;
                            }
                        }
                    };
                } else {
                    return new InsetsSourceConsumer(id, type, controller.getState(),
                            Transaction::new, controller);
                }
            }, mTestHandler);
            final Rect rect = new Rect(5, 5, 5, 5);
            mStatusSource = new InsetsSource(ID_STATUS_BAR, statusBars());
            mStatusSource.setFrame(new Rect(0, 0, 100, 10));
            mNavSource = new InsetsSource(ID_NAVIGATION_BAR, navigationBars());
            mNavSource.setFrame(new Rect(0, 90, 100, 100));
            mImeSource = new InsetsSource(ID_IME, ime());
            mImeSource.setFrame(new Rect(0, 0, 100, 10));
            InsetsState state = new InsetsState();
            state.addSource(mStatusSource);
            state.addSource(mNavSource);
            state.addSource(mImeSource);
            state.setDisplayFrame(new Rect(0, 0, 100, 100));
            state.setDisplayCutout(new DisplayCutout(
                    Insets.of(10, 10, 10, 10), rect, rect, rect, rect));
            mController.onStateChanged(state);
            mController.calculateInsets(
                    false,
                    TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                    SOFT_INPUT_ADJUST_RESIZE, 0, 0);
            mController.onFrameChanged(new Rect(0, 0, 100, 100));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testControlsChanged() {
        mController.onControlsChanged(createSingletonControl(ID_STATUS_BAR, statusBars()));
        assertNotNull(
                mController.getSourceConsumer(ID_STATUS_BAR, statusBars()).getControl().getLeash());
        mController.addOnControllableInsetsChangedListener(
                ((controller, typeMask) -> assertEquals(statusBars(), typeMask)));
    }

    @Test
    public void testControlsRevoked() {
        OnControllableInsetsChangedListener listener
                = mock(OnControllableInsetsChangedListener.class);
        mController.addOnControllableInsetsChangedListener(listener);
        mController.onControlsChanged(createSingletonControl(ID_STATUS_BAR, statusBars()));
        mController.onControlsChanged(new InsetsSourceControl[0]);
        assertNull(mController.getSourceConsumer(ID_STATUS_BAR, statusBars()).getControl());
        InOrder inOrder = Mockito.inOrder(listener);
        inOrder.verify(listener).onControllableInsetsChanged(eq(mController), eq(0));
        inOrder.verify(listener).onControllableInsetsChanged(eq(mController), eq(statusBars()));
        inOrder.verify(listener).onControllableInsetsChanged(eq(mController), eq(0));
    }

    @Test
    public void testControlsRevoked_duringAnim() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.onControlsChanged(createSingletonControl(ID_STATUS_BAR, statusBars()));

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
    public void testFrameDoesntOverlapWithInsets() {
        WindowInsetsAnimationControlListener controlListener =
                mock(WindowInsetsAnimationControlListener.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            //  The frame doesn't overlap with status bar.
            mController.onFrameChanged(new Rect(0, 10, 100, 100));

            InsetsSourceControl control =
                    new InsetsSourceControl(
                            ID_STATUS_BAR, statusBars(), mLeash, true, new Point(),
                            Insets.of(0, 10, 0, 0));
            mController.onControlsChanged(new InsetsSourceControl[]{control});
            mController.controlWindowInsetsAnimation(0, 0 /* durationMs */,
                    new LinearInterpolator(),
                    new CancellationSignal(), controlListener);
            mController.addOnControllableInsetsChangedListener(
                    (controller, typeMask) -> assertEquals(0, typeMask));
        });
        verify(controlListener).onCancelled(null);
        verify(controlListener, never()).onReady(any(), anyInt());
    }

    @Test
    public void testSystemDrivenInsetsAnimationLoggingListener_onReady() {
        var loggingListener = mock(WindowInsetsAnimationControlListener.class);

        prepareControls();
        // only the original thread that created view hierarchy can touch its views
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.setSystemDrivenInsetsAnimationLoggingListener(loggingListener);
            mController.getSourceConsumer(ID_IME, ime()).onWindowFocusGained(true);
            // since there is no focused view, forcefully make IME visible.
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
            // When using the animation thread, this must not invoke onReady()
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();
        });
        // Wait for onReady() being dispatched on the animation thread.
        InsetsAnimationThread.get().getThreadHandler().runWithScissors(() -> {}, 500);

        verify(loggingListener).onReady(notNull(), anyInt());
    }

    @Test
    public void testAnimationEndState() {
        prepareControls();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.getSourceConsumer(ID_IME, ime()).onWindowFocusGained(true);
            // since there is no focused view, forcefully make IME visible.
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
            mController.show(all());
            // quickly jump to final state by cancelling it.
            mController.cancelExistingAnimations();
            @InsetsType final int types = navigationBars() | statusBars() | ime();
            assertEquals(types, mController.getRequestedVisibleTypes() & types);

            mController.hide(ime(), true /* fromIme */, ImeTracker.Token.empty());
            mController.hide(all());
            mController.cancelExistingAnimations();
            assertEquals(0, mController.getRequestedVisibleTypes() & types);
            mController.getSourceConsumer(ID_IME, ime()).onWindowFocusLost();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testApplyImeVisibility() {
        InsetsSourceControl ime = createControl(ID_IME, ime());
        mController.onControlsChanged(new InsetsSourceControl[] { ime });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.getSourceConsumer(ID_IME, ime()).onWindowFocusGained(true);
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
            mController.cancelExistingAnimations();
            assertTrue(isRequestedVisible(mController, ime()));
            mController.hide(ime(), true /* fromIme */, ImeTracker.Token.empty());
            mController.cancelExistingAnimations();
            assertFalse(isRequestedVisible(mController, ime()));
            mController.getSourceConsumer(ID_IME, ime()).onWindowFocusLost();
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
            int types = navigationBars() | statusBars();
            // test hide select types.
            mController.hide(types);
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(navigationBars()));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(statusBars()));
            mController.cancelExistingAnimations();
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(navigationBars()));
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(statusBars()));
            assertEquals(0, mController.getRequestedVisibleTypes() & (types | ime()));

            // test show all
            mController.show(types);
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(navigationBars()));
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(statusBars()));
            mController.cancelExistingAnimations();
            assertEquals(types, mController.getRequestedVisibleTypes() & (types | ime()));
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
            int types = navigationBars() | statusBars();
            // test show select types.
            mController.show(types);
            mController.cancelExistingAnimations();
            assertEquals(types, mController.getRequestedVisibleTypes() & types);
            assertEquals(0, mController.getRequestedVisibleTypes() & ime());

            // test hide all
            mController.hide(all());
            mController.cancelExistingAnimations();
            assertEquals(0, mController.getRequestedVisibleTypes() & (types | ime()));

            // test single show
            mController.show(navigationBars());
            mController.cancelExistingAnimations();
            assertEquals(navigationBars(),
                    mController.getRequestedVisibleTypes() & (types | ime()));

            // test single hide
            mController.hide(navigationBars());
            assertEquals(0, mController.getRequestedVisibleTypes() & (types | ime()));

        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowHideMultiple() {
        prepareControls();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // start two animations and see if previous is cancelled and final state is reached.
            mController.hide(navigationBars());
            mController.hide(systemBars());
            int types = navigationBars() | statusBars();
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(navigationBars()));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(statusBars()));
            mController.cancelExistingAnimations();
            assertEquals(0, mController.getRequestedVisibleTypes() & (types | ime()));

            mController.show(navigationBars());
            mController.show(systemBars());
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(navigationBars()));
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(statusBars()));
            mController.cancelExistingAnimations();
            assertEquals(types, mController.getRequestedVisibleTypes() & (types | ime()));

            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(navigationBars());
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(navigationBars()));
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(statusBars()));
            mController.cancelExistingAnimations();
            assertEquals(statusBars(), mController.getRequestedVisibleTypes() & (types | ime()));

            mController.hide(systemBars());
            assertEquals(ANIMATION_TYPE_NONE, mController.getAnimationType(navigationBars()));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(statusBars()));
            mController.cancelExistingAnimations();
            assertEquals(0, mController.getRequestedVisibleTypes() & (types | ime()));
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
            int types = navigationBars() | statusBars();
            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(navigationBars());
            mController.cancelExistingAnimations();
            assertEquals(statusBars(), mController.getRequestedVisibleTypes() & (types | ime()));

            mController.hide(systemBars());
            mController.cancelExistingAnimations();
            assertEquals(0, mController.getRequestedVisibleTypes() & (types | ime()));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testRestoreStartsAnimation() {
        mController.onControlsChanged(createSingletonControl(ID_STATUS_BAR, statusBars()));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.hide(statusBars());
            mController.cancelExistingAnimations();
            assertFalse(isRequestedVisible(mController, statusBars()));
            assertFalse(mController.getState().peekSource(ID_STATUS_BAR).isVisible());

            // Loosing control
            InsetsState state = new InsetsState(mController.getState());
            state.setSourceVisible(ID_STATUS_BAR, true);
            mController.onStateChanged(state);
            mController.onControlsChanged(new InsetsSourceControl[0]);
            assertFalse(isRequestedVisible(mController, statusBars()));
            assertTrue(mController.getState().peekSource(ID_STATUS_BAR).isVisible());

            // Gaining control
            mController.onControlsChanged(createSingletonControl(ID_STATUS_BAR, statusBars()));
            assertEquals(ANIMATION_TYPE_HIDE, mController.getAnimationType(statusBars()));
            mController.cancelExistingAnimations();
            assertFalse(isRequestedVisible(mController, statusBars()));
            assertFalse(mController.getState().peekSource(ID_STATUS_BAR).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testStartImeAnimationAfterGettingControl() {

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            mController.show(ime());
            assertFalse(mController.getState().peekSource(ID_IME).isVisible());

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());

            // Gaining control shortly after
            mController.onControlsChanged(createSingletonControl(ID_IME, ime()));

            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ime()));
            mController.cancelExistingAnimations();
            assertTrue(isRequestedVisible(mController, ime()));
            assertTrue(mController.getState().peekSource(ID_IME).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testStartImeAnimationAfterGettingControl_imeLater() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            mController.show(ime());
            assertFalse(mController.getState().peekSource(ID_IME).isVisible());

            // Gaining control shortly after
            mController.onControlsChanged(createSingletonControl(ID_IME, ime()));

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());

            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ime()));
            mController.cancelExistingAnimations();
            assertTrue(isRequestedVisible(mController, ime()));
            assertTrue(mController.getState().peekSource(ID_IME).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testAnimationEndState_controller() throws Exception {
        mController.onControlsChanged(createSingletonControl(ID_STATUS_BAR, statusBars()));

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
            assertFalse(isRequestedVisible(mController, statusBars()));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testCancellation_afterGainingControl() throws Exception {
        mController.onControlsChanged(createSingletonControl(ID_STATUS_BAR, statusBars()));

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
            assertFalse(isRequestedVisible(mController, statusBars()));
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
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());

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

            mController.onControlsChanged(createSingletonControl(ID_IME, ime()));

            // Pretend IME is calling
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());

            InsetsState copy = new InsetsState(mController.getState(), true /* copySources */);
            copy.peekSource(ID_IME).setFrame(0, 1, 2, 3);
            copy.peekSource(ID_IME).setVisibleFrame(new Rect(4, 5, 6, 7));
            mController.onStateChanged(copy);
            assertNotEquals(new Rect(0, 1, 2, 3),
                    mController.getState().peekSource(ID_IME).getFrame());
            assertNotEquals(new Rect(4, 5, 6, 7),
                    mController.getState().peekSource(ID_IME).getVisibleFrame());
            mController.cancelExistingAnimations();
            assertEquals(new Rect(0, 1, 2, 3),
                    mController.getState().peekSource(ID_IME).getFrame());
            assertEquals(new Rect(4, 5, 6, 7),
                    mController.getState().peekSource(ID_IME).getVisibleFrame());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testResizeAnimation_withFlagAnimateResizing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final int id = ID_NAVIGATION_BAR;
            final @InsetsType int type = navigationBars();
            final InsetsState state1 = new InsetsState();
            state1.getOrCreateSource(id, type)
                    .setVisible(true)
                    .setFrame(0, 0, 500, 50)
                    .setFlags(FLAG_ANIMATE_RESIZING, FLAG_ANIMATE_RESIZING);
            final InsetsState state2 = new InsetsState(state1, true /* copySources */);
            state2.peekSource(id).setFrame(0, 0, 500, 60);

            // New insets source won't cause the resize animation.
            mController.onStateChanged(state1);
            assertEquals("There must not be resize animation.", ANIMATION_TYPE_NONE,
                    mController.getAnimationType(type));

            // Changing frame of the source with FLAG_ANIMATE_RESIZING will cause the resize
            // animation.
            mController.onStateChanged(state2);
            assertEquals("There must be resize animation.", ANIMATION_TYPE_RESIZE,
                    mController.getAnimationType(type));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testResizeAnimation_withoutFlagAnimateResizing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final int id = ID_STATUS_BAR;
            final @InsetsType int type = statusBars();
            final InsetsState state1 = new InsetsState();
            state1.getOrCreateSource(id, type)
                    .setVisible(true)
                    .setFrame(0, 0, 500, 50)
                    .setFlags(0, FLAG_ANIMATE_RESIZING);
            final InsetsState state2 = new InsetsState(state1, true /* copySources */);
            state2.peekSource(id).setFrame(0, 0, 500, 60);
            final String message = "There must not be resize animation.";

            // New insets source won't cause the resize animation.
            mController.onStateChanged(state1);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));

            // Changing frame of the source without FLAG_ANIMATE_RESIZING must not cause the resize
            // animation.
            mController.onStateChanged(state2);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testResizeAnimation_sourceFrame() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final int id = ID_STATUS_BAR;
            final @InsetsType int type = statusBars();
            final InsetsState state1 = new InsetsState();
            state1.setDisplayFrame(new Rect(0, 0, 500, 1000));
            state1.getOrCreateSource(id, type).setFrame(0, 0, 500, 50);
            final InsetsState state2 = new InsetsState(state1, true /* copySources */);
            state2.setDisplayFrame(state1.getDisplayFrame());
            state2.peekSource(id).setFrame(0, 0, 500, 0);
            final String message = "There must not be resize animation.";

            // New insets source won't cause the resize animation.
            mController.onStateChanged(state1);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));

            // Changing frame won't cause the resize animation if the new frame is empty.
            mController.onStateChanged(state2);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));

            // Changing frame won't cause the resize animation if the existing frame is empty.
            mController.onStateChanged(state1);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testResizeAnimation_displayFrame() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final int id = ID_STATUS_BAR;
            final @InsetsType int type = statusBars();
            final InsetsState state1 = new InsetsState();
            state1.setDisplayFrame(new Rect(0, 0, 500, 1000));
            state1.getOrCreateSource(id, type).setFrame(0, 0, 500, 50);
            final InsetsState state2 = new InsetsState(state1, true /* copySources */);
            state2.setDisplayFrame(new Rect(0, 0, 500, 1010));
            state2.peekSource(id).setFrame(0, 0, 500, 60);
            final String message = "There must not be resize animation.";

            // New insets source won't cause the resize animation.
            mController.onStateChanged(state1);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));

            // Changing frame won't cause the resize animation if the display frame is also changed.
            mController.onStateChanged(state2);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testResizeAnimation_visibility() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final int id = ID_STATUS_BAR;
            final @InsetsType int type = statusBars();
            final InsetsState state1 = new InsetsState();
            state1.getOrCreateSource(id, type).setVisible(true).setFrame(0, 0, 500, 50);
            final InsetsState state2 = new InsetsState(state1, true /* copySources */);
            state2.peekSource(id).setVisible(false).setFrame(0, 0, 500, 60);
            final InsetsState state3 = new InsetsState(state2, true /* copySources */);
            state3.peekSource(id).setVisible(true).setFrame(0, 0, 500, 70);
            final String message = "There must not be resize animation.";

            // New insets source won't cause the resize animation.
            mController.onStateChanged(state1);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));

            // Changing source visibility (visible --> invisible) won't cause the resize animation.
            // The previous source and the current one must be both visible.
            mController.onStateChanged(state2);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));

            // Changing source visibility (invisible --> visible) won't cause the resize animation.
            // The previous source and the current one must be both visible.
            mController.onStateChanged(state3);
            assertEquals(message, ANIMATION_TYPE_NONE, mController.getAnimationType(type));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testRequestedState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.hide(statusBars() | navigationBars());
            assertFalse(mTestHost.isRequestedVisible(statusBars()));
            assertFalse(mTestHost.isRequestedVisible(navigationBars()));

            mController.show(statusBars() | navigationBars());
            assertTrue(mTestHost.isRequestedVisible(statusBars()));
            assertTrue(mTestHost.isRequestedVisible(navigationBars()));
        });
    }

    @Test
    public void testInsetsChangedCount_controlSystemBars() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            prepareControls();

            // Calling to hide system bars once should only cause insets change once.
            clearInvocations(mTestHost);
            mController.hide(statusBars() | navigationBars());
            verify(mTestHost, times(1)).notifyInsetsChanged();

            // Sending the same insets state should not cause insets change.
            // This simulates the callback from server after hiding system bars.
            clearInvocations(mTestHost);
            mController.onStateChanged(mController.getState());
            verify(mTestHost, never()).notifyInsetsChanged();

            // Calling to show system bars once should only cause insets change once.
            clearInvocations(mTestHost);
            mController.show(statusBars() | navigationBars());
            verify(mTestHost, times(1)).notifyInsetsChanged();

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
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
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
            newState.peekSource(ID_STATUS_BAR).getFrame().bottom++;
            mController.onStateChanged(newState);
            verify(mTestHost, times(1)).notifyInsetsChanged();

            // Changing status bar visibility should cause notifyInsetsChanged.
            clearInvocations(mTestHost);
            newState = new InsetsState(localState, true /* copySources */);
            newState.peekSource(ID_STATUS_BAR).setVisible(false);
            mController.onStateChanged(newState);
            verify(mTestHost, times(1)).notifyInsetsChanged();

            // Changing invisible IME frame should not cause notifyInsetsChanged.
            clearInvocations(mTestHost);
            newState = new InsetsState(localState, true /* copySources */);
            newState.peekSource(ID_IME).getFrame().top--;
            mController.onStateChanged(newState);
            verify(mTestHost, never()).notifyInsetsChanged();

            // Changing IME visibility should cause notifyInsetsChanged.
            clearInvocations(mTestHost);
            newState = new InsetsState(localState, true /* copySources */);
            newState.peekSource(ID_IME).setVisible(true);
            mController.onStateChanged(newState);
            verify(mTestHost, times(1)).notifyInsetsChanged();
        });
    }

    @Test
    public void testImeRequestedVisibleWhenImeNotControllable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Simulate IME insets is not controllable
            mController.onControlsChanged(new InsetsSourceControl[0]);
            final InsetsSourceConsumer imeInsetsConsumer =
                    mController.getSourceConsumer(ID_IME, ime());
            assertNull(imeInsetsConsumer.getControl());

            // Verify IME requested visibility should be updated to IME consumer from controller.
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
            assertTrue(isRequestedVisible(mController, ime()));

            mController.hide(ime());
            assertFalse(isRequestedVisible(mController, ime()));
        });
    }

    @Test
    public void testImeRequestedVisibleDuringPredictiveBackAnim() {
        prepareControls();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // show ime as initial state
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
            mController.cancelExistingAnimations(); // fast forward show animation
            assertTrue(mController.getState().peekSource(ID_IME).isVisible());

            // start control request (for predictive back animation)
            WindowInsetsAnimationControlListener listener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(ime(), /*cancellationSignal*/ null,
                    listener, /*fromIme*/ false, /*duration*/ -1, /*interpolator*/ null,
                    ANIMATION_TYPE_USER, /*fromPredictiveBack*/ true);

            // Verify that onReady is called (after next predraw)
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();
            verify(listener).onReady(notNull(), eq(ime()));

            // verify that insets are requested visible during animation
            assertTrue(isRequestedVisible(mController, ime()));
        });
    }

    @Test
    public void testImeShowRequestCancelsPredictiveBackPostCommitAnim() {
        prepareControls();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // show ime as initial state
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
            mController.cancelExistingAnimations(); // fast forward show animation
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(mController.getState().peekSource(ID_IME).isVisible());

            // start control request (for predictive back animation)
            WindowInsetsAnimationControlListener listener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(ime(), /*cancellationSignal*/ null,
                    listener, /*fromIme*/ false, /*duration*/ -1, /*interpolator*/ null,
                    ANIMATION_TYPE_USER, /*fromPredictiveBack*/ true);

            // verify that controller
            // has ANIMATION_TYPE_USER set for ime()
            assertEquals(ANIMATION_TYPE_USER, mController.getAnimationType(ime()));

            // verify show request is ignored during pre commit phase of predictive back anim
            mController.show(ime(), true /* fromIme */, null /* statsToken */);
            assertEquals(ANIMATION_TYPE_USER, mController.getAnimationType(ime()));

            // verify show request is applied during post commit phase of predictive back anim
            mController.setPredictiveBackImeHideAnimInProgress(true);
            mController.show(ime(), true /* fromIme */, null /* statsToken */);
            assertEquals(ANIMATION_TYPE_SHOW, mController.getAnimationType(ime()));

            // additionally verify that IME ends up visible
            mController.cancelExistingAnimations();
            assertTrue(mController.getState().peekSource(ID_IME).isVisible());
        });
    }

    @Test
    public void testImeHideRequestIgnoredDuringPredictiveBackPostCommitAnim() {
        prepareControls();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // show ime as initial state
            mController.show(ime(), true /* fromIme */, ImeTracker.Token.empty());
            mController.cancelExistingAnimations(); // fast forward show animation
            mViewRoot.getView().getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(mController.getState().peekSource(ID_IME).isVisible());

            // start control request (for predictive back animation)
            WindowInsetsAnimationControlListener listener =
                    mock(WindowInsetsAnimationControlListener.class);
            mController.controlWindowInsetsAnimation(ime(), /*cancellationSignal*/ null,
                    listener, /*fromIme*/ false, /*duration*/ -1, /*interpolator*/ null,
                    ANIMATION_TYPE_USER, /*fromPredictiveBack*/ true);

            // verify that controller has ANIMATION_TYPE_USER set for ime()
            assertEquals(ANIMATION_TYPE_USER, mController.getAnimationType(ime()));

            // verify hide request is ignored during post commit phase of predictive back anim
            // since IME is already animating away
            mController.setPredictiveBackImeHideAnimInProgress(true);
            mController.hide(ime(), true /* fromIme */, null /* statsToken */);
            assertEquals(ANIMATION_TYPE_USER, mController.getAnimationType(ime()));
        });
    }

    private void waitUntilNextFrame() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        Choreographer.getMainThreadInstance().postCallback(Choreographer.CALLBACK_COMMIT,
                latch::countDown, null /* token */);
        latch.await();
    }

    private InsetsSourceControl createControl(int id, @InsetsType int type) {

        // Simulate binder behavior by copying SurfaceControl. Otherwise, InsetsController will
        // attempt to release mLeash directly.
        SurfaceControl copy = new SurfaceControl(mLeash, "InsetsControllerTest.createControl");
        return new InsetsSourceControl(id, type, copy,
                (type & WindowInsets.Type.defaultVisible()) != 0, new Point(), Insets.NONE);
    }

    private InsetsSourceControl[] createSingletonControl(int id, @InsetsType int type) {
        return new InsetsSourceControl[] { createControl(id, type) };
    }

    private InsetsSourceControl[] prepareControls() {
        final InsetsSourceControl navBar = createControl(ID_NAVIGATION_BAR, navigationBars());
        final InsetsSourceControl statusBar = createControl(ID_STATUS_BAR, statusBars());
        final InsetsSourceControl ime = createControl(ID_IME, ime());

        InsetsSourceControl[] controls = new InsetsSourceControl[3];
        controls[0] = navBar;
        controls[1] = statusBar;
        controls[2] = ime;
        mController.onControlsChanged(controls);
        return controls;
    }

    private static boolean isRequestedVisible(InsetsController controller, @InsetsType int type) {
        return (controller.getRequestedVisibleTypes() & type) != 0;
    }

    public static class TestHost extends ViewRootInsetsControllerHost {

        private @InsetsType int mRequestedVisibleTypes = defaultVisible();

        TestHost(ViewRootImpl viewRoot) {
            super(viewRoot);
        }

        @Override
        public void updateRequestedVisibleTypes(@InsetsType int requestedVisibleTypes) {
            mRequestedVisibleTypes = requestedVisibleTypes;
            super.updateRequestedVisibleTypes(requestedVisibleTypes);
        }

        public boolean isRequestedVisible(@InsetsType int types) {
            return (mRequestedVisibleTypes & types) != 0;
        }
    }
}
