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
 * A display adapter makes one or more display devices available to the system.
 * <p>
 * For now, all display adapters are registered in the system server but
 * in principle it could be done from other processes.
 * </p>
 */
public abstract class DisplayAdapter {
    /**
     * Gets the display adapter name.
     * @return The display adapter name.
     */
    public abstract String getName();

    // TODO: dynamically register display devices
    public abstract DisplayDevice[] getDisplayDevices();
}
