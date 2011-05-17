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

package android.net;

import android.os.RemoteException;

/**
 * Manager for creating and modifying network policy rules.
 *
 * {@hide}
 */
public class NetworkPolicyManager {

    /** No specific network policy, use system default. */
    public static final int POLICY_NONE = 0x0;
    /** Reject network usage when application in background. */
    public static final int POLICY_REJECT_BACKGROUND = 0x1;
    /** Reject network usage on paid network connections. */
    public static final int POLICY_REJECT_PAID = 0x2;
    /** Application should conserve data. */
    public static final int POLICY_CONSERVE_DATA = 0x4;

    private INetworkPolicyManager mService;

    public NetworkPolicyManager(INetworkPolicyManager service) {
        if (service == null) {
            throw new IllegalArgumentException("missing INetworkPolicyManager");
        }
        mService = service;
    }

    /**
     * Set policy flags for specific UID.
     *
     * @param policy {@link #POLICY_NONE} or combination of
     *            {@link #POLICY_REJECT_BACKGROUND}, {@link #POLICY_REJECT_PAID},
     *            or {@link #POLICY_CONSERVE_DATA}.
     */
    public void setUidPolicy(int uid, int policy) {
        try {
            mService.setUidPolicy(uid, policy);
        } catch (RemoteException e) {
        }
    }

    public int getUidPolicy(int uid) {
        try {
            return mService.getUidPolicy(uid);
        } catch (RemoteException e) {
            return POLICY_NONE;
        }
    }

}
