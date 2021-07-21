/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import android.hardware.input.InputManager;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Command that sends input events to the device.
 */

public class InputShellCommand extends ShellCommand {
    private static final String INVALID_ARGUMENTS = "Error: Invalid arguments for command: ";
    private static final String INVALID_DISPLAY_ARGUMENTS =
            "Error: Invalid arguments for display ID.";
    private static final int DEFAULT_DEVICE_ID = 0;
    private static final float DEFAULT_PRESSURE = 1.0f;
    private static final float NO_PRESSURE = 0.0f;
    private static final float DEFAULT_SIZE = 1.0f;
    private static final int DEFAULT_META_STATE = 0;
    private static final float DEFAULT_PRECISION_X = 1.0f;
    private static final float DEFAULT_PRECISION_Y = 1.0f;
    private static final int DEFAULT_EDGE_FLAGS = 0;
    private static final int DEFAULT_BUTTON_STATE = 0;
    private static final int DEFAULT_FLAGS = 0;

    private static final Map<String, Integer> SOURCES = new HashMap<String, Integer>() {{
            put("keyboard", InputDevice.SOURCE_KEYBOARD);
            put("dpad", InputDevice.SOURCE_DPAD);
            put("gamepad", InputDevice.SOURCE_GAMEPAD);
            put("touchscreen", InputDevice.SOURCE_TOUCHSCREEN);
            put("mouse", InputDevice.SOURCE_MOUSE);
            put("stylus", InputDevice.SOURCE_STYLUS);
            put("trackball", InputDevice.SOURCE_TRACKBALL);
            put("touchpad", InputDevice.SOURCE_TOUCHPAD);
            put("touchnavigation", InputDevice.SOURCE_TOUCH_NAVIGATION);
            put("joystick", InputDevice.SOURCE_JOYSTICK);
        }};

    private void injectKeyEvent(KeyEvent event) {
        InputManager.getInstance().injectInputEvent(event,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private int getInputDeviceId(int inputSource) {
        int[] devIds = InputDevice.getDeviceIds();
        for (int devId : devIds) {
            InputDevice inputDev = InputDevice.getDevice(devId);
            if (inputDev.supportsSource(inputSource)) {
                return devId;
            }
        }
        return DEFAULT_DEVICE_ID;
    }

    private int getDisplayId() {
        String displayArg = getNextArgRequired();
        if ("INVALID_DISPLAY".equalsIgnoreCase(displayArg)) {
            return INVALID_DISPLAY;
        } else if ("DEFAULT_DISPLAY".equalsIgnoreCase(displayArg)) {
            return DEFAULT_DISPLAY;
        } else {
            try {
                final int displayId = Integer.parseInt(displayArg);
                if (displayId == INVALID_DISPLAY) {
                    return INVALID_DISPLAY;
                }
                return Math.max(displayId, 0);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(INVALID_DISPLAY_ARGUMENTS);
            }
        }
    }

    /**
     * Builds a MotionEvent and injects it into the event stream.
     *
     * @param inputSource the InputDevice.SOURCE_* sending the input event
     * @param action the MotionEvent.ACTION_* for the event
     * @param downTime the value of the ACTION_DOWN event happened
     * @param when the value of SystemClock.uptimeMillis() at which the event happened
     * @param x x coordinate of event
     * @param y y coordinate of event
     * @param pressure pressure of event
     */
    private void injectMotionEvent(int inputSource, int action, long downTime, long when,
            float x, float y, float pressure, int displayId) {
        final int pointerCount = 1;
        MotionEvent.PointerProperties[] pointerProperties =
                new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            pointerProperties[i] = new MotionEvent.PointerProperties();
            pointerProperties[i].id = i;
            pointerProperties[i].toolType = getToolType(inputSource);
            pointerCoords[i] = new MotionEvent.PointerCoords();
            pointerCoords[i].x = x;
            pointerCoords[i].y = y;
            pointerCoords[i].pressure = pressure;
            pointerCoords[i].size = DEFAULT_SIZE;
        }
        if (displayId == INVALID_DISPLAY
                && (inputSource & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            displayId = DEFAULT_DISPLAY;
        }
        MotionEvent event = MotionEvent.obtain(downTime, when, action, pointerCount,
                pointerProperties, pointerCoords, DEFAULT_META_STATE, DEFAULT_BUTTON_STATE,
                DEFAULT_PRECISION_X, DEFAULT_PRECISION_Y, getInputDeviceId(inputSource),
                DEFAULT_EDGE_FLAGS, inputSource, displayId, DEFAULT_FLAGS);
        InputManager.getInstance().injectInputEvent(event,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private float lerp(float a, float b, float alpha) {
        return (b - a) * alpha + a;
    }

    private int getSource(int inputSource, int defaultSource) {
        return inputSource == InputDevice.SOURCE_UNKNOWN ? defaultSource : inputSource;
    }

    private int getToolType(int inputSource) {
        switch(inputSource) {
            case InputDevice.SOURCE_MOUSE:
            case InputDevice.SOURCE_MOUSE_RELATIVE:
            case InputDevice.SOURCE_TRACKBALL:
                return MotionEvent.TOOL_TYPE_MOUSE;

            case InputDevice.SOURCE_STYLUS:
            case InputDevice.SOURCE_BLUETOOTH_STYLUS:
                return MotionEvent.TOOL_TYPE_STYLUS;

            case InputDevice.SOURCE_TOUCHPAD:
            case InputDevice.SOURCE_TOUCHSCREEN:
            case InputDevice.SOURCE_TOUCH_NAVIGATION:
                return MotionEvent.TOOL_TYPE_FINGER;
        }
        return MotionEvent.TOOL_TYPE_UNKNOWN;
    }

    @Override
    public final int onCommand(String cmd) {
        String arg = cmd;
        int inputSource = InputDevice.SOURCE_UNKNOWN;
        // Get source (optional).
        if (SOURCES.containsKey(arg)) {
            inputSource = SOURCES.get(arg);
            arg = getNextArgRequired();
        }

        // Get displayId (optional).
        int displayId = INVALID_DISPLAY;
        if ("-d".equals(arg)) {
            displayId = getDisplayId();
            arg = getNextArgRequired();
        }

        try {
            if ("text".equals(arg)) {
                runText(inputSource, displayId);
            } else if ("keyevent".equals(arg)) {
                runKeyEvent(inputSource, displayId);
            } else if ("tap".equals(arg)) {
                runTap(inputSource, displayId);
            } else if ("swipe".equals(arg)) {
                runSwipe(inputSource, displayId);
            } else if ("draganddrop".equals(arg)) {
                runDragAndDrop(inputSource, displayId);
            } else if ("press".equals(arg)) {
                runPress(inputSource, displayId);
            } else if ("roll".equals(arg)) {
                runRoll(inputSource, displayId);
            }  else if ("motionevent".equals(arg)) {
                runMotionEvent(inputSource, displayId);
            } else if ("keycombination".equals(arg)) {
                runKeyCombination(inputSource, displayId);
            } else {
                handleDefaultCommands(arg);
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(INVALID_ARGUMENTS + arg);
        }
        return 0;
    }

    @Override
    public final void onHelp() {
        try (PrintWriter out = getOutPrintWriter();) {
            out.println("Usage: input [<source>] [-d DISPLAY_ID] <command> [<arg>...]");
            out.println();
            out.println("The sources are: ");
            for (String src : SOURCES.keySet()) {
                out.println("      " + src);
            }
            out.println();
            out.printf("-d: specify the display ID.\n      (Default: %d for key event, "
                    + "%d for motion event if not specified.)",
                    INVALID_DISPLAY, DEFAULT_DISPLAY);
            out.println();
            out.println("The commands and default sources are:");
            out.println("      text <string> (Default: touchscreen)");
            out.println("      keyevent [--longpress|--doubletap] <key code number or name> ..."
                    + " (Default: keyboard)");
            out.println("      tap <x> <y> (Default: touchscreen)");
            out.println("      swipe <x1> <y1> <x2> <y2> [duration(ms)]"
                    + " (Default: touchscreen)");
            out.println("      draganddrop <x1> <y1> <x2> <y2> [duration(ms)]"
                    + " (Default: touchscreen)");
            out.println("      press (Default: trackball)");
            out.println("      roll <dx> <dy> (Default: trackball)");
            out.println("      motionevent <DOWN|UP|MOVE|CANCEL> <x> <y> (Default: touchscreen)");
            out.println("      keycombination <key code 1> <key code 2> ..."
                    + " (Default: keyboard)");
        }
    }

    private void runText(int inputSource, int displayId) {
        inputSource = getSource(inputSource, InputDevice.SOURCE_KEYBOARD);
        sendText(inputSource, getNextArgRequired(), displayId);
    }

    /**
     * Convert the characters of string text into key event's and send to
     * device.
     *
     * @param text is a string of characters you want to input to the device.
     */
    private void sendText(int source, final String text, int displayId) {
        final StringBuilder buff = new StringBuilder(text);
        boolean escapeFlag = false;
        for (int i = 0; i < buff.length(); i++) {
            if (escapeFlag) {
                escapeFlag = false;
                if (buff.charAt(i) == 's') {
                    buff.setCharAt(i, ' ');
                    buff.deleteCharAt(--i);
                }
            }
            if (buff.charAt(i) == '%') {
                escapeFlag = true;
            }
        }

        final char[] chars = buff.toString().toCharArray();
        final KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        final KeyEvent[] events = kcm.getEvents(chars);
        for (int i = 0; i < events.length; i++) {
            KeyEvent e = events[i];
            if (source != e.getSource()) {
                e.setSource(source);
            }
            e.setDisplayId(displayId);
            injectKeyEvent(e);
        }
    }

    private void runKeyEvent(int inputSource, int displayId) {
        String arg = getNextArgRequired();
        final boolean longpress = "--longpress".equals(arg);
        if (longpress) {
            arg = getNextArgRequired();
        } else {
            final boolean doubleTap = "--doubletap".equals(arg);
            if (doubleTap) {
                arg = getNextArgRequired();
                final int keycode = KeyEvent.keyCodeFromString(arg);
                sendKeyDoubleTap(inputSource, keycode, displayId);
                return;
            }
        }

        do {
            final int keycode = KeyEvent.keyCodeFromString(arg);
            sendKeyEvent(inputSource, keycode, longpress, displayId);
        } while ((arg = getNextArg()) != null);
    }

    private void sendKeyEvent(int inputSource, int keyCode, boolean longpress, int displayId) {
        final long now = SystemClock.uptimeMillis();

        KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0 /* repeatCount */,
                0 /*metaState*/, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/, 0 /*flags*/,
                inputSource);
        event.setDisplayId(displayId);

        injectKeyEvent(event);
        if (longpress) {
            // Some long press behavior would check the event time, we set a new event time here.
            final long nextEventTime = now + ViewConfiguration.getGlobalActionKeyTimeout();
            injectKeyEvent(KeyEvent.changeTimeRepeat(event, nextEventTime, 1 /* repeatCount */,
                    KeyEvent.FLAG_LONG_PRESS));
        }
        injectKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP));
    }

    private void sendKeyDoubleTap(int inputSource, int keyCode, int displayId) {
        sendKeyEvent(inputSource, keyCode, false, displayId);
        try {
            Thread.sleep(ViewConfiguration.getDoubleTapMinTime());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        sendKeyEvent(inputSource, keyCode, false, displayId);
    }

    private void runTap(int inputSource, int displayId) {
        inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
        sendTap(inputSource, Float.parseFloat(getNextArgRequired()),
                Float.parseFloat(getNextArgRequired()), displayId);
    }

    private void sendTap(int inputSource, float x, float y, int displayId) {
        final long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, now, now, x, y, 1.0f,
                displayId);
        injectMotionEvent(inputSource, MotionEvent.ACTION_UP, now, now, x, y, 0.0f, displayId);
    }

    private void runPress(int inputSource, int displayId) {
        inputSource = getSource(inputSource, InputDevice.SOURCE_TRACKBALL);
        sendTap(inputSource, 0.0f, 0.0f, displayId);
    }

    private void runSwipe(int inputSource, int displayId) {
        inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
        sendSwipe(inputSource, displayId, false);
    }

    private void sendSwipe(int inputSource, int displayId, boolean isDragDrop) {
        // Parse two points and duration.
        final float x1 = Float.parseFloat(getNextArgRequired());
        final float y1 = Float.parseFloat(getNextArgRequired());
        final float x2 = Float.parseFloat(getNextArgRequired());
        final float y2 = Float.parseFloat(getNextArgRequired());
        String durationArg = getNextArg();
        int duration = durationArg != null ? Integer.parseInt(durationArg) : -1;
        if (duration < 0) {
            duration = 300;
        }

        final long down = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, down, down, x1, y1, 1.0f,
                displayId);
        if (isDragDrop) {
            // long press until drag start.
            try {
                Thread.sleep(ViewConfiguration.getLongPressTimeout());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        long now = SystemClock.uptimeMillis();
        final long endTime = down + duration;
        while (now < endTime) {
            final long elapsedTime = now - down;
            final float alpha = (float) elapsedTime / duration;
            injectMotionEvent(inputSource, MotionEvent.ACTION_MOVE, down, now,
                    lerp(x1, x2, alpha), lerp(y1, y2, alpha), 1.0f, displayId);
            now = SystemClock.uptimeMillis();
        }
        injectMotionEvent(inputSource, MotionEvent.ACTION_UP, down, now, x2, y2, 0.0f,
                displayId);
    }

    private void runDragAndDrop(int inputSource, int displayId) {
        inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
        sendSwipe(inputSource, displayId, true);
    }

    private void runRoll(int inputSource, int displayId) {
        inputSource = getSource(inputSource, InputDevice.SOURCE_TRACKBALL);
        sendMove(inputSource, Float.parseFloat(getNextArgRequired()),
                Float.parseFloat(getNextArgRequired()), displayId);
    }

    /**
     * Sends a simple zero-pressure move event.
     *
     * @param inputSource the InputDevice.SOURCE_* sending the input event
     * @param dx change in x coordinate due to move
     * @param dy change in y coordinate due to move
     */
    private void sendMove(int inputSource, float dx, float dy, int displayId) {
        final long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_MOVE, now, now, dx, dy, 0.0f,
                displayId);
    }

    private int getAction() {
        String actionString = getNextArgRequired();
        switch (actionString.toUpperCase()) {
            case "DOWN":
                return MotionEvent.ACTION_DOWN;
            case "UP":
                return MotionEvent.ACTION_UP;
            case "MOVE":
                return MotionEvent.ACTION_MOVE;
            case "CANCEL":
                return MotionEvent.ACTION_CANCEL;
            default:
                throw new IllegalArgumentException("Unknown action: " + actionString);
        }
    }

    private void runMotionEvent(int inputSource, int displayId) {
        inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
        int action = getAction();
        float x = 0, y = 0;
        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP) {
            x = Float.parseFloat(getNextArgRequired());
            y = Float.parseFloat(getNextArgRequired());
        } else {
            // For ACTION_CANCEL, the positions are optional
            String xString = getNextArg();
            String yString = getNextArg();
            if (xString != null && yString != null) {
                x = Float.parseFloat(xString);
                y = Float.parseFloat(yString);
            }
        }

        sendMotionEvent(inputSource, action, x, y, displayId);
    }

    private void sendMotionEvent(int inputSource, int action, float x, float y,
            int displayId) {
        float pressure = NO_PRESSURE;

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            pressure = DEFAULT_PRESSURE;
        }

        final long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, action, now, now, x, y, pressure, displayId);
    }

    private void runKeyCombination(int inputSource, int displayId) {
        String arg = getNextArgRequired();
        ArrayList<Integer> keyCodes = new ArrayList<>();

        while (arg != null) {
            final int keyCode = KeyEvent.keyCodeFromString(arg);
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                throw new IllegalArgumentException("Unknown keycode: " + arg);
            }
            keyCodes.add(keyCode);
            arg = getNextArg();
        }

        // At least 2 keys.
        if (keyCodes.size() < 2) {
            throw new IllegalArgumentException("keycombination requires at least 2 keycodes");
        }

        sendKeyCombination(inputSource, keyCodes, displayId);
    }

    private void injectKeyEventAsync(KeyEvent event) {
        InputManager.getInstance().injectInputEvent(event,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private void sendKeyCombination(int inputSource, ArrayList<Integer> keyCodes, int displayId) {
        final long now = SystemClock.uptimeMillis();
        final int count = keyCodes.size();
        final KeyEvent[] events = new KeyEvent[count];
        for (int i = 0; i < count; i++) {
            final KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCodes.get(i), 0,
                    0 /*metaState*/, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/, 0 /*flags*/,
                    inputSource);
            event.setDisplayId(displayId);
            events[i] = event;
        }

        for (KeyEvent event: events) {
            // Use async inject so interceptKeyBeforeQueueing or interceptKeyBeforeDispatching could
            // handle keys.
            injectKeyEventAsync(event);
        }

        try {
            Thread.sleep(ViewConfiguration.getTapTimeout());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        for (KeyEvent event: events) {
            injectKeyEventAsync(KeyEvent.changeAction(event, KeyEvent.ACTION_UP));
        }
    }
}
