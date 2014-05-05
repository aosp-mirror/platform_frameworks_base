/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.hdmi;

import android.os.IBinder;
import android.os.RemoteException;

/**
 * The HdmiCecManager class is used to provide an HdmiCecClient instance,
 * get various information on HDMI ports configuration. It is connected to actual hardware
 * via HdmiCecService.
 *
 * @hide
 */
public final class HdmiCecManager {
    private final IHdmiCecService mService;

    /**
     * @hide - hide this constructor because it has a parameter of type IHdmiCecService,
     * which is a system private class. The right way to create an instance of this class
     * is using the factory Context.getSystemService.
     */
    public HdmiCecManager(IHdmiCecService service) {
        mService = service;
    }

    /**
     * Provide the HdmiCecClient instance of the given type. It also registers the listener
     * for client to get the events coming to the device.
     *
     * @param type type of the HDMI-CEC logical device
     * @param listener listener to be called
     * @return {@link HdmiCecClient} instance. {@code null} on failure.
     */
    public HdmiCecClient getClient(int type, HdmiCecClient.Listener listener) {
        if (mService == null) {
            return null;
        }
        try {
            IBinder b = mService.allocateLogicalDevice(type, getListenerWrapper(listener));
            return HdmiCecClient.create(mService, b);
        } catch (RemoteException e) {
            return null;
        }
    }

    private IHdmiCecListener getListenerWrapper(final HdmiCecClient.Listener listener) {
        // TODO: The message/events are not yet forwarded to client since it is not clearly
        //       defined as to how/who to handle them. Revisit it once the decision is
        //       made on what messages will have to reach the clients, what will be
        //       handled by service/manager.
        return new IHdmiCecListener.Stub() {
            @Override
            public void onMessageReceived(HdmiCecMessage message) {
                // Do nothing.
            }

            @Override
            public void onCableStatusChanged(boolean connected) {
                // Do nothing.
            }
        };
    }
}
