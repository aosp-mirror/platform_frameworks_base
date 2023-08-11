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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.Mockito.times;

import android.os.Handler;
import android.os.test.TestLooper;
import android.view.Display;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


@SmallTest
public class DisplayPowerStateTest {
    private static final int DISPLAY_ID = 123;

    private DisplayPowerState mDisplayPowerState;
    private TestLooper mTestLooper;
    @Mock
    private DisplayBlanker mDisplayBlankerMock;
    @Mock
    private ColorFade mColorFadeMock;

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Before
    public void setUp() {
        mTestLooper = new TestLooper();
        mDisplayPowerState = new DisplayPowerState(
                mDisplayBlankerMock, mColorFadeMock, DISPLAY_ID, Display.STATE_ON,
                new Handler(mTestLooper.getLooper()));
    }

    @Test
    public void testColorFadeStopsOnDpsStop() {
        mDisplayPowerState.stop();
        verify(mColorFadeMock, times(1)).stop();
    }
}
