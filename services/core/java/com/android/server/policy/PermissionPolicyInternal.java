/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.TaskInfo;
import android.content.Intent;

/**
 * Internal calls into {@link PermissionPolicyService}.
 */
public abstract class PermissionPolicyInternal {

    /**
     * Callback for initializing the permission policy service.
     */
    public interface OnInitializedCallback {

        /**
         * Called when initialized for the given user.
         *
         * @param userId The initialized user.
         */
        void onInitialized(@UserIdInt int userId);
    }

    /**
     * Check whether an activity should be started.
     *
     * @param intent the {@link Intent} for the activity start
     * @param callingUid the calling uid starting the activity
     * @param callingPackage the calling package starting the activity
     *
     * @return whether the activity should be started
     */
    public abstract boolean checkStartActivity(@NonNull Intent intent, int callingUid,
            @Nullable String callingPackage);

    /**
     * Check whether a notification permission prompt should be shown for the given package. A
     * prompt should be shown if the app targets S-, is currently running in a visible, focused
     * task, has the REVIEW_REQUIRED flag set on its implicit notification permission, and has
     * created at least one notification channel (even if it has since been deleted).
     *
     * @param packageName The package whose permission is being checked
     * @param userId The user for whom the package is being started
     * @param taskId The task the notification prompt should be attached to
     */
    public abstract void showNotificationPromptIfNeeded(@NonNull String packageName, int userId,
            int taskId);

    /**
     * Determine if a particular task is in the proper state to show a system-triggered permission
     * prompt. A prompt can be shown if the task is focused, visible, and running and
     * 1. The intent is a launcher intent (action is ACTION_MAIN, category is LAUNCHER), or
     * 2. The activity belongs to the same package as the one which launched the task originally,
     * and the task was started with a launcher intent
     *
     * @param taskInfo The task to be checked
     * @param currPkg The package of the current top visible activity
     * @param callingPkg The package that started the top visible activity
     * @param intent The intent of the current top visible activity
     * @param activityName The name of the current top visible activity
     */
    public abstract boolean shouldShowNotificationDialogForTask(@Nullable TaskInfo taskInfo,
            @Nullable String currPkg, @Nullable String callingPkg, @Nullable Intent intent,
            @NonNull String activityName);

    /**
     * @return true if an intent will resolve to a permission request dialog activity
     */
    public abstract boolean isIntentToPermissionDialog(@NonNull Intent intent);

    /**
     * @return Whether the policy is initialized for a user.
     */
    public abstract boolean isInitialized(@UserIdInt int userId);

    /**
     * Set a callback for users being initialized. If the user is already
     * initialized the callback will not be invoked.
     *
     * @param callback The callback to register.
     */
    public abstract void setOnInitializedCallback(@NonNull OnInitializedCallback callback);
}
