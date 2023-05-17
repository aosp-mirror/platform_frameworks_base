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

package com.android.server.policy;

import static android.view.KeyEvent.KEYCODE_STEM_PRIMARY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import android.content.Context;
import android.content.res.Resources;

import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

/**
 * Test class for stem key gesture.
 *
 * Build/Install/Run:
 * atest WmTests:StemKeyGestureTests
 */
public class StemKeyGestureTests extends ShortcutKeyTestBase {
    @Mock private Resources mResources;

    /**
     * Stem single key should not launch behavior during set up.
     */
    @Test
    public void stemSingleKey_duringSetup_doNothing() {
        stemKeySetup(
                () -> overrideBehavior(
                        com.android.internal.R.integer.config_shortPressOnStemPrimaryBehavior,
                        SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS));
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(false);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertNotOpenAllAppView();
    }

    /**
     * Stem single key should launch all app after set up.
     */
    @Test
    public void stemSingleKey_AfterSetup_openAllApp() {
        stemKeySetup(
                () -> overrideBehavior(
                        com.android.internal.R.integer.config_shortPressOnStemPrimaryBehavior,
                        SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS));
        mPhoneWindowManager.setKeyguardServiceDelegateIsShowing(false);
        mPhoneWindowManager.overrideIsUserSetupComplete(true);

        sendKey(KEYCODE_STEM_PRIMARY);

        mPhoneWindowManager.assertOpenAllAppView();
    }

    private void stemKeySetup(Runnable behaviorOverrideRunnable) {
        super.tearDown();
        setupResourcesMock();
        behaviorOverrideRunnable.run();
        super.setUp();
    }

    private void setupResourcesMock() {
        Resources realResources = mContext.getResources();

        mResources = Mockito.mock(Resources.class);
        doReturn(mResources).when(mContext).getResources();

        doAnswer(invocation -> realResources.getXml((Integer) invocation.getArguments()[0]))
                .when(mResources).getXml(anyInt());
        doAnswer(invocation -> realResources.getString((Integer) invocation.getArguments()[0]))
                .when(mResources).getString(anyInt());
        doAnswer(invocation -> realResources.getBoolean((Integer) invocation.getArguments()[0]))
                .when(mResources).getBoolean(anyInt());
    }

    private void overrideBehavior(int resId, int expectedBehavior) {
        doReturn(expectedBehavior).when(mResources).getInteger(eq(resId));
    }
}
