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

import android.net.LinkProperties;

/**
 * @hide
 *
 * Interface with methods necessary to notify that a given interface is ready for tethering.
 *
 * Rename to something more representative, e.g. IpServingControlCallback.
 *
 * All methods MUST be called on the TetherMasterSM main Looper's thread.
 */
public class IControlsTethering {
    public static final int STATE_UNAVAILABLE = 0;
    public static final int STATE_AVAILABLE   = 1;
    public static final int STATE_TETHERED    = 2;
    public static final int STATE_LOCAL_ONLY  = 3;

    public static String getStateString(int state) {
        switch (state) {
            case STATE_UNAVAILABLE: return "UNAVAILABLE";
            case STATE_AVAILABLE:   return "AVAILABLE";
            case STATE_TETHERED:    return "TETHERED";
            case STATE_LOCAL_ONLY:  return "LOCAL_ONLY";
        }
        return "UNKNOWN: " + state;
    }

    /**
     * Notify that |who| has changed its tethering state.
     *
     * TODO: Remove the need for the |who| argument.
     *
     * @param who corresponding instance of a TetherInterfaceStateMachine
     * @param state one of IControlsTethering.STATE_*
     * @param lastError one of ConnectivityManager.TETHER_ERROR_*
     */
    public void updateInterfaceState(TetherInterfaceStateMachine who, int state, int lastError) {}

    /**
     * Notify that |who| has new LinkProperties.
     *
     * TODO: Remove the need for the |who| argument.
     *
     * @param who corresponding instance of a TetherInterfaceStateMachine
     * @param newLp the new LinkProperties to report
     */
    public void updateLinkProperties(TetherInterfaceStateMachine who, LinkProperties newLp) {}
}
