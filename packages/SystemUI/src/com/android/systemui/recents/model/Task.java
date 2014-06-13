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

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;


/**
 * A task represents the top most task in the system's task stack.
 */
public class Task {
    /* Task callbacks */
    public interface TaskCallbacks {
        /* Notifies when a task has been bound */
        public void onTaskDataLoaded(boolean reloadingTaskData);
        /* Notifies when a task has been unbound */
        public void onTaskDataUnloaded();
    }

    /* The Task Key represents the unique primary key for the task */
    public static class TaskKey {
        public final int id;
        public final Intent baseIntent;
        public final int userId;

        public TaskKey(int id, Intent intent, int userId) {
            this.id = id;
            this.baseIntent = intent;
            this.userId = userId;
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
                    + "u" + userId + ", "
                    + baseIntent.getComponent().getPackageName();
        }
    }

    public TaskKey key;
    public Drawable applicationIcon;
    public Drawable activityIcon;
    public String activityLabel;
    public int colorPrimary;
    public Bitmap thumbnail;
    public boolean isActive;
    public int userId;

    TaskCallbacks mCb;

    public Task() {
        // Only used by RecentsService for task rect calculations.
    }

    public Task(int id, boolean isActive, Intent intent, String activityTitle,
                BitmapDrawable activityIcon, int colorPrimary, int userId) {
        this.key = new TaskKey(id, intent, userId);
        this.activityLabel = activityTitle;
        this.activityIcon = activityIcon;
        this.colorPrimary = colorPrimary;
        this.isActive = isActive;
        this.userId = userId;
    }

    /** Set the callbacks */
    public void setCallbacks(TaskCallbacks cb) {
        mCb = cb;
    }

    /** Notifies the callback listeners that this task has been loaded */
    public void notifyTaskDataLoaded(Bitmap thumbnail, Drawable applicationIcon,
                                     boolean reloadingTaskData) {
        this.applicationIcon = applicationIcon;
        this.thumbnail = thumbnail;
        if (mCb != null) {
            mCb.onTaskDataLoaded(reloadingTaskData);
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
        return "Task: " + key.baseIntent.getComponent().getPackageName() + " [" + super.toString() + "]";
    }
}
