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

import android.os.SystemProperties;
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
    private static final String SUPPORT_ONE_HANDED_MODE = "ro.support_one_handed_mode";

    boolean mIsSupportOneHandedMode;
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
        mIsSupportOneHandedMode = SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false);
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
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        mOneHandedUI.startOneHanded();

        verify(mMockOneHandedManagerImpl, times(1)).startOneHanded();
    }

    @Test
    public void testStopOneHanded() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        mOneHandedUI.stopOneHanded();

        verify(mMockOneHandedManagerImpl, times(1)).stopOneHanded();
    }

    @Test
    public void testRegisterSettingsObserver_forEnabled() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        final String key = Settings.Secure.ONE_HANDED_MODE_ENABLED;

        verify(mMockSettingsUtil, times(1)).registerSettingsKeyObserver(key, any(), any());
    }

    @Test
    public void testRegisterSettingsObserver_forTimeout() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        final String key = Settings.Secure.ONE_HANDED_MODE_TIMEOUT;

        verify(mMockSettingsUtil, times(1)).registerSettingsKeyObserver(key, any(), any());
    }

    @Test
    public void testRegisterSettingsObserver_forTapAppExit() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        final String key = Settings.Secure.TAPS_APP_TO_EXIT;

        verify(mMockSettingsUtil, times(1)).registerSettingsKeyObserver(key, any(), any());
    }

    @Test
    public void tesSettingsObserver_updateTapAppToExit() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.TAPS_APP_TO_EXIT, 1);

        verify(mMockOneHandedManagerImpl, times(1)).setTaskChangeToExit(true);
    }

    @Test
    public void tesSettingsObserver_updateEnabled() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 1);

        verify(mMockOneHandedManagerImpl, times(1)).setOneHandedEnabled(true);
    }

    @Test
    public void tesSettingsObserver_updateTimeout() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);

        verify(mMockTimeoutHandler).setTimeout(
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
    }

    @Ignore("Clarifying do not receive callback")
    @Test
    public void testKeyguardBouncerShowing_shouldStopOneHanded() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true);

        verify(mMockOneHandedManagerImpl, times(1)).stopOneHanded();
    }

    @Test
    public void testScreenTurningOff_shouldStopOneHanded() {
        // Bypass test if device not support one-handed mode
        if (!mIsSupportOneHandedMode) {
            return;
        }
        mScreenLifecycle.dispatchScreenTurningOff();

        verify(mMockOneHandedManagerImpl, times(1)).stopOneHanded();
    }

}
