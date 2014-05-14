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
    final int uId;

    /** At reschedule time we need to know whether to update task on disk. */
    final boolean persisted;

    // Constraints.
    final AtomicBoolean chargingConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean timeDelayConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean deadlineConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean idleConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean meteredConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean connectivityConstraintSatisfied = new AtomicBoolean();

    /**
     * Earliest point in the future at which this task will be eligible to run. A value of 0
     * indicates there is no delay constraint. See {@link #hasTimingDelayConstraint()}.
     */
    private long earliestRunTimeElapsedMillis;
    /**
     * Latest point in the future at which this task must be run. A value of {@link Long#MAX_VALUE}
     * indicates there is no deadline constraint. See {@link #hasDeadlineConstraint()}.
     */
    private long latestRunTimeElapsedMillis;

    private final int numFailures;

    /** Provide a handle to the service that this task will be run on. */
    public int getServiceToken() {
        return uId;
    }

    /** Create a newly scheduled task. */
    public TaskStatus(Task task, int uId, boolean persisted) {
        this.task = task;
        this.uId = uId;
        this.numFailures = 0;
        this.persisted = persisted;

        final long elapsedNow = SystemClock.elapsedRealtime();
        // Timing constraints
        if (task.isPeriodic()) {
            earliestRunTimeElapsedMillis = elapsedNow;
            latestRunTimeElapsedMillis = elapsedNow + task.getIntervalMillis();
        } else {
            earliestRunTimeElapsedMillis = task.hasEarlyConstraint() ?
                    elapsedNow + task.getMinLatencyMillis() : 0L;
            latestRunTimeElapsedMillis = task.hasLateConstraint() ?
                    elapsedNow + task.getMaxExecutionDelayMillis() : Long.MAX_VALUE;
        }
    }

    public TaskStatus(TaskStatus rescheduling, long newEarliestRuntimeElapsed,
                      long newLatestRuntimeElapsed, int backoffAttempt) {
        this.task = rescheduling.task;

        this.uId = rescheduling.getUid();
        this.persisted = rescheduling.isPersisted();
        this.numFailures = backoffAttempt;

        earliestRunTimeElapsedMillis = newEarliestRuntimeElapsed;
        latestRunTimeElapsedMillis = newLatestRuntimeElapsed;
    }

    public Task getTask() {
        return task;
    }

    public int getTaskId() {
        return task.getId();
    }

    public int getNumFailures() {
        return numFailures;
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
        return task.getExtras();
    }

    public boolean hasConnectivityConstraint() {
        return task.getNetworkCapabilities() == Task.NetworkType.ANY;
    }

    public boolean hasMeteredConstraint() {
        return task.getNetworkCapabilities() == Task.NetworkType.UNMETERED;
    }

    public boolean hasChargingConstraint() {
        return task.isRequireCharging();
    }

    public boolean hasTimingDelayConstraint() {
        return earliestRunTimeElapsedMillis != 0L;
    }

    public boolean hasDeadlineConstraint() {
        return latestRunTimeElapsedMillis != Long.MAX_VALUE;
    }

    public boolean hasIdleConstraint() {
        return task.isRequireDeviceIdle();
    }

    public long getEarliestRunTime() {
        return earliestRunTimeElapsedMillis;
    }

    public long getLatestRunTimeElapsed() {
        return latestRunTimeElapsedMillis;
    }

    public boolean isPersisted() {
        return persisted;
    }
    /**
     * @return Whether or not this task is ready to run, based on its requirements.
     */
    public synchronized boolean isReady() {
        return (!hasChargingConstraint() || chargingConstraintSatisfied.get())
                && (!hasTimingDelayConstraint() || timeDelayConstraintSatisfied.get())
                && (!hasConnectivityConstraint() || connectivityConstraintSatisfied.get())
                && (!hasMeteredConstraint() || meteredConstraintSatisfied.get())
                && (!hasIdleConstraint() || idleConstraintSatisfied.get())
                && (!hasDeadlineConstraint() || deadlineConstraintSatisfied.get());
    }

    @Override
    public int hashCode() {
        int result = getServiceComponent().hashCode();
        result = 31 * result + task.getId();
        result = 31 * result + uId;
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskStatus)) return false;

        TaskStatus that = (TaskStatus) o;
        return ((task.getId() == that.task.getId())
                && (uId == that.uId)
                && (getServiceComponent().equals(that.getServiceComponent())));
    }

    // Dumpsys infrastructure
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("Task "); pw.println(task.getId());
        pw.print(prefix); pw.print("uid="); pw.println(uId);
        pw.print(prefix); pw.print("component="); pw.println(task.getService());
    }
}
