/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN;

import android.util.Log;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.window.TaskFragmentOrganizer;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

/** Controls the TaskFragment remote animations. */
class TaskFragmentAnimationController {

    private static final String TAG = "TaskFragAnimationCtrl";
    static final boolean DEBUG = false;

    private final TaskFragmentOrganizer mOrganizer;
    private final TaskFragmentAnimationRunner mRemoteRunner = new TaskFragmentAnimationRunner();
    @VisibleForTesting
    final RemoteAnimationDefinition mDefinition;
    private boolean mIsRegistered;

    TaskFragmentAnimationController(@NonNull TaskFragmentOrganizer organizer) {
        mOrganizer = organizer;
        mDefinition = new RemoteAnimationDefinition();
        final RemoteAnimationAdapter animationAdapter =
                new RemoteAnimationAdapter(mRemoteRunner, 0, 0, true /* changeNeedsSnapshot */);
        mDefinition.addRemoteAnimation(TRANSIT_OLD_ACTIVITY_OPEN, animationAdapter);
        mDefinition.addRemoteAnimation(TRANSIT_OLD_TASK_FRAGMENT_OPEN, animationAdapter);
        mDefinition.addRemoteAnimation(TRANSIT_OLD_ACTIVITY_CLOSE, animationAdapter);
        mDefinition.addRemoteAnimation(TRANSIT_OLD_TASK_FRAGMENT_CLOSE, animationAdapter);
        mDefinition.addRemoteAnimation(TRANSIT_OLD_TASK_FRAGMENT_CHANGE, animationAdapter);
    }

    void registerRemoteAnimations() {
        if (DEBUG) {
            Log.v(TAG, "registerRemoteAnimations");
        }
        if (mIsRegistered) {
            return;
        }
        mOrganizer.registerRemoteAnimations(mDefinition);
        mIsRegistered = true;
    }

    void unregisterRemoteAnimations() {
        if (DEBUG) {
            Log.v(TAG, "unregisterRemoteAnimations");
        }
        if (!mIsRegistered) {
            return;
        }
        mOrganizer.unregisterRemoteAnimations();
        mIsRegistered = false;
    }
}
