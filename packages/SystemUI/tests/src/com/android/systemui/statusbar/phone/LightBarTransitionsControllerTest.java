/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.LightBarTransitionsController.DarkIntensityApplier;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class LightBarTransitionsControllerTest extends SysuiTestCase {

    @Mock
    private DarkIntensityApplier mApplier;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private StatusBarStateController mStatusBarStateController;

    private LightBarTransitionsController mLightBarTransitionsController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLightBarTransitionsController = new LightBarTransitionsController(mContext, mApplier,
                new CommandQueue(mContext), mKeyguardStateController, mStatusBarStateController);
    }

    @Test
    public void setIconsDark_lightAndDark() {
        mLightBarTransitionsController.setIconsDark(true /* dark */, false /* animate */);
        verify(mApplier).applyDarkIntensity(eq(1f));

        mLightBarTransitionsController.setIconsDark(false /* dark */, false /* animate */);
        verify(mApplier).applyDarkIntensity(eq(0f));
    }

    @Test
    public void onDozeAmountChanged_lightWhenDozing() {
        mLightBarTransitionsController.onDozeAmountChanged(1f /* linear */, 1f /* eased */);
        mLightBarTransitionsController.setIconsDark(true /* dark */, false /* animate */);
        verify(mApplier, times(2)).applyDarkIntensity(eq(0f));

        reset(mApplier);
        mLightBarTransitionsController.setIconsDark(false /* dark */, false /* animate */);
        verify(mApplier).applyDarkIntensity(eq(0f));
    }

}
