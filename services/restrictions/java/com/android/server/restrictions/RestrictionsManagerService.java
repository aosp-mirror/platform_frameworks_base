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

package com.android.server.restrictions;

import android.Manifest;
import android.accounts.IAccountAuthenticator;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.IDevicePolicyManager;
import android.content.AbstractRestrictionsProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IPermissionResponseCallback;
import android.content.IRestrictionsProvider;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IRestrictionsManager;
import android.content.RestrictionsManager;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;

/**
 * SystemService wrapper for the RestrictionsManager implementation. Publishes the
 * Context.RESTRICTIONS_SERVICE.
 */
public final class RestrictionsManagerService extends SystemService {

    static final String LOG_TAG = "RestrictionsManagerService";
    static final boolean DEBUG = false;

    private final RestrictionsManagerImpl mRestrictionsManagerImpl;

    public RestrictionsManagerService(Context context) {
        super(context);
        mRestrictionsManagerImpl = new RestrictionsManagerImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.RESTRICTIONS_SERVICE, mRestrictionsManagerImpl);
    }

    class RestrictionsManagerImpl extends IRestrictionsManager.Stub {
        final Context mContext;
        private final IUserManager mUm;
        private final IDevicePolicyManager mDpm;

        public RestrictionsManagerImpl(Context context) {
            mContext = context;
            mUm = (IUserManager) getBinderService(Context.USER_SERVICE);
            mDpm = (IDevicePolicyManager) getBinderService(Context.DEVICE_POLICY_SERVICE);
        }

        @Override
        public Bundle getApplicationRestrictions(String packageName) throws RemoteException {
            return mUm.getApplicationRestrictions(packageName);
        }

        @Override
        public boolean hasRestrictionsProvider() throws RemoteException {
            int userHandle = UserHandle.getCallingUserId();
            if (mDpm != null) {
                long ident = Binder.clearCallingIdentity();
                try {
                    return mDpm.getRestrictionsProvider(userHandle) != null;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                return false;
            }
        }

        @Override
        public void requestPermission(final String packageName, final String requestType,
                final Bundle requestData) throws RemoteException {
            if (DEBUG) {
                Log.i(LOG_TAG, "requestPermission");
            }
            int callingUid = Binder.getCallingUid();
            int userHandle = UserHandle.getUserId(callingUid);
            if (mDpm != null) {
                long ident = Binder.clearCallingIdentity();
                try {
                    ComponentName restrictionsProvider =
                            mDpm.getRestrictionsProvider(userHandle);
                    // Check if there is a restrictions provider
                    if (restrictionsProvider == null) {
                        throw new IllegalStateException(
                            "Cannot request permission without a restrictions provider registered");
                    }
                    // Check that the packageName matches the caller.
                    enforceCallerMatchesPackage(callingUid, packageName, "Package name does not" +
                            " match caller ");
                    // Prepare and broadcast the intent to the provider
                    Intent intent = new Intent();
                    intent.setComponent(restrictionsProvider);
                    new ProviderServiceConnection(intent, null, userHandle) {
                        @Override
                        public void run() throws RemoteException {
                            if (DEBUG) {
                                Log.i(LOG_TAG, "calling requestPermission for " + packageName
                                        + ", type=" + requestType + ", data=" + requestData);
                            }
                            mRestrictionsProvider.requestPermission(packageName,
                                    requestType, requestData);
                        }
                    }.bind();
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void getPermissionResponse(final String packageName, final String requestId,
                final IPermissionResponseCallback callback) throws RemoteException {
            int callingUid = Binder.getCallingUid();
            int userHandle = UserHandle.getUserId(callingUid);
            if (mDpm != null) {
                long ident = Binder.clearCallingIdentity();
                try {
                    ComponentName restrictionsProvider =
                            mDpm.getRestrictionsProvider(userHandle);
                    // Check if there is a restrictions provider
                    if (restrictionsProvider == null) {
                        throw new IllegalStateException(
                            "Cannot fetch permission without a restrictions provider registered");
                    }
                    // Check that the packageName matches the caller.
                    enforceCallerMatchesPackage(callingUid, packageName, "Package name does not" +
                            " match caller ");
                    // Prepare and broadcast the intent to the provider
                    Intent intent = new Intent();
                    intent.setComponent(restrictionsProvider);
                    new ProviderServiceConnection(intent, callback.asBinder(), userHandle) {
                        @Override
                        public void run() throws RemoteException {
                            if (DEBUG) {
                                Log.i(LOG_TAG, "calling getPermissionResponse for " + packageName
                                        + ", id=" + requestId);
                            }
                            Bundle response = mRestrictionsProvider.getPermissionResponse(
                                    packageName, requestId);
                            callback.onResponse(response);
                        }
                    }.bind();
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void notifyPermissionResponse(String packageName, Bundle response)
                throws RemoteException {
            // Check caller
            int callingUid = Binder.getCallingUid();
            int userHandle = UserHandle.getUserId(callingUid);
            if (mDpm != null) {
                long ident = Binder.clearCallingIdentity();
                try {
                    ComponentName permProvider = mDpm.getRestrictionsProvider(userHandle);
                    if (permProvider == null) {
                        throw new SecurityException("No restrictions provider registered for user");
                    }
                    enforceCallerMatchesPackage(callingUid, permProvider.getPackageName(),
                            "Restrictions provider does not match caller ");

                    // Post the response to target package
                    Intent responseIntent = new Intent(
                            RestrictionsManager.ACTION_PERMISSION_RESPONSE_RECEIVED);
                    responseIntent.setPackage(packageName);
                    responseIntent.putExtra(RestrictionsManager.EXTRA_RESPONSE_BUNDLE, response);
                    mContext.sendBroadcastAsUser(responseIntent, new UserHandle(userHandle));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        private void enforceCallerMatchesPackage(int callingUid, String packageName,
                String message) {
            try {
                String[] pkgs = AppGlobals.getPackageManager().getPackagesForUid(callingUid);
                if (pkgs != null) {
                    if (!ArrayUtils.contains(pkgs, packageName)) {
                        throw new SecurityException(message + callingUid);
                    }
                }
            } catch (RemoteException re) {
                // Shouldn't happen
            }
        }

        abstract class ProviderServiceConnection
                implements IBinder.DeathRecipient, ServiceConnection {

            protected IRestrictionsProvider mRestrictionsProvider;
            private Intent mIntent;
            protected int mUserHandle;
            protected IBinder mResponse;
            private boolean mAbort;

            public ProviderServiceConnection(Intent intent, IBinder response, int userHandle) {
                mIntent = intent;
                mResponse = response;
                mUserHandle = userHandle;
                if (mResponse != null) {
                    try {
                        mResponse.linkToDeath(this, 0 /* flags */);
                    } catch (RemoteException re) {
                        close();
                    }
                }
            }

            /** Bind to the RestrictionsProvider process */
            public void bind() {
                if (DEBUG) {
                    Log.i(LOG_TAG, "binding to service: " + mIntent);
                }
                mContext.bindServiceAsUser(mIntent, this, Context.BIND_AUTO_CREATE,
                        new UserHandle(mUserHandle));
            }

            private void close() {
                mAbort = true;
                unbind();
            }

            private void unbind() {
                if (DEBUG) {
                    Log.i(LOG_TAG, "unbinding from service");
                }
                mContext.unbindService(this);
            }

            /** Implement this to call the appropriate method on the service */
            public abstract void run() throws RemoteException;

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "connected to " + name);
                }
                mRestrictionsProvider = IRestrictionsProvider.Stub.asInterface(service);
                if (!mAbort) {
                    try {
                        run();
                    } catch (RemoteException re) {
                        Log.w("RestrictionsProvider", "Remote exception: " + re);
                    }
                }
                close();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "disconnected from " + name);
                }
                mRestrictionsProvider = null;
            }

            @Override
            public void binderDied() {
                mAbort = true;
            }
        }
    }
}
