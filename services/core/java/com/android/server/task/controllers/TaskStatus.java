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
import android.os.PersistableBundle;
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
    public static final long NO_LATEST_RUNTIME = Long.MAX_VALUE;
    public static final long NO_EARLIEST_RUNTIME = 0L;

    final Task task;
    final int uId;

    /** At reschedule time we need to know whether to update task on disk. */
    final boolean persisted;

    // Constraints.
    final AtomicBoolean chargingConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean timeDelayConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean deadlineConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean idleConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean unmeteredConstraintSatisfied = new AtomicBoolean();
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
    /** How many times this task has failed, used to compute back-off. */
    private final int numFailures;

    /** Provide a handle to the service that this task will be run on. */
    public int getServiceToken() {
        return uId;
    }

    private TaskStatus(Task task, int uId, boolean persisted, int numFailures) {
        this.task = task;
        this.uId = uId;
        this.numFailures = numFailures;
        this.persisted = persisted;
    }

    /** Create a newly scheduled task. */
    public TaskStatus(Task task, int uId, boolean persisted) {
        this(task, uId, persisted, 0);

        final long elapsedNow = SystemClock.elapsedRealtime();

        if (task.isPeriodic()) {
            earliestRunTimeElapsedMillis = elapsedNow;
            latestRunTimeElapsedMillis = elapsedNow + task.getIntervalMillis();
        } else {
            earliestRunTimeElapsedMillis = task.hasEarlyConstraint() ?
                    elapsedNow + task.getMinLatencyMillis() : NO_EARLIEST_RUNTIME;
            latestRunTimeElapsedMillis = task.hasLateConstraint() ?
                    elapsedNow + task.getMaxExecutionDelayMillis() : NO_LATEST_RUNTIME;
        }
    }

    /**
     * Create a new TaskStatus that was loaded from disk. We ignore the provided
     * {@link android.app.task.Task} time criteria because we can load a persisted periodic task
     * from the {@link com.android.server.task.TaskStore} and still want to respect its
     * wallclock runtime rather than resetting it on every boot.
     * We consider a freshly loaded task to no longer be in back-off.
     */
    public TaskStatus(Task task, int uId, long earliestRunTimeElapsedMillis,
                      long latestRunTimeElapsedMillis) {
        this(task, uId, true, 0);

        this.earliestRunTimeElapsedMillis = earliestRunTimeElapsedMillis;
        this.latestRunTimeElapsedMillis = latestRunTimeElapsedMillis;
    }

    /** Create a new task to be rescheduled with the provided parameters. */
    public TaskStatus(TaskStatus rescheduling, long newEarliestRuntimeElapsedMillis,
                      long newLatestRuntimeElapsedMillis, int backoffAttempt) {
        this(rescheduling.task, rescheduling.getUid(), rescheduling.isPersisted(), backoffAttempt);

        earliestRunTimeElapsedMillis = newEarliestRuntimeElapsedMillis;
        latestRunTimeElapsedMillis = newLatestRuntimeElapsedMillis;
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

    public PersistableBundle getExtras() {
        return task.getExtras();
    }

    public boolean hasConnectivityConstraint() {
        return task.getNetworkCapabilities() == Task.NetworkType.ANY;
    }

    public boolean hasUnmeteredConstraint() {
        return task.getNetworkCapabilities() == Task.NetworkType.UNMETERED;
    }

    public boolean hasChargingConstraint() {
        return task.isRequireCharging();
    }

    public boolean hasTimingDelayConstraint() {
        return earliestRunTimeElapsedMillis != NO_EARLIEST_RUNTIME;
    }

    public boolean hasDeadlineConstraint() {
        return latestRunTimeElapsedMillis != NO_LATEST_RUNTIME;
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
                && (!hasUnmeteredConstraint() || unmeteredConstraintSatisfied.get())
                && (!hasIdleConstraint() || idleConstraintSatisfied.get())
                // Also ready if the deadline has expired - special case.
                || (hasDeadlineConstraint() && deadlineConstraintSatisfied.get());
    }

    /*@Override
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
    }*/

    public boolean matches(int uid, int taskId) {
        return this.task.getId() == taskId && this.uId == uid;
    }

    @Override
    public String toString() {
        return String.valueOf(hashCode()).substring(0, 3) + ".."
                + ":[" + task.getService().getPackageName() + ",tId=" + task.getId()
                + ",R=(" + earliestRunTimeElapsedMillis + "," + latestRunTimeElapsedMillis + ")"
                + ",N=" + task.getNetworkCapabilities() + ",C=" + task.isRequireCharging()
                + ",I=" + task.isRequireDeviceIdle() + ",F=" + numFailures
                + (isReady() ? "(READY)" : "")
                + "]";
    }
    // Dumpsys infrastructure
    public void dump(PrintWriter pw, String prefix) {
        pw.println(this.toString());
    }
}
