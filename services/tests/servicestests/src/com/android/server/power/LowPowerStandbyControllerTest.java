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

package com.android.server.power;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.content.Intent;
import android.content.res.Resources;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link com.android.server.power.LowPowerStandbyController}.
 *
 * Build/Install/Run:
 * atest LowPowerStandbyControllerTest
 */
public class LowPowerStandbyControllerTest {
    private static final int STANDBY_TIMEOUT = 5000;

    private LowPowerStandbyController mController;
    private BroadcastInterceptingContext mContextSpy;
    private Resources mResourcesSpy;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;

    @Mock
    private AlarmManager mAlarmManagerMock;
    @Mock
    private IPowerManager mIPowerManagerMock;
    @Mock
    private PowerManagerInternal mPowerManagerInternalMock;
    @Mock
    private NetworkPolicyManagerInternal mNetworkPolicyManagerInternal;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new BroadcastInterceptingContext(InstrumentationRegistry.getContext()));
        when(mContextSpy.getSystemService(AlarmManager.class)).thenReturn(mAlarmManagerMock);
        PowerManager powerManager = new PowerManager(mContextSpy, mIPowerManagerMock, null, null);
        when(mContextSpy.getSystemService(PowerManager.class)).thenReturn(powerManager);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
        addLocalServiceMock(NetworkPolicyManagerInternal.class, mNetworkPolicyManagerInternal);

        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_lowPowerStandbySupported))
                .thenReturn(true);
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer.config_lowPowerStandbyNonInteractiveTimeout))
                .thenReturn(STANDBY_TIMEOUT);
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_lowPowerStandbyEnabledByDefault))
                .thenReturn(false);

        FakeSettingsProvider.clearSettingsProvider();
        MockContentResolver cr = new MockContentResolver(mContextSpy);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContextSpy.getContentResolver()).thenReturn(cr);

        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);

        mController = new LowPowerStandbyController(mContextSpy, mTestLooper.getLooper(),
                () -> mClock.now());
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(LowPowerStandbyControllerInternal.class);
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
    }

    @Test
    public void testOnSystemReady_isInactivate() {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternal, never()).setLowPowerStandbyActive(anyBoolean());
    }

    @Test
    public void testActivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();
        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternal, times(1)).setLowPowerStandbyActive(true);
    }

    private void awaitStandbyTimeoutAlarm() {
        ArgumentCaptor<Long> timeArg = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<AlarmManager.OnAlarmListener> listenerArg =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);
        verify(mAlarmManagerMock).setExact(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                timeArg.capture(), anyString(),
                listenerArg.capture(), any());
        mClock.reset();
        mClock.fastForward(timeArg.getValue());
        listenerArg.getValue().onAlarm();
        mTestLooper.dispatchAll();
    }

    @Test
    public void testOnNonInteractive_notImmediatelyActive() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);

        setNonInteractive();
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternal, never()).setLowPowerStandbyActive(anyBoolean());
    }

    @Test
    public void testOnNonInteractive_activateAfterStandbyTimeout() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);

        setNonInteractive();
        awaitStandbyTimeoutAlarm();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternal, times(1)).setLowPowerStandbyActive(true);
    }

    @Test
    public void testOnNonInteractive_doesNotActivateWhenBecomingInteractive() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);

        setNonInteractive();
        advanceTime(STANDBY_TIMEOUT / 2);
        setInteractive();
        verifyStandbyAlarmCancelled();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternal, never()).setLowPowerStandbyActive(anyBoolean());
    }

    private void verifyStandbyAlarmCancelled() {
        InOrder inOrder = inOrder(mAlarmManagerMock);
        inOrder.verify(mAlarmManagerMock, atLeast(0)).setExact(anyInt(), anyLong(), anyString(),
                any(), any());
        inOrder.verify(mAlarmManagerMock).cancel((AlarmManager.OnAlarmListener) any());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testOnInteractive_deactivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setInteractive();
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternal, times(1)).setLowPowerStandbyActive(false);
    }

    @Test
    public void testOnDozeMaintenance_deactivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        mController.setActiveDuringMaintenance(false);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setDeviceIdleMode(false);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock, times(1)).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternal, times(1)).setLowPowerStandbyActive(false);
    }

    @Test
    public void testOnDozeMaintenance_activeDuringMaintenance_staysActive() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        mController.setActiveDuringMaintenance(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setDeviceIdleMode(false);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternal, never()).setLowPowerStandbyActive(false);
    }

    @Test
    public void testOnDozeMaintenanceEnds_activate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(true);
        setNonInteractive();
        setDeviceIdleMode(true);
        awaitStandbyTimeoutAlarm();

        setDeviceIdleMode(false);
        advanceTime(1000);
        setDeviceIdleMode(true);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock, times(2)).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternal, times(2)).setLowPowerStandbyActive(true);
    }

    @Test
    public void testLowPowerStandbyDisabled_doesNotActivate() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();
        mController.setEnabled(false);
        setNonInteractive();

        assertThat(mController.isActive()).isFalse();
        verify(mAlarmManagerMock, never()).setExact(anyInt(), anyLong(), anyString(), any(), any());
        verify(mPowerManagerInternalMock, never()).setLowPowerStandbyActive(anyBoolean());
        verify(mNetworkPolicyManagerInternal, never()).setLowPowerStandbyActive(anyBoolean());
    }

    @Test
    public void testLowPowerStandbyEnabled_EnabledChangedBroadcastsAreSent() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();

        BroadcastInterceptingContext.FutureIntent futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
        mController.setEnabled(false);
        futureIntent.assertNotReceived();

        futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
        mController.setEnabled(true);
        assertThat(futureIntent.get(1, TimeUnit.SECONDS)).isNotNull();

        futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
        mController.setEnabled(true);
        futureIntent.assertNotReceived();

        futureIntent = mContextSpy.nextBroadcastIntent(
                PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);

        mController.setEnabled(false);
        assertThat(futureIntent.get(1, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    public void testSetEnabled_WhenNotSupported_DoesNotEnable() throws Exception {
        setLowPowerStandbySupportedConfig(false);
        mController.systemReady();

        mController.setEnabled(true);

        assertThat(mController.isEnabled()).isFalse();
    }

    @Test
    public void testIsSupported_WhenSupported() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();

        assertThat(mController.isSupported()).isTrue();
    }

    @Test
    public void testIsSupported_WhenNotSupported() throws Exception {
        setLowPowerStandbySupportedConfig(false);
        mController.systemReady();

        assertThat(mController.isSupported()).isFalse();
    }

    @Test
    public void testAllowlistChange_servicesAreNotified() throws Exception {
        setLowPowerStandbySupportedConfig(true);
        mController.systemReady();

        LowPowerStandbyControllerInternal service = LocalServices.getService(
                LowPowerStandbyControllerInternal.class);
        service.addToAllowlist(10);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[] {10});
        verify(mNetworkPolicyManagerInternal).setLowPowerStandbyAllowlist(new int[] {10});

        service.removeFromAllowlist(10);
        mTestLooper.dispatchAll();
        verify(mPowerManagerInternalMock).setLowPowerStandbyAllowlist(new int[] {});
        verify(mNetworkPolicyManagerInternal).setLowPowerStandbyAllowlist(new int[] {});
    }

    @Test
    public void testForceActive() throws Exception {
        setLowPowerStandbySupportedConfig(false);
        mController.systemReady();

        mController.forceActive(true);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isTrue();
        verify(mPowerManagerInternalMock).setLowPowerStandbyActive(true);
        verify(mNetworkPolicyManagerInternal).setLowPowerStandbyActive(true);

        mController.forceActive(false);
        mTestLooper.dispatchAll();

        assertThat(mController.isActive()).isFalse();
        verify(mPowerManagerInternalMock).setLowPowerStandbyActive(false);
        verify(mNetworkPolicyManagerInternal).setLowPowerStandbyActive(false);
    }

    private void setLowPowerStandbySupportedConfig(boolean supported) {
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_lowPowerStandbySupported))
                .thenReturn(supported);
    }

    private void setInteractive() throws Exception {
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);
        mContextSpy.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
    }

    private void setNonInteractive() throws Exception {
        when(mIPowerManagerMock.isInteractive()).thenReturn(false);
        mContextSpy.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
    }

    private void setDeviceIdleMode(boolean idle) throws Exception {
        when(mIPowerManagerMock.isDeviceIdleMode()).thenReturn(idle);
        mContextSpy.sendBroadcast(new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }
}
