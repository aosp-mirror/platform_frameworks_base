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

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
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
    private Context mContext;
    private volatile boolean mKeyReceived = false;

    @Before
    public void setUp() throws Exception {
        mContext = getInstrumentation().getTargetContext();

        getInstrumentation().runOnMainSync(() ->
                mViewRootImpl = new ViewRootImpl(mContext, mContext.getDisplayNoVerify()));
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
        final View view = new View(mContext);
        view.setScrollCaptureCallback(new TestScrollCaptureCallback()); // Does nothing
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
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

    /**
     * When window doesn't have focus, keys should be dropped.
     */
    @Test
    public void whenWindowDoesNotHaveFocus_keysAreDropped() {
        checkKeyEvent(() -> {
            mViewRootImpl.windowFocusChanged(false /*hasFocus*/, true /*inTouchMode*/);
        }, false /*shouldReceiveKey*/);
    }

    /**
     * When window has focus, keys should be received
     */
    @Test
    public void whenWindowHasFocus_keysAreReceived() {
        checkKeyEvent(() -> {
            mViewRootImpl.windowFocusChanged(true /*hasFocus*/, true /*inTouchMode*/);
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
        final KeyView view = new KeyView(mContext);

        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WindowManager wm = mContext.getSystemService(WindowManager.class);
            wm.addView(view, wmlp);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        mViewRootImpl = view.getViewRootImpl();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(setup);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        // Inject a key event, and wait for it to be processed
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
            mViewRootImpl.dispatchInputEvent(event);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(mKeyReceived, shouldReceiveKey);
    }
}
