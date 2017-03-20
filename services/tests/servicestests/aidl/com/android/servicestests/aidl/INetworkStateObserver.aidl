/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.servicestests.aidl;

oneway interface INetworkStateObserver {
    /**
     * {@param resultData} will be in the format
     * NetinfoState|NetinfoDetailedState|RealConnectionCheck|RealConnectionCheckDetails|Netinfo.
     * For detailed info, see
     * servicestests/test-apps/ConnTestApp/.../ConnTestActivity#checkNetworkStatus
     */
    void onNetworkStateChecked(String resultData);
}