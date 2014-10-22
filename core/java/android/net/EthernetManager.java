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

package android.net;

import android.content.Context;
import android.net.IEthernetManager;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.os.RemoteException;

/**
 * A class representing the IP configuration of the Ethernet network.
 *
 * @hide
 */
public class EthernetManager {
    private static final String TAG = "EthernetManager";

    private final Context mContext;
    private final IEthernetManager mService;

    /**
     * Create a new EthernetManager instance.
     * Applications will almost always want to use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#ETHERNET_SERVICE Context.ETHERNET_SERVICE}.
     */
    public EthernetManager(Context context, IEthernetManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Get Ethernet configuration.
     * @return the Ethernet Configuration, contained in {@link IpConfiguration}.
     */
    public IpConfiguration getConfiguration() {
        if (mService == null) {
            return new IpConfiguration();
        }
        try {
            return mService.getConfiguration();
        } catch (RemoteException e) {
            return new IpConfiguration();
        }
    }

    /**
     * Set Ethernet configuration.
     */
    public void setConfiguration(IpConfiguration config) {
        if (mService == null) {
            return;
        }
        try {
            mService.setConfiguration(config);
        } catch (RemoteException e) {
        }
    }
}
