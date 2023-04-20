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
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.notetask.NoteTaskInitializer;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tracing.ProtoTracer;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.desktopmode.DesktopMode;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedEventCallback;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.sysui.ShellInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.concurrent.Executor;

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

    @Mock ShellInterface mShellInterface;
    @Mock CommandQueue mCommandQueue;
    @Mock ConfigurationController mConfigurationController;
    @Mock KeyguardStateController mKeyguardStateController;
    @Mock KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock ScreenLifecycle mScreenLifecycle;
    @Mock SysUiState mSysUiState;
    @Mock Pip mPip;
    @Mock SplitScreen mSplitScreen;
    @Mock OneHanded mOneHanded;
    @Mock WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock ProtoTracer mProtoTracer;
    @Mock UserTracker mUserTracker;
    @Mock ShellExecutor mSysUiMainExecutor;
    @Mock NoteTaskInitializer mNoteTaskInitializer;
    @Mock DesktopMode mDesktopMode;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mWMShell = new WMShell(
                mContext,
                mShellInterface,
                Optional.of(mPip),
                Optional.of(mSplitScreen),
                Optional.of(mOneHanded),
                Optional.of(mDesktopMode),
                mCommandQueue,
                mConfigurationController,
                mKeyguardStateController,
                mKeyguardUpdateMonitor,
                mScreenLifecycle,
                mSysUiState,
                mProtoTracer,
                mWakefulnessLifecycle,
                mUserTracker,
                mNoteTaskInitializer,
                mSysUiMainExecutor
        );
    }

    @Test
    public void initPip_registersCommandQueueCallback() {
        mWMShell.initPip(mPip);

        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks.class));
    }

    @Test
    public void initOneHanded_registersCallbacks() {
        mWMShell.initOneHanded(mOneHanded);

        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks.class));
        verify(mScreenLifecycle).addObserver(any(ScreenLifecycle.Observer.class));
        verify(mOneHanded).registerTransitionCallback(any(OneHandedTransitionCallback.class));
        verify(mOneHanded).registerEventCallback(any(OneHandedEventCallback.class));
    }

    @Test
    public void initDesktopMode_registersListener() {
        mWMShell.initDesktopMode(mDesktopMode);
        verify(mDesktopMode).addListener(
                any(DesktopModeTaskRepository.VisibleTasksListener.class),
                any(Executor.class));
    }
}
