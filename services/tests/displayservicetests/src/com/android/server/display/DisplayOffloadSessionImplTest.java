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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class DisplayOffloadSessionImplTest {

    @Mock
    private DisplayManagerInternal.DisplayOffloader mDisplayOffloader;

    @Mock
    private DisplayPowerControllerInterface mDisplayPowerController;

    private DisplayOffloadSessionImpl mSession;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDisplayOffloader.startOffload()).thenReturn(true);
        mSession = new DisplayOffloadSessionImpl(mDisplayOffloader, mDisplayPowerController);
    }

    @Test
    public void testStartOffload() {
        mSession.startOffload();
        assertTrue(mSession.isActive());

        // An active session shouldn't be started again
        mSession.startOffload();
        verify(mDisplayOffloader, times(1)).startOffload();
    }

    @Test
    public void testStopOffload() {
        mSession.startOffload();
        mSession.stopOffload();

        assertFalse(mSession.isActive());

        // An inactive session shouldn't be stopped again
        mSession.stopOffload();
        verify(mDisplayOffloader, times(1)).stopOffload();
    }

    @Test
    public void testUpdateBrightness_sessionInactive() {
        mSession.updateBrightness(0.3f);
        verify(mDisplayPowerController, never()).setBrightnessFromOffload(anyFloat());
    }

    @Test
    public void testUpdateBrightness_sessionActive() {
        float brightness = 0.3f;

        mSession.startOffload();
        mSession.updateBrightness(brightness);

        verify(mDisplayPowerController).setBrightnessFromOffload(brightness);
    }

    @Test
    public void testBlockScreenOn() {
        Runnable unblocker = () -> {};
        mSession.blockScreenOn(unblocker);

        verify(mDisplayOffloader).onBlockingScreenOn(eq(unblocker));
    }

    @Test
    public void testUnblockScreenOn() {
        mSession.cancelBlockScreenOn();

        verify(mDisplayOffloader).cancelBlockScreenOn();
    }
}
