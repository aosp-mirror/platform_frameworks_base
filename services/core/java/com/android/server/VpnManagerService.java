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

package com.android.server;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.IVpnManager;
import android.net.NetworkStack;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.KeyStore;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.connectivity.Vpn;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Service that tracks and manages VPNs, and backs the VpnService and VpnManager APIs.
 * @hide
 */
public class VpnManagerService extends IVpnManager.Stub {
    private static final String TAG = VpnManagerService.class.getSimpleName();

    @VisibleForTesting
    protected final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final Context mContext;
    private final Context mUserAllContext;


    private final Dependencies mDeps;

    private final ConnectivityManager mCm;
    private final KeyStore mKeyStore;
    private final INetworkManagementService mNMS;
    private final INetd mNetd;
    private final UserManager mUserManager;

    @VisibleForTesting
    @GuardedBy("mVpns")
    protected final SparseArray<Vpn> mVpns = new SparseArray<>();

    /**
     * Dependencies of VpnManager, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** Returns the calling UID of an IPC. */
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        /** Creates a HandlerThread to be used by this class. */
        public HandlerThread makeHandlerThread() {
            return new HandlerThread("VpnManagerService");
        }

        /** Returns the KeyStore instance to be used by this class. */
        public KeyStore getKeyStore() {
            return KeyStore.getInstance();
        }

        public INetd getNetd() {
            return NetdService.getInstance();
        }

        public INetworkManagementService getINetworkManagementService() {
            return INetworkManagementService.Stub.asInterface(
                    ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        }
    }

    public VpnManagerService(Context context, Dependencies deps) {
        mContext = context;
        mDeps = deps;
        mHandlerThread = mDeps.makeHandlerThread();
        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
        mKeyStore = mDeps.getKeyStore();
        mUserAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);
        mCm = mContext.getSystemService(ConnectivityManager.class);
        mNMS = mDeps.getINetworkManagementService();
        mNetd = mDeps.getNetd();
        mUserManager = mContext.getSystemService(UserManager.class);
        log("VpnManagerService starting up");
    }

    /** Creates a new VpnManagerService */
    public static VpnManagerService create(Context context) {
        return new VpnManagerService(context, new Dependencies());
    }

    /** Informs the service that the system is ready. */
    public void systemReady() {
    }

    @Override
    /** Dumps service state. */
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
    }

    private void ensureRunningOnHandlerThread() {
        if (mHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on ConnectivityService thread: "
                            + Thread.currentThread().getName());
        }
    }

    private boolean checkAnyPermissionOf(String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private void enforceAnyPermissionOf(String... permissions) {
        if (!checkAnyPermissionOf(permissions)) {
            throw new SecurityException("Requires one of the following permissions: "
                    + String.join(", ", permissions) + ".");
        }
    }

    private void enforceControlAlwaysOnVpnPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_ALWAYS_ON_VPN,
                "ConnectivityService");
    }

    /**
     * Require that the caller is either in the same user or has appropriate permission to interact
     * across users.
     *
     * @param userId Target user for whatever operation the current IPC is supposed to perform.
     */
    private void enforceCrossUserPermission(int userId) {
        if (userId == UserHandle.getCallingUserId()) {
            // Not a cross-user call.
            return;
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                "VpnManagerService");
    }

    private void enforceSettingsPermission() {
        enforceAnyPermissionOf(
                android.Manifest.permission.NETWORK_SETTINGS,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void logw(String s) {
        Log.w(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
