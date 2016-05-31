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
 * limitations under the License.
 */

package com.android.server.connectivity.tethering;

/**
 * @hide
 *
 * Interface with methods necessary to notify that a given interface is ready for tethering.
 */
public interface IControlsTethering {
    public final int STATE_UNAVAILABLE = 0;
    public final int STATE_AVAILABLE = 1;
    public final int STATE_TETHERED = 2;

    /**
     * Notify that |who| has changed its tethering state.  This may be called from any thread.
     *
     * @param iface a network interface (e.g. "wlan0")
     * @param who corresponding instance of a TetherInterfaceStateMachine
     * @param state one of IControlsTethering.STATE_*
     * @param lastError one of ConnectivityManager.TETHER_ERROR_*
     */
    void notifyInterfaceStateChange(String iface, TetherInterfaceStateMachine who,
                                    int state, int lastError);
}
