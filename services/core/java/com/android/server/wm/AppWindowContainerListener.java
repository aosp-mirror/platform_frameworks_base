/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

/** Interface used by the creator of the controller to listen to changes with the container. */
public interface AppWindowContainerListener extends WindowContainerListener {
    /** Called when the windows associated app window container are drawn. */
    void onWindowsDrawn(long timestamp);
    /** Called when the windows associated app window container are visible. */
    void onWindowsVisible();
    /** Called when the windows associated app window container are no longer visible. */
    void onWindowsGone();

    /**
     * Called when the starting window for this container is drawn.
     */
    void onStartingWindowDrawn(long timestamp);

    /**
     * Called when the key dispatching to a window associated with the app window container
     * timed-out.
     *
     * @param reason The reason for the key dispatching time out.
     * @param windowPid The pid of the window key dispatching timed out on.
     * @return True if input dispatching should be aborted.
     */
    boolean keyDispatchingTimedOut(String reason, int windowPid);
}
