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

package com.android.keyguard;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.media.AudioManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardHostViewControllerTest extends SysuiTestCase {

    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardHostView mKeyguardHostView;
    @Mock
    private KeyguardSecurityContainerController mKeyguardSecurityContainerController;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private ViewMediatorCallback mViewMediatorCallback;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private KeyguardHostViewController mKeyguardHostViewController;

    @Before
    public void setup() {
        mKeyguardHostViewController = new KeyguardHostViewController(
                mKeyguardHostView, mKeyguardUpdateMonitor, mKeyguardSecurityContainerController,
                mAudioManager, mTelephonyManager, mViewMediatorCallback);
    }

    @Test
    public void testHasDismissActions() {
        Assert.assertFalse("Action not set yet", mKeyguardHostViewController.hasDismissActions());
        mKeyguardHostViewController.setOnDismissAction(mock(OnDismissAction.class),
                null /* cancelAction */);
        Assert.assertTrue("Action should exist", mKeyguardHostViewController.hasDismissActions());
    }

    @Test
    public void testOnStartingToHide() {
        mKeyguardHostViewController.onStartingToHide();
        verify(mKeyguardSecurityContainerController).onStartingToHide();
    }
}
