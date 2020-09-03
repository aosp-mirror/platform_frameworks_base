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
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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

    @Before
    public void setUp() throws Exception {
        final Context context = getInstrumentation().getTargetContext();

        getInstrumentation().runOnMainSync(() ->
                mViewRootImpl = new ViewRootImpl(context, context.getDisplayNoVerify()));
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
        final int appearance = 0;
        controller.setSystemBarsAppearance(appearance, 0xffffffff);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_LOW_PROFILE
                | SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Appearance must not be adjusted due to legacy system UI visibility after calling
        // setSystemBarsAppearance.
        assertEquals(appearance, controller.getSystemBarsAppearance());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_noAdjustBehavior() {
        final WindowInsetsController controller = mViewRootImpl.getInsetsController();
        final WindowManager.LayoutParams attrs = mViewRootImpl.mWindowAttributes;
        final int behavior = BEHAVIOR_SHOW_BARS_BY_TOUCH;
        controller.setSystemBarsBehavior(behavior);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Behavior must not be adjusted due to legacy system UI visibility after calling
        // setSystemBarsBehavior.
        assertEquals(behavior, controller.getSystemBarsBehavior());
    }
}
