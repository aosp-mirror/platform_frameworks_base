/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.display.AmbientDisplayConfiguration;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerFake;
import com.android.systemui.doze.DozeMachine.State;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class DozeDockHandlerTest extends SysuiTestCase {
    @Mock private DozeMachine mMachine;
    private AmbientDisplayConfiguration mConfig;
    private DockManagerFake mDockManagerFake;
    private DozeDockHandler mDockHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mConfig = DozeConfigurationUtil.createMockConfig();
        mDockManagerFake = spy(new DockManagerFake());
        mDockHandler = new DozeDockHandler(mConfig, mMachine, mDockManagerFake);

        when(mMachine.getState()).thenReturn(State.DOZE_AOD);
        doReturn(true).when(mConfig).alwaysOnEnabled(anyInt());
        mDockHandler.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
    }

    @Test
    public void transitionToInitialized_registersDockEventListener() {
        verify(mDockManagerFake).addListener(any());
    }

    @Test
    public void transitionToFinish_unregistersDockEventListener() {
        mDockHandler.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.FINISH);

        verify(mDockManagerFake).removeListener(any());
    }

    @Test
    public void onEvent_docked_requestsDockedAodState() {
        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKED);

        verify(mMachine).requestState(eq(State.DOZE_AOD_DOCKED));
    }

    @Test
    public void onEvent_noneWhileEnabledAod_ignoresIfAlreadyNone() {
        mDockManagerFake.setDockEvent(DockManager.STATE_NONE);

        verify(mMachine, never()).requestState(eq(State.DOZE_AOD));
    }

    @Test
    public void onEvent_noneWhileEnabledAod_requestsAodState() {
        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKED);
        clearInvocations(mMachine);
        mDockManagerFake.setDockEvent(DockManager.STATE_NONE);

        verify(mMachine).requestState(eq(State.DOZE_AOD));
    }

    @Test
    public void onEvent_noneWhileDisabledAod_requestsDozeState() {
        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKED);
        clearInvocations(mMachine);
        doReturn(false).when(mConfig).alwaysOnEnabled(anyInt());

        mDockManagerFake.setDockEvent(DockManager.STATE_NONE);

        verify(mMachine).requestState(eq(State.DOZE));
    }

    @Test
    public void onEvent_hide_requestsDozeState() {
        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKED_HIDE);

        verify(mMachine).requestState(eq(State.DOZE));
    }

    @Test
    public void onEvent_dockedWhilePulsing_wontRequestStateChange() {
        when(mMachine.getState()).thenReturn(State.DOZE_PULSING);

        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKED);

        verify(mMachine, never()).requestState(any(State.class));
    }

    @Test
    public void onEvent_noneWhilePulsing_wontRequestStateChange() {
        when(mMachine.getState()).thenReturn(State.DOZE_PULSING);

        mDockManagerFake.setDockEvent(DockManager.STATE_NONE);

        verify(mMachine, never()).requestState(any(State.class));
    }

    @Test
    public void onEvent_hideWhilePulsing_wontRequestStateChange() {
        when(mMachine.getState()).thenReturn(State.DOZE_PULSING);

        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKED_HIDE);

        verify(mMachine, never()).requestState(any(State.class));
    }
}
