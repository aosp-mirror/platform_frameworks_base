/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.Surface.ROTATION_0;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.UserHandle;
import android.support.test.InstrumentationRegistry;
import android.testing.TestableResources;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;

import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.android.server.wm.DisplayFrames;

import org.junit.Before;

public class PhoneWindowManagerTestBase {
    static final int DISPLAY_WIDTH = 500;
    static final int DISPLAY_HEIGHT = 1000;

    static final int STATUS_BAR_HEIGHT = 10;
    static final int NAV_BAR_HEIGHT = 15;

    TestablePhoneWindowManager mPolicy;
    TestContextWrapper mContext;
    DisplayFrames mFrames;

    FakeWindowState mStatusBar;
    FakeWindowState mNavigationBar;

    @Before
    public void setUpBase() throws Exception {
        mContext = new TestContextWrapper(InstrumentationRegistry.getTargetContext());
        mContext.getResourceMocker().addOverride(
                com.android.internal.R.dimen.status_bar_height, STATUS_BAR_HEIGHT);
        mContext.getResourceMocker().addOverride(
                com.android.internal.R.dimen.navigation_bar_height, NAV_BAR_HEIGHT);
        mContext.getResourceMocker().addOverride(
                com.android.internal.R.dimen.navigation_bar_height_landscape, NAV_BAR_HEIGHT);
        mContext.getResourceMocker().addOverride(
                com.android.internal.R.dimen.navigation_bar_width, NAV_BAR_HEIGHT);

        mPolicy = TestablePhoneWindowManager.create(mContext);

        DisplayInfo info = new DisplayInfo();
        info.logicalWidth = DISPLAY_WIDTH;
        info.logicalHeight = DISPLAY_HEIGHT;
        info.rotation = ROTATION_0;
        mFrames = new DisplayFrames(Display.DEFAULT_DISPLAY, info);
    }

    public void addStatusBar() {
        mStatusBar = new FakeWindowState();
        mStatusBar.attrs = new WindowManager.LayoutParams(MATCH_PARENT, STATUS_BAR_HEIGHT,
                TYPE_STATUS_BAR, 0 /* flags */, PixelFormat.TRANSLUCENT);
        mStatusBar.attrs.gravity = Gravity.TOP;

        mPolicy.addWindow(mStatusBar);
        mPolicy.mLastSystemUiFlags |= View.STATUS_BAR_TRANSPARENT;
    }

    public void addNavigationBar() {
        mNavigationBar = new FakeWindowState();
        mNavigationBar.attrs = new WindowManager.LayoutParams(MATCH_PARENT, STATUS_BAR_HEIGHT,
                TYPE_NAVIGATION_BAR, 0 /* flags */, PixelFormat.TRANSLUCENT);
        mNavigationBar.attrs.gravity = Gravity.BOTTOM;

        mPolicy.addWindow(mNavigationBar);
        mPolicy.mHasNavigationBar = true;
        mPolicy.mLastSystemUiFlags |= View.NAVIGATION_BAR_TRANSPARENT;
    }

    /** Asserts that {@code actual} is inset by the given amounts from the full display rect. */
    public void assertInsetBy(Rect actual, int expectedInsetLeft, int expectedInsetTop,
            int expectedInsetRight, int expectedInsetBottom) {
        assertEquals(new Rect(expectedInsetLeft, expectedInsetTop,
                DISPLAY_WIDTH - expectedInsetRight, DISPLAY_HEIGHT - expectedInsetBottom), actual);
    }

    /**
     * Asserts that {@code actual} is inset by the given amounts from the full display rect.
     *
     * Convenience wrapper for when only the top and bottom inset are non-zero.
     */
    public void assertInsetByTopBottom(Rect actual, int expectedInsetTop, int expectedInsetBottom) {
        assertInsetBy(actual, 0, expectedInsetTop, 0, expectedInsetBottom);
    }

    static class TestContextWrapper extends ContextWrapper {
        private final TestableResources mResourceMocker;

        public TestContextWrapper(Context targetContext) {
            super(targetContext);
            mResourceMocker = new TestableResources(targetContext.getResources());
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public Resources getResources() {
            return mResourceMocker.getResources();
        }

        public TestableResources getResourceMocker() {
            return mResourceMocker;
        }
    }

    static class TestablePhoneWindowManager extends PhoneWindowManager {

        public TestablePhoneWindowManager() {
        }

        @Override
        void initializeHdmiState() {
            // Do nothing.
        }

        @Override
        Context getSystemUiContext() {
            return mContext;
        }

        void addWindow(WindowState state) {
            if (state instanceof FakeWindowState) {
                ((FakeWindowState) state).surfaceLayer =
                        getWindowLayerFromTypeLw(state.getAttrs().type);
            }
            adjustWindowParamsLw(state, state.getAttrs(), true /* hasStatusBarPermission */);
            assertEquals(WindowManagerGlobal.ADD_OKAY, prepareAddWindowLw(state, state.getAttrs()));
        }

        public static TestablePhoneWindowManager create(Context context) {
            TestablePhoneWindowManager[] policy = new TestablePhoneWindowManager[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                policy[0] = new TestablePhoneWindowManager();
                policy[0].mContext = context;
                policy[0].mKeyguardDelegate = mock(KeyguardServiceDelegate.class);
                policy[0].mAccessibilityManager = new AccessibilityManager(context,
                        mock(IAccessibilityManager.class), UserHandle.USER_CURRENT);
                policy[0].mSystemGestures = mock(SystemGesturesPointerEventListener.class);
                policy[0].onConfigurationChanged();
            });
            return policy[0];
        }
    }
}
