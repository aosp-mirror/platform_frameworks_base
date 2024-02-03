/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.content.pm.ActivityInfo;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
/** Tests the MagnificationSettingsController. */
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MagnificationSettingsControllerTest extends SysuiTestCase {

    private MagnificationSettingsController mMagnificationSettingsController;
    @Mock
    private MagnificationSettingsController.Callback mMagnificationSettingControllerCallback;

    @Mock
    private WindowMagnificationSettings mWindowMagnificationSettings;

    @Mock
    private SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    private SecureSettings mSecureSettings;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMagnificationSettingsController = new MagnificationSettingsController(
                mContext, mSfVsyncFrameProvider,
                mMagnificationSettingControllerCallback, mSecureSettings,
                mWindowMagnificationSettings);
    }

    @After
    public void tearDown() {
        mMagnificationSettingsController.closeMagnificationSettings();
    }

    @Test
    public void testShowSettingsPanel() {
        mMagnificationSettingsController.toggleSettingsPanelVisibility();

        verify(mWindowMagnificationSettings).toggleSettingsPanelVisibility();
    }

    @Test
    public void testHideSettingsPanel() {
        mMagnificationSettingsController.closeMagnificationSettings();

        verify(mWindowMagnificationSettings).hideSettingPanel();
    }

    @Test
    public void testSetMagnificationScale() {
        final float scale = 3.0f;
        mMagnificationSettingsController.setMagnificationScale(scale);

        verify(mWindowMagnificationSettings).setMagnificationScale(eq(scale));
    }

    @Test
    public void testOnConfigurationChanged_notifySettingsPanel() {
        mMagnificationSettingsController.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);

        verify(mWindowMagnificationSettings).onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);
    }

    @Test
    public void testPanelOnSetDiagonalScrolling_delegateToCallback() {
        final boolean enable = true;
        mMagnificationSettingsController.mWindowMagnificationSettingsCallback
                .onSetDiagonalScrolling(enable);

        verify(mMagnificationSettingControllerCallback).onSetDiagonalScrolling(
                eq(mContext.getDisplayId()), eq(enable));
    }

    @Test
    public void testPanelOnModeSwitch_delegateToCallback() {
        final int newMode = ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
        mMagnificationSettingsController.mWindowMagnificationSettingsCallback
                .onModeSwitch(newMode);

        verify(mMagnificationSettingControllerCallback).onModeSwitch(
                eq(mContext.getDisplayId()), eq(newMode));
    }

    @Test
    public void testPanelOnSettingsPanelVisibilityChanged_delegateToCallback() {
        final boolean shown = true;
        mMagnificationSettingsController.mWindowMagnificationSettingsCallback
                .onSettingsPanelVisibilityChanged(shown);

        verify(mMagnificationSettingControllerCallback).onSettingsPanelVisibilityChanged(
                eq(mContext.getDisplayId()), eq(shown));
    }

    @Test
    public void testPanelOnSetMagnifierSize_delegateToCallback() {
        final @MagnificationSize int index = MagnificationSize.SMALL;
        mMagnificationSettingsController.mWindowMagnificationSettingsCallback
                .onSetMagnifierSize(index);

        verify(mMagnificationSettingControllerCallback).onSetMagnifierSize(
                eq(mContext.getDisplayId()), eq(index));
    }

    @Test
    public void testPanelOnEditMagnifierSizeMode_delegateToCallback() {
        final boolean enable = true;
        mMagnificationSettingsController.mWindowMagnificationSettingsCallback
                .onEditMagnifierSizeMode(enable);

        verify(mMagnificationSettingControllerCallback).onEditMagnifierSizeMode(
                eq(mContext.getDisplayId()), eq(enable));
    }

    @Test
    public void testPanelOnMagnifierScale_delegateToCallback() {
        final float scale = 3.0f;
        final boolean updatePersistence = true;
        mMagnificationSettingsController.mWindowMagnificationSettingsCallback
                .onMagnifierScale(scale, updatePersistence);

        verify(mMagnificationSettingControllerCallback).onMagnifierScale(
                eq(mContext.getDisplayId()), eq(scale), eq(updatePersistence));
    }
}
