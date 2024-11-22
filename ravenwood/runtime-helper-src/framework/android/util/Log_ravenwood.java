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
package android.util;

import android.util.Log.Level;

import com.android.internal.os.RuntimeInit;
import com.android.ravenwood.RavenwoodRuntimeNative;
import com.android.ravenwood.common.RavenwoodCommonUtils;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ravenwood "native substitution" class for {@link android.util.Log}.
 *
 * {@link android.util.Log} already uses the actual native code and doesn't use this class.
 * In order to switch to this Java implementation, uncomment the @RavenwoodNativeSubstitutionClass
 * annotation on {@link android.util.Log}.
 */
public class Log_ravenwood {

    public static final SimpleDateFormat sTimestampFormat =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    public static boolean isLoggable(String tag, @Level int level) {
        return true;
    }

    public static int println_native(int bufID, int priority, String tag, String msg) {
        if (priority < Log.INFO && !RavenwoodCommonUtils.RAVENWOOD_VERBOSE_LOGGING) {
            return msg.length(); // No verbose logging.
        }

        final String prio;
        switch (priority) {
            case Log.VERBOSE: prio = "V"; break;
            case Log.DEBUG: prio = "D"; break;
            case Log.INFO: prio = "I"; break;
            case Log.WARN: prio = "W"; break;
            case Log.ERROR: prio = "E"; break;
            case Log.ASSERT: prio = "A"; break;
            default: prio = "prio:" + priority; break;
        }

        String leading =  sTimestampFormat.format(new Date())
                + " %-6d %-6d %s %-8s: ".formatted(getPid(), getTid(), prio, tag);
        var out = getRealOut();
        for (String s : msg.split("\\n")) {
            out.print(leading);
            out.println(s);
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

    /**
     * PID. We need to use a JNI method to get it, but JNI isn't initially ready.
     * Call {@link #onRavenwoodRuntimeNativeReady} to signal when JNI is ready, at which point
     * we set this field.
     * (We don't want to call native methods that may not be fully initialized even with a
     * try-catch, because partially initialized JNI methods could crash the process.)
     */
    private static volatile int sPid = 0;

    private static ThreadLocal<Integer> sTid =
            ThreadLocal.withInitial(RavenwoodRuntimeNative::gettid);

    /**
     * Call it when {@link RavenwoodRuntimeNative} is usable.
     */
    public static void onRavenwoodRuntimeNativeReady() {
        sPid = RavenwoodRuntimeNative.getpid();
    }

    private static int getPid() {
        return sPid;
    }

    private static int getTid() {
        if (sPid == 0) {
            return 0; // Native methods not ready yet.
        }
        return sTid.get();
    }
}
