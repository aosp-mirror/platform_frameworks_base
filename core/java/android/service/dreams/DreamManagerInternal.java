/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.dreams;

import android.content.ComponentName;

/**
 * Dream manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class DreamManagerInternal {
    /**
     * Called by the power manager to start a dream.
     *
     * @param doze If true, starts the doze dream component if one has been configured,
     * otherwise starts the user-specified dream.
     */
    public abstract void startDream(boolean doze);

    /**
     * Called by the power manager to stop a dream.
     *
     * @param immediate If true, ends the dream summarily, otherwise gives it some time
     * to perform a proper exit transition.
     */
    public abstract void stopDream(boolean immediate);

    /**
     * Called by the power manager to determine whether a dream is running.
     */
    public abstract boolean isDreaming();

    /**
     * Ask the power manager to nap.  It will eventually call back into startDream() if/when it is
     * appropriate to start dreaming.
     */
    public abstract void requestDream();

    /**
     * Called by the ActivityTaskManagerService to verify that the startDreamActivity
     * request comes from the current active dream component.
     *
     * This function and its call path should not acquire the DreamManagerService lock
     * to avoid deadlock with the ActivityTaskManager lock.
     *
     * TODO: Make this interaction push-based - the DreamManager should inform the
     * ActivityTaskManager whenever the active dream component changes.
     *
     * @param doze If true returns the current active doze component. Otherwise, returns the
     *             active dream component.
     */
    public abstract ComponentName getActiveDreamComponent(boolean doze);
}
