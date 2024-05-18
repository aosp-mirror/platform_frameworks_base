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

package com.android.systemui.statusbar.phone;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class KeyguardDismissUtilTest extends SysuiTestCase {
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private ActivityStarter.OnDismissAction mAction;

    private KeyguardDismissUtil mKeyguardDismissUtil;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mKeyguardDismissUtil = new KeyguardDismissUtil(
                mKeyguardStateController, mStatusBarStateController, mActivityStarter);
    }

    @Test
    public void testSetLeaveOpenOnKeyguardHideWhenKeyGuardStateControllerIsShowing() {
        doReturn(true).when(mKeyguardStateController).isShowing();

        mKeyguardDismissUtil.executeWhenUnlocked(mAction, true /* requiresShadeOpen */,
                true /* afterKeyguardGone */);

        verify(mStatusBarStateController).setLeaveOpenOnKeyguardHide(true);

        verify(mActivityStarter).dismissKeyguardThenExecute(mAction, null, true);

    }

    @Test
    public void testSetLeaveOpenOnKeyguardHideWhenKeyGuardStateControllerIsNotShowing() {
        doReturn(false).when(mKeyguardStateController).isShowing();

        mKeyguardDismissUtil.executeWhenUnlocked(mAction, true /* requiresShadeOpen */,
                true /* afterKeyguardGone */);

        //no interaction with mStatusBarStateController
        verify(mStatusBarStateController, times(0)).setLeaveOpenOnKeyguardHide(true);

        verify(mActivityStarter).dismissKeyguardThenExecute(mAction, null, true);

    }
}
