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
import android.net.NetworkTemplate;

/**
 * Interface that creates and modifies network policy rules.
 *
 * {@hide}
 */
interface INetworkPolicyManager {

    void setUidPolicy(int uid, int policy);
    int getUidPolicy(int uid);

    boolean isUidForeground(int uid);

    void registerListener(INetworkPolicyListener listener);
    void unregisterListener(INetworkPolicyListener listener);

    void setNetworkPolicies(in NetworkPolicy[] policies);
    NetworkPolicy[] getNetworkPolicies();

    void snoozePolicy(in NetworkTemplate template);

}
