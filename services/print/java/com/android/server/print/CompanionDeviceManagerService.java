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

import android.app.PendingIntent;
import android.companion.AssociationRequest;
import android.companion.ICompanionDeviceManager;
import android.companion.ICompanionDeviceManagerService;
import android.companion.IOnAssociateCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.SystemService;

//TODO move to own package!
/** @hide */
public class CompanionDeviceManagerService extends SystemService {

    private static final ComponentName SERVICE_TO_BIND_TO = ComponentName.createRelative(
            "com.android.companiondevicemanager", ".DeviceDiscoveryService");

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
                IOnAssociateCallback callback,
                String callingPackage) throws RemoteException {
            if (DEBUG) {
                Log.i(LOG_TAG, "associate(request = " + request + ", callback = " + callback
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
            final IOnAssociateCallback callback,
            final String callingPackage) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) {
                    Log.i(LOG_TAG,
                            "onServiceConnected(name = " + name + ", service = "
                                    + service + ")");
                }
                try {
                    ICompanionDeviceManagerService.Stub
                            .asInterface(service)
                            .startDiscovery(
                                    request,
                                    getCallback(callingPackage, callback),
                                    callingPackage);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Log.i(LOG_TAG, "onServiceDisconnected(name = " + name + ")");
            }
        };
    }

    private IOnAssociateCallback.Stub getCallback(
            String callingPackage,
            IOnAssociateCallback propagateTo) {
        return new IOnAssociateCallback.Stub() {

            @Override
            public void onSuccess(PendingIntent launcher)
                    throws RemoteException {
                if (DEBUG) Log.i(LOG_TAG, "onSuccess(launcher = " + launcher + ")");
                recordSpecialPriviledgesForPackage(callingPackage);
                propagateTo.onSuccess(launcher);
            }

            @Override
            public void onFailure(CharSequence reason) throws RemoteException {
                if (DEBUG) Log.i(LOG_TAG, "onFailure()");
                propagateTo.onFailure(reason);
            }
        };
    }

    void recordSpecialPriviledgesForPackage(String priviledgedPackage) {
        //TODO Show dialog before recording notification access
//        final SettingStringHelper setting =
//                new SettingStringHelper(
//                        getContext().getContentResolver(),
//                        Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
//                        Binder.getCallingUid());
//        setting.write(ColonDelimitedSet.OfStrings.add(setting.read(), priviledgedPackage));
    }
}
