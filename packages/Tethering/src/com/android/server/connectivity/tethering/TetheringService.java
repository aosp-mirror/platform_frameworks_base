/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ITetherInternalCallback;
import android.net.ITetheringConnector;
import android.net.NetworkRequest;
import android.net.util.SharedLog;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Android service used to manage tethering.
 *
 * <p>The service returns a binder for the system server to communicate with the tethering.
 */
public class TetheringService extends Service {
    private static final String TAG = TetheringService.class.getSimpleName();

    private final SharedLog mLog = new SharedLog(TAG);
    private TetheringConnector mConnector;
    private Context mContext;
    private TetheringDependencies mDeps;
    private Tethering mTethering;

    @Override
    public void onCreate() {
        mLog.mark("onCreate");
        mDeps = getTetheringDependencies();
        mContext = mDeps.getContext();
        mTethering = makeTethering(mDeps);
    }

    /**
     * Make a reference to Tethering object.
     */
    @VisibleForTesting
    public Tethering makeTethering(TetheringDependencies deps) {
        return new Tethering(deps);
    }

    /**
     * Create a binder connector for the system server to communicate with the tethering.
     */
    private synchronized IBinder makeConnector() {
        if (mConnector == null) {
            mConnector = new TetheringConnector(mTethering);
        }
        return mConnector;
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        mLog.mark("onBind");
        return makeConnector();
    }

    private static class TetheringConnector extends ITetheringConnector.Stub {
        private final Tethering mService;

        TetheringConnector(Tethering tether) {
            mService = tether;
        }

        @Override
        public void tether(String iface) {
            mService.tether(iface);
        }

        @Override
        public void untether(String iface) {
            mService.untether(iface);
        }

        @Override
        public void setUsbTethering(boolean enable) {
            mService.setUsbTethering(enable);
        }

        @Override
        public void startTethering(int type, ResultReceiver receiver, boolean showProvisioningUi) {
            mService.startTethering(type, receiver, showProvisioningUi);
        }

        @Override
        public void stopTethering(int type) {
            mService.stopTethering(type);
        }

        @Override
        public void requestLatestTetheringEntitlementResult(int type, ResultReceiver receiver,
                boolean showEntitlementUi) {
            mService.requestLatestTetheringEntitlementResult(type, receiver, showEntitlementUi);
        }

        @Override
        public void registerTetherInternalCallback(ITetherInternalCallback callback) {
            mService.registerTetherInternalCallback(callback);
        }
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
                @Nullable String[] args) {
        mTethering.dump(fd, writer, args);
    }

    /**
     * An injection method for testing.
     */
    @VisibleForTesting
    public TetheringDependencies getTetheringDependencies() {
        if (mDeps == null) {
            mDeps = new TetheringDependencies() {
                @Override
                public NetworkRequest getDefaultNetworkRequest() {
                    ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                            Context.CONNECTIVITY_SERVICE);
                    return cm.getDefaultRequest();
                }

                @Override
                public Looper getTetheringLooper() {
                    final HandlerThread tetherThread = new HandlerThread("android.tethering");
                    tetherThread.start();
                    return tetherThread.getLooper();
                }

                @Override
                public boolean isTetheringSupported() {
                    int defaultVal =
                            SystemProperties.get("ro.tether.denied").equals("true") ? 0 : 1;
                    boolean tetherSupported = Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.TETHER_SUPPORTED, defaultVal) != 0;
                    return tetherSupported;
                }

                @Override
                public Context getContext() {
                    return TetheringService.this;
                }
            };
        }

        return mDeps;
    }
}
