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

import android.content.Context;
import android.os.RemoteException;

import java.io.PrintWriter;

/**
 * Manager for creating and modifying network policy rules.
 *
 * {@hide}
 */
public class NetworkPolicyManager {

    /** No specific network policy, use system default. */
    public static final int POLICY_NONE = 0x0;
    /** Reject network usage on paid networks when application in background. */
    public static final int POLICY_REJECT_PAID_BACKGROUND = 0x1;

    /** All network traffic should be allowed. */
    public static final int RULE_ALLOW_ALL = 0x0;
    /** Reject traffic on paid networks. */
    public static final int RULE_REJECT_PAID = 0x1;

    private INetworkPolicyManager mService;

    public NetworkPolicyManager(INetworkPolicyManager service) {
        if (service == null) {
            throw new IllegalArgumentException("missing INetworkPolicyManager");
        }
        mService = service;
    }

    public static NetworkPolicyManager getSystemService(Context context) {
        return (NetworkPolicyManager) context.getSystemService(Context.NETWORK_POLICY_SERVICE);
    }

    /** {@hide} */
    public void setNetworkPolicy(int networkType, String subscriberId, NetworkPolicy policy) {
        try {
            mService.setNetworkPolicy(networkType, subscriberId, policy);
        } catch (RemoteException e) {
        }
    }

    /** {@hide} */
    public NetworkPolicy getNetworkPolicy(int networkType, String subscriberId) {
        try {
            return mService.getNetworkPolicy(networkType, subscriberId);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Set policy flags for specific UID.
     *
     * @param policy {@link #POLICY_NONE} or combination of flags like
     *            {@link #POLICY_REJECT_PAID_BACKGROUND}.
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
    
    /** {@hide} */
    public static void dumpPolicy(PrintWriter fout, int policy) {
        fout.write("[");
        if ((policy & POLICY_REJECT_PAID_BACKGROUND) != 0) {
            fout.write("REJECT_PAID_BACKGROUND");
        }
        fout.write("]");
    }

    /** {@hide} */
    public static void dumpRules(PrintWriter fout, int rules) {
        fout.write("[");
        if ((rules & RULE_REJECT_PAID) != 0) {
            fout.write("REJECT_PAID");
        }
        fout.write("]");
    }

}
