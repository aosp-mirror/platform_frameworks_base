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

package com.android.server.vibrator;

import static com.google.common.truth.Truth.assertThat;

import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;

public class VibratorControlServiceTest {

    private VibratorControlService mVibratorControlService;
    private final Object mLock = new Object();

    @Before
    public void setUp() throws Exception {
        mVibratorControlService = new VibratorControlService(new VibratorControllerHolder(), mLock);
    }

    @Test
    public void testRegisterVibratorController() throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController);

        assertThat(fakeController.isLinkedToDeath).isTrue();
    }

    @Test
    public void testUnregisterVibratorController_providingTheRegisteredController_performsRequest()
            throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController);
        mVibratorControlService.unregisterVibratorController(fakeController);
        assertThat(fakeController.isLinkedToDeath).isFalse();
    }

    @Test
    public void testUnregisterVibratorController_providingAnInvalidController_ignoresRequest()
            throws RemoteException {
        FakeVibratorController fakeController1 = new FakeVibratorController();
        FakeVibratorController fakeController2 = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController1);

        mVibratorControlService.unregisterVibratorController(fakeController2);
        assertThat(fakeController1.isLinkedToDeath).isTrue();
    }
}
