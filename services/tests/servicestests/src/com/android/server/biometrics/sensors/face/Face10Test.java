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

package com.android.server.biometrics.sensors.face;

import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.LockoutResetDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class Face10Test {

    private static final String TAG = "Face10Test";
    private static final int SENSOR_ID = 1;

    @Mock
    private Context mContext;

    private LockoutResetDispatcher mLockoutResetDispatcher;
    private Face10 mFace10;
    private IBinder mBinder;

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mLockoutResetDispatcher = new LockoutResetDispatcher(mContext);
        mFace10 = new Face10(mContext, SENSOR_ID, BiometricManager.Authenticators.BIOMETRIC_STRONG,
                mLockoutResetDispatcher, false /* supportsSelfIllumination */,
                1 /* maxTemplatesAllowed */);
        mBinder = new Binder();
    }

    @Test
    public void scheduleRevokeChallenge_doesNotCrash() {
        mFace10.scheduleRevokeChallenge(0 /* sensorId */, 0 /* userId */, mBinder, TAG,
                0 /* challenge */);
        waitForIdle();
    }
}
