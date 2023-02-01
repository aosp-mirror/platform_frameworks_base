/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.annotation.IdRes;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.CompoundButton;

import androidx.test.filters.SmallTest;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class WindowMagnificationSettingsTest extends SysuiTestCase {

    private static final int MAGNIFICATION_SIZE_SMALL = 1;
    private static final int MAGNIFICATION_SIZE_MEDIUM = 2;
    private static final int MAGNIFICATION_SIZE_LARGE = 3;

    private ViewGroup mSettingView;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private WindowMagnificationSettingsCallback mWindowMagnificationSettingsCallback;
    private TestableWindowManager mWindowManager;
    private WindowMagnificationSettings mWindowMagnificationSettings;
    private MotionEventHelper mMotionEventHelper = new MotionEventHelper();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = getContext();
        mContext.setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        mWindowManager = spy(new TestableWindowManager(wm));
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);

        mWindowMagnificationSettings = new WindowMagnificationSettings(mContext,
                mWindowMagnificationSettingsCallback, mSfVsyncFrameProvider,
                mSecureSettings);

        mSettingView = mWindowMagnificationSettings.getSettingView();
    }

    @After
    public void tearDown() {
        mMotionEventHelper.recycleEvents();
        mWindowMagnificationSettings.hideSettingPanel();
    }

    @Test
    public void showSettingPanel_hasAccessibilityWindowTitle() {
        mWindowMagnificationSettings.showSettingPanel();

        final WindowManager.LayoutParams layoutPrams =
                mWindowManager.getLayoutParamsFromAttachedView();
        assertNotNull(layoutPrams);
        assertEquals(getContext().getResources()
                        .getString(com.android.internal.R.string.android_system_label),
                layoutPrams.accessibilityTitle.toString());
    }

    @Test
    public void performClick_smallSizeButton_changeMagnifierSizeSmall() {
        // Open view
        mWindowMagnificationSettings.showSettingPanel();

        verifyOnSetMagnifierSize(R.id.magnifier_small_button, MAGNIFICATION_SIZE_SMALL);
    }

    @Test
    public void performClick_mediumSizeButton_changeMagnifierSizeMedium() {
        // Open view
        mWindowMagnificationSettings.showSettingPanel();

        verifyOnSetMagnifierSize(R.id.magnifier_medium_button, MAGNIFICATION_SIZE_MEDIUM);
    }

    @Test
    public void performClick_largeSizeButton_changeMagnifierSizeLarge() {
        // Open view
        mWindowMagnificationSettings.showSettingPanel();

        verifyOnSetMagnifierSize(R.id.magnifier_large_button, MAGNIFICATION_SIZE_LARGE);
    }

    private void verifyOnSetMagnifierSize(@IdRes int viewId, int expectedSizeIndex) {
        View changeSizeButton = getInternalView(viewId);

        // Perform click
        changeSizeButton.performClick();

        verify(mWindowMagnificationSettingsCallback).onSetMagnifierSize(expectedSizeIndex);
    }


    @Test
    public void performClick_fullScreenModeButton_setEditMagnifierSizeMode() {
        View fullScreenModeButton = getInternalView(R.id.magnifier_full_button);
        getInternalView(R.id.magnifier_panel_view);

        // Open view
        mWindowMagnificationSettings.showSettingPanel();

        // Perform click
        fullScreenModeButton.performClick();

        verify(mWindowManager).removeView(mSettingView);
        verify(mWindowMagnificationSettingsCallback)
                .onModeSwitch(Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void performClick_editButton_setEditMagnifierSizeMode() {
        View editButton = getInternalView(R.id.magnifier_edit_button);

        // Open view
        mWindowMagnificationSettings.showSettingPanel();

        // Perform click
        editButton.performClick();

        verify(mWindowMagnificationSettingsCallback).onEditMagnifierSizeMode(true);
        verify(mWindowManager).removeView(mSettingView);
    }

    @Test
    public void performClick_setDiagonalScrollingSwitch_toggleDiagonalScrollingSwitchMode() {
        CompoundButton diagonalScrollingSwitch =
                getInternalView(R.id.magnifier_horizontal_lock_switch);
        final boolean currentCheckedState = diagonalScrollingSwitch.isChecked();

        // Open view
        mWindowMagnificationSettings.showSettingPanel();

        // Perform click
        diagonalScrollingSwitch.performClick();

        verify(mWindowMagnificationSettingsCallback).onSetDiagonalScrolling(!currentCheckedState);
    }

    @Test
    public void onConfigurationChanged_selectedButtonIsStillSelected() {
        // Open view
        mWindowMagnificationSettings.showSettingPanel();
        View magnifierMediumButton = getInternalView(R.id.magnifier_medium_button);
        magnifierMediumButton.performClick();

        mWindowMagnificationSettings.onConfigurationChanged(ActivityInfo.CONFIG_UI_MODE);

        // Since the view is re-inflated after onConfigurationChanged,
        // we need to get the view again.
        magnifierMediumButton = getInternalView(R.id.magnifier_medium_button);
        assertThat(magnifierMediumButton.isSelected()).isTrue();
    }

    private <T extends View> T getInternalView(@IdRes int idRes) {
        T view = mSettingView.findViewById(idRes);
        assertNotNull(view);
        return view;
    }
}
