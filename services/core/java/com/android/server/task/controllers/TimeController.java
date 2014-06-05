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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

import com.android.server.task.StateChangedListener;
import com.android.server.task.TaskManagerService;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * This class sets an alarm for the next expiring task, and determines whether a task's minimum
 * delay has been satisfied.
 */
public class TimeController extends StateController {
    private static final String TAG = "TaskManager.Time";
    private static final String ACTION_TASK_EXPIRED =
            "android.content.taskmanager.TASK_EXPIRED";
    private static final String ACTION_TASK_DELAY_EXPIRED =
            "android.content.taskmanager.TASK_DELAY_EXPIRED";

    /** Set an alarm for the next task expiry. */
    private final PendingIntent mTaskExpiredAlarmIntent;
    /** Set an alarm for the next task delay expiry. This*/
    private final PendingIntent mNextDelayExpiredAlarmIntent;

    private long mNextTaskExpiredElapsedMillis;
    private long mNextDelayExpiredElapsedMillis;

    private AlarmManager mAlarmService = null;
    /** List of tracked tasks, sorted asc. by deadline */
    private final List<TaskStatus> mTrackedTasks = new LinkedList<TaskStatus>();
    /** Singleton. */
    private static TimeController mSingleton;

    public static synchronized TimeController get(TaskManagerService taskManager) {
        if (mSingleton == null) {
            mSingleton = new TimeController(taskManager, taskManager.getContext());
        }
        return mSingleton;
    }

    private TimeController(StateChangedListener stateChangedListener, Context context) {
        super(stateChangedListener, context);
        mTaskExpiredAlarmIntent =
                PendingIntent.getBroadcast(mContext, 0 /* ignored */,
                        new Intent(ACTION_TASK_EXPIRED), 0);
        mNextDelayExpiredAlarmIntent =
                PendingIntent.getBroadcast(mContext, 0 /* ignored */,
                        new Intent(ACTION_TASK_DELAY_EXPIRED), 0);

        // Register BR for these intents.
        IntentFilter intentFilter = new IntentFilter(ACTION_TASK_EXPIRED);
        intentFilter.addAction(ACTION_TASK_DELAY_EXPIRED);
        mContext.registerReceiver(mAlarmExpiredReceiver, intentFilter);
    }

    /**
     * Check if the task has a timing constraint, and if so determine where to insert it in our
     * list.
     */
    @Override
    public synchronized void maybeStartTrackingTask(TaskStatus task) {
        if (task.hasTimingDelayConstraint()) {
            ListIterator<TaskStatus> it = mTrackedTasks.listIterator(mTrackedTasks.size());
            while (it.hasPrevious()) {
                TaskStatus ts = it.previous();
                if (ts.equals(task)) {
                    // Update
                    it.remove();
                    it.add(task);
                    break;
                } else if (ts.getLatestRunTimeElapsed() < task.getLatestRunTimeElapsed()) {
                    // Insert
                    it.add(task);
                    break;
                }
            }
            maybeUpdateAlarms(task.getEarliestRunTime(), task.getLatestRunTimeElapsed());
        }
    }

    /**
     * If the task passed in is being tracked, figure out if we need to update our alarms, and if
     * so, update them.
     */
    @Override
    public synchronized void maybeStopTrackingTask(TaskStatus taskStatus) {
        if (mTrackedTasks.remove(taskStatus)) {
            if (mNextDelayExpiredElapsedMillis <= taskStatus.getEarliestRunTime()) {
                handleTaskDelayExpired();
            }
            if (mNextTaskExpiredElapsedMillis <= taskStatus.getLatestRunTimeElapsed()) {
                handleTaskDeadlineExpired();
            }
        }
    }

    /**
     * Set an alarm with the {@link android.app.AlarmManager} for the next time at which a task's
     * delay will expire.
     * This alarm <b>will not</b> wake up the phone.
     */
    private void setDelayExpiredAlarm(long alarmTimeElapsedMillis) {
        ensureAlarmService();
        mAlarmService.set(AlarmManager.ELAPSED_REALTIME, alarmTimeElapsedMillis,
                mNextDelayExpiredAlarmIntent);
    }

    /**
     * Set an alarm with the {@link android.app.AlarmManager} for the next time at which a task's
     * deadline will expire.
     * This alarm <b>will</b> wake up the phone.
     */
    private void setDeadlineExpiredAlarm(long alarmTimeElapsedMillis) {
        ensureAlarmService();
        mAlarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTimeElapsedMillis,
                mTaskExpiredAlarmIntent);
    }

    /**
     * Determines whether this controller can stop tracking the given task.
     * The controller is no longer interested in a task once its time constraint is satisfied, and
     * the task's deadline is fulfilled - unlike other controllers a time constraint can't toggle
     * back and forth.
     */
    private boolean canStopTrackingTask(TaskStatus taskStatus) {
        return (!taskStatus.hasTimingDelayConstraint() ||
                taskStatus.timeDelayConstraintSatisfied.get()) &&
                (!taskStatus.hasDeadlineConstraint() ||
                        taskStatus.deadlineConstraintSatisfied.get());
    }

    private void maybeUpdateAlarms(long delayExpiredElapsed, long deadlineExpiredElapsed) {
        if (delayExpiredElapsed < mNextDelayExpiredElapsedMillis) {
            mNextDelayExpiredElapsedMillis = delayExpiredElapsed;
            setDelayExpiredAlarm(mNextDelayExpiredElapsedMillis);
        }
        if (deadlineExpiredElapsed < mNextTaskExpiredElapsedMillis) {
            mNextTaskExpiredElapsedMillis = deadlineExpiredElapsed;
            setDeadlineExpiredAlarm(mNextTaskExpiredElapsedMillis);
        }
    }

    private void ensureAlarmService() {
        if (mAlarmService == null) {
            mAlarmService = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        }
    }

    /**
     * Handles alarm that notifies that a task has expired. When this function is called at least
     * one task must be run.
     */
    private synchronized void handleTaskDeadlineExpired() {
        long nextExpiryTime = Long.MAX_VALUE;
        final long nowElapsedMillis = SystemClock.elapsedRealtime();

        Iterator<TaskStatus> it = mTrackedTasks.iterator();
        while (it.hasNext()) {
            TaskStatus ts = it.next();
            final long taskDeadline = ts.getLatestRunTimeElapsed();

            if (taskDeadline <= nowElapsedMillis) {
                ts.deadlineConstraintSatisfied.set(true);
                mStateChangedListener.onTaskDeadlineExpired(ts);
                it.remove();
            } else {  // Sorted by expiry time, so take the next one and stop.
                nextExpiryTime = taskDeadline;
                break;
            }
        }
        maybeUpdateAlarms(Long.MAX_VALUE, nextExpiryTime);
    }

    /**
     * Handles alarm that notifies us that a task's delay has expired. Iterates through the list of
     * tracked tasks and marks them as ready as appropriate.
     */
    private synchronized void handleTaskDelayExpired() {
        final long nowElapsedMillis = SystemClock.elapsedRealtime();
        long nextDelayTime = Long.MAX_VALUE;

        Iterator<TaskStatus> it = mTrackedTasks.iterator();
        while (it.hasNext()) {
            final TaskStatus ts = it.next();
            if (!ts.hasTimingDelayConstraint()) {
                continue;
            }
            final long taskDelayTime = ts.getEarliestRunTime();
            if (taskDelayTime < nowElapsedMillis) {
                ts.timeDelayConstraintSatisfied.set(true);
                if (canStopTrackingTask(ts)) {
                    it.remove();
                }
            } else {  // Keep going through list to get next delay time.
                if (nextDelayTime > taskDelayTime) {
                    nextDelayTime = taskDelayTime;
                }
            }
        }
        mStateChangedListener.onControllerStateChanged();
        maybeUpdateAlarms(nextDelayTime, Long.MAX_VALUE);
    }

    private final BroadcastReceiver mAlarmExpiredReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An task has just expired, so we run through the list of tasks that we have and
            // notify our StateChangedListener.
            if (ACTION_TASK_EXPIRED.equals(intent.getAction())) {
                handleTaskDeadlineExpired();
            } else if (ACTION_TASK_DELAY_EXPIRED.equals(intent.getAction())) {
                handleTaskDelayExpired();
            }
        }
    };
}
