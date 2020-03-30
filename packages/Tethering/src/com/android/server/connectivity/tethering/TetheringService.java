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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.TetheringManager.TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.net.dhcp.IDhcpServer.STATUS_UNKNOWN_ERROR;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.net.IIntResultListener;
import android.net.INetworkStackConnector;
import android.net.ITetheringConnector;
import android.net.ITetheringEventCallback;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkStack;
import android.net.TetheringRequestParcel;
import android.net.dhcp.DhcpServerCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.ip.IpServer;
import android.net.util.SharedLog;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

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
    private UserManager mUserManager;

    @Override
    public void onCreate() {
        mLog.mark("onCreate");
        mDeps = getTetheringDependencies();
        mContext = mDeps.getContext();
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mTethering = makeTethering(mDeps);
        mTethering.startStateMachineUpdaters();
    }

    /**
     * Make a reference to Tethering object.
     */
    @VisibleForTesting
    public Tethering makeTethering(TetheringDependencies deps) {
        System.loadLibrary("tetherutilsjni");
        return new Tethering(deps);
    }

    /**
     * Create a binder connector for the system server to communicate with the tethering.
     */
    private synchronized IBinder makeConnector() {
        if (mConnector == null) {
            mConnector = new TetheringConnector(mTethering, TetheringService.this);
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
        private final TetheringService mService;
        private final Tethering mTethering;

        TetheringConnector(Tethering tether, TetheringService service) {
            mTethering = tether;
            mService = service;
        }

        @Override
        public void tether(String iface, String callerPkg, IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, listener)) return;

            try {
                listener.onResult(mTethering.tether(iface));
            } catch (RemoteException e) { }
        }

        @Override
        public void untether(String iface, String callerPkg, IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, listener)) return;

            try {
                listener.onResult(mTethering.untether(iface));
            } catch (RemoteException e) { }
        }

        @Override
        public void setUsbTethering(boolean enable, String callerPkg, IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, listener)) return;

            try {
                listener.onResult(mTethering.setUsbTethering(enable));
            } catch (RemoteException e) { }
        }

        @Override
        public void startTethering(TetheringRequestParcel request, String callerPkg,
                IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, listener)) return;

            mTethering.startTethering(request, listener);
        }

        @Override
        public void stopTethering(int type, String callerPkg, IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, listener)) return;

            try {
                mTethering.stopTethering(type);
                listener.onResult(TETHER_ERROR_NO_ERROR);
            } catch (RemoteException e) { }
        }

        @Override
        public void requestLatestTetheringEntitlementResult(int type, ResultReceiver receiver,
                boolean showEntitlementUi, String callerPkg) {
            if (checkAndNotifyCommonError(callerPkg, receiver)) return;

            mTethering.requestLatestTetheringEntitlementResult(type, receiver, showEntitlementUi);
        }

        @Override
        public void registerTetheringEventCallback(ITetheringEventCallback callback,
                String callerPkg) {
            try {
                if (!mService.hasTetherAccessPermission()) {
                    callback.onCallbackStopped(TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);
                    return;
                }
                mTethering.registerTetheringEventCallback(callback);
            } catch (RemoteException e) { }
        }

        @Override
        public void unregisterTetheringEventCallback(ITetheringEventCallback callback,
                String callerPkg) {
            try {
                if (!mService.hasTetherAccessPermission()) {
                    callback.onCallbackStopped(TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);
                    return;
                }
                mTethering.unregisterTetheringEventCallback(callback);
            } catch (RemoteException e) { }
        }

        @Override
        public void stopAllTethering(String callerPkg, IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, listener)) return;

            try {
                mTethering.untetherAll();
                listener.onResult(TETHER_ERROR_NO_ERROR);
            } catch (RemoteException e) { }
        }

        @Override
        public void isTetheringSupported(String callerPkg, IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, listener)) return;

            try {
                listener.onResult(TETHER_ERROR_NO_ERROR);
            } catch (RemoteException e) { }
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
                    @Nullable String[] args) {
            mTethering.dump(fd, writer, args);
        }

        private boolean checkAndNotifyCommonError(String callerPkg, IIntResultListener listener) {
            try {
                if (!mService.hasTetherChangePermission(callerPkg)) {
                    listener.onResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
                    return true;
                }
                if (!mService.isTetheringSupported()) {
                    listener.onResult(TETHER_ERROR_UNSUPPORTED);
                    return true;
                }
            } catch (RemoteException e) {
                return true;
            }

            return false;
        }

        private boolean checkAndNotifyCommonError(String callerPkg, ResultReceiver receiver) {
            if (!mService.hasTetherChangePermission(callerPkg)) {
                receiver.send(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION, null);
                return true;
            }
            if (!mService.isTetheringSupported()) {
                receiver.send(TETHER_ERROR_UNSUPPORTED, null);
                return true;
            }

            return false;
        }

    }

    // if ro.tether.denied = true we default to no tethering
    // gservices could set the secure setting to 1 though to enable it on a build where it
    // had previously been turned off.
    private boolean isTetheringSupported() {
        final int defaultVal =
                SystemProperties.get("ro.tether.denied").equals("true") ? 0 : 1;
        final boolean tetherSupported = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.TETHER_SUPPORTED, defaultVal) != 0;
        final boolean tetherEnabledInSettings = tetherSupported
                && !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING);

        return tetherEnabledInSettings && mTethering.hasTetherableConfiguration();
    }

    private boolean hasTetherChangePermission(String callerPkg) {
        if (checkCallingOrSelfPermission(
                android.Manifest.permission.TETHER_PRIVILEGED) == PERMISSION_GRANTED) {
            return true;
        }

        if (mTethering.isTetherProvisioningRequired()) return false;


        int uid = Binder.getCallingUid();
        // If callerPkg's uid is not same as Binder.getCallingUid(),
        // checkAndNoteWriteSettingsOperation will return false and the operation will be denied.
        if (Settings.checkAndNoteWriteSettingsOperation(mContext, uid, callerPkg,
                false /* throwException */)) {
            return true;
        }

        return false;
    }

    private boolean hasTetherAccessPermission() {
        if (checkCallingOrSelfPermission(
                android.Manifest.permission.TETHER_PRIVILEGED) == PERMISSION_GRANTED) {
            return true;
        }

        if (checkCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE) == PERMISSION_GRANTED) {
            return true;
        }

        return false;
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
                    // TODO: b/147280869, add a proper system API to replace this.
                    final NetworkRequest trackDefaultRequest = new NetworkRequest.Builder()
                            .clearCapabilities()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    return trackDefaultRequest;
                }

                @Override
                public Looper getTetheringLooper() {
                    final HandlerThread tetherThread = new HandlerThread("android.tethering");
                    tetherThread.start();
                    return tetherThread.getLooper();
                }

                @Override
                public boolean isTetheringSupported() {
                    return TetheringService.this.isTetheringSupported();
                }

                @Override
                public Context getContext() {
                    return TetheringService.this;
                }

                @Override
                public IpServer.Dependencies getIpServerDependencies() {
                    return new IpServer.Dependencies() {
                        @Override
                        public void makeDhcpServer(String ifName, DhcpServingParamsParcel params,
                                DhcpServerCallbacks cb) {
                            try {
                                final INetworkStackConnector service = getNetworkStackConnector();
                                if (service == null) return;

                                service.makeDhcpServer(ifName, params, cb);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Fail to make dhcp server");
                                try {
                                    cb.onDhcpServerCreated(STATUS_UNKNOWN_ERROR, null);
                                } catch (RemoteException re) { }
                            }
                        }
                    };
                }

                // TODO: replace this by NetworkStackClient#getRemoteConnector after refactoring
                // networkStackClient.
                static final int NETWORKSTACK_TIMEOUT_MS = 60_000;
                private INetworkStackConnector getNetworkStackConnector() {
                    IBinder connector;
                    try {
                        final long before = System.currentTimeMillis();
                        while ((connector = NetworkStack.getService()) == null) {
                            if (System.currentTimeMillis() - before > NETWORKSTACK_TIMEOUT_MS) {
                                Log.wtf(TAG, "Timeout, fail to get INetworkStackConnector");
                                return null;
                            }
                            Thread.sleep(200);
                        }
                    } catch (InterruptedException e) {
                        Log.wtf(TAG, "Interrupted, fail to get INetworkStackConnector");
                        return null;
                    }
                    return INetworkStackConnector.Stub.asInterface(connector);
                }

                @Override
                public BluetoothAdapter getBluetoothAdapter() {
                    return BluetoothAdapter.getDefaultAdapter();
                }
            };
        }
        return mDeps;
    }
}
