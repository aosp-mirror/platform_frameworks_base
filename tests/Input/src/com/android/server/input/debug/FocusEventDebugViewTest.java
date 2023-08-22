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

package com.android.server.input.debug;

import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.input.InputManagerService;

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
    private RotaryInputValueView mRotaryInputValueView;
    private RotaryInputGraphView mRotaryInputGraphView;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getContext();
        InputManagerService mockService = mock(InputManagerService.class);
        when(mockService.monitorInput(anyString(), anyInt()))
                .thenReturn(InputChannel.openInputChannelPair("FocusEventDebugViewTest")[1]);

        mRotaryInputValueView = spy(new RotaryInputValueView(context));
        mRotaryInputGraphView = spy(new RotaryInputGraphView(context));
        mFocusEventDebugView = new FocusEventDebugView(context, mockService,
                () -> mRotaryInputValueView, () -> mRotaryInputGraphView);
    }

    @Test
    public void handleRotaryInput_sendsMotionEventWhenEnabled() {
        mFocusEventDebugView.handleUpdateShowRotaryInput(true);

        mFocusEventDebugView.handleRotaryInput(createRotaryMotionEvent(0.5f,  10L));

        verify(mRotaryInputGraphView).addValue(0.5f, 10L);
        verify(mRotaryInputValueView).updateValue(0.5f);
    }

    @Test
    public void handleRotaryInput_doesNotSendMotionEventWhenDisabled() {
        mFocusEventDebugView.handleUpdateShowRotaryInput(false);

        mFocusEventDebugView.handleRotaryInput(createRotaryMotionEvent(0.5f, 10L));

        verify(mRotaryInputGraphView, never()).addValue(anyFloat(), anyLong());
        verify(mRotaryInputValueView, never()).updateValue(anyFloat());
    }

    private MotionEvent createRotaryMotionEvent(float scrollAxisValue, long eventTime) {
        PointerCoords pointerCoords = new PointerCoords();
        pointerCoords.setAxisValue(MotionEvent.AXIS_SCROLL, scrollAxisValue);
        PointerProperties pointerProperties = new PointerProperties();

        return MotionEvent.obtain(
                /* downTime */ 0,
                /* eventTime */ eventTime,
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
