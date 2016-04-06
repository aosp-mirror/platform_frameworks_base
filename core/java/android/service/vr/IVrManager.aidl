/**
 * Copyright (c) 2016, The Android Open Source Project
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

package android.service.vr;

import android.service.vr.IVrStateCallbacks;

/** @hide */
interface IVrManager {

    /**
     * Add a callback to be notified when VR mode state changes.
     *
     * @param cb the callback instance to add.
     */
    void registerListener(in IVrStateCallbacks cb);

    /**
     * Remove the callack from the current set of registered callbacks.
     *
     * @param cb the callback to remove.
     */
    void unregisterListener(in IVrStateCallbacks cb);

    /**
     * Return current VR mode state.
     *
     * @return {@code true} if VR mode is enabled.
     */
    boolean getVrModeState();

}

