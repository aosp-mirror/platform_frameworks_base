/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

/** @hide */
oneway interface ITaskStackListener {
    /** Called whenever there are changes to the state of tasks in a stack. */
    void onTaskStackChanged();

    /** Called whenever an Activity is moved to the pinned stack from another stack. */
    void onActivityPinned();

    /**
     * Called whenever IActivityManager.startActivity is called on an activity that is already
     * running in the pinned stack and the activity is not actually started, but the task is either
     * brought to the front or a new Intent is delivered to it.
     */
    void onPinnedActivityRestartAttempt();

    /**
     * Called whenever the pinned stack is done animating a resize.
     */
    void onPinnedStackAnimationEnded();

    /**
     * Called when we launched an activity that we forced to be resizable.
     */
    void onActivityForcedResizable(String packageName, int taskId);

    /**
     * Callen when we launched an activity that is dismissed the docked stack.
     */
    void onActivityDismissingDockedStack();
}
