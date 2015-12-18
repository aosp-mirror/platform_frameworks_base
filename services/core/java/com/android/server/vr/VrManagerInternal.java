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
package com.android.server.vr;

/**
 * VR mode local system service interface.
 *
 * @hide Only for use within system server.
 */
public abstract class VrManagerInternal {

    /**
     * Return current VR mode state.
     *
     * @return {@code true} if VR mode is enabled.
     */
    public abstract boolean isInVrMode();

    /**
     * Set the current VR mode state.
     *
     * @param enabled {@code true} to enable VR mode.
     */
    public abstract void setVrMode(boolean enabled);

    /**
     * Add a listener for VR mode state changes.
     * <p>
     * This listener will immediately be called with the current VR mode state.
     * </p>
     * @param listener the listener instance to add.
     */
    public abstract void registerListener(VrStateListener listener);

    /**
     * Remove the listener from the current set of listeners.
     *
     * @param listener the listener to remove.
     */
    public abstract void unregisterListener(VrStateListener listener);

}
