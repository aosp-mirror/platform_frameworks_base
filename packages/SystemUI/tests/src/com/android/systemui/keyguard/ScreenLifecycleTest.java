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

package com.android.systemui.keyguard;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScreenLifecycleTest extends SysuiTestCase {

    private ScreenLifecycle mScreen;
    private ScreenLifecycle.Observer mScreenObserverMock;

    @Before
    public void setUp() throws Exception {
        mScreen = new ScreenLifecycle(mock(DumpManager.class));
        mScreenObserverMock = mock(ScreenLifecycle.Observer.class);
        mScreen.addObserver(mScreenObserverMock);
    }

    @Test
    public void baseState() throws Exception {
        assertEquals(ScreenLifecycle.SCREEN_OFF, mScreen.getScreenState());
        verifyNoMoreInteractions(mScreenObserverMock);
    }

    @Test
    public void screenTurningOn() throws Exception {
        Runnable onDrawn = () -> {};
        mScreen.dispatchScreenTurningOn(onDrawn);

        assertEquals(ScreenLifecycle.SCREEN_TURNING_ON, mScreen.getScreenState());
        verify(mScreenObserverMock).onScreenTurningOn(onDrawn);
    }

    @Test
    public void screenTurnedOn() throws Exception {
        mScreen.dispatchScreenTurningOn(null);
        mScreen.dispatchScreenTurnedOn();

        assertEquals(ScreenLifecycle.SCREEN_ON, mScreen.getScreenState());
        verify(mScreenObserverMock).onScreenTurnedOn();
    }

    @Test
    public void screenTurningOff() throws Exception {
        mScreen.dispatchScreenTurningOn(null);
        mScreen.dispatchScreenTurnedOn();
        mScreen.dispatchScreenTurningOff();

        assertEquals(ScreenLifecycle.SCREEN_TURNING_OFF, mScreen.getScreenState());
        verify(mScreenObserverMock).onScreenTurningOff();
    }

    @Test
    public void screenTurnedOff() throws Exception {
        mScreen.dispatchScreenTurningOn(null);
        mScreen.dispatchScreenTurnedOn();
        mScreen.dispatchScreenTurningOff();
        mScreen.dispatchScreenTurnedOff();

        assertEquals(ScreenLifecycle.SCREEN_OFF, mScreen.getScreenState());
        verify(mScreenObserverMock).onScreenTurnedOff();
    }

    @Test
    public void dump() throws Exception {
        mScreen.dump(new PrintWriter(new ByteArrayOutputStream()), new String[0]);
    }
}
