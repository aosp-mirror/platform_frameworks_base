/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_REQUEST_PULSE;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.junit.Assert.assertEquals;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeScreenStateTest extends SysuiTestCase {

    DozeServiceFake mServiceFake;
    DozeScreenState mScreen;

    @Before
    public void setUp() throws Exception {
        mServiceFake = new DozeServiceFake();
        mScreen = new DozeScreenState(mServiceFake);
    }

    @Test
    public void testScreen_offInDoze() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void testScreen_onInAod() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        assertEquals(Display.STATE_DOZE_SUSPEND, mServiceFake.screenState);
    }

    @Test
    public void testScreen_onInPulse() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);
        mScreen.transitionTo(DOZE_REQUEST_PULSE, DOZE_PULSING);

        assertEquals(Display.STATE_ON, mServiceFake.screenState);
    }

    @Test
    public void testScreen_offInRequestPulseWithoutAoD() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void testScreen_offInRequestPulseWithAoD() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

}