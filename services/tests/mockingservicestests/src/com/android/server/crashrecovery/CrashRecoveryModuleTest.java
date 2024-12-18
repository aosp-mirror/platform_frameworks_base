/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.crashrecovery;

import static com.android.server.SystemService.PHASE_ACTIVITY_MANAGER_READY;
import static com.android.server.SystemService.PHASE_THIRD_PARTY_APPS_CAN_START;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;


import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.PackageWatchdog;
import com.android.server.RescueParty;
import com.android.server.crashrecovery.CrashRecoveryModule.Lifecycle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Test CrashRecoveryModule.
 */
@RunWith(AndroidJUnit4.class)
public class CrashRecoveryModuleTest {

    @Rule
    public SetFlagsRule mSetFlagsRule;

    private MockitoSession mSession;
    private Lifecycle mLifecycle;

    @Mock PackageWatchdog mPackageWatchdog;

    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(PackageWatchdog.class)
                .mockStatic(RescueParty.class)
                .startMocking();
        when(PackageWatchdog.getInstance(context)).thenReturn(mPackageWatchdog);
        ExtendedMockito.doNothing().when(() -> RescueParty.registerHealthObserver(context));
        mLifecycle = new Lifecycle(context);
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
    }

    @Test
    public void testLifecycleServiceStart() {
        mLifecycle.onStart();
        doNothing().when(mPackageWatchdog).registerShutdownBroadcastReceiver();

        verify(mPackageWatchdog, times(1)).noteBoot();
        verify(mPackageWatchdog, times(1)).registerShutdownBroadcastReceiver();
        ExtendedMockito.verify(() -> RescueParty.registerHealthObserver(any()),
                Mockito.times(1));
    }

    @Test
    public void testLifecycleServiceOnBootPhase() {
        doNothing().when(mPackageWatchdog).onPackagesReady();

        mLifecycle.onBootPhase(PHASE_ACTIVITY_MANAGER_READY);
        verify(mPackageWatchdog, never()).onPackagesReady();

        mLifecycle.onBootPhase(PHASE_THIRD_PARTY_APPS_CAN_START);
        verify(mPackageWatchdog, times(1)).onPackagesReady();
    }
}
