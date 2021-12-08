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

package com.android.server.companion.virtual;

/**
 * Virtual device manager local service interface.
 */
public abstract class VirtualDeviceManagerInternal {

    /**
     * Returns true if the given {@code uid} is the owner of any virtual devices that are
     * currently active.
     */
    public abstract boolean isAppOwnerOfAnyVirtualDevice(int uid);

    /**
     * Returns true if the given {@code uid} is currently running on any virtual devices. This is
     * determined by whether the app has any activities in the task stack on a virtual-device-owned
     * display.
     */
    public abstract boolean isAppRunningOnAnyVirtualDevice(int uid);
}
