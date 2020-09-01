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

package com.android.commands.input;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.os.BaseCommand;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Command that sends key events to the device, either by their keycode, or by
 * desired character output.
 */

public class Input extends BaseCommand {
    private static final String TAG = "Input";
    private static final String INVALID_ARGUMENTS = "Error: Invalid arguments for command: ";
    private static final String INVALID_DISPLAY_ARGUMENTS =
            "Error: Invalid arguments for display ID.";

    private static final float DEFAULT_PRESSURE = 1.0f;
    private static final float NO_PRESSURE = 0.0f;

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

    private static final Map<String, InputCmd> COMMANDS = new HashMap<String, InputCmd>();

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        (new Input()).run(args);
    }

    Input() {
        COMMANDS.put("text", new InputText());
        COMMANDS.put("keyevent", new InputKeyEvent());
        COMMANDS.put("tap", new InputTap());
        COMMANDS.put("swipe", new InputSwipe());
        COMMANDS.put("draganddrop", new InputDragAndDrop());
        COMMANDS.put("press", new InputPress());
        COMMANDS.put("roll", new InputRoll());
        COMMANDS.put("motionevent", new InputMotionEvent());
    }

    @Override
    public void onRun() throws Exception {
        String arg = nextArgRequired();
        int inputSource = InputDevice.SOURCE_UNKNOWN;

        // Get source (optional).
        if (SOURCES.containsKey(arg)) {
            inputSource = SOURCES.get(arg);
            arg = nextArgRequired();
        }

        // Get displayId (optional).
        int displayId = INVALID_DISPLAY;
        if ("-d".equals(arg)) {
            displayId = getDisplayId();
            arg = nextArgRequired();
        }

        // Get command and run.
        InputCmd cmd = COMMANDS.get(arg);
        if (cmd != null) {
            try {
                cmd.run(inputSource, displayId);
                return;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(INVALID_ARGUMENTS + arg);
            }
        }

        throw new IllegalArgumentException("Error: Unknown command: " + arg);
    }

    private int getDisplayId() {
        String displayArg = nextArgRequired();
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

    class InputText implements InputCmd {
        @Override
        public void run(int inputSource, int displayId) {
            inputSource = getSource(inputSource, InputDevice.SOURCE_KEYBOARD);
            sendText(inputSource, nextArgRequired(), displayId);
        }

        /**
         * Convert the characters of string text into key event's and send to
         * device.
         *
         * @param text is a string of characters you want to input to the device.
         */
        private void sendText(int source, final String text, int displayId) {
            final StringBuffer buff = new StringBuffer(text);
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
    }

    class InputKeyEvent implements InputCmd {
        @Override
        public void run(int inputSource, int displayId) {
            String arg = nextArgRequired();
            final boolean longpress = "--longpress".equals(arg);
            if (longpress) {
                arg = nextArgRequired();
            }

            do {
                final int keycode = KeyEvent.keyCodeFromString(arg);
                sendKeyEvent(inputSource, keycode, longpress, displayId);
            } while ((arg = nextArg()) != null);
        }

        private void sendKeyEvent(int inputSource, int keyCode, boolean longpress, int displayId) {
            final long now = SystemClock.uptimeMillis();
            int repeatCount = 0;

            KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, repeatCount,
                    0 /*metaState*/, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/, 0 /*flags*/,
                    inputSource);
            event.setDisplayId(displayId);

            injectKeyEvent(event);
            if (longpress) {
                repeatCount++;
                injectKeyEvent(KeyEvent.changeTimeRepeat(event, now, repeatCount,
                        KeyEvent.FLAG_LONG_PRESS));
            }
            injectKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP));
        }
    }

    class InputTap implements InputCmd {
        @Override
        public void run(int inputSource, int displayId) {
            inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
            sendTap(inputSource, Float.parseFloat(nextArgRequired()),
                    Float.parseFloat(nextArgRequired()), displayId);
        }

        void sendTap(int inputSource, float x, float y, int displayId) {
            final long now = SystemClock.uptimeMillis();
            injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, now, now, x, y, 1.0f,
                    displayId);
            injectMotionEvent(inputSource, MotionEvent.ACTION_UP, now, now, x, y, 0.0f, displayId);
        }
    }

    class InputPress extends InputTap {
        @Override
        public void run(int inputSource, int displayId) {
            inputSource = getSource(inputSource, InputDevice.SOURCE_TRACKBALL);
            sendTap(inputSource, 0.0f, 0.0f, displayId);
        }
    }

    class InputSwipe implements InputCmd {
        @Override
        public void run(int inputSource, int displayId) {
            inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
            sendSwipe(inputSource, displayId, false);
        }

        void sendSwipe(int inputSource, int displayId, boolean isDragDrop) {
            // Parse two points and duration.
            final float x1 = Float.parseFloat(nextArgRequired());
            final float y1 = Float.parseFloat(nextArgRequired());
            final float x2 = Float.parseFloat(nextArgRequired());
            final float y2 = Float.parseFloat(nextArgRequired());
            String durationArg = nextArg();
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
    }

    class InputDragAndDrop extends InputSwipe {
        @Override
        public void run(int inputSource, int displayId) {
            inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
            sendSwipe(inputSource, displayId, true);
        }
    }

    class InputRoll implements InputCmd {
        @Override
        public void run(int inputSource, int displayId) {
            inputSource = getSource(inputSource, InputDevice.SOURCE_TRACKBALL);
            sendMove(inputSource, Float.parseFloat(nextArgRequired()),
                    Float.parseFloat(nextArgRequired()), displayId);
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
    }

    class InputMotionEvent implements InputCmd {
        @Override
        public void run(int inputSource, int displayId) {
            inputSource = getSource(inputSource, InputDevice.SOURCE_TOUCHSCREEN);
            sendMotionEvent(inputSource, nextArgRequired(), Float.parseFloat(nextArgRequired()),
                    Float.parseFloat(nextArgRequired()), displayId);
        }

        private void sendMotionEvent(int inputSource, String motionEventType, float x, float y,
                int displayId) {
            final int action;
            final float pressure;

            switch (motionEventType.toUpperCase()) {
                case "DOWN":
                    action = MotionEvent.ACTION_DOWN;
                    pressure = DEFAULT_PRESSURE;
                    break;
                case "UP":
                    action = MotionEvent.ACTION_UP;
                    pressure = NO_PRESSURE;
                    break;
                case "MOVE":
                    action = MotionEvent.ACTION_MOVE;
                    pressure = DEFAULT_PRESSURE;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown motionevent " + motionEventType);
            }

            final long now = SystemClock.uptimeMillis();
            injectMotionEvent(inputSource, action, now, now, x, y, pressure, displayId);
        }
    }

    /**
     * Abstract class for command
     * use nextArgRequired or nextArg to check next argument if necessary.
     */
    private interface InputCmd {
        void run(int inputSource, int displayId);
    }

    private static void injectKeyEvent(KeyEvent event) {
        InputManager.getInstance().injectInputEvent(event,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private static int getInputDeviceId(int inputSource) {
        final int DEFAULT_DEVICE_ID = 0;
        int[] devIds = InputDevice.getDeviceIds();
        for (int devId : devIds) {
            InputDevice inputDev = InputDevice.getDevice(devId);
            if (inputDev.supportsSource(inputSource)) {
                return devId;
            }
        }
        return DEFAULT_DEVICE_ID;
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
    private static void injectMotionEvent(int inputSource, int action, long downTime, long when,
            float x, float y, float pressure, int displayId) {
        final float DEFAULT_SIZE = 1.0f;
        final int DEFAULT_META_STATE = 0;
        final float DEFAULT_PRECISION_X = 1.0f;
        final float DEFAULT_PRECISION_Y = 1.0f;
        final int DEFAULT_EDGE_FLAGS = 0;
        MotionEvent event = MotionEvent.obtain(downTime, when, action, x, y, pressure, DEFAULT_SIZE,
                DEFAULT_META_STATE, DEFAULT_PRECISION_X, DEFAULT_PRECISION_Y,
                getInputDeviceId(inputSource), DEFAULT_EDGE_FLAGS);
        event.setSource(inputSource);
        if (displayId == INVALID_DISPLAY && (inputSource & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            displayId = DEFAULT_DISPLAY;
        }
        event.setDisplayId(displayId);
        InputManager.getInstance().injectInputEvent(event,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private static final float lerp(float a, float b, float alpha) {
        return (b - a) * alpha + a;
    }

    private static final int getSource(int inputSource, int defaultSource) {
        return inputSource == InputDevice.SOURCE_UNKNOWN ? defaultSource : inputSource;
    }

    @Override
    public void onShowUsage(PrintStream out) {
        out.println("Usage: input [<source>] [-d DISPLAY_ID] <command> [<arg>...]");
        out.println();
        out.println("The sources are: ");
        for (String src : SOURCES.keySet()) {
            out.println("      " + src);
        }
        out.println();
        out.printf("-d: specify the display ID.\n"
                + "      (Default: %d for key event, %d for motion event if not specified.)",
                INVALID_DISPLAY, DEFAULT_DISPLAY);
        out.println();
        out.println("The commands and default sources are:");
        out.println("      text <string> (Default: touchscreen)");
        out.println("      keyevent [--longpress] <key code number or name> ..."
                + " (Default: keyboard)");
        out.println("      tap <x> <y> (Default: touchscreen)");
        out.println("      swipe <x1> <y1> <x2> <y2> [duration(ms)]"
                + " (Default: touchscreen)");
        out.println("      draganddrop <x1> <y1> <x2> <y2> [duration(ms)]"
                + " (Default: touchscreen)");
        out.println("      press (Default: trackball)");
        out.println("      roll <dx> <dy> (Default: trackball)");
        out.println("      motionevent <DOWN|UP|MOVE> <x> <y> (Default: touchscreen)");
    }
}
