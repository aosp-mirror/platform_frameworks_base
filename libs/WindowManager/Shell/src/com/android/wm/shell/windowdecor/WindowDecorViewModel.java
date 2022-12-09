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

package com.android.wm.shell.windowdecor;

import android.app.ActivityManager;
import android.view.SurfaceControl;

import androidx.annotation.Nullable;

import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;

/**
 * The interface used by some {@link com.android.wm.shell.ShellTaskOrganizer.TaskListener} to help
 * customize {@link WindowDecoration}. Its implementations are responsible to interpret user's
 * interactions with UI widgets in window decorations and send corresponding requests to system
 * servers.
 *
 * @param <T> The actual decoration type
 */
public interface WindowDecorViewModel<T extends AutoCloseable> {

    /**
     * Sets the transition starter that starts freeform task transitions.
     *
     * @param transitionStarter the transition starter that starts freeform task transitions
     */
    void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter);

    /**
     * Creates a window decoration for the given task.
     * Can be {@code null} for Fullscreen tasks but not Freeform ones.
     *
     * @param taskInfo the initial task info of the task
     * @param taskSurface the surface of the task
     * @param startT the start transaction to be applied before the transition
     * @param finishT the finish transaction to restore states after the transition
     * @return the window decoration object
     */
    @Nullable T createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT);

    /**
     * Adopts the window decoration if possible.
     * May be {@code null} if a window decor is not needed or the given one is incompatible.
     *
     * @param windowDecor the potential window decoration to adopt
     * @return the window decoration if it can be adopted, or {@code null} otherwise.
     */
    @Nullable T adoptWindowDecoration(@Nullable AutoCloseable windowDecor);

    /**
     * Notifies a task info update on the given task, with the window decoration created previously
     * for this task by {@link #createWindowDecoration}.
     *
     * @param taskInfo the new task info of the task
     * @param windowDecoration the window decoration created for the task
     */
    void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo, T windowDecoration);

    /**
     * Notifies a transition is about to start about the given task to give the window decoration a
     * chance to prepare for this transition.
     *
     * @param startT the start transaction to be applied before the transition
     * @param finishT the finish transaction to restore states after the transition
     * @param windowDecoration the window decoration created for the task
     */
    void setupWindowDecorationForTransition(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT,
            T windowDecoration);
}
