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

package com.android.systemui.doze;

import static android.content.res.Configuration.UI_MODE_TYPE_CAR;
import static android.content.res.Configuration.UI_MODE_TYPE_NORMAL;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_SUSPEND_TRIGGERS;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.testing.AndroidTestingRunner;
import android.testing.UiThreadTest;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.BiometricUnlockController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import dagger.Lazy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class DozeSuppressorTest extends SysuiTestCase {

    DozeSuppressor mDozeSuppressor;
    @Mock
    private DozeLog mDozeLog;
    @Mock
    private DozeHost mDozeHost;
    @Mock
    private AmbientDisplayConfiguration mConfig;
    @Mock
    private Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    @Mock
    private BiometricUnlockController mBiometricUnlockController;
    @Mock
    private UserTracker mUserTracker;

    @Mock
    private DozeMachine mDozeMachine;

    @Captor
    private ArgumentCaptor<DozeHost.Callback> mDozeHostCaptor;
    private DozeHost.Callback mDozeHostCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // setup state for NOT ending doze immediately
        when(mBiometricUnlockControllerLazy.get()).thenReturn(mBiometricUnlockController);
        when(mBiometricUnlockController.hasPendingAuthentication()).thenReturn(false);
        when(mDozeHost.isProvisioned()).thenReturn(true);
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());

        mDozeSuppressor = new DozeSuppressor(
                mDozeHost,
                mConfig,
                mDozeLog,
                mBiometricUnlockControllerLazy,
                mUserTracker);

        mDozeSuppressor.setDozeMachine(mDozeMachine);
    }

    @After
    public void tearDown() {
        mDozeSuppressor.destroy();
    }

    @Test
    public void testRegistersListenersOnInitialized_unregisteredOnFinish() {
        // check that receivers and callbacks registered
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        captureDozeHostCallback();

        // check that receivers and callbacks are unregistered
        mDozeSuppressor.transitionTo(INITIALIZED, FINISH);
        verify(mDozeHost).removeCallback(mDozeHostCallback);
    }

    @Test
    public void testSuspendTriggersDoze_carMode() {
        // GIVEN car mode
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);

        // WHEN dozing begins
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);

        // THEN doze continues with all doze triggers disabled.
        verify(mDozeMachine, atLeastOnce()).requestState(DOZE_SUSPEND_TRIGGERS);
        verify(mDozeMachine, never())
                .requestState(AdditionalMatchers.not(eq(DOZE_SUSPEND_TRIGGERS)));
    }

    @Test
    public void testSuspendTriggersDoze_enterCarMode() {
        // GIVEN currently dozing
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeSuppressor.transitionTo(INITIALIZED, DOZE);

        // WHEN car mode entered
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);

        // THEN doze continues with all doze triggers disabled.
        verify(mDozeMachine).requestState(DOZE_SUSPEND_TRIGGERS);
    }

    @Test
    public void testDozeResume_exitCarMode() {
        // GIVEN currently suspended, with AOD not enabled
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);
        when(mConfig.alwaysOnEnabled(anyInt())).thenReturn(false);
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeSuppressor.transitionTo(INITIALIZED, DOZE_SUSPEND_TRIGGERS);

        // WHEN exiting car mode
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_NORMAL);

        // THEN doze is resumed
        verify(mDozeMachine).requestState(DOZE);
    }

    @Test
    public void testDozeAoDResume_exitCarMode() {
        // GIVEN currently suspended, with AOD not enabled
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);
        when(mConfig.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeSuppressor.transitionTo(INITIALIZED, DOZE_SUSPEND_TRIGGERS);

        // WHEN exiting car mode
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_NORMAL);

        // THEN doze AOD is resumed
        verify(mDozeMachine).requestState(DOZE_AOD);
    }

    @Test
    public void testUiModeDoesNotChange_noStateTransition() {
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        clearInvocations(mDozeMachine);

        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);

        verify(mDozeMachine, times(1)).requestState(DOZE_SUSPEND_TRIGGERS);
        verify(mDozeMachine, never())
                .requestState(AdditionalMatchers.not(eq(DOZE_SUSPEND_TRIGGERS)));
    }

    @Test
    public void testUiModeTypeChange_whenDozeMachineIsNotReady_doesNotDoAnything() {
        when(mDozeMachine.isUninitializedOrFinished()).thenReturn(true);

        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);

        verify(mDozeMachine, never()).requestState(any());
    }

    @Test
    public void testUiModeTypeChange_CarModeEnabledAndDozeMachineNotReady_suspendsTriggersAfter() {
        when(mDozeMachine.isUninitializedOrFinished()).thenReturn(true);
        mDozeSuppressor.onUiModeTypeChanged(UI_MODE_TYPE_CAR);
        verify(mDozeMachine, never()).requestState(any());

        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);

        verify(mDozeMachine, times(1)).requestState(DOZE_SUSPEND_TRIGGERS);
    }

    @Test
    public void testEndDoze_unprovisioned() {
        // GIVEN device unprovisioned
        when(mDozeHost.isProvisioned()).thenReturn(false);

        // WHEN dozing begins
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);

        // THEN doze immediately ends
        verify(mDozeMachine).requestState(FINISH);
    }

    @Test
    public void testEndDoze_hasPendingUnlock() {
        // GIVEN device unprovisioned
        when(mBiometricUnlockController.hasPendingAuthentication()).thenReturn(true);

        // WHEN dozing begins
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);

        // THEN doze immediately ends
        verify(mDozeMachine).requestState(FINISH);
    }

    @Test
    public void testPowerSaveChanged_active() {
        // GIVEN AOD power save is active and doze is initialized
        when(mDozeHost.isPowerSaveActive()).thenReturn(true);
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        captureDozeHostCallback();

        // WHEN power save change gets triggered (even if active = false, since it
        // may differ from the aodPowerSaveActive state reported by DostHost)
        mDozeHostCallback.onPowerSaveChanged(false);

        // THEN the state changes to DOZE
        verify(mDozeMachine).requestState(DOZE);
    }

    @Test
    public void testPowerSaveChanged_notActive() {
        // GIVEN DOZE (not showing aod content)
        when(mConfig.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        when(mDozeMachine.getState()).thenReturn(DOZE);
        captureDozeHostCallback();

        // WHEN power save mode is no longer active
        when(mDozeHost.isPowerSaveActive()).thenReturn(false);
        mDozeHostCallback.onPowerSaveChanged(false);

        // THEN the state changes to DOZE_AOD
        verify(mDozeMachine).requestState(DOZE_AOD);
    }

    @Test
    public void testAlwaysOnSuppressedChanged_nowSuppressed() {
        // GIVEN DOZE_AOD
        when(mConfig.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        when(mDozeMachine.getState()).thenReturn(DOZE_AOD);
        captureDozeHostCallback();

        // WHEN alwaysOnSuppressedChanged to suppressed=true
        mDozeHostCallback.onAlwaysOnSuppressedChanged(true);

        // THEN DOZE requested
        verify(mDozeMachine).requestState(DOZE);
    }

    @Test
    public void testAlwaysOnSuppressedChanged_notSuppressed() {
        // GIVEN DOZE
        when(mConfig.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeSuppressor.transitionTo(UNINITIALIZED, INITIALIZED);
        when(mDozeMachine.getState()).thenReturn(DOZE);
        captureDozeHostCallback();

        // WHEN alwaysOnSuppressedChanged to suppressed=false
        mDozeHostCallback.onAlwaysOnSuppressedChanged(false);

        // THEN DOZE_AOD requested
        verify(mDozeMachine).requestState(DOZE_AOD);
    }

    private void captureDozeHostCallback() {
        verify(mDozeHost).addCallback(mDozeHostCaptor.capture());
        mDozeHostCallback = mDozeHostCaptor.getValue();
    }
}
