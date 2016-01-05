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
 * limitations under the License.
 */

package com.android.systemui.recents.model;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;

import java.util.ArrayList;
import java.util.Objects;


/**
 * A task represents the top most task in the system's task stack.
 */
public class Task {
    /* Task callbacks */
    public interface TaskCallbacks {
        /* Notifies when a task has been bound */
        public void onTaskDataLoaded(Task task);
        /* Notifies when a task has been unbound */
        public void onTaskDataUnloaded();
        /* Notifies when a task's stack id has changed. */
        public void onTaskStackIdChanged();
    }

    /* The Task Key represents the unique primary key for the task */
    public static class TaskKey {
        public final int id;
        public int stackId;
        public final Intent baseIntent;
        public final int userId;
        public long firstActiveTime;
        public long lastActiveTime;

        public TaskKey(int id, int stackId, Intent intent, int userId, long firstActiveTime,
                long lastActiveTime) {
            this.id = id;
            this.stackId = stackId;
            this.baseIntent = intent;
            this.userId = userId;
            this.firstActiveTime = firstActiveTime;
            this.lastActiveTime = lastActiveTime;
        }

        public ComponentName getComponent() {
            return this.baseIntent.getComponent();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TaskKey)) {
                return false;
            }
            TaskKey otherKey = (TaskKey) o;
            return id == otherKey.id && stackId == otherKey.stackId && userId == otherKey.userId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, stackId, userId);
        }

        @Override
        public String toString() {
            return "Task.Key: " + id + ", "
                    + "s: " + stackId + ", "
                    + "u: " + userId + ", "
                    + "lat: " + lastActiveTime + ", "
                    + getComponent().getPackageName();
        }
    }

    public TaskKey key;

    /**
     * The group will be computed separately from the initialization of the task
     */
    public TaskGrouping group;
    /**
     * The affiliationTaskId is the task id of the parent task or itself if it is not affiliated
     * with any task.
     */
    public int affiliationTaskId;
    public int affiliationColor;

    /**
     * The icon is the task description icon (if provided), which falls back to the activity icon,
     * which can then fall back to the application icon.
     */
    public Drawable icon;
    public Bitmap thumbnail;
    public String title;
    public String contentDescription;
    public int colorPrimary;
    public boolean useLightOnPrimaryColor;

    /**
     * The bounds of the task, used only if it is a freeform task.
     */
    public Rect bounds;

    /**
     * The task description for this task, only used to reload task icons.
     */
    public ActivityManager.TaskDescription taskDescription;

    /**
     * The state isLaunchTarget will be set for the correct task upon launching Recents.
     */
    public boolean isLaunchTarget;
    public boolean isHistorical;

    private ArrayList<TaskCallbacks> mCallbacks = new ArrayList<>();

    public Task() {
        // Do nothing
    }

    public Task(TaskKey key, int affiliationTaskId, int affiliationColor, Drawable icon,
                Bitmap thumbnail, String title, String contentDescription, int colorPrimary,
                boolean isHistorical, Rect bounds,
                ActivityManager.TaskDescription taskDescription) {
        boolean isInAffiliationGroup = (affiliationTaskId != key.id);
        boolean hasAffiliationGroupColor = isInAffiliationGroup && (affiliationColor != 0);
        this.key = key;
        this.affiliationTaskId = affiliationTaskId;
        this.affiliationColor = affiliationColor;
        this.icon = icon;
        this.thumbnail = thumbnail;
        this.title = title;
        this.contentDescription = contentDescription;
        this.colorPrimary = hasAffiliationGroupColor ? affiliationColor : colorPrimary;
        this.useLightOnPrimaryColor = Utilities.computeContrastBetweenColors(this.colorPrimary,
                Color.WHITE) > 3f;
        this.bounds = bounds;
        this.taskDescription = taskDescription;
        this.isHistorical = isHistorical;
    }

    /** Copies the other task. */
    public void copyFrom(Task o) {
        this.key = o.key;
        this.group = o.group;
        this.affiliationTaskId = o.affiliationTaskId;
        this.affiliationColor = o.affiliationColor;
        this.icon = o.icon;
        this.thumbnail = o.thumbnail;
        this.title = o.title;
        this.contentDescription = o.contentDescription;
        this.colorPrimary = o.colorPrimary;
        this.useLightOnPrimaryColor = o.useLightOnPrimaryColor;
        this.bounds = o.bounds;
        this.isLaunchTarget = o.isLaunchTarget;
        this.isHistorical = o.isHistorical;
    }

    /**
     * Add a callback.
     */
    public void addCallback(TaskCallbacks cb) {
        if (!mCallbacks.contains(cb)) {
            mCallbacks.add(cb);
        }
    }

    /**
     * Remove a callback.
     */
    public void removeCallback(TaskCallbacks cb) {
        mCallbacks.remove(cb);
    }

    /** Set the grouping */
    public void setGroup(TaskGrouping group) {
        this.group = group;
    }

    /**
     * Updates the stack id of this task.
     */
    public void setStackId(int stackId) {
        key.stackId = stackId;
        int callbackCount = mCallbacks.size();
        for (int i = 0; i < callbackCount; i++) {
            mCallbacks.get(i).onTaskStackIdChanged();
        }
    }

    /**
     * Returns whether this task is on the freeform task stack.
     */
    public boolean isFreeformTask() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        return ssp.hasFreeformWorkspaceSupport() && ssp.isFreeformStack(key.stackId);
    }

    /** Notifies the callback listeners that this task has been loaded */
    public void notifyTaskDataLoaded(Bitmap thumbnail, Drawable applicationIcon) {
        this.icon = applicationIcon;
        this.thumbnail = thumbnail;
        int callbackCount = mCallbacks.size();
        for (int i = 0; i < callbackCount; i++) {
            mCallbacks.get(i).onTaskDataLoaded(this);
        }
    }

    /** Notifies the callback listeners that this task has been unloaded */
    public void notifyTaskDataUnloaded(Bitmap defaultThumbnail, Drawable defaultApplicationIcon) {
        icon = defaultApplicationIcon;
        thumbnail = defaultThumbnail;
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            mCallbacks.get(i).onTaskDataUnloaded();
        }
    }

    /**
     * Returns whether this task is affiliated with another task.
     */
    public boolean isAffiliatedTask() {
        return key.id != affiliationTaskId;
    }

    @Override
    public boolean equals(Object o) {
        // Check that the id matches
        Task t = (Task) o;
        return key.equals(t.key);
    }

    @Override
    public String toString() {
        String groupAffiliation = "no group";
        if (group != null) {
            groupAffiliation = Integer.toString(group.affiliation);
        }
        return "Task (" + groupAffiliation + "): " + key +
                " [" + super.toString() + "]";
    }
}
