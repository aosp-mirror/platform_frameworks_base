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

package com.android.internal.policy;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;
import android.view.ViewRootImpl;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests {@link PhoneWindow}'s {@link ActionMode} related methods.
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class PhoneWindowTest {

    private PhoneWindow mPhoneWindow;
    private Context mContext;

    private static Instrumentation sInstrumentation =
            InstrumentationRegistry.getInstrumentation();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void layoutInDisplayCutoutMode_unset() throws Exception {
        createPhoneWindowWithTheme(R.style.LayoutInDisplayCutoutModeUnset);
        installDecor();

        if ((mPhoneWindow.getAttributes().privateFlags & PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED) != 0
                && !mPhoneWindow.isFloating()) {
            assertThat(mPhoneWindow.getAttributes().layoutInDisplayCutoutMode,
                    is(LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS));
        } else {
            assertThat(mPhoneWindow.getAttributes().layoutInDisplayCutoutMode,
                    is(LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT));
        }
    }

    @Test
    public void layoutInDisplayCutoutMode_default() throws Exception {
        createPhoneWindowWithTheme(R.style.LayoutInDisplayCutoutModeDefault);
        installDecor();

        assertThat(mPhoneWindow.getAttributes().layoutInDisplayCutoutMode,
                is(LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT));
    }

    @Test
    public void layoutInDisplayCutoutMode_shortEdges() throws Exception {
        createPhoneWindowWithTheme(R.style.LayoutInDisplayCutoutModeShortEdges);
        installDecor();

        assertThat(mPhoneWindow.getAttributes().layoutInDisplayCutoutMode,
                is(LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES));
    }

    @Test
    public void layoutInDisplayCutoutMode_never() throws Exception {
        createPhoneWindowWithTheme(R.style.LayoutInDisplayCutoutModeNever);
        installDecor();

        assertThat(mPhoneWindow.getAttributes().layoutInDisplayCutoutMode,
                is(LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER));
    }

    @Test
    public void layoutInDisplayCutoutMode_always() throws Exception {
        createPhoneWindowWithTheme(R.style.LayoutInDisplayCutoutModeAlways);
        installDecor();

        assertThat(mPhoneWindow.getAttributes().layoutInDisplayCutoutMode,
                is(LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS));
    }

    @Test
    public void testWindowBackground_colorLiteral() {
        createPhoneWindowWithTheme(R.style.WindowBackgroundColorLiteral);
        installDecor();

        Drawable backgroundDrawable = mPhoneWindow.getDecorView().getBackground();
        assertThat(backgroundDrawable instanceof ColorDrawable, is(true));

        ColorDrawable colorDrawable = (ColorDrawable) backgroundDrawable;
        assertThat(colorDrawable.getColor(), is(Color.GREEN));
    }

    @Test
    public void testWindowBackgroundFallback_colorLiteral() {
        createPhoneWindowWithTheme(R.style.WindowBackgroundFallbackColorLiteral);
        installDecor();

        DecorView decorView = (DecorView) mPhoneWindow.getDecorView();
        Drawable fallbackDrawable = decorView.getBackgroundFallback();

        assertThat(fallbackDrawable instanceof ColorDrawable, is(true));

        ColorDrawable colorDrawable = (ColorDrawable) fallbackDrawable;
        assertThat(colorDrawable.getColor(), is(Color.BLUE));
    }

    @Test
    public void testWindowBackgroundFallbackWithExplicitBackgroundSet_colorLiteral() {
        createPhoneWindowWithTheme(R.style.WindowBackgroundFallbackColorLiteral);
        // set background before decorView is created
        mPhoneWindow.setBackgroundDrawable(new ColorDrawable(Color.CYAN));
        installDecor();
        // clear background so that fallback is used
        mPhoneWindow.setBackgroundDrawable(null);

        DecorView decorView = (DecorView) mPhoneWindow.getDecorView();
        Drawable fallbackDrawable = decorView.getBackgroundFallback();

        assertThat(fallbackDrawable instanceof ColorDrawable, is(true));

        ColorDrawable colorDrawable = (ColorDrawable) fallbackDrawable;
        assertThat(colorDrawable.getColor(), is(Color.BLUE));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void testWindowFrameRateHint_disabled() {
        createPhoneWindowWithTheme(R.style.IsFrameRatePowerSavingsBalancedDisabled);
        installDecor();

        DecorView decorView = (DecorView) mPhoneWindow.getDecorView();

        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = mContext.getSystemService(WindowManager.class);
            wm.addView(decorView, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = decorView.getViewRootImpl();
        assertFalse(viewRootImpl.isFrameRatePowerSavingsBalanced());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void testWindowFrameRateHint_enabled() {
        createPhoneWindowWithTheme(R.style.IsFrameRatePowerSavingsBalancedEnabled);
        installDecor();

        DecorView decorView = (DecorView) mPhoneWindow.getDecorView();

        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        wmlp.token = new Binder(); // Set a fake token to bypass 'is your activity running' check

        sInstrumentation.runOnMainSync(() -> {
            WindowManager wm = mContext.getSystemService(WindowManager.class);
            wm.addView(decorView, wmlp);
        });
        sInstrumentation.waitForIdleSync();

        ViewRootImpl viewRootImpl = decorView.getViewRootImpl();
        assertTrue(viewRootImpl.isFrameRatePowerSavingsBalanced());
    }

    private void createPhoneWindowWithTheme(int theme) {
        mPhoneWindow = new PhoneWindow(new ContextThemeWrapper(mContext, theme));
    }

    private void installDecor() {
        mPhoneWindow.getDecorView();
    }
}