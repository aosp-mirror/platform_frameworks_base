/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

import android.app.ActivityThread;
import android.app.Application;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps track of interesting falsing data.
 *
 * By default the log only gets collected on userdebug builds. To turn it on on user:
 *  adb shell setprop debug.falsing_log true
 *
 * The log gets dumped as part of the SystemUI services. To dump on demand:
 *  adb shell dumpsys activity service com.android.systemui SystemBars | grep -A 999 FALSING | less
 *
 * To dump into logcat:
 *  adb shell setprop debug.falsing_logcat true
 *
 * To adjust the log buffer size:
 *  adb shell setprop debug.falsing_log_size 200
 */
public class FalsingLog {
    public static final boolean ENABLED = SystemProperties.getBoolean("debug.falsing_log",
            Build.IS_DEBUGGABLE);
    private static final boolean LOGCAT = SystemProperties.getBoolean("debug.falsing_logcat",
            false);

    public static final boolean VERBOSE = false;

    private static final int MAX_SIZE = SystemProperties.getInt("debug.falsing_log_size", 100);

    private static final String TAG = "FalsingLog";

    private final ArrayDeque<String> mLog = new ArrayDeque<>(MAX_SIZE);
    private final SimpleDateFormat mFormat = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static FalsingLog sInstance;

    private FalsingLog() {
    }

    public static void v(String tag, String s) {
        if (!VERBOSE) {
            return;
        }
        if (LOGCAT) {
            Log.v(TAG, tag + "\t" + s);
        }
        log("V", tag, s);
    }

    public static void i(String tag, String s) {
        if (LOGCAT) {
            Log.i(TAG, tag + "\t" + s);
        }
        log("I", tag, s);
    }

    public static void wLogcat(String tag, String s) {
        Log.w(TAG, tag + "\t" + s);
        log("W", tag, s);
    }

    public static void w(String tag, String s) {
        if (LOGCAT) {
            Log.w(TAG, tag + "\t" + s);
        }
        log("W", tag, s);
    }

    public static void e(String tag, String s) {
        if (LOGCAT) {
            Log.e(TAG, tag + "\t" + s);
        }
        log("E", tag, s);
    }

    public static synchronized void log(String level, String tag, String s) {
        if (!ENABLED) {
            return;
        }
        if (sInstance == null) {
            sInstance = new FalsingLog();
        }

        if (sInstance.mLog.size() >= MAX_SIZE) {
            sInstance.mLog.removeFirst();
        }
        String entry = new StringBuilder().append(sInstance.mFormat.format(new Date()))
            .append(" ").append(level).append(" ")
            .append(tag).append(" ").append(s).toString();
        sInstance.mLog.add(entry);
    }

    public static synchronized void dump(PrintWriter pw) {
        pw.println("FALSING LOG:");
        if (!ENABLED) {
            pw.println("Disabled, to enable: setprop debug.falsing_log 1");
            pw.println();
            return;
        }
        if (sInstance == null || sInstance.mLog.isEmpty()) {
            pw.println("<empty>");
            pw.println();
            return;
        }
        for (String s : sInstance.mLog) {
            pw.println(s);
        }
        pw.println();
    }

    public static synchronized void wtf(String tag, String s, Throwable here) {
        if (!ENABLED) {
            return;
        }
        e(tag, s);

        Application application = ActivityThread.currentApplication();
        String fileMessage = "";
        if (Build.IS_DEBUGGABLE && application != null) {
            File f = new File(application.getDataDir(), "falsing-"
                    + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date()) + ".txt");
            PrintWriter pw = null;
            try {
                pw = new PrintWriter(f);
                dump(pw);
                pw.close();
                fileMessage = "Log written to " + f.getAbsolutePath();
            } catch (IOException e) {
                Log.e(TAG, "Unable to write falsing log", e);
            } finally {
                if (pw != null) {
                    pw.close();
                }
            }
        } else {
            Log.e(TAG, "Unable to write log, build must be debuggable.");
        }

        Log.wtf(TAG, tag + " " + s + "; " + fileMessage, here);
    }
}
