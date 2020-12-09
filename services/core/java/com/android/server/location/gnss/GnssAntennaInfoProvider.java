/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import static com.android.server.location.gnss.GnssManagerService.D;
import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.location.GnssAntennaInfo;
import android.location.GnssCapabilities;
import android.location.IGnssAntennaInfoListener;
import android.location.util.identity.CallerIdentity;
import android.util.Log;

import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.Injector;

import java.util.Collection;
import java.util.List;

/**
 * Provides GNSS antenna information to clients.
 */
public class GnssAntennaInfoProvider extends
        GnssListenerMultiplexer<Void, IGnssAntennaInfoListener, Void> implements
        GnssNative.BaseCallbacks, GnssNative.AntennaInfoCallbacks {

    private final GnssNative mGnssNative;

    public GnssAntennaInfoProvider(Injector injector, GnssNative gnssNative) {
        super(injector);
        mGnssNative = gnssNative;

        mGnssNative.addBaseCallbacks(this);
        mGnssNative.addAntennaInfoCallbacks(this);
    }

    @Override
    protected boolean isServiceSupported() {
        return mGnssNative.isAntennaInfoListeningSupported();
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssAntennaInfoListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerWithService(Void ignored,
            Collection<GnssListenerRegistration> registrations) {
        if (mGnssNative.startAntennaInfoListening()) {
            if (D) {
                Log.d(TAG, "starting gnss antenna info");
            }
            return true;
        } else {
            Log.e(TAG, "error starting gnss antenna info");
            return false;
        }
    }

    @Override
    protected void unregisterWithService() {
        if (mGnssNative.stopAntennaInfoListening()) {
            if (D) {
                Log.d(TAG, "stopping gnss antenna info");
            }
        } else {
            Log.e(TAG, "error stopping gnss antenna info");
        }
    }

    @Override
    public void onHalRestarted() {
        resetService();
    }

    @Override
    public void onCapabilitiesChanged(GnssCapabilities oldCapabilities,
            GnssCapabilities newCapabilities) {}

    @Override
    public void onReportAntennaInfo(List<GnssAntennaInfo> antennaInfos) {
        deliverToListeners(listener -> {
            listener.onGnssAntennaInfoReceived(antennaInfos);
        });
    }
}
