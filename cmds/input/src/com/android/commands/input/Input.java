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

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Command that sends key events to the device, either by their keycode, or by
 * desired character output.
 */

public class Input {
    private static final String TAG = "Input";

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        (new Input()).run(args);
    }

    private void run(String[] args) {
        if (args.length < 1) {
            showUsage();
            return;
        }

        String command = args[0];

        try {
            if (command.equals("text")) {
                if (args.length == 2) {
                    sendText(args[1]);
                    return;
                }
            } else if (command.equals("keyevent")) {
                if (args.length >= 2) {
                    for (int i=1; i < args.length; i++) {
                        int keyCode = KeyEvent.keyCodeFromString(args[i]);
                        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                            keyCode = KeyEvent.keyCodeFromString("KEYCODE_" + args[i]);
                        }
                        sendKeyEvent(keyCode);
                    }
                    return;
                }
            } else if (command.equals("tap")) {
                if (args.length == 3) {
                    sendTap(InputDevice.SOURCE_TOUCHSCREEN, Float.parseFloat(args[1]), Float.parseFloat(args[2]));
                    return;
                }
            } else if (command.equals("swipe")) {
                if (args.length == 5) {
                    sendSwipe(InputDevice.SOURCE_TOUCHSCREEN, Float.parseFloat(args[1]), Float.parseFloat(args[2]),
                            Float.parseFloat(args[3]), Float.parseFloat(args[4]));
                    return;
                }
            } else if (command.equals("touchscreen") || command.equals("touchpad")) {
                // determine input source
                int inputSource = InputDevice.SOURCE_TOUCHSCREEN;
                if (command.equals("touchpad")) {
                    inputSource = InputDevice.SOURCE_TOUCHPAD;
                }
                // determine subcommand
                if (args.length > 1) {
                    String subcommand = args[1];
                    if (subcommand.equals("tap")) {
                        if (args.length == 4) {
                            sendTap(inputSource, Float.parseFloat(args[2]),
                                    Float.parseFloat(args[3]));
                            return;
                        }
                    } else if (subcommand.equals("swipe")) {
                        if (args.length == 6) {
                            sendSwipe(inputSource, Float.parseFloat(args[2]),
                                    Float.parseFloat(args[3]), Float.parseFloat(args[4]),
                                    Float.parseFloat(args[5]));
                            return;
                        }
                    }
                }
            } else if (command.equals("trackball")) {
                // determine subcommand
                if (args.length > 1) {
                    String subcommand = args[1];
                    if (subcommand.equals("press")) {
                        sendTap(InputDevice.SOURCE_TRACKBALL, 0.0f, 0.0f);
                        return;
                    } else if (subcommand.equals("roll")) {
                        if (args.length == 4) {
                            sendMove(InputDevice.SOURCE_TRACKBALL, Float.parseFloat(args[2]),
                                    Float.parseFloat(args[3]));
                            return;
                        }
                    }
                }
            } else {
                System.err.println("Error: Unknown command: " + command);
                showUsage();
                return;
            }
        } catch (NumberFormatException ex) {
        }
        System.err.println("Error: Invalid arguments for command: " + command);
        showUsage();
    }

    /**
     * Convert the characters of string text into key event's and send to
     * device.
     *
     * @param text is a string of characters you want to input to the device.
     */
    private void sendText(String text) {

        StringBuffer buff = new StringBuffer(text);

        boolean escapeFlag = false;
        for (int i=0; i<buff.length(); i++) {
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

        char[] chars = buff.toString().toCharArray();

        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events = kcm.getEvents(chars);
        for(int i = 0; i < events.length; i++) {
            injectKeyEvent(events[i]);
        }
    }

    private void sendKeyEvent(int keyCode) {
        long now = SystemClock.uptimeMillis();
        injectKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        injectKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private void sendTap(int inputSource, float x, float y) {
        long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, now, x, y, 1.0f);
        injectMotionEvent(inputSource, MotionEvent.ACTION_UP, now, x, y, 0.0f);
    }

    private void sendSwipe(int inputSource, float x1, float y1, float x2, float y2) {
        final int NUM_STEPS = 11;
        long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_DOWN, now, x1, y1, 1.0f);
        for (int i = 1; i < NUM_STEPS; i++) {
            float alpha = (float) i / NUM_STEPS;
            injectMotionEvent(inputSource, MotionEvent.ACTION_MOVE, now, lerp(x1, x2, alpha),
                    lerp(y1, y2, alpha), 1.0f);
        }
        injectMotionEvent(inputSource, MotionEvent.ACTION_UP, now, x1, y1, 0.0f);
    }

    /**
     * Sends a simple zero-pressure move event.
     *
     * @param inputSource the InputDevice.SOURCE_* sending the input event
     * @param dx change in x coordinate due to move
     * @param dy change in y coordinate due to move
     */
    private void sendMove(int inputSource, float dx, float dy) {
        long now = SystemClock.uptimeMillis();
        injectMotionEvent(inputSource, MotionEvent.ACTION_MOVE, now, dx, dy, 0.0f);
    }

    private void injectKeyEvent(KeyEvent event) {
        Log.i(TAG, "injectKeyEvent: " + event);
        InputManager.getInstance().injectInputEvent(event,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    /**
     * Builds a MotionEvent and injects it into the event stream.
     *
     * @param inputSource the InputDevice.SOURCE_* sending the input event
     * @param action the MotionEvent.ACTION_* for the event
     * @param when the value of SystemClock.uptimeMillis() at which the event happened
     * @param x x coordinate of event
     * @param y y coordinate of event
     * @param pressure pressure of event
     */
    private void injectMotionEvent(int inputSource, int action, long when, float x, float y, float pressure) {
        final float DEFAULT_SIZE = 1.0f;
        final int DEFAULT_META_STATE = 0;
        final float DEFAULT_PRECISION_X = 1.0f;
        final float DEFAULT_PRECISION_Y = 1.0f;
        final int DEFAULT_DEVICE_ID = 0;
        final int DEFAULT_EDGE_FLAGS = 0;
        MotionEvent event = MotionEvent.obtain(when, when, action, x, y, pressure, DEFAULT_SIZE,
                DEFAULT_META_STATE, DEFAULT_PRECISION_X, DEFAULT_PRECISION_Y, DEFAULT_DEVICE_ID,
                DEFAULT_EDGE_FLAGS);
        event.setSource(inputSource);
        Log.i(TAG, "injectMotionEvent: " + event);
        InputManager.getInstance().injectInputEvent(event,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private static final float lerp(float a, float b, float alpha) {
        return (b - a) * alpha + a;
    }

    private void showUsage() {
        System.err.println("usage: input ...");
        System.err.println("       input text <string>");
        System.err.println("       input keyevent <key code number or name> ...");
        System.err.println("       input [touchscreen|touchpad] tap <x> <y>");
        System.err.println("       input [touchscreen|touchpad] swipe <x1> <y1> <x2> <y2>");
        System.err.println("       input trackball press");
        System.err.println("       input trackball roll <dx> <dy>");
    }
}
