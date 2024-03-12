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

package com.android.server.wm;

import static android.server.wm.UiDeviceUtils.pressUnlockButton;
import static android.server.wm.UiDeviceUtils.pressWakeupButton;

import android.app.KeyguardManager;
import android.os.PowerManager;
import android.view.cts.surfacevalidator.CapturedActivity;

import androidx.test.rule.ActivityTestRule;

import com.android.server.wm.utils.CommonUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.Objects;

public class SurfaceViewSyncContinuousTest {
    @Rule
    public TestName mName = new TestName();

    @Rule
    public ActivityTestRule<CapturedActivity> mActivityRule =
            new ActivityTestRule<>(CapturedActivity.class);

    public CapturedActivity mCapturedActivity;

    @Before
    public void setup() {
        mCapturedActivity = mActivityRule.getActivity();

        final KeyguardManager km = mCapturedActivity.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked() || !Objects.requireNonNull(
                mCapturedActivity.getSystemService(PowerManager.class)).isInteractive()) {
            pressWakeupButton();
            pressUnlockButton();
        }
    }

    @After
    public void tearDown() {
        CommonUtils.waitUntilActivityRemoved(mCapturedActivity);
    }

    @Test
    public void testSurfaceViewSyncDuringResize() throws Throwable {
        mCapturedActivity.verifyTest(new SurfaceViewSyncValidatorTestCase(), mName);
    }
}
