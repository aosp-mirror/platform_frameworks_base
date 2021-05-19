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

import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tracing.ProtoTracer;
import com.android.wm.shell.ShellCommandHandler;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.android.wm.shell.pip.Pip;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Tests for {@link WMShell}.
 *
 * Build/Install/Run:
 *  atest SystemUITests:WMShellTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WMShellTest extends SysuiTestCase {
    WMShell mWMShell;

    @Mock CommandQueue mCommandQueue;
    @Mock ConfigurationController mConfigurationController;
    @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock NavigationModeController mNavigationModeController;
    @Mock ScreenLifecycle mScreenLifecycle;
    @Mock SysUiState mSysUiState;
    @Mock Pip mPip;
    @Mock LegacySplitScreen mLegacySplitScreen;
    @Mock OneHanded mOneHanded;
    @Mock HideDisplayCutout mHideDisplayCutout;
    @Mock ProtoTracer mProtoTracer;
    @Mock ShellCommandHandler mShellCommandHandler;
    @Mock ShellExecutor mSysUiMainExecutor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mWMShell = new WMShell(mContext, Optional.of(mPip), Optional.of(mLegacySplitScreen),
                Optional.of(mOneHanded), Optional.of(mHideDisplayCutout),
                Optional.of(mShellCommandHandler), mCommandQueue, mConfigurationController,
                mKeyguardUpdateMonitor, mNavigationModeController,
                mScreenLifecycle, mSysUiState, mProtoTracer, mSysUiMainExecutor);
    }

    @Test
    public void initPip_registersCommandQueueCallback() {
        mWMShell.initPip(mPip);

        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks.class));
    }

    @Test
    public void initSplitScreen_registersCallbacks() {
        mWMShell.initSplitScreen(mLegacySplitScreen);

        verify(mKeyguardUpdateMonitor).registerCallback(any(KeyguardUpdateMonitorCallback.class));
    }

    @Test
    public void initOneHanded_registersCallbacks() {
        mWMShell.initOneHanded(mOneHanded);

        verify(mKeyguardUpdateMonitor).registerCallback(any(KeyguardUpdateMonitorCallback.class));
        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks.class));
        verify(mScreenLifecycle).addObserver(any(ScreenLifecycle.Observer.class));
        verify(mOneHanded).registerTransitionCallback(any(OneHandedTransitionCallback.class));
    }

    @Test
    public void initHideDisplayCutout_registersCallbacks() {
        mWMShell.initHideDisplayCutout(mHideDisplayCutout);

        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }
}
