/*
 * Copyright (C) 2011 The Android Open Source Project
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

 package android.net.wifi;

/**
 * Stores supplicant state change information passed from WifiMonitor to
 * a state machine. WifiStateMachine, SupplicantStateTracker and WpsStateMachine
 * are example state machines that handle it.
 * @hide
 */
public class StateChangeResult {
    StateChangeResult(int networkId, String BSSID, SupplicantState state) {
        this.state = state;
        this.BSSID = BSSID;
        this.networkId = networkId;
    }
    int networkId;
    String BSSID;
    SupplicantState state;
}
