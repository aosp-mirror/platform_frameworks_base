/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import com.android.internal.os.BackgroundThread;
import com.android.server.wm.WindowManagerInternal.TaskSystemBarsListener;

import java.util.HashSet;
import java.util.concurrent.Executor;

/**
 * Manages dispatch of task system bar changes to interested listeners. All invocations must be
 * performed while the {@link WindowManagerService#getWindowManagerLock() Window Manager Lock} is
 * held.
 */
final class TaskSystemBarsListenerController {

    private final HashSet<TaskSystemBarsListener> mListeners = new HashSet<>();
    private final Executor mBackgroundExecutor;

    TaskSystemBarsListenerController() {
        this.mBackgroundExecutor = BackgroundThread.getExecutor();
    }

    void registerListener(TaskSystemBarsListener listener) {
        mListeners.add(listener);
    }

    void unregisterListener(TaskSystemBarsListener listener) {
        mListeners.remove(listener);
    }

    void dispatchTransientSystemBarVisibilityChanged(
            int taskId,
            boolean visible,
            boolean wereRevealedFromSwipeOnSystemBar) {
        HashSet<TaskSystemBarsListener> localListeners;
        localListeners = new HashSet<>(mListeners);

        mBackgroundExecutor.execute(() -> {
            for (TaskSystemBarsListener listener : localListeners) {
                listener.onTransientSystemBarsVisibilityChanged(
                        taskId,
                        visible,
                        wereRevealedFromSwipeOnSystemBar);
            }
        });
    }
}
