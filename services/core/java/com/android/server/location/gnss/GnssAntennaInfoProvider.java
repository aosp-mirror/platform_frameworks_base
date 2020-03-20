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

import android.location.GnssAntennaInfo;
import android.location.IGnssAntennaInfoListener;
import android.location.util.identity.CallerIdentity;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.AppOpsHelper;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;

import java.util.List;

/**
 * Provides GNSS antenna information to clients.
 */
public class GnssAntennaInfoProvider extends
        GnssListenerManager<Void, IGnssAntennaInfoListener, Void> {

    private final GnssAntennaInfoProviderNative mNative;

    public GnssAntennaInfoProvider(UserInfoHelper userInfoHelper, SettingsHelper settingsHelper,
            AppOpsHelper appOpsHelper, AppForegroundHelper appForegroundHelper) {
        this(userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper,
                new GnssAntennaInfoProviderNative());
    }

    @VisibleForTesting
    public GnssAntennaInfoProvider(UserInfoHelper userInfoHelper, SettingsHelper settingsHelper,
            AppOpsHelper appOpsHelper, AppForegroundHelper appForegroundHelper,
            GnssAntennaInfoProviderNative aNative) {
        super(userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper);
        mNative = aNative;
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssAntennaInfoListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerService(Void ignored) {
        if (mNative.isAntennaInfoSupported()) {
            if (mNative.startAntennaInfoListening()) {
                if (GnssManagerService.D) {
                    Log.d(GnssManagerService.TAG, "starting gnss antenna info");
                }
                return true;
            }

            Log.e(GnssManagerService.TAG, "error starting gnss antenna info");
        }
        return false;
    }

    @Override
    protected void unregisterService() {
        if (mNative.stopAntennaInfoListening()) {
            if (GnssManagerService.D) {
                Log.d(GnssManagerService.TAG, "stopping gnss antenna info");
            }
        } else {
            Log.e(GnssManagerService.TAG, "error stopping gnss antenna info");
        }
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onGnssAntennaInfoAvailable(List<GnssAntennaInfo> gnssAntennaInfos) {
        deliverToListeners((listener) -> {
            try {
                listener.onGnssAntennaInfoReceived(gnssAntennaInfos);
            } catch (RemoteException e) {
                // ignore - the listener will get cleaned up later anyways
            }
        });
    }

    @Override
    protected boolean isServiceSupported() {
        return mNative.isAntennaInfoSupported();
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
