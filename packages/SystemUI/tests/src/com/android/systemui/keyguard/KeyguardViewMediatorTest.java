/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static android.view.WindowManagerPolicyConstants.OFF_BECAUSE_OF_USER;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class KeyguardViewMediatorTest extends SysuiTestCase {
    private KeyguardViewMediator mViewMediator;

    private @Mock DevicePolicyManager mDevicePolicyManager;
    private @Mock LockPatternUtils mLockPatternUtils;
    private @Mock KeyguardUpdateMonitor mUpdateMonitor;
    private @Mock StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private @Mock StatusBarWindowController mStatusBarWindowController;
    private @Mock BroadcastDispatcher mBroadcastDispatcher;
    private @Mock DismissCallbackRegistry mDismissCallbackRegistry;

    private FalsingManagerFake mFalsingManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFalsingManager = new FalsingManagerFake();

        mDependency.injectTestDependency(FalsingManager.class, mFalsingManager);
        mDependency.injectTestDependency(KeyguardUpdateMonitor.class, mUpdateMonitor);

        when(mLockPatternUtils.getDevicePolicyManager()).thenReturn(mDevicePolicyManager);

        TestableLooper.get(this).runWithLooper(() -> {
            mViewMediator = new KeyguardViewMediator(
                    mContext, mFalsingManager, mLockPatternUtils, mBroadcastDispatcher,
                    mStatusBarWindowController, () -> mStatusBarKeyguardViewManager,
                    mDismissCallbackRegistry);
        });
    }

    @Test
    public void testOnGoingToSleep_UpdatesKeyguardGoingAway() {
        mViewMediator.start();
        mViewMediator.onStartedGoingToSleep(OFF_BECAUSE_OF_USER);
        verify(mUpdateMonitor).setKeyguardGoingAway(false);
        verify(mStatusBarWindowController, never()).setKeyguardGoingAway(anyBoolean());
    }
}
