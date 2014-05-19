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
 * limitations under the License
 */

package com.android.server.task.controllers;

import android.app.task.Task;
import android.content.ComponentName;
import android.content.pm.PackageParser;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uniquely identifies a task internally.
 * Created from the public {@link android.app.task.Task} object when it lands on the scheduler.
 * Contains current state of the requirements of the task, as well as a function to evaluate
 * whether it's ready to run.
 * This object is shared among the various controllers - hence why the different fields are atomic.
 * This isn't strictly necessary because each controller is only interested in a specific field,
 * and the receivers that are listening for global state change will all run on the main looper,
 * but we don't enforce that so this is safer.
 * @hide
 */
public class TaskStatus {
    final Task task;
    final int taskId;
    final int uId;
    final Bundle extras;

    final AtomicBoolean chargingConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean timeConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean idleConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean meteredConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean connectivityConstraintSatisfied = new AtomicBoolean();

    private final boolean hasChargingConstraint;
    private final boolean hasTimingConstraint;
    private final boolean hasIdleConstraint;
    private final boolean hasMeteredConstraint;
    private final boolean hasConnectivityConstraint;

    private long earliestRunTimeElapsedMillis;
    private long latestRunTimeElapsedMillis;

    /** Provide a handle to the service that this task will be run on. */
    public int getServiceToken() {
        return uId;
    }

    /** Generate a TaskStatus object for a given task and uid. */
    // TODO: reimplement this to reuse these objects instead of creating a new one each time?
    public static TaskStatus getForTaskAndUser(Task task, int userId, int uId) {
        return new TaskStatus(task, userId, uId);
    }

    /** Set up the state of a newly scheduled task. */
    TaskStatus(Task task, int userId, int uId) {
        this.task = task;
        this.taskId = task.getTaskId();
        this.extras = task.getExtras();
        this.uId = uId;

        hasChargingConstraint = task.isRequireCharging();
        hasIdleConstraint = task.isRequireDeviceIdle();

        // Timing constraints
        if (task.isPeriodic()) {
            long elapsedNow = SystemClock.elapsedRealtime();
            earliestRunTimeElapsedMillis = elapsedNow;
            latestRunTimeElapsedMillis = elapsedNow + task.getIntervalMillis();
            hasTimingConstraint = true;
        } else if (task.getMinLatencyMillis() != 0L || task.getMaxExecutionDelayMillis() != 0L) {
            earliestRunTimeElapsedMillis = task.getMinLatencyMillis() > 0L ?
                    task.getMinLatencyMillis() : Long.MAX_VALUE;
            latestRunTimeElapsedMillis = task.getMaxExecutionDelayMillis() > 0L ?
                    task.getMaxExecutionDelayMillis() : Long.MAX_VALUE;
            hasTimingConstraint = true;
        } else {
            hasTimingConstraint = false;
        }

        // Networking constraints
        hasMeteredConstraint = task.getNetworkCapabilities() == Task.NetworkType.UNMETERED;
        hasConnectivityConstraint = task.getNetworkCapabilities() == Task.NetworkType.ANY;
    }

    public Task getTask() {
        return task;
    }

    public int getTaskId() {
        return taskId;
    }

    public ComponentName getServiceComponent() {
        return task.getService();
    }

    public int getUserId() {
        return UserHandle.getUserId(uId);
    }

    public int getUid() {
        return uId;
    }

    public Bundle getExtras() {
        return extras;
    }

    boolean hasConnectivityConstraint() {
        return hasConnectivityConstraint;
    }

    boolean hasMeteredConstraint() {
        return hasMeteredConstraint;
    }

    boolean hasChargingConstraint() {
        return hasChargingConstraint;
    }

    boolean hasTimingConstraint() {
        return hasTimingConstraint;
    }

    boolean hasIdleConstraint() {
        return hasIdleConstraint;
    }

    long getEarliestRunTime() {
        return earliestRunTimeElapsedMillis;
    }

    long getLatestRunTime() {
        return latestRunTimeElapsedMillis;
    }

    /**
     * @return whether this task is ready to run, based on its requirements.
     */
    public synchronized boolean isReady() {
        return (!hasChargingConstraint || chargingConstraintSatisfied.get())
                && (!hasTimingConstraint || timeConstraintSatisfied.get())
                && (!hasConnectivityConstraint || connectivityConstraintSatisfied.get())
                && (!hasMeteredConstraint || meteredConstraintSatisfied.get())
                && (!hasIdleConstraint || idleConstraintSatisfied.get());
    }

    @Override
    public int hashCode() {
        int result = getServiceComponent().hashCode();
        result = 31 * result + taskId;
        result = 31 * result + uId;
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskStatus)) return false;

        TaskStatus that = (TaskStatus) o;
        return ((taskId == that.taskId)
                && (uId == that.uId)
                && (getServiceComponent().equals(that.getServiceComponent())));
    }

    // Dumpsys infrastructure
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("Task "); pw.println(taskId);
        pw.print(prefix); pw.print("uid="); pw.println(uId);
        pw.print(prefix); pw.print("component="); pw.println(task.getService());
    }
}
