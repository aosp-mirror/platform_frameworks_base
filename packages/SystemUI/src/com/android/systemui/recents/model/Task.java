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

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.android.systemui.recents.Constants;


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

    /* The Task Key represents the unique primary key for the task */
    public static class TaskKey {
        public final int id;
        public final Intent intent;

        public TaskKey(int id, Intent intent) {
            this.id = id;
            this.intent = intent;
        }

        @Override
        public boolean equals(Object o) {
            return hashCode() == o.hashCode();
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public String toString() {
            return "Task.Key: " + id + ", " + intent.getComponent().getPackageName();
        }
    }

    public TaskKey key;
    public String title;
    public Drawable icon;
    public Bitmap thumbnail;

    TaskCallbacks mCb;

    public Task(int id, Intent intent, String activityTitle) {
        this(id, intent, activityTitle, null, null);
    }

    public Task(int id, Intent intent, String activityTitle, Drawable icon, Bitmap thumbnail) {
        this.key = new TaskKey(id, intent);
        this.title = activityTitle;
        this.icon = icon;
        this.thumbnail = thumbnail;
    }

    /** Set the callbacks */
    public void setCallbacks(TaskCallbacks cb) {
        mCb = cb;
    }

    /** Notifies the callback listeners that this task has been loaded */
    public void notifyTaskDataLoaded(Bitmap thumbnail, Drawable icon) {
        this.icon = icon;
        this.thumbnail = thumbnail;
        if (mCb != null) {
            mCb.onTaskDataLoaded();
        }
    }

    /** Notifies the callback listeners that this task has been unloaded */
    public void notifyTaskDataUnloaded(Bitmap defaultThumbnail, Drawable defaultIcon) {
        icon = defaultIcon;
        thumbnail = defaultThumbnail;
        if (mCb != null) {
            mCb.onTaskDataUnloaded();
        }
    }

    @Override
    public boolean equals(Object o) {
        // If we have multiple task entries for the same task, then we do the simple object
        // equality check
        if (Constants.Values.RecentsTaskLoader.TaskEntryMultiplier > 1) {
            return super.equals(o);
        }

        // Otherwise, check that the id and intent match (the other fields can be asynchronously
        // loaded and is unsuitable to testing the identity of this Task)
        Task t = (Task) o;
        return key.equals(t.key);
    }

    @Override
    public String toString() {
        return "Task: " + key.intent.getComponent().getPackageName() + " [" + super.toString() + "]";
    }
}
