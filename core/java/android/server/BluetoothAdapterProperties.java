/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.server;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

class BluetoothAdapterProperties {

    private static final String TAG = "BluetoothAdapterProperties";

    private final Map<String, String> mPropertiesMap;
    private final Context mContext;
    private final BluetoothService mService;

    BluetoothAdapterProperties(Context context, BluetoothService service) {
        mPropertiesMap = new HashMap<String, String>();
        mContext = context;
        mService = service;
    }

    synchronized String getProperty(String name) {
        if (mPropertiesMap.isEmpty()) {
            getAllProperties();
        }
        return mPropertiesMap.get(name);
    }

    String getObjectPath() {
        return getProperty("ObjectPath");
    }

    synchronized void clear() {
        mPropertiesMap.clear();
    }

    synchronized boolean isEmpty() {
        return mPropertiesMap.isEmpty();
    }

    synchronized void setProperty(String name, String value) {
        mPropertiesMap.put(name, value);
    }

    synchronized void getAllProperties() {
        mContext.enforceCallingOrSelfPermission(
                BluetoothService.BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        mPropertiesMap.clear();

        String properties[] = (String[]) mService
                .getAdapterPropertiesNative();
        // The String Array consists of key-value pairs.
        if (properties == null) {
            Log.e(TAG, "*Error*: GetAdapterProperties returned NULL");
            return;
        }

        for (int i = 0; i < properties.length; i++) {
            String name = properties[i];
            String newValue = null;
            if (name == null) {
                Log.e(TAG, "Error:Adapter Property at index " + i + " is null");
                continue;
            }
            if (name.equals("Devices") || name.equals("UUIDs")) {
                StringBuilder str = new StringBuilder();
                int len = Integer.valueOf(properties[++i]);
                for (int j = 0; j < len; j++) {
                    str.append(properties[++i]);
                    str.append(",");
                }
                if (len > 0) {
                    newValue = str.toString();
                }
            } else {
                newValue = properties[++i];
            }
            mPropertiesMap.put(name, newValue);
        }

        // Add adapter object path property.
        String adapterPath = mService.getAdapterPathNative();
        if (adapterPath != null) {
            mPropertiesMap.put("ObjectPath", adapterPath + "/dev_");
        }
    }
}
