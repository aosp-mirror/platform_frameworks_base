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

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.verify;

import android.os.SystemProperties;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
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

    CommandQueue mCommandQueue;
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    OneHandedUI mOneHandedUI;
    ScreenLifecycle mScreenLifecycle;
    @Mock
    OneHandedController mOneHandedController;
    @Mock
    OneHandedTimeoutHandler mMockTimeoutHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mCommandQueue = new CommandQueue(mContext);
        mScreenLifecycle = new ScreenLifecycle();
        mOneHandedUI = new OneHandedUI(mContext,
                mCommandQueue,
                mOneHandedController,
                mScreenLifecycle);
        mOneHandedUI.start();
        mKeyguardUpdateMonitor = mDependency.injectMockDependency(KeyguardUpdateMonitor.class);
    }

    @Before
    public void assumeOneHandedModeSupported() {
        assumeTrue(SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false));
    }

    @Test
    public void testStartOneHanded() {
        mOneHandedUI.startOneHanded();

        verify(mOneHandedController).startOneHanded();
    }

    @Test
    public void testStopOneHanded() {
        mOneHandedUI.stopOneHanded();

        verify(mOneHandedController).stopOneHanded();
    }

    @Test
    public void tesSettingsObserver_updateTapAppToExit() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.TAPS_APP_TO_EXIT, 1);

        verify(mOneHandedController).setTaskChangeToExit(true);
    }

    @Test
    public void tesSettingsObserver_updateEnabled() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 1);

        verify(mOneHandedController).setOneHandedEnabled(true);
    }

    @Test
    public void tesSettingsObserver_updateTimeout() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);

        verify(mMockTimeoutHandler).setTimeout(
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
    }

    @Test
    public void tesSettingsObserver_updateSwipeToNotification() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED, 1);

        verify(mOneHandedController).setSwipeToNotificationEnabled(true);
    }

    @Ignore("Clarifying do not receive callback")
    @Test
    public void testKeyguardBouncerShowing_shouldStopOneHanded() {
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true);

        verify(mOneHandedController).stopOneHanded();
    }

    @Test
    public void testScreenTurningOff_shouldStopOneHanded() {
        mScreenLifecycle.dispatchScreenTurningOff();

        verify(mOneHandedController).stopOneHanded();
    }

}
