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

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import com.android.systemui.recents.misc.Utilities;

import java.util.Objects;


/**
 * A task represents the top most task in the system's task stack.
 */
public class Task {
    /* Task callbacks */
    public interface TaskCallbacks {
        /* Notifies when a task has been bound */
        public void onTaskDataLoaded();
        /* Notifies when a task has been unbound */
        public void onTaskDataUnloaded();
    }

    /** The ComponentNameKey represents the unique primary key for a component
     * belonging to a specified user. */
    public static class ComponentNameKey {
        final ComponentName component;
        final int userId;

        public ComponentNameKey(ComponentName cn, int user) {
            component = cn;
            userId = user;
        }

        @Override
        public int hashCode() {
            return Objects.hash(component, userId);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ComponentNameKey)) {
                return false;
            }
            return component.equals(((ComponentNameKey) o).component) &&
                    userId == ((ComponentNameKey) o).userId;
        }
    }

    /* The Task Key represents the unique primary key for the task */
    public static class TaskKey {
        final ComponentNameKey mComponentNameKey;
        public final int id;
        public final Intent baseIntent;
        public final int userId;
        public long firstActiveTime;
        public long lastActiveTime;

        public TaskKey(int id, Intent intent, int userId, long firstActiveTime, long lastActiveTime) {
            mComponentNameKey = new ComponentNameKey(intent.getComponent(), userId);
            this.id = id;
            this.baseIntent = intent;
            this.userId = userId;
            this.firstActiveTime = firstActiveTime;
            this.lastActiveTime = lastActiveTime;
        }

        /** Returns the component name key for this task. */
        public ComponentNameKey getComponentNameKey() {
            return mComponentNameKey;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TaskKey)) {
                return false;
            }
            return id == ((TaskKey) o).id
                    && userId == ((TaskKey) o).userId;
        }

        @Override
        public int hashCode() {
            return (id << 5) + userId;
        }

        @Override
        public String toString() {
            return "Task.Key: " + id + ", "
                    + "u: " + userId + ", "
                    + "lat: " + lastActiveTime + ", "
                    + baseIntent.getComponent().getPackageName();
        }
    }

    public TaskKey key;
    public TaskGrouping group;
    public int taskAffiliation;
    public int taskAffiliationColor;
    public boolean isLaunchTarget;
    public Drawable applicationIcon;
    public Drawable activityIcon;
    public String activityLabel;
    public int colorPrimary;
    public boolean useLightOnPrimaryColor;
    public Bitmap thumbnail;
    public boolean isActive;
    public boolean lockToThisTask;
    public boolean lockToTaskEnabled;
    public Bitmap icon;
    public String iconFilename;
    TaskCallbacks mCb;

    public Task() {
        // Do nothing
    }

    public Task(TaskKey key, boolean isActive, int taskAffiliation, int taskAffiliationColor,
                String activityTitle, Drawable activityIcon, int colorPrimary,
                boolean lockToThisTask, boolean lockToTaskEnabled, Bitmap icon,
                String iconFilename) {
        boolean isInAffiliationGroup = (taskAffiliation != key.id);
        boolean hasAffiliationGroupColor = isInAffiliationGroup && (taskAffiliationColor != 0);
        this.key = key;
        this.taskAffiliation = taskAffiliation;
        this.taskAffiliationColor = taskAffiliationColor;
        this.activityLabel = activityTitle;
        this.activityIcon = activityIcon;
        this.colorPrimary = hasAffiliationGroupColor ? taskAffiliationColor : colorPrimary;
        this.useLightOnPrimaryColor = Utilities.computeContrastBetweenColors(this.colorPrimary,
                Color.WHITE) > 3f;
        this.isActive = isActive;
        this.lockToThisTask = lockToTaskEnabled && lockToThisTask;
        this.lockToTaskEnabled = lockToTaskEnabled;
        this.icon = icon;
        this.iconFilename = iconFilename;
    }

    /** Copies the other task. */
    public void copyFrom(Task o) {
        this.key = o.key;
        this.taskAffiliation = o.taskAffiliation;
        this.taskAffiliationColor = o.taskAffiliationColor;
        this.activityLabel = o.activityLabel;
        this.activityIcon = o.activityIcon;
        this.colorPrimary = o.colorPrimary;
        this.useLightOnPrimaryColor = o.useLightOnPrimaryColor;
        this.isActive = o.isActive;
        this.lockToThisTask = o.lockToThisTask;
        this.lockToTaskEnabled = o.lockToTaskEnabled;
    }

    /** Set the callbacks */
    public void setCallbacks(TaskCallbacks cb) {
        mCb = cb;
    }

    /** Set the grouping */
    public void setGroup(TaskGrouping group) {
        if (group != null && this.group != null) {
            throw new RuntimeException("This task is already assigned to a group.");
        }
        this.group = group;
    }

    /** Notifies the callback listeners that this task has been loaded */
    public void notifyTaskDataLoaded(Bitmap thumbnail, Drawable applicationIcon) {
        this.applicationIcon = applicationIcon;
        this.thumbnail = thumbnail;
        if (mCb != null) {
            mCb.onTaskDataLoaded();
        }
    }

    /** Notifies the callback listeners that this task has been unloaded */
    public void notifyTaskDataUnloaded(Bitmap defaultThumbnail, Drawable defaultApplicationIcon) {
        applicationIcon = defaultApplicationIcon;
        thumbnail = defaultThumbnail;
        if (mCb != null) {
            mCb.onTaskDataUnloaded();
        }
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
        return "Task (" + groupAffiliation + "): " + key.baseIntent.getComponent().getPackageName() +
                " [" + super.toString() + "]";
    }
}
