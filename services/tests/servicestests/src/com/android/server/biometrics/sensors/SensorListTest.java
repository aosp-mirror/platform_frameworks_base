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

package com.android.server.biometrics.sensors;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.app.IActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.face.aidl.Sensor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class SensorListTest {
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    Sensor mSensor;
    @Mock
    IActivityManager mActivityManager;
    @Mock
    SynchronousUserSwitchObserver mUserSwitchObserver;

    SensorList<Sensor> mSensorList;

    @Before
    public void setUp() throws RemoteException {
        mSensorList = new SensorList<>(mActivityManager);
    }

    @Test
    public void testAddingSensor() throws RemoteException {
        mSensorList.addSensor(0, mSensor, UserHandle.USER_NULL, mUserSwitchObserver);

        verify(mUserSwitchObserver).onUserSwitching(UserHandle.USER_SYSTEM);
        verify(mActivityManager).registerUserSwitchObserver(eq(mUserSwitchObserver), anyString());
    }
}
