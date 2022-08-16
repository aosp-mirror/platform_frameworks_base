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

import android.app.NotificationManager;
import android.content.ComponentName;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.NotificationListenerService;
import android.service.notification.ZenModeConfig;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class ZenLog {
    private static final String TAG = "ZenLog";
    // the ZenLog is *very* verbose, so be careful about setting this to true
    private static final boolean DEBUG = false;

    private static final int SIZE = Build.IS_DEBUGGABLE ? 200 : 100;

    private static final long[] TIMES = new long[SIZE];
    private static final int[] TYPES = new int[SIZE];
    private static final String[] MSGS = new String[SIZE];

    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private static final int TYPE_INTERCEPTED = 1;
    private static final int TYPE_ALLOW_DISABLE = 2;
    private static final int TYPE_SET_RINGER_MODE_EXTERNAL = 3;
    private static final int TYPE_SET_RINGER_MODE_INTERNAL = 4;
    private static final int TYPE_DOWNTIME = 5;
    private static final int TYPE_SET_ZEN_MODE = 6;
    private static final int TYPE_UPDATE_ZEN_MODE = 7;
    private static final int TYPE_EXIT_CONDITION = 8;
    private static final int TYPE_SUBSCRIBE = 9;
    private static final int TYPE_UNSUBSCRIBE = 10;
    private static final int TYPE_CONFIG = 11;
    private static final int TYPE_NOT_INTERCEPTED = 12;
    private static final int TYPE_DISABLE_EFFECTS = 13;
    private static final int TYPE_SUPPRESSOR_CHANGED = 14;
    private static final int TYPE_LISTENER_HINTS_CHANGED = 15;
    private static final int TYPE_SET_NOTIFICATION_POLICY = 16;
    private static final int TYPE_SET_CONSOLIDATED_ZEN_POLICY = 17;
    private static final int TYPE_MATCHES_CALL_FILTER = 18;

    private static int sNext;
    private static int sSize;

    public static void traceIntercepted(NotificationRecord record, String reason) {
        if (record != null && record.isIntercepted()) return;  // already logged
        append(TYPE_INTERCEPTED, record.getKey() + "," + reason);
    }

    public static void traceNotIntercepted(NotificationRecord record, String reason) {
        if (record != null && record.isUpdate) return;  // already logged
        append(TYPE_NOT_INTERCEPTED, record.getKey() + "," + reason);
    }

    public static void traceSetRingerModeExternal(int ringerModeOld, int ringerModeNew,
            String caller, int ringerModeInternalIn, int ringerModeInternalOut) {
        append(TYPE_SET_RINGER_MODE_EXTERNAL, caller + ",e:" +
                ringerModeToString(ringerModeOld) + "->" +
                ringerModeToString(ringerModeNew)  + ",i:" +
                ringerModeToString(ringerModeInternalIn) + "->" +
                ringerModeToString(ringerModeInternalOut));
    }

    public static void traceSetRingerModeInternal(int ringerModeOld, int ringerModeNew,
            String caller, int ringerModeExternalIn, int ringerModeExternalOut) {
        append(TYPE_SET_RINGER_MODE_INTERNAL, caller + ",i:" +
                ringerModeToString(ringerModeOld) + "->" +
                ringerModeToString(ringerModeNew)  + ",e:" +
                ringerModeToString(ringerModeExternalIn) + "->" +
                ringerModeToString(ringerModeExternalOut));
    }

    public static void traceDowntimeAutotrigger(String result) {
        append(TYPE_DOWNTIME, result);
    }

    public static void traceSetZenMode(int zenMode, String reason) {
        append(TYPE_SET_ZEN_MODE, zenModeToString(zenMode) + "," + reason);
    }

    /**
     * trace setting the consolidated zen policy
     */
    public static void traceSetConsolidatedZenPolicy(NotificationManager.Policy policy,
            String reason) {
        append(TYPE_SET_CONSOLIDATED_ZEN_POLICY, policy.toString() + "," + reason);
    }

    public static void traceUpdateZenMode(int fromMode, int toMode) {
        append(TYPE_UPDATE_ZEN_MODE, zenModeToString(fromMode) + " -> " + zenModeToString(toMode));
    }

    public static void traceExitCondition(Condition c, ComponentName component, String reason) {
        append(TYPE_EXIT_CONDITION, c + "," + componentToString(component) + "," + reason);
    }

    public static void traceSetNotificationPolicy(String pkg, int targetSdk,
            NotificationManager.Policy policy) {
        String policyLog = "pkg=" + pkg + " targetSdk=" + targetSdk
                + " NotificationPolicy=" + policy.toString();
        append(TYPE_SET_NOTIFICATION_POLICY, policyLog);
        // TODO(b/180205791): remove when we can better surface apps that are changing policy
        Log.d(TAG, "Zen Policy Changed: " + policyLog);
    }

    public static void traceSubscribe(Uri uri, IConditionProvider provider, RemoteException e) {
        append(TYPE_SUBSCRIBE, uri + "," + subscribeResult(provider, e));
    }

    public static void traceUnsubscribe(Uri uri, IConditionProvider provider, RemoteException e) {
        append(TYPE_UNSUBSCRIBE, uri + "," + subscribeResult(provider, e));
    }

    public static void traceConfig(String reason, ZenModeConfig oldConfig,
            ZenModeConfig newConfig) {
        ZenModeConfig.Diff diff = ZenModeConfig.diff(oldConfig, newConfig);
        if (diff.isEmpty()) {
            append(TYPE_CONFIG, reason + " no changes");
        } else {
            append(TYPE_CONFIG, reason
                    + ",\n" + (newConfig != null ? newConfig.toString() : null)
                    + ",\n" + ZenModeConfig.diff(oldConfig, newConfig));
        }
    }

    public static void traceDisableEffects(NotificationRecord record, String reason) {
        append(TYPE_DISABLE_EFFECTS, record.getKey() + "," + reason);
    }

    public static void traceEffectsSuppressorChanged(List<ComponentName> oldSuppressors,
            List<ComponentName> newSuppressors, long suppressedEffects) {
        append(TYPE_SUPPRESSOR_CHANGED, "suppressed effects:" + suppressedEffects + ","
                + componentListToString(oldSuppressors) + "->"
                + componentListToString(newSuppressors));
    }

    public static void traceListenerHintsChanged(int oldHints, int newHints, int listenerCount) {
        append(TYPE_LISTENER_HINTS_CHANGED, hintsToString(oldHints) + "->"
            + hintsToString(newHints) + ",listeners=" + listenerCount);
    }

    /*
     * Trace calls to matchesCallFilter with the result of the call and the reason for the result.
     */
    public static void traceMatchesCallFilter(boolean result, String reason) {
        append(TYPE_MATCHES_CALL_FILTER, "result=" + result + ", reason=" + reason);
    }

    private static String subscribeResult(IConditionProvider provider, RemoteException e) {
        return provider == null ? "no provider" : e != null ? e.getMessage() : "ok";
    }

    private static String typeToString(int type) {
        switch (type) {
            case TYPE_INTERCEPTED: return "intercepted";
            case TYPE_ALLOW_DISABLE: return "allow_disable";
            case TYPE_SET_RINGER_MODE_EXTERNAL: return "set_ringer_mode_external";
            case TYPE_SET_RINGER_MODE_INTERNAL: return "set_ringer_mode_internal";
            case TYPE_DOWNTIME: return "downtime";
            case TYPE_SET_ZEN_MODE: return "set_zen_mode";
            case TYPE_UPDATE_ZEN_MODE: return "update_zen_mode";
            case TYPE_EXIT_CONDITION: return "exit_condition";
            case TYPE_SUBSCRIBE: return "subscribe";
            case TYPE_UNSUBSCRIBE: return "unsubscribe";
            case TYPE_CONFIG: return "config";
            case TYPE_NOT_INTERCEPTED: return "not_intercepted";
            case TYPE_DISABLE_EFFECTS: return "disable_effects";
            case TYPE_SUPPRESSOR_CHANGED: return "suppressor_changed";
            case TYPE_LISTENER_HINTS_CHANGED: return "listener_hints_changed";
            case TYPE_SET_NOTIFICATION_POLICY: return "set_notification_policy";
            case TYPE_SET_CONSOLIDATED_ZEN_POLICY: return "set_consolidated_policy";
            case TYPE_MATCHES_CALL_FILTER: return "matches_call_filter";
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
            case Global.ZEN_MODE_ALARMS: return "alarms";
            case Global.ZEN_MODE_NO_INTERRUPTIONS: return "no_interruptions";
            default: return "unknown";
        }
    }

    private static String hintsToString(int hints) {
        switch (hints) {
            case 0 : return "none";
            case NotificationListenerService.HINT_HOST_DISABLE_EFFECTS:
                    return "disable_effects";
            case NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS:
                    return "disable_call_effects";
            case NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS:
                    return "disable_notification_effects";
            default: return Integer.toString(hints);
        }
    }

    private static String componentToString(ComponentName component) {
        return component != null ? component.toShortString() : null;
    }

    private static String componentListToString(List<ComponentName> components) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < components.size(); ++i) {
            if (i > 0) {
                stringBuilder.append(", ");
            }
            stringBuilder.append(componentToString(components.get(i)));
        }

        return stringBuilder.toString();
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
        if (DEBUG) Slog.d(TAG, typeToString(type) + ": " + msg);
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
