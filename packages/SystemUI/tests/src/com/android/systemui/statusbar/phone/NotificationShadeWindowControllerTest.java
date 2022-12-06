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

package com.android.systemui.statusbar.phone;

import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NotificationShadeWindowControllerTest extends SysuiTestCase {

    @Mock private WindowManager mWindowManager;
    @Mock private DozeParameters mDozeParameters;
    @Mock private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock private IActivityManager mActivityManager;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private SysuiColorExtractor mColorExtractor;
    @Mock ColorExtractor.GradientColors mGradientColors;
    @Mock private DumpManager mDumpManager;
    @Captor private ArgumentCaptor<WindowManager.LayoutParams> mLayoutParameters;

    private NotificationShadeWindowController mNotificationShadeWindowController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);

        mNotificationShadeWindowController = new NotificationShadeWindowController(mContext,
                mWindowManager, mActivityManager, mDozeParameters, mStatusBarStateController,
                mConfigurationController, mKeyguardViewMediator, mKeyguardBypassController,
                mColorExtractor, mDumpManager) {
                    @Override
                    protected boolean isDebuggable() {
                        return false;
                    }
            };
        mNotificationShadeWindowController.setNotificationShadeView(mNotificationShadeWindowView);

        mNotificationShadeWindowController.attach();
    }

    @Test
    public void testSetDozing_hidesSystemOverlays() {
        mNotificationShadeWindowController.setDozing(true);
        ArgumentCaptor<WindowManager.LayoutParams> captor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mWindowManager).updateViewLayout(any(), captor.capture());
        int flag = captor.getValue().privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        assertThat(flag).isNotEqualTo(0);

        reset(mWindowManager);
        mNotificationShadeWindowController.setDozing(false);
        verify(mWindowManager).updateViewLayout(any(), captor.capture());
        flag = captor.getValue().privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        assertThat(flag).isEqualTo(0);
    }

    @Test
    public void testOnThemeChanged_doesntCrash() {
        mNotificationShadeWindowController.onThemeChanged();
    }

    @Test
    public void testAdd_updatesVisibilityFlags() {
        verify(mNotificationShadeWindowView).setSystemUiVisibility(anyInt());
    }

    @Test
    public void testSetForcePluginOpen_beforeStatusBarInitialization() {
        mNotificationShadeWindowController.setForcePluginOpen(true);
    }

    @Test
    public void attach_visibleWithWallpaper() {
        clearInvocations(mWindowManager);
        when(mKeyguardViewMediator.isShowingAndNotOccluded()).thenReturn(true);
        mNotificationShadeWindowController.attach();

        verify(mNotificationShadeWindowView).setVisibility(eq(View.VISIBLE));
        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SHOW_WALLPAPER) != 0).isTrue();
    }

    @Test
    public void setBackgroundBlurRadius_expandedWithBlurs() {
        mNotificationShadeWindowController.setBackgroundBlurRadius(10);
        verify(mNotificationShadeWindowView).setVisibility(eq(View.VISIBLE));

        mNotificationShadeWindowController.setBackgroundBlurRadius(0);
        verify(mNotificationShadeWindowView).setVisibility(eq(View.INVISIBLE));
    }

    @Test
    public void setBouncerShowing_isFocusable_whenNeedsInput() {
        mNotificationShadeWindowController.setKeyguardNeedsInput(true);
        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setBouncerShowing(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) == 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) == 0).isTrue();
    }

    @Test
    public void setKeyguardShowing_focusable_notAltFocusable_whenNeedsInput() {
        mNotificationShadeWindowController.setKeyguardShowing(true);
        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setKeyguardNeedsInput(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) == 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) == 0).isTrue();
    }

    @Test
    public void setPanelExpanded_notFocusable_altFocusable_whenPanelIsOpen() {
        mNotificationShadeWindowController.setPanelExpanded(true);
        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setNotificationShadeFocusable(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) == 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) != 0).isTrue();
    }

    @Test
    public void setKeyguardShowing_notFocusable_byDefault() {
        mNotificationShadeWindowController.setKeyguardShowing(false);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) != 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) == 0).isTrue();
    }

    @Test
    public void setKeyguardShowing_enablesSecureFlag() {
        mNotificationShadeWindowController.setBouncerShowing(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SECURE) != 0).isTrue();
    }

    @Test
    public void setKeyguardNotShowing_disablesSecureFlag() {
        mNotificationShadeWindowController.setBouncerShowing(false);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SECURE) == 0).isTrue();
    }
}
