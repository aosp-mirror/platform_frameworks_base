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

import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import java.util.BitSet;


public class EthernetSignalController extends
        SignalController<SignalController.State, SignalController.IconGroup> {

    public EthernetSignalController(Context context,
            CallbackHandler callbackHandler, NetworkControllerImpl networkController) {
        super("EthernetSignalController", context, NetworkCapabilities.TRANSPORT_ETHERNET,
                callbackHandler, networkController);
        mCurrentState.iconGroup = mLastState.iconGroup = new IconGroup(
                "Ethernet Icons",
                EthernetIcons.ETHERNET_ICONS,
                null,
                AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES,
                0, 0, 0, 0,
                AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES[0]);
    }

    @Override
    public void updateConnectivity(BitSet connectedTransports, BitSet validatedTransports) {
        mCurrentState.connected = connectedTransports.get(mTransportType);
        super.updateConnectivity(connectedTransports, validatedTransports);
    }

    @Override
    public void notifyListeners(SignalCallback callback) {
        boolean ethernetVisible = mCurrentState.connected;
        String contentDescription = getStringIfExists(getContentDescription()).toString();

        // TODO: wire up data transfer using WifiSignalPoller.
        callback.setEthernetIndicators(new IconState(ethernetVisible, getCurrentIconId(),
                contentDescription));
    }

    @Override
    public SignalController.State cleanState() {
        return new SignalController.State();
    }
}
