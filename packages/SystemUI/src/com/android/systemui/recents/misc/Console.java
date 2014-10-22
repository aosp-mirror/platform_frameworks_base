/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.misc;


import android.content.ComponentCallbacks2;
import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;


public class Console {
    // Timer
    public static final Map<Object, Long> mTimeLogs = new HashMap<Object, Long>();

    // Colors
    public static final String AnsiReset = "\u001B[0m";
    public static final String AnsiBlack = "\u001B[30m";
    public static final String AnsiRed = "\u001B[31m";      // SystemUIHandshake
    public static final String AnsiGreen = "\u001B[32m";    // MeasureAndLayout
    public static final String AnsiYellow = "\u001B[33m";   // SynchronizeViewsWithModel
    public static final String AnsiBlue = "\u001B[34m";     // TouchEvents, Search
    public static final String AnsiPurple = "\u001B[35m";   // Draw
    public static final String AnsiCyan = "\u001B[36m";     // ClickEvents
    public static final String AnsiWhite = "\u001B[37m";

    // Console enabled state
    public static boolean Enabled = false;

    /** Logs a key */
    public static void log(String key) {
        log(true, key, "", AnsiReset);
    }

    /** Logs a conditioned key */
    public static void log(boolean condition, String key) {
        if (condition) {
            log(condition, key, "", AnsiReset);
        }
    }

    /** Logs a key in a specific color */
    public static void log(boolean condition, String key, Object data) {
        if (condition) {
            log(condition, key, data, AnsiReset);
        }
    }

    /** Logs a key with data in a specific color */
    public static void log(boolean condition, String key, Object data, String color) {
        if (condition) {
            System.out.println(color + key + AnsiReset + " " + data.toString());
        }
    }

    /** Logs an error */
    public static void logError(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        Log.e("Recents", msg);
    }

    /** Logs a raw error */
    public static void logRawError(String msg, Exception e) {
        Log.e("Recents", msg, e);
    }

    /** Logs a divider bar */
    public static void logDivider(boolean condition) {
        if (condition) {
            System.out.println("==== [" + System.currentTimeMillis() +
                    "] ============================================================");
        }
    }

    /** Starts a time trace */
    public static void logStartTracingTime(boolean condition, String key) {
        if (condition) {
            long curTime = System.currentTimeMillis();
            mTimeLogs.put(key, curTime);
            Console.log(condition, "[Recents|" + key + "]",
                    "started @ " + curTime);
        }
    }

    /** Continues a time trace */
    public static void logTraceTime(boolean condition, String key, String desc) {
        if (condition) {
            long timeDiff = System.currentTimeMillis() - mTimeLogs.get(key);
            Console.log(condition, "[Recents|" + key + "|" + desc + "]",
                    "+" + timeDiff + "ms");
        }
    }

    /** Logs a stack trace */
    public static void logStackTrace() {
        logStackTrace("", 99);
    }

    /** Logs a stack trace to a certain depth */
    public static void logStackTrace(int depth) {
        logStackTrace("", depth);
    }

    /** Logs a stack trace to a certain depth with a key */
    public static void logStackTrace(String key, int depth) {
        int offset = 0;
        StackTraceElement[] callStack = Thread.currentThread().getStackTrace();
        String tinyStackTrace = "";
        // Skip over the known stack trace classes
        for (int i = 0; i < callStack.length; i++) {
            StackTraceElement el = callStack[i];
            String className = el.getClassName();
            if (className.indexOf("dalvik.system.VMStack") == -1 &&
                className.indexOf("java.lang.Thread") == -1 &&
                className.indexOf("recents.Console") == -1) {
                break;
            } else {
                offset++;
            }
        }
        // Build the pretty stack trace
        int start = Math.min(offset + depth, callStack.length);
        int end = offset;
        String indent = "";
        for (int i = start - 1; i >= end; i--) {
            StackTraceElement el = callStack[i];
            tinyStackTrace += indent + " -> " + el.getClassName() +
                    "[" + el.getLineNumber() + "]." + el.getMethodName();
            if (i > end) {
                tinyStackTrace += "\n";
                indent += "  ";
            }
        }
        log(true, key, tinyStackTrace, AnsiRed);
    }


    /** Returns the stringified MotionEvent action */
    public static String motionEventActionToString(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "Down";
            case MotionEvent.ACTION_UP:
                return "Up";
            case MotionEvent.ACTION_MOVE:
                return "Move";
            case MotionEvent.ACTION_CANCEL:
                return "Cancel";
            case MotionEvent.ACTION_POINTER_DOWN:
                return "Pointer Down";
            case MotionEvent.ACTION_POINTER_UP:
                return "Pointer Up";
            default:
                return "" + action;
        }
    }

    public static String trimMemoryLevelToString(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                return "UI Hidden";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                return "Running Moderate";
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                return "Background";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                return "Running Low";
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                return "Moderate";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                return "Critical";
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                return "Complete";
            default:
                return "" + level;
        }
    }
}
