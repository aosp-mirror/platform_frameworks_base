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

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.location.IGnssNmeaListener;
import android.location.util.identity.CallerIdentity;
import android.util.Log;

import com.android.internal.listeners.ListenerExecutor;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;

import java.util.Collection;
import java.util.function.Function;

/**
 * Implementation of a handler for {@link IGnssNmeaListener}.
 */
class GnssNmeaProvider extends GnssListenerMultiplexer<Void, IGnssNmeaListener, Void> implements
        GnssNative.BaseCallbacks, GnssNative.NmeaCallbacks {

    private final AppOpsHelper mAppOpsHelper;
    private final GnssNative mGnssNative;

    // preallocated to avoid memory allocation in onReportNmea()
    private final byte[] mNmeaBuffer = new byte[120];

    GnssNmeaProvider(Injector injector, GnssNative gnssNative) {
        super(injector);

        mAppOpsHelper = injector.getAppOpsHelper();
        mGnssNative = gnssNative;

        mGnssNative.addBaseCallbacks(this);
        mGnssNative.addNmeaCallbacks(this);
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssNmeaListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerWithService(Void ignored,
            Collection<GnssListenerRegistration> registrations) {
        if (mGnssNative.startNmeaMessageCollection()) {
            if (D) {
                Log.d(TAG, "starting gnss nmea messages collection");
            }
            return true;
        } else {
            Log.e(TAG, "error starting gnss nmea messages collection");
            return false;
        }
    }

    @Override
    protected void unregisterWithService() {
        if (mGnssNative.stopNmeaMessageCollection()) {
            if (D) {
                Log.d(TAG, "stopping gnss nmea messages collection");
            }
        } else {
            Log.e(TAG, "error stopping gnss nmea messages collection");
        }
    }

    @Override
    public void onHalRestarted() {
        resetService();
    }

    @Override
    public void onReportNmea(long timestamp) {
        deliverToListeners(
                new Function<GnssListenerRegistration,
                        ListenerExecutor.ListenerOperation<IGnssNmeaListener>>() {

                    // only read in the nmea string if we need to
                    private @Nullable String mNmea;

                    @Override
                    public ListenerExecutor.ListenerOperation<IGnssNmeaListener> apply(
                            GnssListenerRegistration registration) {
                        if (mAppOpsHelper.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION,
                                registration.getIdentity())) {
                            if (mNmea == null) {
                                int length = mGnssNative.readNmea(mNmeaBuffer,
                                        mNmeaBuffer.length);
                                mNmea = new String(mNmeaBuffer, 0, length);
                            }
                            return listener -> listener.onNmeaReceived(timestamp, mNmea);
                        } else {
                            return null;
                        }
                    }
                });
    }
}
