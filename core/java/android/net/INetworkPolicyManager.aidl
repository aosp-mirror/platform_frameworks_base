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

/**
 * Interface that creates and modifies network policy rules.
 *
 * {@hide}
 */
interface INetworkPolicyManager {

    void onForegroundActivitiesChanged(int uid, int pid, boolean foregroundActivities);
    void onProcessDied(int uid, int pid);

    void setUidPolicy(int uid, int policy);
    int getUidPolicy(int uid);

    // TODO: build API to surface stats details for settings UI

}
