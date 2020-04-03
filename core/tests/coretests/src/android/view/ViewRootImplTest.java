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
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private Context mContext;
    private ViewRootImplAccessor mViewRootImpl;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mViewRootImpl = new ViewRootImplAccessor(
                    new ViewRootImpl(mContext, mContext.getDisplayNoVerify()));
        });
    }

    @Test
    public void negativeInsets_areSetToZero() throws Exception {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        mViewRootImpl.getAttachInfo().getContentInsets().set(-10, -20, -30 , -40);
        mViewRootImpl.getAttachInfo().getStableInsets().set(-10, -20, -30 , -40);
        final WindowInsets insets = mViewRootImpl.getWindowInsets(true /* forceConstruct */);

        assertThat(insets.getSystemWindowInsets(), equalTo(Insets.NONE));
        assertThat(insets.getStableInsets(), equalTo(Insets.NONE));
    }

    @Test
    public void negativeInsets_areSetToZero_positiveAreLeftAsIs() throws Exception {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        mViewRootImpl.getAttachInfo().getContentInsets().set(-10, 20, -30 , 40);
        mViewRootImpl.getAttachInfo().getStableInsets().set(10, -20, 30 , -40);
        final WindowInsets insets = mViewRootImpl.getWindowInsets(true /* forceConstruct */);

        assertThat(insets.getSystemWindowInsets(), equalTo(Insets.of(0, 20, 0, 40)));
        assertThat(insets.getStableInsets(), equalTo(Insets.of(10, 0, 30, 0)));
    }

    @Test
    public void positiveInsets_areLeftAsIs() throws Exception {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        mViewRootImpl.getAttachInfo().getContentInsets().set(10, 20, 30 , 40);
        mViewRootImpl.getAttachInfo().getStableInsets().set(10, 20, 30 , 40);
        final WindowInsets insets = mViewRootImpl.getWindowInsets(true /* forceConstruct */);

        assertThat(insets.getSystemWindowInsets(), equalTo(Insets.of(10, 20, 30, 40)));
        assertThat(insets.getStableInsets(), equalTo(Insets.of(10, 20, 30, 40)));
    }

    @Test
    public void adjustLayoutParamsForCompatibility_layoutFullscreen() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Type.statusBars() must be removed.
        assertEquals(0, attrs.getFitInsetsTypes() & Type.statusBars());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_layoutInScreen() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.flags = FLAG_LAYOUT_IN_SCREEN;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Type.statusBars() must be removed.
        assertEquals(0, attrs.getFitInsetsTypes() & Type.statusBars());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_layoutHideNavigation() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Type.systemBars() must be removed.
        assertEquals(0, attrs.getFitInsetsTypes() & Type.systemBars());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_toast() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_TOAST);
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        assertEquals(true, attrs.isFitInsetsIgnoringVisibility());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_systemAlert() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_SYSTEM_ALERT);
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        assertEquals(true, attrs.isFitInsetsIgnoringVisibility());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_fitSystemBars() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // A window which fits system bars must fit IME, unless its type is toast or system alert.
        assertEquals(Type.systemBars() | Type.ime(), attrs.getFitInsetsTypes());
    }

    @Test
    public void adjustLayoutParamsForCompatibility_noAdjustLayout() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

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
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final ViewRootImpl viewRoot = mViewRootImpl.get();
        final WindowInsetsController controller = viewRoot.getInsetsController();
        final WindowManager.LayoutParams attrs = viewRoot.mWindowAttributes;
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
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final ViewRootImpl viewRoot = mViewRootImpl.get();
        final WindowInsetsController controller = viewRoot.getInsetsController();
        final WindowManager.LayoutParams attrs = viewRoot.mWindowAttributes;
        final int behavior = BEHAVIOR_SHOW_BARS_BY_TOUCH;
        controller.setSystemBarsBehavior(behavior);
        attrs.systemUiVisibility = SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        ViewRootImpl.adjustLayoutParamsForCompatibility(attrs);

        // Behavior must not be adjusted due to legacy system UI visibility after calling
        // setSystemBarsBehavior.
        assertEquals(behavior, controller.getSystemBarsBehavior());
    }

    private static class ViewRootImplAccessor {

        private final ViewRootImpl mViewRootImpl;

        ViewRootImplAccessor(ViewRootImpl viewRootImpl) {
            mViewRootImpl = viewRootImpl;
        }

        public ViewRootImpl get() {
            return mViewRootImpl;
        }

        AttachInfoAccessor getAttachInfo() throws Exception {
            return new AttachInfoAccessor(
                    getField(mViewRootImpl, ViewRootImpl.class.getDeclaredField("mAttachInfo")));
        }

        WindowInsets getWindowInsets(boolean forceConstruct) throws Exception {
            return (WindowInsets) invokeMethod(mViewRootImpl,
                    ViewRootImpl.class.getDeclaredMethod("getWindowInsets", boolean.class),
                    forceConstruct);
        }

        class AttachInfoAccessor {

            private final Class<?> mClass;
            private final Object mAttachInfo;

            AttachInfoAccessor(Object attachInfo) throws Exception {
                mAttachInfo = attachInfo;
                mClass = ViewRootImpl.class.getClassLoader().loadClass(
                        "android.view.View$AttachInfo");
            }

            Rect getContentInsets() throws Exception {
                return (Rect) getField(mAttachInfo, mClass.getDeclaredField("mContentInsets"));
            }

            Rect getStableInsets() throws Exception {
                return (Rect) getField(mAttachInfo, mClass.getDeclaredField("mStableInsets"));
            }
        }

        private static Object getField(Object o, Field field) throws Exception {
            field.setAccessible(true);
            return field.get(o);
        }

        private static Object invokeMethod(Object o, Method method, Object... args)
                throws Exception {
            method.setAccessible(true);
            return method.invoke(o, args);
        }
    }
}
