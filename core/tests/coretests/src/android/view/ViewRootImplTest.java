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

import static android.view.accessibility.Flags.FLAG_FORCE_INVERT_COLOR;
import static android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY;
import static android.view.flags.Flags.FLAG_TOOLKIT_FRAME_RATE_BY_SIZE_READ_ONLY;
import static android.view.Surface.FRAME_RATE_CATEGORY_HIGH;
import static android.view.Surface.FRAME_RATE_CATEGORY_HIGH_HINT;
import static android.view.Surface.FRAME_RATE_CATEGORY_LOW;
import static android.view.Surface.FRAME_RATE_CATEGORY_NORMAL;
import static android.view.Surface.FRAME_RATE_CATEGORY_NO_PREFERENCE;
import static android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE;
import static android.view.Surface.FRAME_RATE_COMPATIBILITY_GTE;
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
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.app.UiModeManager;
import android.content.Context;
import android.graphics.ForceDarkType;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Binder;
import android.os.SystemProperties;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.window.flags.Flags;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
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
    private static final String TAG = "ViewRootImplTest";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private ViewRootImpl mViewRootImpl;
    private volatile boolean mKeyReceived = false;

    private static Context sContext;
    private static Instrumentation sInstrumentation = InstrumentationRegistry.getInstrumentation();

    // The touch mode state before the test was started, needed to return the system to the original
    // state after the test completes.
    private static boolean sOriginalTouchMode;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

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

    @After
    public void teardown() {
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            Settings.Secure.resetToDefaults(sContext.getContentResolver(), TAG);

            var uiModeManager = sContext.getSystemService(UiModeManager.class);
            uiModeManager.setNightMode(UiModeManager.MODE_NIGHT_NO);

            setForceDarkSysProp(false);
        });
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

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
    public void whenViewIsAttachedToWindow_getHostToken() {
        View view = new View(sContext);
        attachViewToWindow(view);

        mViewRootImpl = view.getViewRootImpl();

        assertThat(mViewRootImpl.getInputTransferToken()).isNotEqualTo(null);
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
                HapticFeedbackConstants.CONTEXT_CLICK, true, false /* fromIme */);

        assertThat(result).isFalse();
    }

    /**
     * Test the default values are properly set
     */
    @UiThreadTest
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_getDefaultValues() {
        ViewRootImpl viewRootImpl = new ViewRootImpl(sContext,
                sContext.getDisplayNoVerify());
        assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                FRAME_RATE_CATEGORY_NO_PREFERENCE);
        assertEquals(viewRootImpl.getPreferredFrameRate(), 0, 0.1);
    }

    /**
     * Test the value of the frame rate cateogry based on the visibility of a view
     * Invsible: FRAME_RATE_CATEGORY_NO_PREFERENCE
     * Visible: FRAME_RATE_CATEGORY_NORMAL
     * Also, mIsFrameRateBoosting should be true when the visibility becomes visible
     */
    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_BY_SIZE_READ_ONLY})
    public void votePreferredFrameRate_voteFrameRateCategory_visibility_bySize() {
        View view = new View(sContext);
        attachViewToWindow(view);
        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            view.setVisibility(View.INVISIBLE);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);
        });
        sInstrumentation.waitForIdleSync();

        sInstrumentation.runOnMainSync(() -> {
            view.setVisibility(View.VISIBLE);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NORMAL);
        });
        sInstrumentation.waitForIdleSync();

        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getIsFrameRateBoosting(), true);
        });
    }

    /**
     * Test the value of the frame rate cateogry based on the size of a view.
     * The current threshold value is 7% of the screen size
     * <7%: FRAME_RATE_CATEGORY_LOW
     */
    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_BY_SIZE_READ_ONLY})
    public void votePreferredFrameRate_voteFrameRateCategory_smallSize_bySize() {
        View view = new View(sContext);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check
        wmlp.width = 1;
        wmlp.height = 1;

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_LOW);
        });
    }

    /**
     * Test the value of the frame rate cateogry based on the size of a view.
     * The current threshold value is 7% of the screen size
     * >=7% : FRAME_RATE_CATEGORY_NORMAL
     */
    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_BY_SIZE_READ_ONLY})
    public void votePreferredFrameRate_voteFrameRateCategory_normalSize_bySize() {
        View view = new View(sContext);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            wmlp.width = (int) (metrics.widthPixels * 0.9);
            wmlp.height = (int) (metrics.heightPixels * 0.9);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_NORMAL);
        });
    }

    /**
     * Test the value of the frame rate cateogry based on the visibility of a view
     * Invsible: FRAME_RATE_CATEGORY_NO_PREFERENCE
     * Visible: FRAME_RATE_CATEGORY_HIGH
     * Also, mIsFrameRateBoosting should be true when the visibility becomes visible
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRateCategory_visibility_defaultHigh() {
        View view = new View(sContext);
        attachViewToWindow(view);
        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            view.setVisibility(View.INVISIBLE);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);
        });
        sInstrumentation.waitForIdleSync();

        sInstrumentation.runOnMainSync(() -> {
            view.setVisibility(View.VISIBLE);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_HIGH);
        });
        sInstrumentation.waitForIdleSync();

        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getIsFrameRateBoosting(), true);
        });
    }

    /**
     * Test the value of the frame rate cateogry based on the size of a view.
     * The current threshold value is 7% of the screen size
     * <7%: FRAME_RATE_CATEGORY_NORMAL
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRateCategory_smallSize_defaultHigh() {
        View view = new View(sContext);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check
        wmlp.width = 1;
        wmlp.height = 1;

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_NORMAL);
        });
    }

    /**
     * Test the value of the frame rate cateogry based on the size of a view.
     * The current threshold value is 7% of the screen size
     * >=7% : FRAME_RATE_CATEGORY_HIGH
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRateCategory_normalSize_defaultHigh() {
        View view = new View(sContext);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            wmlp.width = (int) (metrics.widthPixels * 0.9);
            wmlp.height = (int) (metrics.heightPixels * 0.9);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_HIGH);
        });
    }

    /**
     * Test how values of the frame rate cateogry are aggregated.
     * It should take the max value among all of the voted categories per frame.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRateCategory_aggregate() {
        View view = new View(sContext);
        attachViewToWindow(view);
        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);
        });

        // reset the frame rate category counts
        for (int i = 0; i < 5; i++) {
            sInstrumentation.runOnMainSync(() -> {
                view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
                view.invalidate();
            });
            sInstrumentation.waitForIdleSync();
        }

        sInstrumentation.runOnMainSync(() -> {
            viewRootImpl.votePreferredFrameRateCategory(FRAME_RATE_CATEGORY_LOW);
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_LOW);
            viewRootImpl.votePreferredFrameRateCategory(FRAME_RATE_CATEGORY_NORMAL);
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_NORMAL);
            viewRootImpl.votePreferredFrameRateCategory(FRAME_RATE_CATEGORY_HIGH_HINT);
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_HIGH_HINT);
            viewRootImpl.votePreferredFrameRateCategory(FRAME_RATE_CATEGORY_HIGH);
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_HIGH);
            viewRootImpl.votePreferredFrameRateCategory(FRAME_RATE_CATEGORY_HIGH_HINT);
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_HIGH);
            viewRootImpl.votePreferredFrameRateCategory(FRAME_RATE_CATEGORY_NORMAL);
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_HIGH);
            viewRootImpl.votePreferredFrameRateCategory(FRAME_RATE_CATEGORY_LOW);
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_HIGH);
        });
    }

    /**
     * Test the accurate aggregation of frame rate values as follows:
     * 1. When values exceed 60Hz, select the maximum value.
     * 2. If frame rates are less than 60Hz and multiple frame rates are voted,
     * prioritize 60Hz..
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRate_aggregate() {
        View view = new View(sContext);
        attachViewToWindow(view);
        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getPreferredFrameRate(), 0, 0.1);
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
            viewRootImpl.votePreferredFrameRate(24, FRAME_RATE_COMPATIBILITY_GTE);
            assertEquals(viewRootImpl.getPreferredFrameRate(), 24, 0.1);
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_GTE);
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
            viewRootImpl.votePreferredFrameRate(30, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            assertEquals(viewRootImpl.getPreferredFrameRate(), 30, 0.1);
            // If there is a conflict, then set compatibility to
            // FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            // Should be true since there is a conflict between 24 and 30.
            assertEquals(viewRootImpl.isFrameRateConflicted(), true);
            view.invalidate();
        });
        sInstrumentation.waitForIdleSync();

        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
            viewRootImpl.votePreferredFrameRate(60, FRAME_RATE_COMPATIBILITY_GTE);
            assertEquals(viewRootImpl.getPreferredFrameRate(), 60, 0.1);
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_GTE);
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
            viewRootImpl.votePreferredFrameRate(120, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            assertEquals(viewRootImpl.getPreferredFrameRate(), 120, 0.1);
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            // Should be false since 60 is a divisor of 120.
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
            viewRootImpl.votePreferredFrameRate(60, FRAME_RATE_COMPATIBILITY_GTE);
            assertEquals(viewRootImpl.getPreferredFrameRate(), 120, 0.1);
            // compatibility should be remained the same (FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            // since the frame rate 60 is smaller than 120.
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            // Should be false since 60 is a divisor of 120.
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);

        });
    }

    /**
     * Override the frame rate category value with setRequestedFrameRate method.
     * This function can replace the existing frameRateCategory value and
     * submit your preferred choice to the ViewRootImpl.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRate_category() {
        View view = new View(sContext);
        attachViewToWindow(view);
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);
        });

        // reset the frame rate category counts
        for (int i = 0; i < 5; i++) {
            sInstrumentation.runOnMainSync(() -> {
                view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
                view.invalidate();
            });
            sInstrumentation.waitForIdleSync();
        }

        sInstrumentation.runOnMainSync(() -> {
            view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_LOW);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_LOW);
            view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_NORMAL);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_NORMAL);
            view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_HIGH);
        });
    }

    /**
     * We should boost the frame rate if the value of mInsetsAnimationRunning is true.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_insetsAnimation() {
        View view = new View(sContext);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            wmlp.width = (int) (metrics.widthPixels * 0.9);
            wmlp.height = (int) (metrics.heightPixels * 0.9);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            view.invalidate();
            viewRootImpl.notifyInsetsAnimationRunningStateChanged(true);
            view.invalidate();
        });
        sInstrumentation.waitForIdleSync();

        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getLastPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_HIGH);
        });
    }


    /**
     * Test FrameRateBoostOnTouchEnabled API
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_frameRateBoostOnTouch() {
        View view = new View(sContext);
        attachViewToWindow(view);
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        final WindowManager.LayoutParams attrs = viewRootImpl.mWindowAttributes;
        assertEquals(attrs.getFrameRateBoostOnTouchEnabled(), true);
        assertEquals(viewRootImpl.getFrameRateBoostOnTouchEnabled(),
                attrs.getFrameRateBoostOnTouchEnabled());

        sInstrumentation.runOnMainSync(() -> {
            attrs.setFrameRateBoostOnTouchEnabled(false);
            viewRootImpl.setLayoutParams(attrs, false);
        });
        sInstrumentation.waitForIdleSync();

        sInstrumentation.runOnMainSync(() -> {
            final WindowManager.LayoutParams newAttrs = viewRootImpl.mWindowAttributes;
            assertEquals(newAttrs.getFrameRateBoostOnTouchEnabled(), false);
            assertEquals(viewRootImpl.getFrameRateBoostOnTouchEnabled(),
                    newAttrs.getFrameRateBoostOnTouchEnabled());
        });
    }

    /**
     * Test votePreferredFrameRate_voteFrameRateTimeOut
     * If no frame rate is voted in 100 milliseconds, the value of
     * mPreferredFrameRate should be set to 0.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRateTimeOut() throws InterruptedException {
        final long delay = 200L;

        View view = new View(sContext);
        attachViewToWindow(view);
        sInstrumentation.waitForIdleSync();
        ViewRootImpl viewRootImpl = view.getViewRootImpl();

        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getPreferredFrameRate(), 0, 0.1);
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
            viewRootImpl.votePreferredFrameRate(24, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            assertEquals(viewRootImpl.getPreferredFrameRate(), 24, 0.1);
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRate(), 24, 0.1);
            assertEquals(viewRootImpl.getFrameRateCompatibility(),
                    FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            assertEquals(viewRootImpl.isFrameRateConflicted(), false);
        });

        Thread.sleep(delay);
        assertEquals(viewRootImpl.getPreferredFrameRate(), 0, 0.1);
        assertEquals(viewRootImpl.getFrameRateCompatibility(),
                FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        assertEquals(viewRootImpl.isFrameRateConflicted(), false);
    }

    /**
     * A View should either vote a frame rate or a frame rate category instead of both.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_voteFrameRateOnly() {
        View view = new View(sContext);
        float frameRate = 20;
        attachViewToWindow(view);
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();
        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);

            view.setRequestedFrameRate(frameRate);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);
            assertEquals(viewRootImpl.getPreferredFrameRate(), frameRate, 0.1);
        });

        // reset the frame rate category counts
        for (int i = 0; i < 5; i++) {
            sInstrumentation.runOnMainSync(() -> {
                view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
                view.invalidate();
            });
            sInstrumentation.waitForIdleSync();
        }

        sInstrumentation.runOnMainSync(() -> {
            view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_LOW);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_LOW);
        });
    }

    /**
     * Test the logic of infrequent layer:
     * - NORMAL for infrequent update: FT2-FT1 > 100 && FT3-FT2 > 100.
     * - HIGH/NORMAL based on size for frequent update: (FT3-FT2) + (FT2 - FT1) < 100.
     * - otherwise, use the previous category value.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_infrequentLayer_defaultHigh() throws InterruptedException {
        final long delay = 200L;

        View view = new View(sContext);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            wmlp.width = (int) (metrics.widthPixels * 0.9);
            wmlp.height = (int) (metrics.heightPixels * 0.9);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();

        // In transistion from frequent update to infrequent update
        Thread.sleep(delay);
        sInstrumentation.runOnMainSync(() -> {
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_HIGH);
        });

        // reset the frame rate category counts
        for (int i = 0; i < 5; i++) {
            sInstrumentation.runOnMainSync(() -> {
                view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
                view.invalidate();
            });
            sInstrumentation.waitForIdleSync();
        }

        // In transistion from frequent update to infrequent update
        Thread.sleep(delay);
        sInstrumentation.runOnMainSync(() -> {
            view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);
        });

        // Infrequent update
        Thread.sleep(delay);
        sInstrumentation.runOnMainSync(() -> {
            view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(), FRAME_RATE_CATEGORY_NORMAL);
        });
    }

    /**
     * Test the IsFrameRatePowerSavingsBalanced values are properly set
     */
    @UiThreadTest
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_isFrameRatePowerSavingsBalanced() {
        ViewRootImpl viewRootImpl = new ViewRootImpl(sContext,
                sContext.getDisplayNoVerify());
        assertEquals(viewRootImpl.isFrameRatePowerSavingsBalanced(), true);
        viewRootImpl.setFrameRatePowerSavingsBalanced(false);
        assertEquals(viewRootImpl.isFrameRatePowerSavingsBalanced(), false);
        viewRootImpl.setFrameRatePowerSavingsBalanced(true);
        assertEquals(viewRootImpl.isFrameRatePowerSavingsBalanced(), true);
    }

    /**
     * Test the TextureView heuristic:
     * 1. Store the last 3 invalidates time - FT1, FT2, FT3.
     * 2. If FT2-FT1 > 15ms && FT3-FT2 > 15ms -> vote for NORMAL category
     */
    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void votePreferredFrameRate_applyTextureViewHeuristic() throws InterruptedException {
        final long delay = 30L;

        TextureView view = new TextureView(sContext);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = sContext.getSystemService(WindowManager.class);
            Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            wmlp.width = (int) (metrics.widthPixels * 0.9);
            wmlp.height = (int) (metrics.heightPixels * 0.9);
            wm.addView(view, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = view.getViewRootImpl();

        sInstrumentation.runOnMainSync(() -> {
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NO_PREFERENCE);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_HIGH);
        });

         // reset the frame rate category counts
        for (int i = 0; i < 5; i++) {
            Thread.sleep(delay);
            sInstrumentation.runOnMainSync(() -> {
                view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
                view.invalidate();
            });
            sInstrumentation.waitForIdleSync();
        }

        Thread.sleep(delay);
        sInstrumentation.runOnMainSync(() -> {
            view.setRequestedFrameRate(view.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT);
            view.invalidate();
            assertEquals(viewRootImpl.getPreferredFrameRateCategory(),
                    FRAME_RATE_CATEGORY_NORMAL);
        });
    }

    @Test
    public void forceInvertOffDarkThemeOff_forceDarkModeDisabled() {
        mSetFlagsRule.enableFlags(FLAG_FORCE_INVERT_COLOR);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            Settings.Secure.putInt(
                    sContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_FORCE_INVERT_COLOR_ENABLED,
                    /* value= */ 0
            );
            var uiModeManager = sContext.getSystemService(UiModeManager.class);
            uiModeManager.setNightMode(UiModeManager.MODE_NIGHT_NO);
        });

        sInstrumentation.runOnMainSync(() ->
                mViewRootImpl.updateConfiguration(sContext.getDisplayNoVerify().getDisplayId())
        );

        assertThat(mViewRootImpl.determineForceDarkType()).isEqualTo(ForceDarkType.NONE);
    }

    @Test
    public void forceInvertOnDarkThemeOff_forceDarkModeEnabled() {
        mSetFlagsRule.enableFlags(FLAG_FORCE_INVERT_COLOR);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            Settings.Secure.putInt(
                    sContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_FORCE_INVERT_COLOR_ENABLED,
                    /* value= */ 1
            );
            var uiModeManager = sContext.getSystemService(UiModeManager.class);
            uiModeManager.setNightMode(UiModeManager.MODE_NIGHT_NO);
        });

        sInstrumentation.runOnMainSync(() ->
                mViewRootImpl.updateConfiguration(sContext.getDisplayNoVerify().getDisplayId())
        );

        assertThat(mViewRootImpl.determineForceDarkType())
                .isEqualTo(ForceDarkType.FORCE_INVERT_COLOR_DARK);
    }

    @Test
    public void forceInvertOffForceDarkOff_forceDarkModeDisabled() {
        mSetFlagsRule.enableFlags(FLAG_FORCE_INVERT_COLOR);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            Settings.Secure.putInt(
                    sContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_FORCE_INVERT_COLOR_ENABLED,
                    /* value= */ 0
            );

            // TODO(b/297556388): figure out how to set this without getting blocked by SELinux
            assumeTrue(setForceDarkSysProp(true));
        });

        sInstrumentation.runOnMainSync(() ->
                mViewRootImpl.updateConfiguration(sContext.getDisplayNoVerify().getDisplayId())
        );

        assertThat(mViewRootImpl.determineForceDarkType()).isEqualTo(ForceDarkType.NONE);
    }

    @Test
    public void forceInvertOffForceDarkOn_forceDarkModeEnabled() {
        mSetFlagsRule.enableFlags(FLAG_FORCE_INVERT_COLOR);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            Settings.Secure.putInt(
                    sContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_FORCE_INVERT_COLOR_ENABLED,
                    /* value= */ 0
            );

            assumeTrue(setForceDarkSysProp(true));
        });

        sInstrumentation.runOnMainSync(() ->
                mViewRootImpl.updateConfiguration(sContext.getDisplayNoVerify().getDisplayId())
        );

        assertThat(mViewRootImpl.determineForceDarkType()).isEqualTo(ForceDarkType.FORCE_DARK);
    }

    private boolean setForceDarkSysProp(boolean isForceDarkEnabled) {
        try {
            SystemProperties.set(
                    ThreadedRenderer.DEBUG_FORCE_DARK,
                    Boolean.toString(isForceDarkEnabled)
            );
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set force_dark sysprop", e);
            return false;
        }
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
