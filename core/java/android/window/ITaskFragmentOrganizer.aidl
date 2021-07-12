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

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.window.TaskFragmentAppearedInfo;
import android.window.TaskFragmentInfo;

/** @hide */
oneway interface ITaskFragmentOrganizer {
    void onTaskFragmentAppeared(in TaskFragmentAppearedInfo taskFragmentAppearedInfo);
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
}
