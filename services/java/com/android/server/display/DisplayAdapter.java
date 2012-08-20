/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

/**
 * A display adapter makes zero or more display devices available to the system
 * and provides facilities for discovering when displays are connected or disconnected.
 * <p>
 * For now, all display adapters are registered in the system server but
 * in principle it could be done from other processes.
 * </p>
 */
public abstract class DisplayAdapter {
    /**
     * Gets the display adapter name for debugging purposes.
     *
     * @return The display adapter name.
     */
    public abstract String getName();

    /**
     * Registers the display adapter with the display manager.
     * The display adapter should register any built-in display devices now.
     * Other display devices can be registered dynamically later.
     *
     * @param listener The listener for callbacks.
     */
    public abstract void register(Listener listener);

    public interface Listener {
        public void onDisplayDeviceAdded(DisplayDevice device);
        public void onDisplayDeviceRemoved(DisplayDevice device);
    }
}
