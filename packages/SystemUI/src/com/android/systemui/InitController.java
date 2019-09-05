/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Created by {@link Dependency} on SystemUI startup. Add tasks which need to be executed only
 * after all other dependencies have been created.
 */
@Singleton
public class InitController {

    /**
     * If a task is added after all tasks are executed, then we've done something terribly wrong
     */
    private boolean mTasksExecuted = false;

    private final ArrayList<Runnable> mTasks = new ArrayList<>();

    @Inject
    public InitController() {
    }

    /**
     * Add a task to be executed after {@link Dependency#start()}
     * @param runnable the task to be executed
     */
    public void addPostInitTask(Runnable runnable) {
        if (mTasksExecuted) {
            throw new IllegalStateException("post init tasks have already been executed!");
        }
        mTasks.add(runnable);
    }

    /**
     * Run post-init tasks and remove them from the tasks list
     */
    public void executePostInitTasks() {
        while (!mTasks.isEmpty()) {
            mTasks.remove(0).run();
        }

        mTasksExecuted = true;
    }
}
