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
}
