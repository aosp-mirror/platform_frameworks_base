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

package android.database;

import android.annotation.Nullable;

import java.util.concurrent.Executor;

/**
 * @hide
 *
 * Receives callbacks for changes to content.
 * Must be implemented by objects which are added to a {@link ContentObservable}.
 */
public abstract class ExecutorContentObserver extends ContentObserver {
    /**
     * Creates a content observer that uses an executor for change handling.
     *
     * @param executor The executor to run {@link #onChange} on, or null if none.
     */
    public ExecutorContentObserver(@Nullable Executor executor) {
        super(executor, 0);
    }
}
