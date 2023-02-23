/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.people.data;

import android.util.Pair;

/** The interface for throttling expensive runnables per package. */
interface PerPackageThrottler {
    /**
     * Schedule a runnable to run in the future, and debounce runnables for same {@code pkgUserId}
     * that occur until that future has run.
     */
    void scheduleDebounced(Pair<String, Integer> pkgUserId, Runnable runnable);
}
