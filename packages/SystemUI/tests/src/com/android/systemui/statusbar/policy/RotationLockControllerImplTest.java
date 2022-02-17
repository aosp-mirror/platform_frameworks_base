/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.view.RotationPolicy;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.wrapper.RotationPolicyWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class RotationLockControllerImplTest extends SysuiTestCase {

    private static final String[] DEFAULT_SETTINGS = new String[] {"0:0", "1:2"};

    @Mock RotationPolicyWrapper mRotationPolicyWrapper;
    @Mock DeviceStateRotationLockSettingController mDeviceStateRotationLockSettingController;

    private ArgumentCaptor<RotationPolicy.RotationPolicyListener> mRotationPolicyListenerCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */ this);

        mRotationPolicyListenerCaptor =
                ArgumentCaptor.forClass(RotationPolicy.RotationPolicyListener.class);
    }

    @Test
    public void whenFlagOff_doesntInteractWithDeviceStateRotationController() {
        createRotationLockController(new String[0]);

        verifyZeroInteractions(mDeviceStateRotationLockSettingController);
    }

    @Test
    public void whenFlagOn_setListeningSetsListeningOnDeviceStateRotationController() {
        createRotationLockController();

        verify(mDeviceStateRotationLockSettingController).setListening(/* listening= */ true);
    }

    @Test
    public void whenFlagOn_deviceStateRotationControllerAddedToCallbacks() {
        createRotationLockController();
        captureRotationPolicyListener().onChange();

        verify(mDeviceStateRotationLockSettingController)
                .onRotationLockStateChanged(anyBoolean(), anyBoolean());
    }

    private RotationPolicy.RotationPolicyListener captureRotationPolicyListener() {
        verify(mRotationPolicyWrapper)
                .registerRotationPolicyListener(mRotationPolicyListenerCaptor.capture(), anyInt());
        return mRotationPolicyListenerCaptor.getValue();
    }

    private void createRotationLockController() {
        createRotationLockController(DEFAULT_SETTINGS);
    }

    private void createRotationLockController(String[] deviceStateRotationLockDefaults) {
        new RotationLockControllerImpl(
                mRotationPolicyWrapper,
                mDeviceStateRotationLockSettingController,
                deviceStateRotationLockDefaults);
    }
}
