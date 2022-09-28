/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.recents;

import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.util.GroupedRecentTaskInfo;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interface for interacting with the recent tasks.
 */
@ExternalThread
public interface RecentTasks {
    /**
     * Returns a binder that can be passed to an external process to fetch recent tasks.
     */
    default IRecentTasks createExternalInterface() {
        return null;
    }

    /**
     * Gets the set of recent tasks.
     */
    default void getRecentTasks(int maxNum, int flags, int userId, Executor callbackExecutor,
            Consumer<List<GroupedRecentTaskInfo>> callback) {
    }
}
