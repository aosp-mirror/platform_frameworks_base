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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static android.view.WindowManager.LayoutParams.FLAG_SECURE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

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
public class StatusBarWindowControllerTest extends SysuiTestCase {

    @Mock
    private WindowManager mWindowManager;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private ViewGroup mStatusBarView;
    @Mock
    private IActivityManager mActivityManager;
    @Captor
    private ArgumentCaptor<WindowManager.LayoutParams> mLayoutParameters;

    private StatusBarWindowController mStatusBarWindowController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);

        mStatusBarWindowController = new StatusBarWindowController(mContext, mWindowManager,
                mActivityManager, mDozeParameters) {
                    @Override
                    protected boolean isDebuggable() {
                        return false;
                    }
            };
        mStatusBarWindowController.add(mStatusBarView, 100 /* height */);
    }

    @Test
    public void testSetDozing_hidesSystemOverlays() {
        mStatusBarWindowController.setDozing(true);
        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        int flag = mLayoutParameters.getValue().privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        assertThat(flag).isNotEqualTo(0);

        reset(mWindowManager);
        mStatusBarWindowController.setDozing(false);
        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        flag = mLayoutParameters.getValue().privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        assertThat(flag).isEqualTo(0);
    }

    @Test
    public void testOnThemeChanged_doesntCrash() {
        mStatusBarWindowController = new StatusBarWindowController(mContext, mWindowManager,
                mActivityManager, mDozeParameters);
        mStatusBarWindowController.onThemeChanged();
    }

    @Test
    public void testAdd_updatesVisibilityFlags() {
        verify(mStatusBarView).setSystemUiVisibility(anyInt());
    }

    @Test
    public void testSetForcePluginOpen_beforeStatusBarInitialization() {
        mStatusBarWindowController = new StatusBarWindowController(mContext, mWindowManager,
                mActivityManager, mDozeParameters);
        mStatusBarWindowController.setForcePluginOpen(true);
    }

    @Test
    public void setKeyguardShowing_enablesSecureFlag() {
        mStatusBarWindowController.setBouncerShowing(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SECURE) != 0).isTrue();
    }

    @Test
    public void setKeyguardNotShowing_disablesSecureFlag() {
        mStatusBarWindowController.setBouncerShowing(false);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SECURE) == 0).isTrue();
    }
}
