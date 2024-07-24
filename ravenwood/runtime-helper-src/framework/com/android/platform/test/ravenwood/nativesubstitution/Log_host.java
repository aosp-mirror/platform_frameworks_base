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
package com.android.platform.test.ravenwood.nativesubstitution;

import android.util.Log;
import android.util.Log.Level;

import com.android.internal.os.RuntimeInit;

import java.io.PrintStream;

/**
 * Ravenwood "native substitution" class for {@link android.util.Log}.
 *
 * {@link android.util.Log} already uses the actual native code and doesn't use this class.
 * In order to switch to this Java implementation, uncomment the @RavenwoodNativeSubstitutionClass
 * annotation on {@link android.util.Log}.
 */
public class Log_host {

    public static boolean isLoggable(String tag, @Level int level) {
        return true;
    }

    public static int println_native(int bufID, int priority, String tag, String msg) {
        final String buffer;
        switch (bufID) {
            case Log.LOG_ID_MAIN: buffer = "main"; break;
            case Log.LOG_ID_RADIO: buffer = "radio"; break;
            case Log.LOG_ID_EVENTS: buffer = "event"; break;
            case Log.LOG_ID_SYSTEM: buffer = "system"; break;
            case Log.LOG_ID_CRASH: buffer = "crash"; break;
            default: buffer = "buf:" + bufID; break;
        };

        final String prio;
        switch (priority) {
            case Log.VERBOSE: prio = "V"; break;
            case Log.DEBUG: prio = "D"; break;
            case Log.INFO: prio = "I"; break;
            case Log.WARN: prio = "W"; break;
            case Log.ERROR: prio = "E"; break;
            case Log.ASSERT: prio = "A"; break;
            default: prio = "prio:" + priority; break;
        };

        for (String s : msg.split("\\n")) {
            getRealOut().println(String.format("logd: [%s] %s %s: %s", buffer, prio, tag, s));
        }
        return msg.length();
    }

    public static int logger_entry_max_payload_native() {
        return 4068; // [ravenwood] This is what people use in various places.
    }

    /**
     * Return the "real" {@code System.out} if it's been swapped by {@code RavenwoodRuleImpl}, so
     * that we don't end up in a recursive loop.
     */
    private static PrintStream getRealOut() {
        if (RuntimeInit.sOut$ravenwood != null) {
            return RuntimeInit.sOut$ravenwood;
        } else {
            return System.out;
        }
    }
}
