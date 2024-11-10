/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.hardware.location.HubInfo;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;
import android.util.Log;

import java.util.Collections;
import java.util.List;

class HubInfoRegistry {
    private static final String TAG = "HubInfoRegistry";

    private final IContextHubWrapper mContextHubWrapper;

    private final List<HubInfo> mHubsInfo;

    HubInfoRegistry(IContextHubWrapper contextHubWrapper) {
        List<HubInfo> hubInfos;
        mContextHubWrapper = contextHubWrapper;
        try {
            hubInfos = mContextHubWrapper.getHubs();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Hub info", e);
            hubInfos = Collections.emptyList();
        }
        mHubsInfo = hubInfos;
    }

    /** Retrieve the list of hubs available. */
    List<HubInfo> getHubs() {
        return mHubsInfo;
    }

    void dump(IndentingPrintWriter ipw) {
        ipw.println(TAG);

        ipw.increaseIndent();
        for (HubInfo hubInfo : mHubsInfo) {
            ipw.println(hubInfo);
        }
        ipw.decreaseIndent();
    }
}
