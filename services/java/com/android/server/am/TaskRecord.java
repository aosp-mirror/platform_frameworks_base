/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.am;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.UserId;
import android.util.Slog;

import java.io.PrintWriter;

class TaskRecord extends ThumbnailHolder {
    final int taskId;       // Unique identifier for this task.
    final String affinity;  // The affinity name for this task, or null.
    Intent intent;          // The original intent that started the task.
    Intent affinityIntent;  // Intent of affinity-moved activity that started this task.
    ComponentName origActivity; // The non-alias activity component of the intent.
    ComponentName realActivity; // The actual activity component that started the task.
    int numActivities;      // Current number of activities in this task.
    long lastActiveTime;    // Last time this task was active, including sleep.
    boolean rootWasReset;   // True if the intent at the root of the task had
                            // the FLAG_ACTIVITY_RESET_TASK_IF_NEEDED flag.
    boolean askedCompatMode;// Have asked the user about compat mode for this task.

    String stringName;      // caching of toString() result.
    int userId;             // user for which this task was created
    
    TaskRecord(int _taskId, ActivityInfo info, Intent _intent) {
        taskId = _taskId;
        affinity = info.taskAffinity;
        setIntent(_intent, info);
    }

    void touchActiveTime() {
        lastActiveTime = android.os.SystemClock.elapsedRealtime();
    }
    
    long getInactiveDuration() {
        return android.os.SystemClock.elapsedRealtime() - lastActiveTime;
    }
    
    void setIntent(Intent _intent, ActivityInfo info) {
        stringName = null;

        if (info.targetActivity == null) {
            if (_intent != null) {
                // If this Intent has a selector, we want to clear it for the
                // recent task since it is not relevant if the user later wants
                // to re-launch the app.
                if (_intent.getSelector() != null || _intent.getSourceBounds() != null) {
                    _intent = new Intent(_intent);
                    _intent.setSelector(null);
                    _intent.setSourceBounds(null);
                }
            }
            if (ActivityManagerService.DEBUG_TASKS) Slog.v(ActivityManagerService.TAG,
                    "Setting Intent of " + this + " to " + _intent);
            intent = _intent;
            realActivity = _intent != null ? _intent.getComponent() : null;
            origActivity = null;
        } else {
            ComponentName targetComponent = new ComponentName(
                    info.packageName, info.targetActivity);
            if (_intent != null) {
                Intent targetIntent = new Intent(_intent);
                targetIntent.setComponent(targetComponent);
                targetIntent.setSelector(null);
                targetIntent.setSourceBounds(null);
                if (ActivityManagerService.DEBUG_TASKS) Slog.v(ActivityManagerService.TAG,
                        "Setting Intent of " + this + " to target " + targetIntent);
                intent = targetIntent;
                realActivity = targetComponent;
                origActivity = _intent.getComponent();
            } else {
                intent = null;
                realActivity = targetComponent;
                origActivity = new ComponentName(info.packageName, info.name);
            }
        }

        if (intent != null &&
                (intent.getFlags()&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            // Once we are set to an Intent with this flag, we count this
            // task as having a true root activity.
            rootWasReset = true;
        }

        if (info.applicationInfo != null) {
            userId = UserId.getUserId(info.applicationInfo.uid);
        }
    }
    
    void dump(PrintWriter pw, String prefix) {
        if (numActivities != 0 || rootWasReset || userId != 0) {
            pw.print(prefix); pw.print("numActivities="); pw.print(numActivities);
                    pw.print(" rootWasReset="); pw.print(rootWasReset);
                    pw.print(" userId="); pw.println(userId);
        }
        if (affinity != null) {
            pw.print(prefix); pw.print("affinity="); pw.println(affinity);
        }
        if (intent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("intent={");
            intent.toShortString(sb, false, true, false, true);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (affinityIntent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("affinityIntent={");
            affinityIntent.toShortString(sb, false, true, false, true);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (origActivity != null) {
            pw.print(prefix); pw.print("origActivity=");
            pw.println(origActivity.flattenToShortString());
        }
        if (realActivity != null) {
            pw.print(prefix); pw.print("realActivity=");
            pw.println(realActivity.flattenToShortString());
        }
        if (!askedCompatMode) {
            pw.print(prefix); pw.print("askedCompatMode="); pw.println(askedCompatMode);
        }
        pw.print(prefix); pw.print("lastThumbnail="); pw.print(lastThumbnail);
                pw.print(" lastDescription="); pw.println(lastDescription);
        pw.print(prefix); pw.print("lastActiveTime="); pw.print(lastActiveTime);
                pw.print(" (inactive for ");
                pw.print((getInactiveDuration()/1000)); pw.println("s)");
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("TaskRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        sb.append(taskId);
        if (affinity != null) {
            sb.append(" A ");
            sb.append(affinity);
        } else if (intent != null) {
            sb.append(" I ");
            sb.append(intent.getComponent().flattenToShortString());
        } else if (affinityIntent != null) {
            sb.append(" aI ");
            sb.append(affinityIntent.getComponent().flattenToShortString());
        } else {
            sb.append(" ??");
        }
        sb.append(" U ");
        sb.append(userId);
        sb.append('}');
        return stringName = sb.toString();
    }
}
