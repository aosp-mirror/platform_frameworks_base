/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager.RecentTaskInfo;

import java.util.ArrayList;

/**
 * Data associated with an app button.
 */
class AppButtonData {
    public final AppInfo appInfo;
    public boolean pinned;
    // Recent tasks for this app, sorted by lastActiveTime, descending.
    public ArrayList<RecentTaskInfo> tasks;

    public AppButtonData(AppInfo appInfo, boolean pinned) {
        this.appInfo = appInfo;
        this.pinned = pinned;
    }

    public int getTaskCount() {
        return tasks == null ? 0 : tasks.size();
    }

    /**
     * Returns true if the button contains no useful information and should be removed.
     */
    public boolean isEmpty() {
        return !pinned && getTaskCount() == 0;
    }

    public void addTask(RecentTaskInfo task) {
        if (tasks == null) {
            tasks = new ArrayList<RecentTaskInfo>();
        }
        tasks.add(task);
    }

    public void clearTasks() {
        if (tasks != null) {
            tasks.clear();
        }
    }
}
