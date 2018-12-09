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
 * limitations under the License
 */

package com.android.keyguard;

import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;

@SmallTest
@RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class KeyguardStatusViewTest extends SysuiTestCase {

    @Mock
    KeyguardSliceView mKeyguardSlice;
    @Mock
    KeyguardClockSwitch mClockView;
    @InjectMocks
    KeyguardStatusView mKeyguardStatusView;

    @Before
    public void setUp() {
        Assert.sMainLooper = TestableLooper.get(this).getLooper();
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        mKeyguardStatusView =
                (KeyguardStatusView) layoutInflater.inflate(R.layout.keyguard_status_view, null);
        org.mockito.MockitoAnnotations.initMocks(this);
    }

    @Test
    public void dozeTimeTick_updatesSlice() {
        mKeyguardStatusView.dozeTimeTick();
        verify(mKeyguardSlice).refresh();
    }

    @Test
    public void dozeTimeTick_updatesClock() {
        mKeyguardStatusView.dozeTimeTick();
        verify(mClockView).refresh();
    }

}
