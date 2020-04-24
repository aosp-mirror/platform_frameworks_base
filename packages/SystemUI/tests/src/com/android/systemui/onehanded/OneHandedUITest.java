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

package com.android.systemui.onehanded;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedUITest extends OneHandedTestCase {
    CommandQueue mCommandQueue;
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    OneHandedUI mOneHandedUI;
    ScreenLifecycle mScreenLifecycle;
    @Mock
    OneHandedManagerImpl mMockOneHandedManagerImpl;
    @Mock
    DumpManager mMockDumpManager;
    @Mock
    OneHandedSettingsUtil mMockSettingsUtil;
    @Mock
    OneHandedTimeoutHandler mMockTimeoutHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mCommandQueue = new CommandQueue(mContext);
        mScreenLifecycle = new ScreenLifecycle();
        mOneHandedUI = new OneHandedUI(mContext,
                mCommandQueue,
                mMockOneHandedManagerImpl,
                mMockDumpManager,
                mMockSettingsUtil,
                mScreenLifecycle);
        mOneHandedUI.start();
        mKeyguardUpdateMonitor = mDependency.injectMockDependency(KeyguardUpdateMonitor.class);
    }

    @Test
    public void testStartOneHanded() {
        mOneHandedUI.startOneHanded();

        verify(mMockOneHandedManagerImpl, times(1)).startOneHanded();
    }

    @Test
    public void testStopOneHanded() {
        mOneHandedUI.stopOneHanded();

        verify(mMockOneHandedManagerImpl, times(1)).stopOneHanded();
    }

    @Test
    public void testRegisterSettingsObserver_forEnabled() {
        final String key = Settings.Secure.ONE_HANDED_MODE_ENABLED;

        verify(mMockSettingsUtil, times(1)).registerSettingsKeyObserver(key, any(), any());
    }

    @Test
    public void testRegisterSettingsObserver_forTimeout() {
        final String key = Settings.Secure.ONE_HANDED_MODE_TIMEOUT;

        verify(mMockSettingsUtil, times(1)).registerSettingsKeyObserver(key, any(), any());
    }

    @Test
    public void testRegisterSettingsObserver_forTapAppExit() {
        final String key = Settings.Secure.TAPS_APP_TO_EXIT;

        verify(mMockSettingsUtil, times(1)).registerSettingsKeyObserver(key, any(), any());
    }

    @Test
    public void tesSettingsObserver_updateEnabled() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 1);

        verify(mMockOneHandedManagerImpl, times(1)).setOneHandedEnabled(true);
    }

    @Test
    public void tesSettingsObserver_updateTimeout() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);

        verify(mMockTimeoutHandler).setTimeout(
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
    }

    @Ignore("Clarifying do not receive callback")
    @Test
    public void testKeyguardBouncerShowing_shouldStopOneHanded() {
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true);

        verify(mMockOneHandedManagerImpl, times(1)).stopOneHanded();
    }

    @Test
    public void testScreenTurningOff_shouldStopOneHanded() {
        mScreenLifecycle.dispatchScreenTurningOff();

        verify(mMockOneHandedManagerImpl, times(1)).stopOneHanded();
    }

}
