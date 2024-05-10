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

public class VibratorControllerHolderTest {

    private final FakeVibratorController mFakeVibratorController = new FakeVibratorController();
    private VibratorControllerHolder mVibratorControllerHolder;

    @Before
    public void setUp() throws Exception {
        mVibratorControllerHolder = new VibratorControllerHolder();
    }

    @Test
    public void testSetVibratorController_linksVibratorControllerToDeath() throws RemoteException {
        mVibratorControllerHolder.setVibratorController(mFakeVibratorController);
        assertThat(mVibratorControllerHolder.getVibratorController())
                .isEqualTo(mFakeVibratorController);
        assertThat(mFakeVibratorController.isLinkedToDeath).isTrue();
    }

    @Test
    public void testSetVibratorController_setControllerToNull_unlinksVibratorControllerToDeath()
            throws RemoteException {
        mVibratorControllerHolder.setVibratorController(mFakeVibratorController);
        mVibratorControllerHolder.setVibratorController(null);
        assertThat(mFakeVibratorController.isLinkedToDeath).isFalse();
        assertThat(mVibratorControllerHolder.getVibratorController()).isNull();
    }

    @Test
    public void testBinderDied_withValidController_unlinksVibratorControllerToDeath()
            throws RemoteException {
        mVibratorControllerHolder.setVibratorController(mFakeVibratorController);
        mVibratorControllerHolder.binderDied(mFakeVibratorController);
        assertThat(mFakeVibratorController.isLinkedToDeath).isFalse();
        assertThat(mVibratorControllerHolder.getVibratorController()).isNull();
    }

    @Test
    public void testBinderDied_withInvalidController_ignoresRequest()
            throws RemoteException {
        mVibratorControllerHolder.setVibratorController(mFakeVibratorController);
        FakeVibratorController imposterVibratorController = new FakeVibratorController();
        mVibratorControllerHolder.binderDied(imposterVibratorController);
        assertThat(mFakeVibratorController.isLinkedToDeath).isTrue();
        assertThat(mVibratorControllerHolder.getVibratorController())
                .isEqualTo(mFakeVibratorController);
    }
}
