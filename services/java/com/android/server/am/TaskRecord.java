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
import android.os.SystemClock;

import java.io.PrintWriter;

class TaskRecord {
    final int taskId;       // Unique identifier for this task.
    final String affinity;  // The affinity name for this task, or null.
    final boolean clearOnBackground; // As per the original activity.
    Intent intent;          // The original intent that started the task.
    Intent affinityIntent;  // Intent of affinity-moved activity that started this task.
    ComponentName origActivity; // The non-alias activity component of the intent.
    ComponentName realActivity; // The actual activity component that started the task.
    int numActivities;      // Current number of activities in this task.
    long lastActiveTime;    // Last time this task was active, including sleep.
    boolean rootWasReset;   // True if the intent at the root of the task had
                            // the FLAG_ACTIVITY_RESET_TASK_IF_NEEDED flag.

    TaskRecord(int _taskId, ActivityInfo info, Intent _intent,
            boolean _clearOnBackground) {
        taskId = _taskId;
        affinity = info.taskAffinity;
        clearOnBackground = _clearOnBackground;
        setIntent(_intent, info);
    }

    void touchActiveTime() {
        lastActiveTime = android.os.SystemClock.elapsedRealtime();
    }
    
    long getInactiveDuration() {
        return android.os.SystemClock.elapsedRealtime() - lastActiveTime;
    }
    
    void setIntent(Intent _intent, ActivityInfo info) {
        if (info.targetActivity == null) {
            intent = _intent;
            realActivity = _intent != null ? _intent.getComponent() : null;
            origActivity = null;
        } else {
            ComponentName targetComponent = new ComponentName(
                    info.packageName, info.targetActivity);
            if (_intent != null) {
                Intent targetIntent = new Intent(_intent);
                targetIntent.setComponent(targetComponent);
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
    }
    
    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "clearOnBackground=" + clearOnBackground
              + " numActivities=" + numActivities
              + " rootWasReset=" + rootWasReset);
        pw.println(prefix + "affinity=" + affinity);
        pw.println(prefix + "intent=" + intent);
        pw.println(prefix + "affinityIntent=" + affinityIntent);
        pw.println(prefix + "origActivity=" + origActivity);
        pw.println(prefix + "lastActiveTime=" + lastActiveTime
                +" (inactive for " + (getInactiveDuration()/1000) + "s)");
    }

    public String toString() {
        return "Task{" + taskId + " "
                + (affinity != null ? affinity
                        : (intent != null ? intent.getComponent().flattenToShortString()
                                : affinityIntent != null ? affinityIntent.getComponent().flattenToShortString() : "??"))
                + "}";
    }
}
