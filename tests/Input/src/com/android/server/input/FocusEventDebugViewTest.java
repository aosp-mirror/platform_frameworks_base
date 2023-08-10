/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ViewConfiguration;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 * atest FocusEventDebugViewTest
 */
@RunWith(AndroidJUnit4.class)
public class FocusEventDebugViewTest {

    private FocusEventDebugView mFocusEventDebugView;
    private FocusEventDebugView.RotaryInputValueView mRotaryInputValueView;
    private float mScaledVerticalScrollFactor;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getContext();
        mScaledVerticalScrollFactor =
                ViewConfiguration.get(context).getScaledVerticalScrollFactor();
        InputManagerService mockService = mock(InputManagerService.class);
        when(mockService.monitorInput(anyString(), anyInt()))
                .thenReturn(InputChannel.openInputChannelPair("FocusEventDebugViewTest")[1]);

        mRotaryInputValueView = new FocusEventDebugView.RotaryInputValueView(context);
        mFocusEventDebugView = new FocusEventDebugView(context, mockService,
                () -> mRotaryInputValueView);
    }

    @Test
    public void startsRotaryInputValueViewWithDefaultValue() {
        assertEquals("+0.0", mRotaryInputValueView.getText());
    }

    @Test
    public void handleRotaryInput_updatesRotaryInputValueViewWithScrollValue() {
        mFocusEventDebugView.handleUpdateShowRotaryInput(true);

        mFocusEventDebugView.handleRotaryInput(createRotaryMotionEvent(0.5f));

        assertEquals(String.format("+%.1f", 0.5f * mScaledVerticalScrollFactor),
                mRotaryInputValueView.getText());
    }

    @Test
    public void updateActivityStatus_setsAndRemovesColorFilter() {
        // It should not be active initially.
        assertNull(mRotaryInputValueView.getBackground().getColorFilter());

        mRotaryInputValueView.updateActivityStatus(true);
        // It should be active after rotary input.
        assertNotNull(mRotaryInputValueView.getBackground().getColorFilter());

        mRotaryInputValueView.updateActivityStatus(false);
        // It should not be active after waiting for mUpdateActivityStatusCallback.
        assertNull(mRotaryInputValueView.getBackground().getColorFilter());
    }

    private MotionEvent createRotaryMotionEvent(float scrollAxisValue) {
        PointerCoords pointerCoords = new PointerCoords();
        pointerCoords.setAxisValue(MotionEvent.AXIS_SCROLL, scrollAxisValue);
        PointerProperties pointerProperties = new PointerProperties();

        return MotionEvent.obtain(
                /* downTime */ 0,
                /* eventTime */ 0,
                /* action */ MotionEvent.ACTION_SCROLL,
                /* pointerCount */ 1,
                /* pointerProperties */ new PointerProperties[] {pointerProperties},
                /* pointerCoords */ new PointerCoords[] {pointerCoords},
                /* metaState */ 0,
                /* buttonState */ 0,
                /* xPrecision */ 0,
                /* yPrecision */ 0,
                /* deviceId */ 0,
                /* edgeFlags */ 0,
                /* source */ InputDevice.SOURCE_ROTARY_ENCODER,
                /* flags */ 0
        );
    }
}
