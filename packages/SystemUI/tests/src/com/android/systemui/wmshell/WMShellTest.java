/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.wmshell;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.onehanded.OneHanded;
import com.android.systemui.onehanded.OneHandedGestureHandler;
import com.android.systemui.onehanded.OneHandedTransitionCallback;
import com.android.systemui.pip.Pip;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.tracing.ProtoTracer;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.splitscreen.SplitScreen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WMShellTest extends SysuiTestCase {

    WMShell mWMShell;
    @Mock CommandQueue mCommandQueue;
    @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock ActivityManagerWrapper mActivityManagerWrapper;
    @Mock DisplayImeController mDisplayImeController;
    @Mock NavigationModeController mNavigationModeController;
    @Mock ScreenLifecycle mScreenLifecycle;
    @Mock SysUiState mSysUiState;
    @Mock Pip mPip;
    @Mock SplitScreen mSplitScreen;
    @Mock OneHanded mOneHanded;
    @Mock ProtoTracer mProtoTracer;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mWMShell = new WMShell(mContext, mCommandQueue, mKeyguardUpdateMonitor,
                mActivityManagerWrapper, mDisplayImeController, mNavigationModeController,
                mScreenLifecycle, mSysUiState, Optional.of(mPip), Optional.of(mSplitScreen),
                Optional.of(mOneHanded), mProtoTracer);
    }

    @Test
    public void start_startsMonitorDisplays() {
        mWMShell.start();

        verify(mDisplayImeController).startMonitorDisplays();
    }

    @Test
    public void initPip_registersCommandQueueCallback() {
        mWMShell.initPip(mPip);

        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks.class));
    }

    @Test
    public void initSplitScreen_registersCallbacks() {
        mWMShell.initSplitScreen(mSplitScreen);

        verify(mKeyguardUpdateMonitor).registerCallback(any(KeyguardUpdateMonitorCallback.class));
        verify(mActivityManagerWrapper).registerTaskStackListener(
                any(TaskStackChangeListener.class));
    }

    @Test
    public void initOneHanded_registersCallbacks() {
        when(mOneHanded.hasOneHandedFeature()).thenReturn(true);
        mWMShell.initOneHanded(mOneHanded);

        verify(mKeyguardUpdateMonitor).registerCallback(any(KeyguardUpdateMonitorCallback.class));
        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks.class));
        verify(mScreenLifecycle).addObserver(any(ScreenLifecycle.Observer.class));
        verify(mNavigationModeController).addListener(
                any(NavigationModeController.ModeChangedListener.class));
        verify(mActivityManagerWrapper).registerTaskStackListener(
                any(TaskStackChangeListener.class));

        verify(mOneHanded).registerGestureCallback(any(
                OneHandedGestureHandler.OneHandedGestureEventCallback.class));
        verify(mOneHanded).registerTransitionCallback(any(OneHandedTransitionCallback.class));
    }
}
