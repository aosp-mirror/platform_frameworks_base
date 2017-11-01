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

package com.android.server.net;

/**
 * Network Policy Manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class NetworkPolicyManagerInternal {

    /**
     * Resets all policies associated with a given user.
     */
    public abstract void resetUserState(int userId);

    /**
     * @return true if the given uid is restricted from doing networking on metered networks.
     */
    public abstract boolean isUidRestrictedOnMeteredNetworks(int uid);

    /**
     * @return true if networking is blocked on the given interface for the given uid according
     * to current networking policies.
     */
    public abstract boolean isUidNetworkingBlocked(int uid, String ifname);
}
