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

package com.android.server.display;

import static android.hardware.display.DisplayManagerGlobal.EVENT_DISPLAY_CONNECTED;
import static android.view.Display.TYPE_EXTERNAL;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.RemoteException;
import android.os.Temperature;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.server.display.DisplayManagerService.SyncRoot;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.notifications.DisplayNotificationManager;
import com.android.server.testutils.TestHandler;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.Consumer;


/**
 * Tests for {@link ExternalDisplayPolicy}
 * Run: atest ExternalDisplayPolicyTest
 */
@SmallTest
@RunWith(TestParameterInjector.class)
public class ExternalDisplayPolicyTest {
    private static final int EXTERNAL_DISPLAY_ID = 1;
    private static final Temperature MODERATE_TEMPERATURE = new Temperature(/*value=*/ 40.5f,
        /*type=*/ Temperature.TYPE_SKIN,
        /*name=*/ "Test",
        /*status=*/ Temperature.THROTTLING_MODERATE);
    private static final Temperature SEVERE_TEMPERATURE = new Temperature(/*value=*/ 50.5f,
        /*type=*/ Temperature.TYPE_SKIN,
        /*name=*/ "Test",
        /*status=*/ Temperature.THROTTLING_SEVERE);
    private static final Temperature CRITICAL_TEMPERATURE = new Temperature(/*value=*/ 70.5f,
            /*type=*/ Temperature.TYPE_SKIN,
            /*name=*/ "Test",
            /*status=*/ Temperature.THROTTLING_CRITICAL);
    private static final Temperature EMERGENCY_TEMPERATURE = new Temperature(/*value=*/ 80.5f,
            /*type=*/ Temperature.TYPE_SKIN,
            /*name=*/ "Test",
            /*status=*/ Temperature.THROTTLING_EMERGENCY);
    @Mock
    private ExternalDisplayPolicy.Injector mMockedInjector;
    @Mock
    private DisplayManagerFlags mMockedFlags;
    @Mock
    private LogicalDisplayMapper mMockedLogicalDisplayMapper;
    @Mock
    private IThermalService mMockedThermalService;
    @Mock
    private SyncRoot mMockedSyncRoot;
    @Mock
    private LogicalDisplay mMockedLogicalDisplay;
    @Mock
    private DisplayNotificationManager mMockedDisplayNotificationManager;
    @Captor
    private ArgumentCaptor<IThermalEventListener> mThermalEventListenerCaptor;
    @Captor
    private ArgumentCaptor<Integer> mThermalEventTypeCaptor;
    @Captor
    private ArgumentCaptor<Consumer<LogicalDisplay>> mLogicalDisplayConsumerCaptor;
    @Captor
    private ArgumentCaptor<Boolean> mIsEnabledCaptor;
    @Captor
    private ArgumentCaptor<LogicalDisplay> mLogicalDisplayCaptor;
    @Captor
    private ArgumentCaptor<Integer> mDisplayEventCaptor;
    private ExternalDisplayPolicy mExternalDisplayPolicy;
    private TestHandler mHandler;

    /** Setup tests. */
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHandler = new TestHandler(/*callback=*/ null);
        when(mMockedFlags.isConnectedDisplayManagementEnabled()).thenReturn(true);
        when(mMockedFlags.isConnectedDisplayErrorHandlingEnabled()).thenReturn(true);
        when(mMockedInjector.getFlags()).thenReturn(mMockedFlags);
        when(mMockedInjector.getLogicalDisplayMapper()).thenReturn(mMockedLogicalDisplayMapper);
        when(mMockedInjector.getThermalService()).thenReturn(mMockedThermalService);
        when(mMockedInjector.getSyncRoot()).thenReturn(mMockedSyncRoot);
        when(mMockedInjector.getDisplayNotificationManager()).thenReturn(
                mMockedDisplayNotificationManager);
        when(mMockedInjector.getHandler()).thenReturn(mHandler);
        mExternalDisplayPolicy = new ExternalDisplayPolicy(mMockedInjector);

        // Initialize mocked logical display
        when(mMockedLogicalDisplay.getDisplayIdLocked()).thenReturn(EXTERNAL_DISPLAY_ID);
        when(mMockedLogicalDisplay.isEnabledLocked()).thenReturn(true);
        final var mockedLogicalDisplayInfo = new DisplayInfo();
        mockedLogicalDisplayInfo.type = TYPE_EXTERNAL;
        when(mMockedLogicalDisplay.getDisplayInfoLocked()).thenReturn(mockedLogicalDisplayInfo);
        when(mMockedLogicalDisplayMapper.getDisplayLocked(EXTERNAL_DISPLAY_ID)).thenReturn(
                mMockedLogicalDisplay);
    }

    @Test
    public void testTryEnableExternalDisplay_criticalThermalCondition() throws RemoteException {
        // Disallow external displays due to thermals.
        setTemperature(registerThermalListener(), List.of(CRITICAL_TEMPERATURE));
        assertIsExternalDisplayAllowed(/*enabled=*/ false);
        assertDisplaySetEnabled(/*enabled=*/ false);

        // Check that display can not be enabled with tryEnableExternalDisplay.
        mExternalDisplayPolicy.setExternalDisplayEnabledLocked(mMockedLogicalDisplay,
                /*enabled=*/ true);
        mHandler.flush();
        verify(mMockedLogicalDisplayMapper, never()).setDisplayEnabledLocked(any(), anyBoolean());
        verify(mMockedDisplayNotificationManager, times(2))
                .onHighTemperatureExternalDisplayNotAllowed();
    }

    @Test
    public void testTryEnableExternalDisplay_featureDisabled(@TestParameter final boolean enable) {
        when(mMockedFlags.isConnectedDisplayManagementEnabled()).thenReturn(false);
        mExternalDisplayPolicy.setExternalDisplayEnabledLocked(mMockedLogicalDisplay, enable);
        mHandler.flush();
        verify(mMockedLogicalDisplayMapper, never()).setDisplayEnabledLocked(any(), anyBoolean());
        verify(mMockedDisplayNotificationManager, never())
                .onHighTemperatureExternalDisplayNotAllowed();
    }

    @Test
    public void testTryDisableExternalDisplay_criticalThermalCondition() throws RemoteException {
        // Disallow external displays due to thermals.
        setTemperature(registerThermalListener(), List.of(CRITICAL_TEMPERATURE));
        assertIsExternalDisplayAllowed(/*enabled=*/ false);
        assertDisplaySetEnabled(/*enabled=*/ false);

        // Check that display can be disabled with tryEnableExternalDisplay.
        mExternalDisplayPolicy.setExternalDisplayEnabledLocked(mMockedLogicalDisplay,
                /*enabled=*/ false);
        mHandler.flush();
        assertDisplaySetEnabled(/*enabled=*/ false);
        // Expected only 1 invocation, upon critical temperature.
        verify(mMockedDisplayNotificationManager).onHighTemperatureExternalDisplayNotAllowed();
    }

    @Test
    public void testSetEnabledExternalDisplay(@TestParameter final boolean enable) {
        mExternalDisplayPolicy.setExternalDisplayEnabledLocked(mMockedLogicalDisplay, enable);
        assertDisplaySetEnabled(enable);
    }

    @Test
    public void testOnExternalDisplayAvailable() {
        when(mMockedLogicalDisplay.isEnabledLocked()).thenReturn(false);
        mExternalDisplayPolicy.handleExternalDisplayConnectedLocked(mMockedLogicalDisplay);
        assertAskedToEnableDisplay();
    }

    @Test
    public void testOnExternalDisplayAvailable_criticalThermalCondition()
            throws RemoteException {
        // Disallow external displays due to thermals.
        setTemperature(registerThermalListener(), List.of(CRITICAL_TEMPERATURE));
        assertIsExternalDisplayAllowed(/*enabled=*/ false);
        assertDisplaySetEnabled(/*enabled=*/ false);

        when(mMockedLogicalDisplay.isEnabledLocked()).thenReturn(false);
        mExternalDisplayPolicy.handleExternalDisplayConnectedLocked(mMockedLogicalDisplay);
        verify(mMockedInjector, never()).sendExternalDisplayEventLocked(any(), anyInt());
        verify(mMockedDisplayNotificationManager, times(2))
                .onHighTemperatureExternalDisplayNotAllowed();
    }

    @Test
    public void testNoThermalListenerRegistered_featureDisabled(
            @TestParameter final boolean isConnectedDisplayManagementEnabled,
            @TestParameter final boolean isErrorHandlingEnabled) throws RemoteException {
        assumeFalse(isConnectedDisplayManagementEnabled && isErrorHandlingEnabled);
        when(mMockedFlags.isConnectedDisplayManagementEnabled()).thenReturn(
                isConnectedDisplayManagementEnabled);
        when(mMockedFlags.isConnectedDisplayErrorHandlingEnabled()).thenReturn(
                isErrorHandlingEnabled);

        mExternalDisplayPolicy.onBootCompleted();
        verify(mMockedThermalService, never()).registerThermalEventListenerWithType(
                any(), anyInt());
    }

    @Test
    public void testOnCriticalTemperature_disallowAndAllowExternalDisplay() throws RemoteException {
        final var thermalListener = registerThermalListener();

        setTemperature(thermalListener, List.of(CRITICAL_TEMPERATURE, EMERGENCY_TEMPERATURE));
        assertIsExternalDisplayAllowed(/*enabled=*/ false);
        assertDisplaySetEnabled(false);

        thermalListener.notifyThrottling(SEVERE_TEMPERATURE);
        thermalListener.notifyThrottling(MODERATE_TEMPERATURE);
        assertIsExternalDisplayAllowed(/*enabled=*/ true);
        verify(mMockedLogicalDisplayMapper, never()).forEachLocked(any());
    }

    private void setTemperature(final IThermalEventListener thermalEventListener,
            final List<Temperature> temperature) throws RemoteException {
        for (var t : temperature) {
            thermalEventListener.notifyThrottling(t);
        }
        verify(mMockedLogicalDisplayMapper).forEachLocked(mLogicalDisplayConsumerCaptor.capture());
        mLogicalDisplayConsumerCaptor.getValue().accept(mMockedLogicalDisplay);
    }

    private void assertDisplaySetEnabled(final boolean enabled) {
        // Check setDisplayEnabledLocked is triggered to disable display.
        verify(mMockedLogicalDisplayMapper).setDisplayEnabledLocked(
                mLogicalDisplayCaptor.capture(), mIsEnabledCaptor.capture());
        assertThat(mLogicalDisplayCaptor.getValue()).isEqualTo(mMockedLogicalDisplay);
        assertThat(mIsEnabledCaptor.getValue()).isEqualTo(enabled);
        clearInvocations(mMockedLogicalDisplayMapper);
        when(mMockedLogicalDisplay.isEnabledLocked()).thenReturn(enabled);
    }

    private void assertAskedToEnableDisplay() {
        // Check sendExternalDisplayEventLocked is triggered when display can be enabled.
        verify(mMockedInjector).sendExternalDisplayEventLocked(mLogicalDisplayCaptor.capture(),
                mDisplayEventCaptor.capture());
        assertThat(mLogicalDisplayCaptor.getValue()).isEqualTo(mMockedLogicalDisplay);
        assertThat(mDisplayEventCaptor.getValue()).isEqualTo(EVENT_DISPLAY_CONNECTED);
        clearInvocations(mMockedLogicalDisplayMapper);
        when(mMockedLogicalDisplay.isEnabledLocked()).thenReturn(true);
    }

    private void assertIsExternalDisplayAllowed(final boolean enabled) {
        assertThat(mExternalDisplayPolicy.isExternalDisplayAllowed()).isEqualTo(enabled);
    }

    private IThermalEventListener registerThermalListener() throws RemoteException {
        // Initialize and register thermal listener
        mExternalDisplayPolicy.onBootCompleted();
        verify(mMockedThermalService).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), mThermalEventTypeCaptor.capture());
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();
        assertThat(listener).isNotNull();
        assertThat(mThermalEventTypeCaptor.getValue()).isEqualTo(Temperature.TYPE_SKIN);
        return listener;
    }
}
