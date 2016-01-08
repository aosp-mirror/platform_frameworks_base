/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.preload.actions;

import com.android.ddmlib.IDevice;

/**
 * Marks an action as being device-specific. The user must set the device through the specified
 * method if the device selection changes.
 *
 * Implementors must tolerate a null device (for example, with a no-op). This includes calling
 * any methods before setDevice has been called.
 */
public interface DeviceSpecific {

    /**
     * Set the device that should be used. Note that there is no restriction on calling other
     * methods of the implementor before a setDevice call. Neither is device guaranteed to be
     * non-null.
     *
     * @param device The device to use going forward.
     */
    public void setDevice(IDevice device);
}
