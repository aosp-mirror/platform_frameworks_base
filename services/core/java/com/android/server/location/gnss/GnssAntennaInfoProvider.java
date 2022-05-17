/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.annotation.Nullable;
import android.location.GnssAntennaInfo;
import android.location.IGnssAntennaInfoListener;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.IBinder;

import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.listeners.BinderListenerRegistration;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.ListenerRegistration;

import java.util.Collection;
import java.util.List;

/**
 * Antenna info HAL module and listener multiplexer.
 */
public class GnssAntennaInfoProvider extends
        ListenerMultiplexer<IBinder, IGnssAntennaInfoListener,
                ListenerRegistration<IGnssAntennaInfoListener>, Void> implements
        GnssNative.BaseCallbacks, GnssNative.AntennaInfoCallbacks {

    /**
     * Registration object for GNSS listeners.
     */
    protected class AntennaInfoListenerRegistration extends
            BinderListenerRegistration<Void, IGnssAntennaInfoListener> {

        protected AntennaInfoListenerRegistration(CallerIdentity callerIdentity,
                IGnssAntennaInfoListener listener) {
            super(null, callerIdentity, listener);
        }

        @Override
        protected GnssAntennaInfoProvider getOwner() {
            return GnssAntennaInfoProvider.this;
        }
    }

    private final GnssNative mGnssNative;

    private volatile @Nullable List<GnssAntennaInfo> mAntennaInfos;

    GnssAntennaInfoProvider(GnssNative gnssNative) {
        mGnssNative = gnssNative;
        mGnssNative.addBaseCallbacks(this);
        mGnssNative.addAntennaInfoCallbacks(this);
    }

    @Nullable List<GnssAntennaInfo> getAntennaInfos() {
        return mAntennaInfos;
    }

    @Override
    public String getTag() {
        return TAG;
    }

    public boolean isSupported() {
        return mGnssNative.isAntennaInfoSupported();
    }

    public void addListener(CallerIdentity callerIdentity, IGnssAntennaInfoListener listener) {
        final long identity = Binder.clearCallingIdentity();
        try {
            putRegistration(listener.asBinder(),
                    new AntennaInfoListenerRegistration(callerIdentity, listener));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void removeListener(IGnssAntennaInfoListener listener) {
        final long identity = Binder.clearCallingIdentity();
        try {
            removeRegistration(listener.asBinder());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    protected boolean registerWithService(Void merged,
            Collection<ListenerRegistration<IGnssAntennaInfoListener>> listenerRegistrations) {
        return true;
    }

    @Override
    protected void unregisterWithService() {}

    @Override
    protected boolean isActive(ListenerRegistration<IGnssAntennaInfoListener> registration) {
        return true;
    }

    @Override
    protected Void mergeRegistrations(
            Collection<ListenerRegistration<IGnssAntennaInfoListener>> listenerRegistrations) {
        return null;
    }

    @Override
    public void onHalStarted() {
        mGnssNative.startAntennaInfoListening();
    }

    @Override
    public void onHalRestarted() {
        mGnssNative.startAntennaInfoListening();
    }

    @Override
    public void onReportAntennaInfo(List<GnssAntennaInfo> antennaInfos) {
        if (antennaInfos.equals(mAntennaInfos)) {
            return;
        }

        mAntennaInfos = antennaInfos;
        deliverToListeners(listener -> {
            listener.onGnssAntennaInfoChanged(antennaInfos);
        });
    }
}
