/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.keyguard;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class KeyguardMessageAreaTest extends SysuiTestCase {
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private KeyguardMessageArea mMessageArea;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mMessageArea = new KeyguardMessageArea(mContext, null, mKeyguardUpdateMonitor,
                mConfigurationController);
        waitForIdleSync();
    }

    @Test
    public void onAttachedToWindow_registersConfigurationCallback() {
        mMessageArea.onAttachedToWindow();
        verify(mConfigurationController).addCallback(eq(mMessageArea));

        mMessageArea.onDetachedFromWindow();
        verify(mConfigurationController).removeCallback(eq(mMessageArea));
    }

    @Test
    public void clearFollowedByMessage_keepsMessage() {
        mMessageArea.setMessage("");
        mMessageArea.setMessage("test");

        CharSequence[] messageText = new CharSequence[1];
        messageText[0] = mMessageArea.getText();

        assertEquals("test", messageText[0]);
    }

}
