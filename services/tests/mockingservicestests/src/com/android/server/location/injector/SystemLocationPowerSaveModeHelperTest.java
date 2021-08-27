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
package com.android.server.location.injector;

import static android.os.PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
import static android.os.PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_NO_CHANGE;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.location.injector.LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemLocationPowerSaveModeHelperTest {

    private static final long TIMEOUT_MS = 5000;
    private static final long FAILURE_TIMEOUT_MS = 200;

    @Mock
    private PowerManagerInternal mPowerManagerInternal;

    private List<Consumer<PowerSaveState>> mListeners = new ArrayList<>();

    private SystemLocationPowerSaveModeHelper mHelper;

    @Before
    public void setUp() {
        initMocks(this);

        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternal);

        doAnswer(invocation -> mListeners.add(invocation.getArgument(1))).when(
                mPowerManagerInternal).registerLowPowerModeObserver(anyInt(), any(Consumer.class));

        PowerManager powerManager = mock(PowerManager.class);
        doReturn(LOCATION_MODE_NO_CHANGE).when(powerManager).getLocationPowerSaveMode();
        Context context = mock(Context.class);
        doReturn(powerManager).when(context).getSystemService(PowerManager.class);

        mHelper = new SystemLocationPowerSaveModeHelper(context);
        mHelper.onSystemReady();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    private void sendPowerSaveState(PowerSaveState powerSaveState) {
        for (Consumer<PowerSaveState> listener : mListeners) {
            listener.accept(powerSaveState);
        }
    }

    @Test
    public void testListener() {
        LocationPowerSaveModeChangedListener listener = mock(
                LocationPowerSaveModeChangedListener.class);
        mHelper.addListener(listener);

        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_NO_CHANGE).setBatterySaverEnabled(false).build());
        verify(listener, after(FAILURE_TIMEOUT_MS).never()).onLocationPowerSaveModeChanged(
                anyInt());
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_NO_CHANGE);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF).setBatterySaverEnabled(false).build());
        verify(listener, after(FAILURE_TIMEOUT_MS).never()).onLocationPowerSaveModeChanged(
                anyInt());
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_NO_CHANGE);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF).setBatterySaverEnabled(false).build());
        verify(listener, after(FAILURE_TIMEOUT_MS).never()).onLocationPowerSaveModeChanged(
                anyInt());
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_NO_CHANGE);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_FOREGROUND_ONLY).setBatterySaverEnabled(false).build());
        verify(listener, after(FAILURE_TIMEOUT_MS).never()).onLocationPowerSaveModeChanged(
                anyInt());
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_NO_CHANGE);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF).setBatterySaverEnabled(
                false).build());
        verify(listener, after(FAILURE_TIMEOUT_MS).never()).onLocationPowerSaveModeChanged(
                anyInt());
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_NO_CHANGE);

        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_NO_CHANGE).setBatterySaverEnabled(true).build());
        verify(listener, after(FAILURE_TIMEOUT_MS).never()).onLocationPowerSaveModeChanged(
                anyInt());
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_NO_CHANGE);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF).setBatterySaverEnabled(true).build());
        verify(listener, timeout(TIMEOUT_MS)).onLocationPowerSaveModeChanged(
                LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF);
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(
                LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF).setBatterySaverEnabled(true).build());
        verify(listener, timeout(TIMEOUT_MS)).onLocationPowerSaveModeChanged(
                LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(
                LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_FOREGROUND_ONLY).setBatterySaverEnabled(true).build());
        verify(listener, timeout(TIMEOUT_MS)).onLocationPowerSaveModeChanged(
                LOCATION_MODE_FOREGROUND_ONLY);
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_FOREGROUND_ONLY);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF).setBatterySaverEnabled(
                true).build());
        verify(listener, timeout(TIMEOUT_MS)).onLocationPowerSaveModeChanged(
                LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF);
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(
                LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF);
        sendPowerSaveState(new PowerSaveState.Builder().setLocationMode(
                LOCATION_MODE_NO_CHANGE).setBatterySaverEnabled(true).build());
        verify(listener, timeout(TIMEOUT_MS)).onLocationPowerSaveModeChanged(
                LOCATION_MODE_NO_CHANGE);
        assertThat(mHelper.getLocationPowerSaveMode()).isEqualTo(LOCATION_MODE_NO_CHANGE);
    }
}
