/*
 * Copyright (C) 2017 The Android Open Source Project
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


package com.android.server.print;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.Manifest;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.ICompanionDeviceDiscoveryService;
import android.companion.ICompanionDeviceDiscoveryServiceCallback;
import android.companion.ICompanionDeviceManager;
import android.companion.IFindDeviceCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;

//TODO move to own package!
/** @hide */
public class CompanionDeviceManagerService extends SystemService {

    private static final ComponentName SERVICE_TO_BIND_TO = ComponentName.createRelative(
            CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
            ".DeviceDiscoveryService");

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "CompanionDeviceManagerService";

    private final CompanionDeviceManagerImpl mImpl;

    public CompanionDeviceManagerService(Context context) {
        super(context);
        mImpl = new CompanionDeviceManagerImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, mImpl);
    }

    class CompanionDeviceManagerImpl extends ICompanionDeviceManager.Stub {
        @Override
        public void associate(
                AssociationRequest request,
                IFindDeviceCallback callback,
                String callingPackage) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "associate(request = " + request + ", callback = " + callback
                        + ", callingPackage = " + callingPackage + ")");
            }
            checkNotNull(request);
            checkNotNull(callback);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                //TODO bindServiceAsUser
                getContext().bindService(
                        new Intent().setComponent(SERVICE_TO_BIND_TO),
                        getServiceConnection(request, callback, callingPackage),
                        Context.BIND_AUTO_CREATE);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }
    }

    private ServiceConnection getServiceConnection(
            final AssociationRequest<?> request,
            final IFindDeviceCallback findDeviceCallback,
            final String callingPackage) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) {
                    Slog.i(LOG_TAG,
                            "onServiceConnected(name = " + name + ", service = "
                                    + service + ")");
                }
                try {
                    ICompanionDeviceDiscoveryService.Stub
                            .asInterface(service)
                            .startDiscovery(
                                    request,
                                    callingPackage,
                                    findDeviceCallback,
                                    getServiceCallback());
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Slog.i(LOG_TAG, "onServiceDisconnected(name = " + name + ")");
            }
        };
    }

    private ICompanionDeviceDiscoveryServiceCallback.Stub getServiceCallback() {
        return new ICompanionDeviceDiscoveryServiceCallback.Stub() {
            @Override
            public void onDeviceSelected(String packageName, int userId) {
                grantSpecialAccessPermissionsIfNeeded(packageName, userId);
            }
        };
    }

    private void grantSpecialAccessPermissionsIfNeeded(String packageName, int userId) {
        final long identity = Binder.clearCallingIdentity();
        final PackageInfo packageInfo;
        try {
            packageInfo = getContext().getPackageManager().getPackageInfoAsUser(
                    packageName, PackageManager.GET_PERMISSIONS, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(LOG_TAG, "Error granting special access permissions to package:"
                    + packageName, e);
            return;
        }
        try {
            if (ArrayUtils.contains(packageInfo.requestedPermissions,
                    Manifest.permission.RUN_IN_BACKGROUND)) {
                IDeviceIdleController idleController = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
                try {
                    idleController.addPowerSaveWhitelistApp(packageName);
                } catch (RemoteException e) {
                    /* ignore - local call */
                }
            }
            if (ArrayUtils.contains(packageInfo.requestedPermissions,
                    Manifest.permission.USE_DATA_IN_BACKGROUND)) {
                NetworkPolicyManager.from(getContext()).addUidPolicy(
                        packageInfo.applicationInfo.uid,
                        NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
