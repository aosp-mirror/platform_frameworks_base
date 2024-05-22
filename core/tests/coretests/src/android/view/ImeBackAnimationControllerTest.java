/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.view.WindowInsets.Type.ime;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.window.BackEvent.EDGE_LEFT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import android.content.Context;
import android.graphics.Insets;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;
import android.view.animation.BackGestureInterpolator;
import android.view.animation.Interpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.window.BackEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

/**
 * Tests for {@link ImeBackAnimationController}.
 *
 * <p>Build/Install/Run:
 * atest FrameworksCoreTests:ImeBackAnimationControllerTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ImeBackAnimationControllerTest {

    private static final float PEEK_FRACTION = 0.1f;
    private static final Interpolator BACK_GESTURE = new BackGestureInterpolator();
    private static final int IME_HEIGHT = 200;
    private static final Insets IME_INSETS = Insets.of(0, 0, 0, IME_HEIGHT);

    @Mock
    private InsetsController mInsetsController;
    @Mock
    private WindowInsetsAnimationController mWindowInsetsAnimationController;
    @Mock
    private ViewRootInsetsControllerHost mViewRootInsetsControllerHost;

    private ViewRootImpl mViewRoot;
    private ImeBackAnimationController mBackAnimationController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InputMethodManager inputMethodManager = context.getSystemService(
                    InputMethodManager.class);
            // cannot mock ViewRootImpl since it's final.
            mViewRoot = new ViewRootImpl(context, context.getDisplayNoVerify());
            try {
                mViewRoot.setView(new TextView(context), new WindowManager.LayoutParams(), null);
            } catch (WindowManager.BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mBackAnimationController = new ImeBackAnimationController(mViewRoot, mInsetsController);

            when(mWindowInsetsAnimationController.getHiddenStateInsets()).thenReturn(Insets.NONE);
            when(mWindowInsetsAnimationController.getShownStateInsets()).thenReturn(IME_INSETS);
            when(mWindowInsetsAnimationController.getCurrentInsets()).thenReturn(IME_INSETS);
            when(mInsetsController.getHost()).thenReturn(mViewRootInsetsControllerHost);
            when(mViewRootInsetsControllerHost.getInputMethodManager()).thenReturn(
                    inputMethodManager);
            try {
                Field field = InsetsController.class.getDeclaredField("mSourceConsumers");
                field.setAccessible(true);
                field.set(mInsetsController, new SparseArray<InsetsSourceConsumer>());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Unable to set mSourceConsumers", e);
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testAdjustResizeWithAppWindowInsetsListenerPlaysAnim() {
        // setup ViewRoot with InsetsAnimationCallback and softInputMode=adjustResize
        mViewRoot.getView()
                .setWindowInsetsAnimationCallback(mock(WindowInsetsAnimation.Callback.class));
        mViewRoot.mWindowAttributes.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
        // start back gesture
        mBackAnimationController.onBackStarted(new BackEvent(0f, 0f, 0f, EDGE_LEFT));
        // verify that ImeBackAnimationController takes control over IME insets
        verify(mInsetsController, times(1)).controlWindowInsetsAnimation(anyInt(), any(), any(),
                anyBoolean(), anyLong(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testAdjustResizeWithoutAppWindowInsetsListenerNotPlayingAnim() {
        // setup ViewRoot with softInputMode=adjustResize
        mViewRoot.mWindowAttributes.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
        // start back gesture
        mBackAnimationController.onBackStarted(new BackEvent(0f, 0f, 0f, EDGE_LEFT));
        // progress back gesture
        mBackAnimationController.onBackProgressed(new BackEvent(100f, 0f, 0.5f, EDGE_LEFT));
        // commit back gesture
        mBackAnimationController.onBackInvoked();
        // verify that InsetsController#hide is called
        verify(mInsetsController, times(1)).hide(ime());
        // verify that ImeBackAnimationController does not take control over IME insets
        verify(mInsetsController, never()).controlWindowInsetsAnimation(anyInt(), any(), any(),
                anyBoolean(), anyLong(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testAdjustPanScrollsViewRoot() {
        // simulate view root being panned upwards by 50px
        int appPan = -50;
        mViewRoot.setScrollY(appPan);
        // setup ViewRoot with softInputMode=adjustPan
        mViewRoot.mWindowAttributes.softInputMode = SOFT_INPUT_ADJUST_PAN;

        // start back gesture
        WindowInsetsAnimationControlListener animationControlListener = startBackGesture();
        // simulate ImeBackAnimationController receiving control
        animationControlListener.onReady(mWindowInsetsAnimationController, ime());

        // progress back gesture
        float progress = 0.5f;
        mBackAnimationController.onBackProgressed(new BackEvent(100f, 0f, progress, EDGE_LEFT));

        // verify that view root is scrolled by expected amount
        float interpolatedProgress = BACK_GESTURE.getInterpolation(progress);
        int expectedViewRootScroll =
                (int) (appPan * (1 - interpolatedProgress * PEEK_FRACTION));
        assertEquals(mViewRoot.getScrollY(), expectedViewRootScroll);
    }

    @Test
    public void testNewGestureAfterCancelSeamlessTakeover() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // start back gesture
            WindowInsetsAnimationControlListener animationControlListener = startBackGesture();
            // simulate ImeBackAnimationController receiving control
            animationControlListener.onReady(mWindowInsetsAnimationController, ime());
            // verify initial animation insets are set
            verify(mWindowInsetsAnimationController, times(1)).setInsetsAndAlpha(
                    eq(Insets.of(0, 0, 0, IME_HEIGHT)), eq(1f), anyFloat());

            // progress back gesture
            mBackAnimationController.onBackProgressed(new BackEvent(100f, 0f, 0.5f, EDGE_LEFT));

            // cancel back gesture
            mBackAnimationController.onBackCancelled();
            // verify that InsetsController does not notified of a hide-anim (because the gesture
            // was cancelled)
            verify(mInsetsController, never()).setPredictiveBackImeHideAnimInProgress(eq(true));

            Mockito.clearInvocations(mWindowInsetsAnimationController);
            // restart back gesture
            mBackAnimationController.onBackStarted(new BackEvent(0f, 0f, 0f, EDGE_LEFT));
            // verify that animation controller is reused and initial insets are set immediately
            verify(mWindowInsetsAnimationController, times(1)).setInsetsAndAlpha(
                    eq(Insets.of(0, 0, 0, IME_HEIGHT)), eq(1f), anyFloat());
        });
    }

    @Test
    public void testImeInsetsManipulationCurve() {
        // start back gesture
        WindowInsetsAnimationControlListener animationControlListener = startBackGesture();
        // simulate ImeBackAnimationController receiving control
        animationControlListener.onReady(mWindowInsetsAnimationController, ime());
        // verify initial animation insets are set
        verify(mWindowInsetsAnimationController, times(1)).setInsetsAndAlpha(
                eq(Insets.of(0, 0, 0, IME_HEIGHT)), eq(1f), anyFloat());

        Mockito.clearInvocations(mWindowInsetsAnimationController);
        // progress back gesture
        float progress = 0.5f;
        mBackAnimationController.onBackProgressed(new BackEvent(100f, 0f, progress, EDGE_LEFT));
        // verify correct ime insets manipulation
        float interpolatedProgress = BACK_GESTURE.getInterpolation(progress);
        int expectedInset =
                (int) (IME_HEIGHT - interpolatedProgress * PEEK_FRACTION * IME_HEIGHT);
        verify(mWindowInsetsAnimationController, times(1)).setInsetsAndAlpha(
                eq(Insets.of(0, 0, 0, expectedInset)), eq(1f), anyFloat());
    }

    @Test
    public void testOnReadyAfterGestureFinished() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // start back gesture
            WindowInsetsAnimationControlListener animationControlListener = startBackGesture();

            // progress back gesture
            mBackAnimationController.onBackProgressed(new BackEvent(100f, 0f, 0.5f, EDGE_LEFT));

            // commit back gesture
            mBackAnimationController.onBackInvoked();

            // verify setInsetsAndAlpha never called due onReady delayed
            verify(mWindowInsetsAnimationController, never()).setInsetsAndAlpha(any(), anyInt(),
                    anyFloat());
            verify(mInsetsController, never()).setPredictiveBackImeHideAnimInProgress(eq(true));

            // simulate ImeBackAnimationController receiving control
            animationControlListener.onReady(mWindowInsetsAnimationController, ime());

            // verify setInsetsAndAlpha immediately called
            verify(mWindowInsetsAnimationController, times(1)).setInsetsAndAlpha(
                    eq(Insets.of(0, 0, 0, IME_HEIGHT)), eq(1f), anyFloat());
            // verify post-commit hide anim has started
            verify(mInsetsController, times(1)).setPredictiveBackImeHideAnimInProgress(eq(true));
        });
    }

    @Test
    public void testOnBackInvokedHidesImeEvenIfInsetsControlCancelled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // start back gesture
            WindowInsetsAnimationControlListener animationControlListener = startBackGesture();

            // simulate ImeBackAnimationController not receiving control (e.g. due to split screen)
            animationControlListener.onCancelled(mWindowInsetsAnimationController);

            // commit back gesture
            mBackAnimationController.onBackInvoked();

            // verify that InsetsController#hide is called
            verify(mInsetsController, times(1)).hide(ime());
        });
    }

    private WindowInsetsAnimationControlListener startBackGesture() {
        // start back gesture
        mBackAnimationController.onBackStarted(new BackEvent(0f, 0f, 0f, EDGE_LEFT));

        // verify controlWindowInsetsAnimation is called and capture animationControlListener
        ArgumentCaptor<WindowInsetsAnimationControlListener> animationControlListener =
                ArgumentCaptor.forClass(WindowInsetsAnimationControlListener.class);
        verify(mInsetsController, times(1)).controlWindowInsetsAnimation(anyInt(), any(),
                animationControlListener.capture(), anyBoolean(), anyLong(), any(), anyInt(),
                anyBoolean());

        return animationControlListener.getValue();
    }
}
