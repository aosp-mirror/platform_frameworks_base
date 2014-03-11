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

package com.android.systemui.recents;


import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

public class Console {
    // Colors
    public static final String AnsiReset = "\u001B[0m";
    public static final String AnsiBlack = "\u001B[30m";
    public static final String AnsiRed = "\u001B[31m";      // SystemUIHandshake
    public static final String AnsiGreen = "\u001B[32m";    // MeasureAndLayout
    public static final String AnsiYellow = "\u001B[33m";   // SynchronizeViewsWithModel
    public static final String AnsiBlue = "\u001B[34m";     // TouchEvents
    public static final String AnsiPurple = "\u001B[35m";   // Draw
    public static final String AnsiCyan = "\u001B[36m";     // ClickEvents
    public static final String AnsiWhite = "\u001B[37m";

    /** Logs a key */
    public static void log(String key) {
        Console.log(true, key, "", AnsiReset);
    }

    /** Logs a conditioned key */
    public static void log(boolean condition, String key) {
        if (condition) {
            Console.log(condition, key, "", AnsiReset);
        }
    }

    /** Logs a key in a specific color */
    public static void log(boolean condition, String key, Object data) {
        if (condition) {
            Console.log(condition, key, data, AnsiReset);
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

    /** Logs a divider bar */
    public static void logDivider(boolean condition) {
        if (condition) {
            System.out.println("==== [" + System.currentTimeMillis() +
                    "] ============================================================");
        }
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
}
