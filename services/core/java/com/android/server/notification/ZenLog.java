/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.content.ComponentName;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.provider.Settings.Global;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.util.Slog;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

public class ZenLog {
    private static final String TAG = "ZenLog";

    private static final int SIZE = Build.IS_DEBUGGABLE ? 100 : 20;

    private static final long[] TIMES = new long[SIZE];
    private static final int[] TYPES = new int[SIZE];
    private static final String[] MSGS = new String[SIZE];

    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private static final int TYPE_INTERCEPTED = 1;
    private static final int TYPE_ALLOW_DISABLE = 2;
    private static final int TYPE_SET_RINGER_MODE = 3;
    private static final int TYPE_DOWNTIME = 4;
    private static final int TYPE_ZEN_MODE = 5;
    private static final int TYPE_EXIT_CONDITION = 6;
    private static final int TYPE_SUBSCRIBE = 7;
    private static final int TYPE_UNSUBSCRIBE = 8;
    private static final int TYPE_CONFIG = 9;

    private static int sNext;
    private static int sSize;

    public static void traceIntercepted(NotificationRecord record, String reason) {
        if (record != null && record.isIntercepted()) return;  // already logged
        append(TYPE_INTERCEPTED, record.getKey() + "," + reason);
    }

    public static void traceAllowDisable(String pkg, boolean allowDisable, String reason) {
        append(TYPE_ALLOW_DISABLE, allowDisable + "," + pkg + "," + reason);
    }

    public static void traceSetRingerMode(int ringerMode) {
        append(TYPE_SET_RINGER_MODE, ringerModeToString(ringerMode));
    }

    public static void traceDowntime(boolean enter, int day, int[] days) {
        append(TYPE_DOWNTIME, enter + ",day=" + day + ",days=" + (days != null ? Arrays.asList(days)
                : null));
    }

    public static void traceUpdateZenMode(int fromMode, int toMode) {
        append(TYPE_ZEN_MODE, zenModeToString(fromMode) + " -> " + zenModeToString(toMode));
    }

    public static void traceExitCondition(Uri id, ComponentName component, String reason) {
        append(TYPE_EXIT_CONDITION, id + "," + componentToString(component) + "," + reason);
    }

    public static void traceSubscribe(Uri uri, IConditionProvider provider, RemoteException e) {
        append(TYPE_SUBSCRIBE, uri + "," + subscribeResult(provider, e));
    }

    public static void traceUnsubscribe(Uri uri, IConditionProvider provider, RemoteException e) {
        append(TYPE_UNSUBSCRIBE, uri + "," + subscribeResult(provider, e));
    }

    public static void traceConfig(ZenModeConfig oldConfig, ZenModeConfig newConfig) {
        append(TYPE_CONFIG, newConfig != null ? newConfig.toString() : null);
    }

    private static String subscribeResult(IConditionProvider provider, RemoteException e) {
        return provider == null ? "no provider" : e != null ? e.getMessage() : "ok";
    }

    private static String typeToString(int type) {
        switch (type) {
            case TYPE_INTERCEPTED: return "intercepted";
            case TYPE_ALLOW_DISABLE: return "allow_disable";
            case TYPE_SET_RINGER_MODE: return "set_ringer_mode";
            case TYPE_DOWNTIME: return "downtime";
            case TYPE_ZEN_MODE: return "zen_mode";
            case TYPE_EXIT_CONDITION: return "exit_condition";
            case TYPE_SUBSCRIBE: return "subscribe";
            case TYPE_UNSUBSCRIBE: return "unsubscribe";
            case TYPE_CONFIG: return "config";
            default: return "unknown";
        }
    }

    private static String ringerModeToString(int ringerMode) {
        switch (ringerMode) {
            case AudioManager.RINGER_MODE_SILENT: return "silent";
            case AudioManager.RINGER_MODE_VIBRATE: return "vibrate";
            case AudioManager.RINGER_MODE_NORMAL: return "normal";
            default: return "unknown";
        }
    }

    private static String zenModeToString(int zenMode) {
        switch (zenMode) {
            case Global.ZEN_MODE_OFF: return "off";
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS: return "important_interruptions";
            case Global.ZEN_MODE_NO_INTERRUPTIONS: return "no_interruptions";
            default: return "unknown";
        }
    }

    private static String componentToString(ComponentName component) {
        return component != null ? component.toShortString() : null;
    }

    private static void append(int type, String msg) {
        synchronized(MSGS) {
            TIMES[sNext] = System.currentTimeMillis();
            TYPES[sNext] = type;
            MSGS[sNext] = msg;
            sNext = (sNext + 1) % SIZE;
            if (sSize < SIZE) {
                sSize++;
            }
        }
        Slog.d(TAG, typeToString(type) + ": " + msg);
    }

    public static void dump(PrintWriter pw, String prefix) {
        synchronized(MSGS) {
            final int start = (sNext - sSize + SIZE) % SIZE;
            for (int i = 0; i < sSize; i++) {
                final int j = (start + i) % SIZE;
                pw.print(prefix);
                pw.print(FORMAT.format(new Date(TIMES[j])));
                pw.print(' ');
                pw.print(typeToString(TYPES[j]));
                pw.print(": ");
                pw.println(MSGS[j]);
            }
        }
    }
}
