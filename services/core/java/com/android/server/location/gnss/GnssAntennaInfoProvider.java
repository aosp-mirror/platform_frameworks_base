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
import android.location.IGnssAntennaInfoListener;
import android.location.util.identity.CallerIdentity;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.location.util.Injector;

import java.util.List;

/**
 * Provides GNSS antenna information to clients.
 */
public class GnssAntennaInfoProvider extends
        GnssListenerMultiplexer<Void, IGnssAntennaInfoListener, Void> {

    private final GnssAntennaInfoProviderNative mNative;

    public GnssAntennaInfoProvider(Injector injector) {
        this(injector, new GnssAntennaInfoProviderNative());
    }

    @VisibleForTesting
    public GnssAntennaInfoProvider(Injector injector, GnssAntennaInfoProviderNative aNative) {
        super(injector);
        mNative = aNative;
    }

    @Override
    protected boolean isServiceSupported() {
        return mNative.isAntennaInfoSupported();
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssAntennaInfoListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerWithService(Void ignored) {
        Preconditions.checkState(mNative.isAntennaInfoSupported());

        if (mNative.startAntennaInfoListening()) {
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
        if (mNative.stopAntennaInfoListening()) {
            if (D) {
                Log.d(TAG, "stopping gnss antenna info");
            }
        } else {
            Log.e(TAG, "error stopping gnss antenna info");
        }
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onGnssAntennaInfoAvailable(List<GnssAntennaInfo> gnssAntennaInfos) {
        deliverToListeners((listener) -> {
            listener.onGnssAntennaInfoReceived(gnssAntennaInfos);
        });
    }

    /**
     * Wrapper class for native methods. This is mocked for testing.
     */
    @VisibleForTesting
    public static class GnssAntennaInfoProviderNative {

        public boolean isAntennaInfoSupported() {
            return native_is_antenna_info_supported();
        }

        /** Start antenna info listening. */
        public boolean startAntennaInfoListening() {
            return native_start_antenna_info_listening();
        }

        /** Stop antenna info listening. */
        public boolean stopAntennaInfoListening() {
            return native_stop_antenna_info_listening();
        }
    }

    static native boolean native_is_antenna_info_supported();

    static native boolean native_start_antenna_info_listening();

    static native boolean native_stop_antenna_info_listening();
}
