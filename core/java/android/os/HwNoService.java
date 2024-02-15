/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.os;

import android.hidl.manager.V1_2.IServiceManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * A fake hwservicemanager that is used locally when HIDL isn't supported on the device.
 *
 * @hide
 */
final class HwNoService extends IServiceManager.Stub implements IHwBinder, IHwInterface {
    private static final String TAG = "HwNoService";

    /** @hide */
    @Override
    public String toString() {
        return "[HwNoService]";
    }

    @Override
    public android.hidl.base.V1_0.IBase get(String fqName, String name)
            throws android.os.RemoteException {
        Log.i(TAG, "get " + fqName + "/" + name + " with no hwservicemanager");
        return null;
    }

    @Override
    public boolean add(String name, android.hidl.base.V1_0.IBase service)
            throws android.os.RemoteException {
        Log.i(TAG, "get " + name + " with no hwservicemanager");
        return false;
    }

    @Override
    public byte getTransport(String fqName, String name) throws android.os.RemoteException {
        Log.i(TAG, "getTransoport " + fqName + "/" + name + " with no hwservicemanager");
        return 0x0;
    }

    @Override
    public java.util.ArrayList<String> list() throws android.os.RemoteException {
        Log.i(TAG, "list with no hwservicemanager");
        return new ArrayList<String>();
    }

    @Override
    public java.util.ArrayList<String> listByInterface(String fqName)
            throws android.os.RemoteException {
        Log.i(TAG, "listByInterface with no hwservicemanager");
        return new ArrayList<String>();
    }

    @Override
    public boolean registerForNotifications(
            String fqName, String name, android.hidl.manager.V1_0.IServiceNotification callback)
            throws android.os.RemoteException {
        Log.i(TAG, "registerForNotifications with no hwservicemanager");
        return true;
    }

    @Override
    public ArrayList<android.hidl.manager.V1_0.IServiceManager.InstanceDebugInfo> debugDump()
            throws android.os.RemoteException {
        Log.i(TAG, "debugDump with no hwservicemanager");
        return new ArrayList<android.hidl.manager.V1_0.IServiceManager.InstanceDebugInfo>();
    }

    @Override
    public void registerPassthroughClient(String fqName, String name)
            throws android.os.RemoteException {
        Log.i(TAG, "registerPassthroughClient with no hwservicemanager");
    }

    @Override
    public boolean unregisterForNotifications(
            String fqName, String name, android.hidl.manager.V1_0.IServiceNotification callback)
            throws android.os.RemoteException {
        Log.i(TAG, "unregisterForNotifications with no hwservicemanager");
        return true;
    }

    @Override
    public boolean registerClientCallback(
            String fqName,
            String name,
            android.hidl.base.V1_0.IBase server,
            android.hidl.manager.V1_2.IClientCallback cb)
            throws android.os.RemoteException {
        Log.i(
                TAG,
                "registerClientCallback for " + fqName + "/" + name + " with no hwservicemanager");
        return true;
    }

    @Override
    public boolean unregisterClientCallback(
            android.hidl.base.V1_0.IBase server, android.hidl.manager.V1_2.IClientCallback cb)
            throws android.os.RemoteException {
        Log.i(TAG, "unregisterClientCallback with no hwservicemanager");
        return true;
    }

    @Override
    public boolean addWithChain(
            String name, android.hidl.base.V1_0.IBase service, java.util.ArrayList<String> chain)
            throws android.os.RemoteException {
        Log.i(TAG, "addWithChain with no hwservicemanager");
        return true;
    }

    @Override
    public java.util.ArrayList<String> listManifestByInterface(String fqName)
            throws android.os.RemoteException {
        Log.i(TAG, "listManifestByInterface for " + fqName + " with no hwservicemanager");
        return new ArrayList<String>();
    }

    @Override
    public boolean tryUnregister(String fqName, String name, android.hidl.base.V1_0.IBase service)
            throws android.os.RemoteException {
        Log.i(TAG, "tryUnregister for " + fqName + "/" + name + " with no hwservicemanager");
        return true;
    }
}
