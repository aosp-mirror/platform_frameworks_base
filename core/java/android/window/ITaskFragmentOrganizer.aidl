/**
 * Copyright (c) 2021 The Android Open Source Project
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

package android.window;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.window.TaskFragmentInfo;

/** @hide */
oneway interface ITaskFragmentOrganizer {
    void onTaskFragmentAppeared(in TaskFragmentInfo taskFragmentInfo);
    void onTaskFragmentInfoChanged(in TaskFragmentInfo taskFragmentInfo);
    void onTaskFragmentVanished(in TaskFragmentInfo taskFragmentInfo);

    /**
     * Called when the parent leaf Task of organized TaskFragments is changed.
     * When the leaf Task is changed, the organizer may want to update the TaskFragments in one
     * transaction.
     *
     * For case like screen size change, it will trigger onTaskFragmentParentInfoChanged with new
     * Task bounds, but may not trigger onTaskFragmentInfoChanged because there can be an override
     * bounds.
     */
    void onTaskFragmentParentInfoChanged(in IBinder fragmentToken, in Configuration parentConfig);

    /**
     * Called when the {@link WindowContainerTransaction} created with
     * {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)} failed on the server side.
     *
     * @param errorCallbackToken    Token set through {@link
     *                              WindowContainerTransaction#setErrorCallbackToken(IBinder)}
     * @param exceptionBundle   Bundle containing the exception. Should be created with
     *                          {@link TaskFragmentOrganizer#putExceptionInBundle}.
     */
    void onTaskFragmentError(in IBinder errorCallbackToken, in Bundle exceptionBundle);

    /**
     * Called when an Activity is reparented to the Task with organized TaskFragment. For example,
     * when an Activity enters and then exits Picture-in-picture, it will be reparented back to its
     * orginial Task. In this case, we need to notify the organizer so that it can check if the
     * Activity matches any split rule.
     *
     * @param taskId            The Task that the activity is reparented to.
     * @param activityIntent    The intent that the activity is original launched with.
     * @param activityToken     If the activity belongs to the same process as the organizer, this
     *                          will be the actual activity token; if the activity belongs to a
     *                          different process, the server will generate a temporary token that
     *                          the organizer can use to reparent the activity through
     *                          {@link WindowContainerTransaction} if needed.
     */
    void onActivityReparentToTask(int taskId, in Intent activityIntent, in IBinder activityToken);
}
