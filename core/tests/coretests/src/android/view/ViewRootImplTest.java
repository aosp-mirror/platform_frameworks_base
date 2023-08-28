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

import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_DEFAULT;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link ViewRootImpl}
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ViewRootImplTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ViewRootImplTest {

    private ViewRootImpl mViewRootImpl;
    private volatile boolean mKeyReceived = false;

    private static Context sContext;
    private static Instrumentation sInstrumentation = InstrumentationRegistry.getInstrumentation();

    // The touch mode state before the test was started, needed to return the system to the original
    // state after the test completes.
    private static boolean sOriginalTouchMode;

    @BeforeClass
    public static void setUpClass() {
        sContext = sInstrumentation.getTargetContext();
        View view = new View(sContext);
        sOriginalTouchMode = view.isInTouchMode();
    }

    @AfterClass
    public static void tearDownClass() {
        sInstrumentation.setInTouchMode(sOriginalTouchMode);
    }

    @Before
    public void setUp() throws Exception {
        sInstrumentation.setInTouchMode(true);
        sInstrumentation.runOnMainSync(() ->
                mViewRootImpl = new ViewRootImpl(sContext, sContext.getDisplayNoVerify()));
    }

    @Test
    public void adjustLayoutParamsForCompatibility_layoutFullscreen() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Type.statusBars() must be removed.
        assertEquals(0, attrs.getFitInsetsTypes() & Type.statusBars());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_layoutInScreen() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.flags = FLAG_LAYOUT_IN_SCREEN;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Type.statusBars() must be removed.
        assertEquals(0, attrs.getFitInsetsTypes() & Type.statusBars());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_layoutHideNavigation() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Type.systemBars() must be removed.
        assertEquals(0, attrs.getFitInsetsTypes() & Type.systemBars());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_toast() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_TOAST);
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        assertTrue(attrs.isFitInsetsIgnoringVisibility());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_systemAlert() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_SYSTEM_ALERT);
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        assertTrue(attrs.isFitInsetsIgnoringVisibility());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_fitSystemBars() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        assertEquals(Type.systemBars(), attrs.getFitInsetsTypes());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_fitSystemBarsAndIme() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.softInputMode |= SOFT_INPUT_ADJUST_RESIZE;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        assertEquals(Type.systemBars() | Type.ime(), attrs.getFitInsetsTypes());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_noAdjustLayout() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        final int types = Type.all();
        final int sides = Side.TOP | Side.LEFT;
        final boolean fitMaxInsets = true;
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        attrs.setFitInsetsTypes(types);
        attrs.setFitInsetsSides(sides);
        attrs.setFitInsetsIgnoringVisibility(fitMaxInsets);
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Fit-insets related fields must not be adjusted due to legacy system UI visibility
        // after calling fit-insets related methods.
        assertEquals(types, attrs.getFitInsetsTypes());
        assertEquals(sides, attrs.getFitInsetsSides());
        assertEquals(fitMaxInsets, attrs.isFitInsetsIgnoringVisibility());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_noAdjustAppearance() {
        final WindowInsetsController controller = mViewRootImpl.getInsetsController();
        final WindowManager.LayoutParams attrs = mViewRootImpl.mWindowAttributes;
        final int appearance = APPEARANCE_OPAQUE_STATUS_BARS;
        controller.setSystemBarsAppearance(appearance, 0xffffffff);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_LOW_PROFILE
                | SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Appearance must not be adjusted due to legacy system UI visibility after calling
        // setSystemBarsAppearance.
        assertEquals(appearance, controller.getSystemBarsAppearance());

        mViewRootImpl.setLayoutParams(new WindowManager.LayoutParams(), false);

        // Appearance must not be adjusted due to setting new LayoutParams.
        assertEquals(appearance, controller.getSystemBarsAppearance());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_noAdjustBehavior() {
        final WindowInsetsController controller = mViewRootImpl.getInsetsController();
        final WindowManager.LayoutParams attrs = mViewRootImpl.mWindowAttributes;
        final int behavior = BEHAVIOR_DEFAULT;
        controller.setSystemBarsBehavior(behavior);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Behavior must not be adjusted due to legacy system UI visibility after calling
        // setSystemBarsBehavior.
        assertEquals(behavior, controller.getSystemBarsBehavior());

        mViewRootImpl.setLayoutParams(new WindowManager.LayoutParams(), false);

        // Behavior must not be adjusted due to setting new LayoutParams.
        assertEquals(behavior, controller.getSystemBarsBehavior());
    }

    /**
     * Ensure scroll capture request handles a ViewRootImpl with no view tree.
     */
    @Test
    public void requestScrollCapture_withoutContentRoot() {
        final CountDownLatch latch = new CountDownLatch(1);
        mViewRootImpl.handleScrollCaptureRequest(new IScrollCaptureResponseListener.Default() {
            @Override
            public void onScrollCaptureResponse(ScrollCaptureResponse response) {
                latch.countDown();
            }
        });
        try {
            if (latch.await(100, TimeUnit.MILLISECONDS)) {
                return; // pass
            }
        } catch (InterruptedException e) { /* ignore */ }
        fail("requestScrollCapture did not respond");
    }

    /**
     * Ensure scroll capture request handles a ViewRootImpl with no view tree.
     */
    @Test
    public void requestScrollCapture_timeout() {
        final View view = new View(sContext);
        view.setScrollCaptureCallback(new TestScrollCaptureCallback()); // Does nothing
        sInstrumentation.runOnMainSync(() -> {
            WindowManager.LayoutParams wmlp =
                    new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
            // Set a fake token to bypass 'is your activity running' check
            wmlp.token = new Binder();
            view.setLayoutParams(wmlp);
            mViewRootImpl.setView(view, wmlp, null);
        });

        final CountDownLatch latch = new CountDownLatch(1);
        mViewRootImpl.setScrollCaptureRequestTimeout(100);
        mViewRootImpl.handleScrollCaptureRequest(new IScrollCaptureResponseListener.Default() {
            @Override
            public void onScrollCaptureResponse(ScrollCaptureResponse response) {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(2500, TimeUnit.MILLISECONDS)) {
                fail("requestScrollCapture timeout did not occur");
            }
        } catch (InterruptedException e) { /* ignore */ }
    }

    @Test
    public void whenTouchModeChanges_viewRootIsNotified() throws Exception {
        View view = new View(sContext);
        attachViewToWindow(view);
        ViewTreeObserver viewTreeObserver = view.getRootView().getViewTreeObserver();
        CountDownLatch latch = new CountDownLatch(1);
        ViewTreeObserver.OnTouchModeChangeListener touchModeListener = (boolean inTouchMode) -> {
            assertWithMessage("addOnTouchModeChangeListener parameter").that(
                    inTouchMode).isFalse();
            latch.countDown();
        };
        viewTreeObserver.addOnTouchModeChangeListener(touchModeListener);

        try {
            view.requestFocusFromTouch();

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(view.isInTouchMode()).isFalse();
        } finally {
            viewTreeObserver.removeOnTouchModeChangeListener(touchModeListener);
        }
    }

    @Test
    public void whenDispatchFakeFocus_focusDoesNotPersist() throws Exception {
        View view = new View(sContext);
        attachViewToWindow(view);
        view.clearFocus();

        assertThat(view.hasWindowFocus()).isFalse();

        mViewRootImpl = view.getViewRootImpl();

        mViewRootImpl.dispatchCompatFakeFocus();
        assertThat(view.hasWindowFocus()).isFalse();
    }

    /**
     * When window doesn't have focus, keys should be dropped.
     */
    @Test
    public void whenWindowDoesNotHaveFocus_keysAreDropped() {
        checkKeyEvent(() -> {
            mViewRootImpl.windowFocusChanged(false /*hasFocus*/);
        }, false /*shouldReceiveKey*/);
    }

    /**
     * When window has focus, keys should be received
     */
    @Test
    public void whenWindowHasFocus_keysAreReceived() {
        checkKeyEvent(() -> {
            mViewRootImpl.windowFocusChanged(true /*hasFocus*/);
        }, true /*shouldReceiveKey*/);
    }

    /**
     * When window is in ambient mode, keys should be dropped
     */
    @Test
    public void whenWindowIsInAmbientMode_keysAreDropped() {
        checkKeyEvent(() -> {
            mViewRootImpl.setIsAmbientMode(true /*ambient*/);
        }, false /*shouldReceiveKey*/);
    }

    /**
     * When window is paused for transition, keys should be dropped
     */
    @Test
    public void whenWindowIsPausedForTransition_keysAreDropped() {
        checkKeyEvent(() -> {
            mViewRootImpl.setPausedForTransition(true /*paused*/);
        }, false /*shouldReceiveKey*/);
    }

    @UiThreadTest
    @Test
    public void playSoundEffect_wrongEffectId_throwException() {
        ViewRootImpl viewRootImpl = new ViewRootImpl(sContext,
                sContext.getDisplayNoVerify());
        View view = new View(sContext);
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                TYPE_APPLICATION_OVERLAY);
        layoutParams.token = new Binder();
        view.setLayoutParams(layoutParams);
        viewRootImpl.setView(view, layoutParams, /* panelParentView= */ null);

        assertThrows(IllegalArgumentException.class,
                () -> viewRootImpl.playSoundEffect(/* effectId= */ -1));
    }

    @UiThreadTest
    @Test
    public void playSoundEffect_wrongEffectId_touchFeedbackDisabled_doNothing() {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.flags = Display.FLAG_TOUCH_FEEDBACK_DISABLED;
        Display display = new Display(DisplayManagerGlobal.getInstance(), /* displayId= */
                0, displayInfo, new DisplayAdjustments());
        ViewRootImpl viewRootImpl = new ViewRootImpl(sContext, display);
        View view = new View(sContext);
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                TYPE_APPLICATION_OVERLAY);
        layoutParams.token = new Binder();
        view.setLayoutParams(layoutParams);
        viewRootImpl.setView(view, layoutParams, /* panelParentView= */ null);

        viewRootImpl.playSoundEffect(/* effectId= */ -1);
    }

    @UiThreadTest
    @Test
    public void performHapticFeedback_touchFeedbackDisabled_doNothing() {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.flags = Display.FLAG_TOUCH_FEEDBACK_DISABLED;
        Display display = new Display(DisplayManagerGlobal.getInstance(), /* displayId= */
                0, displayInfo, new DisplayAdjustments());
        ViewRootImpl viewRootImpl = new ViewRootImpl(sContext, display);

        boolean result = viewRootImpl.performHapticFeedback(
                HapticFeedbackConstants.CONTEXT_CLICK, true);

        assertThat(result).isFalse();
    }

    class KeyView extends View {
        KeyView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEventPreIme(KeyEvent event) {
            mKeyReceived = true;
            return true /*handled*/;
        }
    }

    /**
     * Create a new view, and add it to window manager.
     * Run the precondition 'setup'.
     * Next, inject an event into this view, and check whether it is received.
     */
    private void checkKeyEvent(Runnable setup, boolean shouldReceiveKey) {
        final KeyView view = new KeyView(sContext);

        attachViewToWindow(view);

        mViewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(setup);
        sInstrumentation.waitForIdleSync();

        // Inject a key event, and wait for it to be processed
        sInstrumentation.runOnMainSync(() -> {
            KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
            mViewRootImpl.dispatchInputEvent(event);
        });
        sInstrumentation.waitForIdleSync();
        assertEquals(mKeyReceived, shouldReceiveKey);
    }

    private void attachViewToWindow(View view) {
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();
    }
}
