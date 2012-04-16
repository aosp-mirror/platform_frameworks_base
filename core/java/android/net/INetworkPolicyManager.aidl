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

import android.net.INetworkPolicyListener;
import android.net.NetworkPolicy;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.NetworkTemplate;

/**
 * Interface that creates and modifies network policy rules.
 *
 * {@hide}
 */
interface INetworkPolicyManager {

    /** Control UID policies. */
    void setAppPolicy(int appId, int policy);
    int getAppPolicy(int appId);
    int[] getAppsWithPolicy(int policy);

    boolean isUidForeground(int uid);

    void registerListener(INetworkPolicyListener listener);
    void unregisterListener(INetworkPolicyListener listener);

    /** Control network policies atomically. */
    void setNetworkPolicies(in NetworkPolicy[] policies);
    NetworkPolicy[] getNetworkPolicies();

    /** Snooze limit on policy matching given template. */
    void snoozeLimit(in NetworkTemplate template);

    /** Control if background data is restricted system-wide. */
    void setRestrictBackground(boolean restrictBackground);
    boolean getRestrictBackground();

    NetworkQuotaInfo getNetworkQuotaInfo(in NetworkState state);
    boolean isNetworkMetered(in NetworkState state);

}
