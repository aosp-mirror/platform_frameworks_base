/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.net.NetworkCapabilities;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.SignalCluster;

import java.util.List;
import java.util.Objects;


public class EthernetSignalController extends
        SignalController<SignalController.State, SignalController.IconGroup> {

    public EthernetSignalController(Context context,
            List<NetworkSignalChangedCallback> signalCallbacks,
            List<SignalCluster> signalClusters, NetworkControllerImpl networkController) {
        super("EthernetSignalController", context, NetworkCapabilities.TRANSPORT_ETHERNET,
                signalCallbacks, signalClusters, networkController);
        mCurrentState.iconGroup = mLastState.iconGroup = new IconGroup(
                "Ethernet Icons",
                EthernetIcons.ETHERNET_ICONS,
                null,
                AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES,
                0, 0, 0, 0,
                AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES[0]);
    }

    @Override
    public void notifyListeners() {
        boolean ethernetVisible = mCurrentState.connected;
        String contentDescription = getStringIfExists(getContentDescription());

        // TODO: wire up data transfer using WifiSignalPoller.
        int signalClustersLength = mSignalClusters.size();
        for (int i = 0; i < signalClustersLength; i++) {
            mSignalClusters.get(i).setEthernetIndicators(ethernetVisible, getCurrentIconId(),
                    contentDescription);
        }
    }

    @Override
    public SignalController.State cleanState() {
        return new SignalController.State();
    }

    public void setConnected(boolean connected) {
        mCurrentState.connected = connected;
        notifyListenersIfNecessary();
    }
}
