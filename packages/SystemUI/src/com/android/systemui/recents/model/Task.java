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
import android.app.ActivityManager.TaskThumbnail;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.ViewDebug;

import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;


/**
 * A task represents the top most task in the system's task stack.
 */
public class Task {

    public static final String TAG = "Task";

    /* Task callbacks */
    public interface TaskCallbacks {
        /* Notifies when a task has been bound */
        public void onTaskDataLoaded(Task task, ThumbnailData thumbnailData);
        /* Notifies when a task has been unbound */
        public void onTaskDataUnloaded();
        /* Notifies when a task's stack id has changed. */
        public void onTaskStackIdChanged();
    }

    /* The Task Key represents the unique primary key for the task */
    public static class TaskKey {
        @ViewDebug.ExportedProperty(category="recents")
        public final int id;
        @ViewDebug.ExportedProperty(category="recents")
        public int stackId;
        @ViewDebug.ExportedProperty(category="recents")
        public final Intent baseIntent;
        @ViewDebug.ExportedProperty(category="recents")
        public final int userId;
        @ViewDebug.ExportedProperty(category="recents")
        public long firstActiveTime;
        @ViewDebug.ExportedProperty(category="recents")
        public long lastActiveTime;

        private int mHashCode;

        public TaskKey(int id, int stackId, Intent intent, int userId, long firstActiveTime,
                long lastActiveTime) {
            this.id = id;
            this.stackId = stackId;
            this.baseIntent = intent;
            this.userId = userId;
            this.firstActiveTime = firstActiveTime;
            this.lastActiveTime = lastActiveTime;
            updateHashCode();
        }

        public void setStackId(int stackId) {
            this.stackId = stackId;
            updateHashCode();
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
            return mHashCode;
        }

        @Override
        public String toString() {
            return "id=" + id + " stackId=" + stackId + " user=" + userId + " lastActiveTime=" +
                    lastActiveTime;
        }

        private void updateHashCode() {
            mHashCode = Objects.hash(id, stackId, userId);
        }
    }

    @ViewDebug.ExportedProperty(deepExport=true, prefix="key_")
    public TaskKey key;

    /**
     * The temporary sort index in the stack, used when ordering the stack.
     */
    public int temporarySortIndexInStack;

    /**
     * The group will be computed separately from the initialization of the task
     */
    @ViewDebug.ExportedProperty(deepExport=true, prefix="group_")
    public TaskGrouping group;
    /**
     * The affiliationTaskId is the task id of the parent task or itself if it is not affiliated
     * with any task.
     */
    @ViewDebug.ExportedProperty(category="recents")
    public int affiliationTaskId;
    @ViewDebug.ExportedProperty(category="recents")
    public int affiliationColor;

    /**
     * The icon is the task description icon (if provided), which falls back to the activity icon,
     * which can then fall back to the application icon.
     */
    public Drawable icon;
    public ThumbnailData thumbnail;
    @ViewDebug.ExportedProperty(category="recents")
    public String title;
    @ViewDebug.ExportedProperty(category="recents")
    public String titleDescription;
    @ViewDebug.ExportedProperty(category="recents")
    public String dismissDescription;
    @ViewDebug.ExportedProperty(category="recents")
    public String appInfoDescription;
    @ViewDebug.ExportedProperty(category="recents")
    public int colorPrimary;
    @ViewDebug.ExportedProperty(category="recents")
    public int colorBackground;
    @ViewDebug.ExportedProperty(category="recents")
    public boolean useLightOnPrimaryColor;

    /**
     * The bounds of the task, used only if it is a freeform task.
     */
    @ViewDebug.ExportedProperty(category="recents")
    public Rect bounds;

    /**
     * The task description for this task, only used to reload task icons.
     */
    public ActivityManager.TaskDescription taskDescription;

    /**
     * The state isLaunchTarget will be set for the correct task upon launching Recents.
     */
    @ViewDebug.ExportedProperty(category="recents")
    public boolean isLaunchTarget;
    @ViewDebug.ExportedProperty(category="recents")
    public boolean isStackTask;
    @ViewDebug.ExportedProperty(category="recents")
    public boolean isSystemApp;
    @ViewDebug.ExportedProperty(category="recents")
    public boolean isDockable;

    /**
     * Resize mode. See {@link ActivityInfo#resizeMode}.
     */
    @ViewDebug.ExportedProperty(category="recents")
    public int resizeMode;

    @ViewDebug.ExportedProperty(category="recents")
    public ComponentName topActivity;

    @ViewDebug.ExportedProperty(category="recents")
    public boolean isLocked;

    private ArrayList<TaskCallbacks> mCallbacks = new ArrayList<>();

    public Task() {
        // Do nothing
    }

    public Task(TaskKey key, int affiliationTaskId, int affiliationColor, Drawable icon,
            ThumbnailData thumbnail, String title, String titleDescription,
            String dismissDescription, String appInfoDescription, int colorPrimary,
            int colorBackground, boolean isLaunchTarget, boolean isStackTask, boolean isSystemApp,
            boolean isDockable, Rect bounds, ActivityManager.TaskDescription taskDescription,
            int resizeMode, ComponentName topActivity, boolean isLocked) {
        boolean isInAffiliationGroup = (affiliationTaskId != key.id);
        boolean hasAffiliationGroupColor = isInAffiliationGroup && (affiliationColor != 0);
        this.key = key;
        this.affiliationTaskId = affiliationTaskId;
        this.affiliationColor = affiliationColor;
        this.icon = icon;
        this.thumbnail = thumbnail;
        this.title = title;
        this.titleDescription = titleDescription;
        this.dismissDescription = dismissDescription;
        this.appInfoDescription = appInfoDescription;
        this.colorPrimary = hasAffiliationGroupColor ? affiliationColor : colorPrimary;
        this.colorBackground = colorBackground;
        this.useLightOnPrimaryColor = Utilities.computeContrastBetweenColors(this.colorPrimary,
                Color.WHITE) > 3f;
        this.bounds = bounds;
        this.taskDescription = taskDescription;
        this.isLaunchTarget = isLaunchTarget;
        this.isStackTask = isStackTask;
        this.isSystemApp = isSystemApp;
        this.isDockable = isDockable;
        this.resizeMode = resizeMode;
        this.topActivity = topActivity;
        this.isLocked = isLocked;
    }

    /**
     * Copies the metadata from another task, but retains the current callbacks.
     */
    public void copyFrom(Task o) {
        this.key = o.key;
        this.group = o.group;
        this.affiliationTaskId = o.affiliationTaskId;
        this.affiliationColor = o.affiliationColor;
        this.icon = o.icon;
        this.thumbnail = o.thumbnail;
        this.title = o.title;
        this.titleDescription = o.titleDescription;
        this.dismissDescription = o.dismissDescription;
        this.appInfoDescription = o.appInfoDescription;
        this.colorPrimary = o.colorPrimary;
        this.colorBackground = o.colorBackground;
        this.useLightOnPrimaryColor = o.useLightOnPrimaryColor;
        this.bounds = o.bounds;
        this.taskDescription = o.taskDescription;
        this.isLaunchTarget = o.isLaunchTarget;
        this.isStackTask = o.isStackTask;
        this.isSystemApp = o.isSystemApp;
        this.isDockable = o.isDockable;
        this.resizeMode = o.resizeMode;
        this.isLocked = o.isLocked;
        this.topActivity = o.topActivity;
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
        key.setStackId(stackId);
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
    public void notifyTaskDataLoaded(ThumbnailData thumbnailData, Drawable applicationIcon) {
        this.icon = applicationIcon;
        this.thumbnail = thumbnailData;
        int callbackCount = mCallbacks.size();
        for (int i = 0; i < callbackCount; i++) {
            mCallbacks.get(i).onTaskDataLoaded(this, thumbnailData);
        }
    }

    /** Notifies the callback listeners that this task has been unloaded */
    public void notifyTaskDataUnloaded(Drawable defaultApplicationIcon) {
        icon = defaultApplicationIcon;
        thumbnail = null;
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

    /**
     * Returns the top activity component.
     */
    public ComponentName getTopComponent() {
        return topActivity != null
                ? topActivity
                : key.baseIntent.getComponent();
    }

    @Override
    public boolean equals(Object o) {
        // Check that the id matches
        Task t = (Task) o;
        return key.equals(t.key);
    }

    @Override
    public String toString() {
        return "[" + key.toString() + "] " + title;
    }

    public void dump(String prefix, PrintWriter writer) {
        writer.print(prefix); writer.print(key);
        if (isAffiliatedTask()) {
            writer.print(" "); writer.print("affTaskId=" + affiliationTaskId);
        }
        if (!isDockable) {
            writer.print(" dockable=N");
        }
        if (isLaunchTarget) {
            writer.print(" launchTarget=Y");
        }
        if (isFreeformTask()) {
            writer.print(" freeform=Y");
        }
        if (isLocked) {
            writer.print(" locked=Y");
        }
        writer.print(" "); writer.print(title);
        writer.println();
    }
}
