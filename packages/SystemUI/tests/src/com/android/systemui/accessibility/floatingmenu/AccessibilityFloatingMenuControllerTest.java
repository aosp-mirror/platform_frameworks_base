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
import static org.mockito.Mockito.when;

import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
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

    private AccessibilityFloatingMenuController mController;
    private AccessibilityButtonTargetsObserver mTargetsObserver;
    private AccessibilityButtonModeObserver mModeObserver;
    @Mock
    private AccessibilityManager mMockA11yManager;

    @Test
    public void initController_registerListeners() {
        mController = setUpController();

        verify(mTargetsObserver).addListener(
                any(AccessibilityButtonTargetsObserver.TargetsChangedListener.class));
        verify(mModeObserver).addListener(
                any(AccessibilityButtonModeObserver.ModeChangedListener.class));
        verify(mMockA11yManager).addAccessibilityStateChangeListener(any(
                AccessibilityManager.AccessibilityStateChangeListener.class));
    }

    @Test
    public void initController_accessibilityManagerEnabled_showWidget() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS);
        when(mMockA11yManager.isEnabled()).thenReturn(true);

        mController = setUpController();

        assertThat(mController.mFloatingMenu).isNotNull();
        verify(mMockA11yManager).removeAccessibilityStateChangeListener(mController);
    }

    @Test
    public void initController_accessibilityManagerDisabledThenCallbackToEnabled_showWidget() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS);
        when(mMockA11yManager.isEnabled()).thenReturn(false);

        mController = setUpController();
        mController.onAccessibilityStateChanged(true);

        assertThat(mController.mFloatingMenu).isNotNull();
        verify(mMockA11yManager).removeAccessibilityStateChangeListener(mController);
    }

    @Test
    public void onAccessibilityButtonModeChanged_floatingModeAndHasButtonTargets_showWidget() {
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS);
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);

        assertThat(mController.mFloatingMenu).isNotNull();
    }

    @Test
    public void onAccessibilityButtonModeChanged_floatingModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, "");
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonModeChanged_navBarModeAndHasButtonTargets_showWidget() {
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS);
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonModeChanged_navBarModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, "");
        mController = setUpController();

        mController.onAccessibilityButtonModeChanged(ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_floatingModeAndHasButtonTargets_showWidget() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged(TEST_A11Y_BTN_TARGETS);

        assertThat(mController.mFloatingMenu).isNotNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_floatingModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged("");

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_navBarModeAndHasButtonTargets_showWidget() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged(TEST_A11Y_BTN_TARGETS);

        assertThat(mController.mFloatingMenu).isNull();
    }

    @Test
    public void onAccessibilityButtonTargetsChanged_buttonModeAndNoButtonTargets_destroyWidget() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);
        mController = setUpController();

        mController.onAccessibilityButtonTargetsChanged("");

        assertThat(mController.mFloatingMenu).isNull();
    }

    private AccessibilityFloatingMenuController setUpController() {
        mTargetsObserver = spy(Dependency.get(AccessibilityButtonTargetsObserver.class));
        mModeObserver = spy(Dependency.get(AccessibilityButtonModeObserver.class));
        mContext.addMockSystemService(AccessibilityManager.class, mMockA11yManager);

        return new AccessibilityFloatingMenuController(mContext, mTargetsObserver,
                mModeObserver);
    }
}
