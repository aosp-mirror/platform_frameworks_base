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

package com.android.settingslib.devicestate;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.settingslib.devicestate.DeviceStateRotationLockSettingsManager.SettableDeviceState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DeviceStateRotationLockSettingsManagerTest {

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getTargetContext();
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getContentResolver()).thenReturn(context.getContentResolver());
    }

    @Test
    public void getSettableDeviceStates_returnsExpectedValuesInOriginalOrder() {
        when(mMockResources.getStringArray(
                R.array.config_perDeviceStateRotationLockDefaults)).thenReturn(
                new String[]{"2:2", "4:0", "1:1", "0:0"});

        List<SettableDeviceState> settableDeviceStates =
                DeviceStateRotationLockSettingsManager.getInstance(
                        mMockContext).getSettableDeviceStates();

        assertThat(settableDeviceStates).containsExactly(
                new SettableDeviceState(/* deviceState= */ 2, /* isSettable= */ true),
                new SettableDeviceState(/* deviceState= */ 4, /* isSettable= */ false),
                new SettableDeviceState(/* deviceState= */ 1, /* isSettable= */ true),
                new SettableDeviceState(/* deviceState= */ 0, /* isSettable= */ false)
        ).inOrder();
    }
}
