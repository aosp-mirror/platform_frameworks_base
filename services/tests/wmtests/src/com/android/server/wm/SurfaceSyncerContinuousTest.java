/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static android.server.wm.WindowManagerState.getLogicalDisplaySize;

import android.app.KeyguardManager;
import android.os.PowerManager;
import android.view.SurfaceControl;
import android.view.cts.surfacevalidator.CapturedActivity;
import android.window.SurfaceSyncer;

import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.Objects;

public class SurfaceSyncerContinuousTest {
    @Rule
    public TestName mName = new TestName();

    @Rule
    public ActivityTestRule<CapturedActivity> mActivityRule =
            new ActivityTestRule<>(CapturedActivity.class);

    public CapturedActivity mCapturedActivity;

    @Before
    public void setup() {
        mCapturedActivity = mActivityRule.getActivity();
        mCapturedActivity.setLogicalDisplaySize(getLogicalDisplaySize());

        final KeyguardManager km = mCapturedActivity.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked() || !Objects.requireNonNull(
                mCapturedActivity.getSystemService(PowerManager.class)).isInteractive()) {
            pressWakeupButton();
            pressUnlockButton();
        }
    }

    @Test
    public void testSurfaceViewSyncDuringResize() throws Throwable {
        SurfaceSyncer.setTransactionFactory(SurfaceControl.Transaction::new);
        mCapturedActivity.verifyTest(new SurfaceSyncerValidatorTestCase(), mName);
    }
}
