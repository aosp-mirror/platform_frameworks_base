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
     */
    public abstract void startDream();

    /**
     * Called by the power manager to stop a dream.
     */
    public abstract void stopDream();

    /**
     * Called by the power manager to determine whether a dream is running.
     */
    public abstract boolean isDreaming();
}
