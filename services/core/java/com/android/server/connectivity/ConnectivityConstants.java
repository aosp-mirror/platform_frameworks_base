/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.connectivity;

/**
 * A class encapsulating various constants used by Connectivity.
 * @hide
 */
public class ConnectivityConstants {

    // Penalty applied to scores of Networks that have not been validated.
    public static final int UNVALIDATED_SCORE_PENALTY = 40;

    // Score for explicitly connected network.
    //
    // This ensures that a) the explicitly selected network is never trumped by anything else, and
    // b) the explicitly selected network is never torn down.
    public static final int EXPLICITLY_SELECTED_NETWORK_SCORE = 100;
    // VPNs typically have priority over other networks. Give them a score that will
    // let them win every single time.
    public static final int VPN_DEFAULT_SCORE = 101;
}
