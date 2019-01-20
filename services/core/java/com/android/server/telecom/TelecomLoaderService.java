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

package com.android.server.telecom;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.DefaultDialerManager;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.telephony.SmsApplication;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.permission.DefaultPermissionGrantPolicy;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

/**
 * Starts the telecom component by binding to its ITelecomService implementation. Telecom is setup
 * to run in the system-server process so once it is loaded into memory it will stay running.
 * @hide
 */
public class TelecomLoaderService extends SystemService {
    private static final String TAG = "TelecomLoaderService";

    private class TelecomServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Normally, we would listen for death here, but since telecom runs in the same process
            // as this loader (process="system") thats redundant here.
            try {
                service.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        connectToTelecom();
                    }
                }, 0);
                SmsApplication.getDefaultMmsApplication(mContext, false);
                ServiceManager.addService(Context.TELECOM_SERVICE, service);

                synchronized (mLock) {
                    if (mDefaultSmsAppRequests != null || mDefaultDialerAppRequests != null
                            || mDefaultSimCallManagerRequests != null) {
                        final DefaultPermissionGrantPolicy permissionPolicy =
                                getDefaultPermissionGrantPolicy();

                        if (mDefaultSmsAppRequests != null) {
                            ComponentName smsComponent = SmsApplication.getDefaultSmsApplication(
                                    mContext, true);
                            if (smsComponent != null) {
                                final int requestCount = mDefaultSmsAppRequests.size();
                                for (int i = requestCount - 1; i >= 0; i--) {
                                    final int userid = mDefaultSmsAppRequests.get(i);
                                    mDefaultSmsAppRequests.remove(i);
                                    permissionPolicy.grantDefaultPermissionsToDefaultSmsApp(
                                            smsComponent.getPackageName(), userid);
                                }
                            }
                        }

                        if (mDefaultDialerAppRequests != null) {
                            String packageName = DefaultDialerManager.getDefaultDialerApplication(
                                    mContext);
                            if (packageName != null) {
                                final int requestCount = mDefaultDialerAppRequests.size();
                                for (int i = requestCount - 1; i >= 0; i--) {
                                    final int userId = mDefaultDialerAppRequests.get(i);
                                    mDefaultDialerAppRequests.remove(i);
                                    permissionPolicy.grantDefaultPermissionsToDefaultDialerApp(
                                            packageName, userId);
                                }
                            }
                        }
                        if (mDefaultSimCallManagerRequests != null) {
                            TelecomManager telecomManager =
                                (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
                            PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager();
                            if (phoneAccount != null) {
                                final int requestCount = mDefaultSimCallManagerRequests.size();
                                final String packageName =
                                    phoneAccount.getComponentName().getPackageName();
                                for (int i = requestCount - 1; i >= 0; i--) {
                                    final int userId = mDefaultSimCallManagerRequests.get(i);
                                    mDefaultSimCallManagerRequests.remove(i);
                                    permissionPolicy
                                            .grantDefaultPermissionsToDefaultSimCallManager(
                                                    packageName, userId);
                                }
                            }
                        }
                    }
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed linking to death.");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            connectToTelecom();
        }
    }

    private DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy() {
        return LocalServices.getService(PermissionManagerServiceInternal.class)
                .getDefaultPermissionGrantPolicy();
    }

    private static final ComponentName SERVICE_COMPONENT = new ComponentName(
            "com.android.server.telecom",
            "com.android.server.telecom.components.TelecomService");

    private static final String SERVICE_ACTION = "com.android.ITelecomService";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private IntArray mDefaultSmsAppRequests;

    @GuardedBy("mLock")
    private IntArray mDefaultDialerAppRequests;

    @GuardedBy("mLock")
    private IntArray mDefaultSimCallManagerRequests;

    private final Context mContext;

    @GuardedBy("mLock")
    private TelecomServiceConnection mServiceConnection;

    public TelecomLoaderService(Context context) {
        super(context);
        mContext = context;
        registerDefaultAppProviders();
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            registerDefaultAppNotifier();
            registerCarrierConfigChangedReceiver();
            connectToTelecom();
        }
    }

    private void connectToTelecom() {
        synchronized (mLock) {
            if (mServiceConnection != null) {
                // TODO: Is unbinding worth doing or wait for system to rebind?
                mContext.unbindService(mServiceConnection);
                mServiceConnection = null;
            }

            TelecomServiceConnection serviceConnection = new TelecomServiceConnection();
            Intent intent = new Intent(SERVICE_ACTION);
            intent.setComponent(SERVICE_COMPONENT);
            int flags = Context.BIND_IMPORTANT | Context.BIND_FOREGROUND_SERVICE
                    | Context.BIND_AUTO_CREATE;

            // Bind to Telecom and register the service
            if (mContext.bindServiceAsUser(intent, serviceConnection, flags, UserHandle.SYSTEM)) {
                mServiceConnection = serviceConnection;
            }
        }
    }


    private void registerDefaultAppProviders() {
        final DefaultPermissionGrantPolicy permissionPolicy = getDefaultPermissionGrantPolicy();

        // Set a callback for the permission grant policy to query the default sms app.
        permissionPolicy.setSmsAppPackagesProvider(userId -> {
            synchronized (mLock) {
                if (mServiceConnection == null) {
                    if (mDefaultSmsAppRequests == null) {
                        mDefaultSmsAppRequests = new IntArray();
                    }
                    mDefaultSmsAppRequests.add(userId);
                    return null;
                }
            }
            ComponentName smsComponent = SmsApplication.getDefaultSmsApplication(
                    mContext, true);
            if (smsComponent != null) {
                return new String[]{smsComponent.getPackageName()};
            }
            return null;
        });

        // Set a callback for the permission grant policy to query the default dialer app.
        permissionPolicy.setDialerAppPackagesProvider(userId -> {
            synchronized (mLock) {
                if (mServiceConnection == null) {
                    if (mDefaultDialerAppRequests == null) {
                        mDefaultDialerAppRequests = new IntArray();
                    }
                    mDefaultDialerAppRequests.add(userId);
                    return null;
                }
            }
            String packageName = DefaultDialerManager.getDefaultDialerApplication(mContext);
            if (packageName != null) {
                return new String[]{packageName};
            }
            return null;
        });

        // Set a callback for the permission grant policy to query the default sim call manager.
        permissionPolicy.setSimCallManagerPackagesProvider(userId -> {
            synchronized (mLock) {
                if (mServiceConnection == null) {
                    if (mDefaultSimCallManagerRequests == null) {
                        mDefaultSimCallManagerRequests = new IntArray();
                    }
                    mDefaultSimCallManagerRequests.add(userId);
                    return null;
                }
            }
            TelecomManager telecomManager =
                    (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
            PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager(userId);
            if (phoneAccount != null) {
                return new String[]{phoneAccount.getComponentName().getPackageName()};
            }
            return null;
        });
    }

    private void registerDefaultAppNotifier() {
        final DefaultPermissionGrantPolicy permissionPolicy = getDefaultPermissionGrantPolicy();

        // Notify the package manager on default app changes
        final Uri defaultSmsAppUri = Settings.Secure.getUriFor(
                Settings.Secure.SMS_DEFAULT_APPLICATION);
        final Uri defaultDialerAppUri = Settings.Secure.getUriFor(
                Settings.Secure.DIALER_DEFAULT_APPLICATION);

        ContentObserver contentObserver = new ContentObserver(
                new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (defaultSmsAppUri.equals(uri)) {
                    ComponentName smsComponent = SmsApplication.getDefaultSmsApplication(
                            mContext, true);
                    if (smsComponent != null) {
                        permissionPolicy.grantDefaultPermissionsToDefaultSmsApp(
                                smsComponent.getPackageName(), userId);
                    }
                } else if (defaultDialerAppUri.equals(uri)) {
                    String packageName = DefaultDialerManager.getDefaultDialerApplication(
                            mContext);
                    if (packageName != null) {
                        permissionPolicy.grantDefaultPermissionsToDefaultDialerApp(
                                packageName, userId);
                    }
                    updateSimCallManagerPermissions(permissionPolicy, userId);
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(defaultSmsAppUri,
                false, contentObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(defaultDialerAppUri,
                false, contentObserver, UserHandle.USER_ALL);
    }


    private void registerCarrierConfigChangedReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                    for (int userId : UserManagerService.getInstance().getUserIds()) {
                        updateSimCallManagerPermissions(getDefaultPermissionGrantPolicy(), userId);
                    }
                }
            }
        };

        mContext.registerReceiverAsUser(receiver, UserHandle.ALL,
            new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED), null, null);
    }

    private void updateSimCallManagerPermissions(
            DefaultPermissionGrantPolicy permissionGrantPolicy, int userId) {
        TelecomManager telecomManager =
            (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
        PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager(userId);
        if (phoneAccount != null) {
            Slog.i(TAG, "updating sim call manager permissions for userId:" + userId);
            String packageName = phoneAccount.getComponentName().getPackageName();
            permissionGrantPolicy.grantDefaultPermissionsToDefaultSimCallManager(
                packageName, userId);
        }
    }
}
