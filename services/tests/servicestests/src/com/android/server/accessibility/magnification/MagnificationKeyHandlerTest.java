/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static com.android.server.accessibility.Flags.FLAG_ENABLE_MAGNIFICATION_KEYBOARD_CONTROL;
import static com.android.server.accessibility.magnification.MagnificationController.PAN_DIRECTION_DOWN;
import static com.android.server.accessibility.magnification.MagnificationController.PAN_DIRECTION_LEFT;
import static com.android.server.accessibility.magnification.MagnificationController.PAN_DIRECTION_RIGHT;
import static com.android.server.accessibility.magnification.MagnificationController.PAN_DIRECTION_UP;
import static com.android.server.accessibility.magnification.MagnificationController.ZOOM_DIRECTION_IN;
import static com.android.server.accessibility.magnification.MagnificationController.ZOOM_DIRECTION_OUT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.Display;
import android.view.KeyEvent;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.EventStreamTransformation;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link MagnificationKeyHandler}.
 */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(FLAG_ENABLE_MAGNIFICATION_KEYBOARD_CONTROL)
public class MagnificationKeyHandlerTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private MagnificationKeyHandler mMkh;

    @Mock
    MagnificationKeyHandler.Callback mCallback;

    @Mock
    EventStreamTransformation mNextHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMkh = new MagnificationKeyHandler(mCallback);
        mMkh.setNext(mNextHandler);
    }

    @Test
    public void onKeyEvent_unusedKeyPress_sendToNext() {
        final KeyEvent event = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, 0, 0);
        mMkh.onKeyEvent(event, 0);

        // No callbacks were called.
        verify(mCallback, times(0)).onPanMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());
        verify(mCallback, times(0)).onScaleMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onScaleMagnificationStop(anyInt());
        verify(mCallback, times(0)).onKeyboardInteractionStop();

        // The event was passed on.
        verify(mNextHandler, times(1)).onKeyEvent(event, 0);
    }

    @Test
    public void onKeyEvent_arrowKeyPressWithIncorrectModifiers_sendToNext() {
        final KeyEvent event =
                new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                        0, KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(event, 0);

        // No callbacks were called.
        verify(mCallback, times(0)).onPanMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());
        verify(mCallback, times(0)).onScaleMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onScaleMagnificationStop(anyInt());
        verify(mCallback, times(0)).onKeyboardInteractionStop();

        // The event was passed on.
        verify(mNextHandler, times(1)).onKeyEvent(event, 0);
    }

    @Test
    public void onKeyEvent_unusedKeyPressWithCorrectModifiers_sendToNext() {
        final KeyEvent event =
                new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_J, 0,
                        KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(event, 0);

        // No callbacks were called.
        verify(mCallback, times(0)).onPanMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());
        verify(mCallback, times(0)).onScaleMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onScaleMagnificationStop(anyInt());
        verify(mCallback, times(0)).onKeyboardInteractionStop();

        // The event was passed on.
        verify(mNextHandler, times(1)).onKeyEvent(event, 0);
    }

    @Test
    public void onKeyEvent_panStartAndEnd_left() {
        testPanMagnification(KeyEvent.KEYCODE_DPAD_LEFT, PAN_DIRECTION_LEFT);
    }

    @Test
    public void onKeyEvent_panStartAndEnd_right() {
        testPanMagnification(KeyEvent.KEYCODE_DPAD_RIGHT, PAN_DIRECTION_RIGHT);
    }

    @Test
    public void onKeyEvent_panStartAndEnd_up() {
        testPanMagnification(KeyEvent.KEYCODE_DPAD_UP, PAN_DIRECTION_UP);
    }

    @Test
    public void onKeyEvent_panStartAndEnd_down() {
        testPanMagnification(KeyEvent.KEYCODE_DPAD_DOWN, PAN_DIRECTION_DOWN);
    }

    @Test
    public void onKeyEvent_scaleStartAndEnd_zoomIn() {
        testScaleMagnification(KeyEvent.KEYCODE_EQUALS, ZOOM_DIRECTION_IN);
    }

    @Test
    public void onKeyEvent_scaleStartAndEnd_zoomOut() {
        testScaleMagnification(KeyEvent.KEYCODE_MINUS, ZOOM_DIRECTION_OUT);
    }

    @Test
    public void onKeyEvent_panStartAndStop_diagonal() {
        final KeyEvent downLeftEvent = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(downLeftEvent, 0);
        verify(mCallback, times(1)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_LEFT);
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());

        Mockito.clearInvocations(mCallback);

        // Also press the down arrow key.
        final KeyEvent downDownEvent = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, 0, KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(downDownEvent, 0);
        verify(mCallback, times(0)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_LEFT);
        verify(mCallback, times(1)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_DOWN);
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());

        Mockito.clearInvocations(mCallback);

        // Lift the left arrow key.
        final KeyEvent upLeftEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(upLeftEvent, 0);
        verify(mCallback, times(0)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_LEFT);
        verify(mCallback, times(0)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_DOWN);
        verify(mCallback, times(1)).onPanMagnificationStop(PAN_DIRECTION_LEFT);
        verify(mCallback, times(0)).onPanMagnificationStop(PAN_DIRECTION_DOWN);

        Mockito.clearInvocations(mCallback);

        // Lift the down arrow key.
        final KeyEvent upDownEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, 0, KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(upDownEvent, 0);
        verify(mCallback, times(0)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_LEFT);
        verify(mCallback, times(0)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_DOWN);
        verify(mCallback, times(0)).onPanMagnificationStop(PAN_DIRECTION_LEFT);
        verify(mCallback, times(1)).onPanMagnificationStop(PAN_DIRECTION_DOWN);

        // The event was not passed on.
        verify(mNextHandler, times(0)).onKeyEvent(any(), anyInt());
    }

    @Test
    public void testPanMagnification_modifiersReleasedBeforeArrows() {
        final KeyEvent downEvent = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, 0,
                KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(downEvent, 0);

        // Pan started.
        verify(mCallback, times(1)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_DOWN);
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());
        verify(mCallback, times(0)).onKeyboardInteractionStop();

        Mockito.clearInvocations(mCallback);

        // Lift the "meta" key.
        final KeyEvent upEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT,
                0,
                KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(upEvent, 0);

        // Pan ended.
        verify(mCallback, times(0)).onPanMagnificationStart(Display.DEFAULT_DISPLAY,
                PAN_DIRECTION_DOWN);
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());
        verify(mCallback, times(1)).onKeyboardInteractionStop();

    }

    private void testPanMagnification(int keyCode, int panDirection) {
        final KeyEvent downEvent = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0,
                KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(downEvent, 0);

        // Pan started.
        verify(mCallback, times(1)).onPanMagnificationStart(Display.DEFAULT_DISPLAY, panDirection);
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());

        Mockito.clearInvocations(mCallback);

        final KeyEvent upEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0,
                KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(upEvent, 0);

        // Pan ended.
        verify(mCallback, times(0)).onPanMagnificationStart(Display.DEFAULT_DISPLAY, panDirection);
        verify(mCallback, times(1)).onPanMagnificationStop(panDirection);

        // Scale callbacks were not called.
        verify(mCallback, times(0)).onScaleMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onScaleMagnificationStop(anyInt());

        // The events were not passed on.
        verify(mNextHandler, times(0)).onKeyEvent(any(), anyInt());
    }

    private void testScaleMagnification(int keyCode, int zoomDirection) {
        final KeyEvent downEvent = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0,
                KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(downEvent, 0);

        // Scale started.
        verify(mCallback, times(1)).onScaleMagnificationStart(Display.DEFAULT_DISPLAY,
                zoomDirection);
        verify(mCallback, times(0)).onScaleMagnificationStop(anyInt());

        Mockito.clearInvocations(mCallback);

        final KeyEvent upEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0,
                KeyEvent.META_META_ON | KeyEvent.META_ALT_ON);
        mMkh.onKeyEvent(upEvent, 0);

        // Scale ended.
        verify(mCallback, times(0)).onScaleMagnificationStart(Display.DEFAULT_DISPLAY,
                zoomDirection);
        verify(mCallback, times(1)).onScaleMagnificationStop(zoomDirection);

        // Pan callbacks were not called.
        verify(mCallback, times(0)).onPanMagnificationStart(anyInt(), anyInt());
        verify(mCallback, times(0)).onPanMagnificationStop(anyInt());

        // The events were not passed on.
        verify(mNextHandler, times(0)).onKeyEvent(any(), anyInt());
    }

}
