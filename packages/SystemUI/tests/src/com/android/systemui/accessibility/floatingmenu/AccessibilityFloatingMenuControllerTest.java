/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Test for {@link AccessibilityFloatingMenuController}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class AccessibilityFloatingMenuControllerTest extends SysuiTestCase {

    private static final String TEST_A11Y_BTN_TARGETS = "Magnification";

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private Context mContextWrapper;
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private AccessibilityFloatingMenuController mController;
    private AccessibilityButtonTargetsObserver mTargetsObserver;
    private AccessibilityButtonModeObserver mModeObserver;
    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardCallbackCaptor;
    private KeyguardUpdateMonitorCallback mKeyguardCallback;

    @Before
    public void setUp() throws Exception {
        mContextWrapper = new ContextWrapper(mContext) {
            @Override
            public Context createContextAsUser(UserHandle user, int flags) {
                return getBaseContext();
            }
        };
    }

    @After
    public void tearDown() {
        if (mController != null) {
            mController.onAccessibilityButtonTargetsChanged("");
            mController = null;
        }
    }

    @Test
    public void initController_registerListeners() {
        mController = setUpController();

        verify(mTargetsObserver).addListener(
                any(AccessibilityButtonTargetsObserver.TargetsChangedListener.class));
        verify(mModeObserver).addListener(
                any(AccessibilityButtonModeObserver.ModeChangedListener.class));
        verify(mKeyguardUpdateMonitor).registerCallback(any(KeyguardUpdateMonitorCallback.class));
    }

    @Test
    public void onUserUnlocked_keyguardNotShow_showWidget() {
        enableAccessibilityFloatingMenuConfig();
        mController = setUpController();
        captureKeyguardUpdateMonitorCallback();
        mKeyguardCallback.onKeyguardVisibilityChanged(false);

        mKeyguardCallback.onUserUnlocked();

        assertThat(mController.mFloatingMenu).isNotNull();
    }

    @Test
    public void onUserUnlocked_keyguardShowing_destroyWidget() {
        enableAccessibilityFloatingMenuConfig();
        mController = setUpController();
        captureKeyguardUpdateMonitorCallback();
        mKeyguardCallback.onKeyguardVisibilityChanged(true);

        mKeyguardCallback.onUserUnlocked();

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onKeyguardVisibilityChanged_showing_destroyWidget() {
        enableAccessibilityFloatingMenuConfig();
        mController = setUpController();
        mController.mFloatingMenu = new AccessibilityFloatingMenu(mContextWrapper);
        captureKeyguardUpdateMonitorCallback();
        mKeyguardCallback.onUserUnlocked();

        mKeyguardCallback.onKeyguardVisibilityChanged(true);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onKeyguardVisibilityChanged_notShow_showWidget() {
        enableAccessibilityFloatingMenuConfig();
        mController = setUpController();
        captureKeyguardUpdateMonitorCallback();
        mKeyguardCallback.onUserUnlocked();

        mKeyguardCallback.onKeyguardVisibilityChanged(false);

        assertThat(mController.mFloatingMenu).isNotNull();
    }

    @Test
    public void onUserSwitching_destroyWidget() {
        final int fakeUserId = 1;
        enableAccessibilityFloatingMenuConfig();
        mController = setUpController();
        mController.mFloatingMenu = new AccessibilityFloatingMenu(mContextWrapper);
        captureKeyguardUpdateMonitorCallback();

        mKeyguardCallback.onUserSwitching(fakeUserId);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onUserSwitch_onKeyguardVisibilityChangedToTrue_destroyWidget() {
        final int fakeUserId = 1;
        enableAccessibilityFloatingMenuConfig();
        mController = setUpController();
        mController.mFloatingMenu = new AccessibilityFloatingMenu(mContextWrapper);
        captureKeyguardUpdateMonitorCallback();
        mKeyguardCallback.onUserUnlocked();
        mKeyguardCallback.onKeyguardVisibilityChanged(true);

        mKeyguardCallback.onUserSwitching(fakeUserId);
        mKeyguardCallback.onUserSwitchComplete(fakeUserId);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onUserSwitch_onKeyguardVisibilityChangedToFalse_showWidget() {
        final int fakeUserId = 1;
        enableAccessibilityFloatingMenuConfig();
        mController = setUpController();
        captureKeyguardUpdateMonitorCallback();
        mKeyguardCallback.onUserUnlocked();
        mKeyguardCallback.onKeyguardVisibilityChanged(false);

        mKeyguardCallback.onUserSwitching(fakeUserId);
        mKeyguardCallback.onUserSwitchComplete(fakeUserId);

        assertThat(mController.mFloatingMenu).isNotNull();
    }

    @Test
    public void onAccessibilityButtonModeChanged_floatingModeAndHasButtonTargets_showWidget() {
        Settings.Secure.putStringForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS,
                UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);

        assertThat(mController.mFloatingMenu).isNotNull();
    }

    @Test
    public void onAccessibilityButtonModeChanged_floatingModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putStringForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, "", UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonModeChanged_navBarModeAndHasButtonTargets_destroyWidget() {
        Settings.Secure.putStringForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS,
                UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonModeChanged_navBarModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putStringForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, "", UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_floatingModeAndHasButtonTargets_showWidget() {
        Settings.Secure.putIntForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU,
                UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged(TEST_A11Y_BTN_TARGETS);

        assertThat(mController.mFloatingMenu).isNotNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_floatingModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putIntForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU,
                UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged("");

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_navBarModeAndHasButtonTargets_destroyWidget() {
        Settings.Secure.putIntForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR, UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged(TEST_A11Y_BTN_TARGETS);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_navBarModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putIntForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR, UserHandle.USER_CURRENT);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged("");

        assertThat(mController.mFloatingMenu).isNull();
    }

    private AccessibilityFloatingMenuController setUpController() {
        mTargetsObserver = spy(Dependency.get(AccessibilityButtonTargetsObserver.class));
        mModeObserver = spy(Dependency.get(AccessibilityButtonModeObserver.class));
        mKeyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        final AccessibilityFloatingMenuController controller =
                new AccessibilityFloatingMenuController(mContextWrapper, mTargetsObserver,
                        mModeObserver, mKeyguardUpdateMonitor);
        controller.init();

        return controller;
    }

    private void enableAccessibilityFloatingMenuConfig() {
        Settings.Secure.putIntForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU,
                UserHandle.USER_CURRENT);
        Settings.Secure.putStringForUser(mContextWrapper.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS,
                UserHandle.USER_CURRENT);
    }

    private void captureKeyguardUpdateMonitorCallback() {
        verify(mKeyguardUpdateMonitor).registerCallback(mKeyguardCallbackCaptor.capture());
        mKeyguardCallback = mKeyguardCallbackCaptor.getValue();
    }
}
