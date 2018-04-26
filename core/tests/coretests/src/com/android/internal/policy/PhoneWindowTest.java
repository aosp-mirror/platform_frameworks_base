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

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;

import com.android.frameworks.coretests.R;

import org.junit.Before;
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

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void layoutInDisplayCutoutMode_unset() throws Exception {
        createPhoneWindowWithTheme(R.style.LayoutInDisplayCutoutModeUnset);
        installDecor();

        assertThat(mPhoneWindow.getAttributes().layoutInDisplayCutoutMode,
                is(LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT));
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

    private void createPhoneWindowWithTheme(int theme) {
        mPhoneWindow = new PhoneWindow(new ContextThemeWrapper(mContext, theme));
    }

    private void installDecor() {
        mPhoneWindow.getDecorView();
    }
}