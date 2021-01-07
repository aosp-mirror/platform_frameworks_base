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

import android.app.AppOpsManager;
import android.location.GnssCapabilities;
import android.location.GnssNavigationMessage;
import android.location.IGnssNavigationMessageListener;
import android.location.util.identity.CallerIdentity;
import android.util.Log;

import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;

import java.util.Collection;

/**
 * An base implementation for GPS navigation messages provider.
 * It abstracts out the responsibility of handling listeners, while still allowing technology
 * specific implementations to be built.
 *
 * @hide
 */
public class GnssNavigationMessageProvider extends
        GnssListenerMultiplexer<Void, IGnssNavigationMessageListener, Void> implements
        GnssNative.BaseCallbacks, GnssNative.NavigationMessageCallbacks {

    private final AppOpsHelper mAppOpsHelper;
    private final GnssNative mGnssNative;

    public GnssNavigationMessageProvider(Injector injector, GnssNative gnssNative) {
        super(injector);
        mAppOpsHelper = injector.getAppOpsHelper();
        mGnssNative = gnssNative;

        mGnssNative.addBaseCallbacks(this);
        mGnssNative.addNavigationMessageCallbacks(this);
    }

    @Override
    protected boolean isServiceSupported() {
        return mGnssNative.isNavigationMessageCollectionSupported();
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssNavigationMessageListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerWithService(Void ignored,
            Collection<GnssListenerRegistration> registrations) {
        if (mGnssNative.startNavigationMessageCollection()) {
            if (D) {
                Log.d(TAG, "starting gnss navigation messages");
            }
            return true;
        } else {
            Log.e(TAG, "error starting gnss navigation messages");
            return false;
        }
    }

    @Override
    protected void unregisterWithService() {
        if (mGnssNative.stopNavigationMessageCollection()) {
            if (D) {
                Log.d(TAG, "stopping gnss navigation messages");
            }
        } else {
            Log.e(TAG, "error stopping gnss navigation messages");
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
    public void onReportNavigationMessage(GnssNavigationMessage event) {
        deliverToListeners(registration -> {
            if (mAppOpsHelper.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION,
                    registration.getIdentity())) {
                return listener -> listener.onGnssNavigationMessageReceived(event);
            } else {
                return null;
            }
        });
    }
}
