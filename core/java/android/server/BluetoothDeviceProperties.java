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

import android.os.ParcelUuid;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class BluetoothDeviceProperties {

    private static final String TAG = "BluetoothDeviceProperties";

    private final HashMap<String, Map<String, String>> mPropertiesMap;
    private final BluetoothService mService;

    BluetoothDeviceProperties(BluetoothService service) {
        mPropertiesMap = new HashMap<String, Map<String, String>>();
        mService = service;
    }

    Map<String, String> addProperties(String address, String[] properties) {
        /*
         * We get a DeviceFound signal every time RSSI changes or name changes.
         * Don't create a new Map object every time.
         */
        Map<String, String> propertyValues;
        synchronized(mPropertiesMap) {
            propertyValues = mPropertiesMap.get(address);
            if (propertyValues == null) {
                propertyValues = new HashMap<String, String>();
            }

            for (int i = 0; i < properties.length; i++) {
                String name = properties[i];
                String newValue = null;
                int len;
                if (name == null) {
                    Log.e(TAG, "Error: Remote Device Property at index "
                        + i + " is null");
                    continue;
                }
                if (name.equals("UUIDs") || name.equals("Nodes")) {
                    StringBuilder str = new StringBuilder();
                    len = Integer.valueOf(properties[++i]);
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

                propertyValues.put(name, newValue);
            }
            mPropertiesMap.put(address, propertyValues);
        }

        // We have added a new remote device or updated its properties.
        // Also update the serviceChannel cache.
        mService.updateDeviceServiceChannelCache(address);
        return propertyValues;
    }

    void setProperty(String address, String name, String value) {
        synchronized(mPropertiesMap) {
            Map <String, String> propVal = mPropertiesMap.get(address);
            if (propVal != null) {
                propVal.put(name, value);
                mPropertiesMap.put(address, propVal);
            } else {
                Log.e(TAG, "setRemoteDeviceProperty for a device not in cache:" + address);
            }
        }
    }

    boolean isInCache(String address) {
        synchronized (mPropertiesMap) {
            return (mPropertiesMap.get(address) != null);
        }
    }

    boolean isEmpty() {
        synchronized (mPropertiesMap) {
            return mPropertiesMap.isEmpty();
        }
    }

    Set<String> keySet() {
        synchronized (mPropertiesMap) {
            return mPropertiesMap.keySet();
        }
    }

    String getProperty(String address, String property) {
        synchronized(mPropertiesMap) {
            Map<String, String> properties = mPropertiesMap.get(address);
            if (properties != null) {
                return properties.get(property);
            } else {
                // Query for remote device properties, again.
                // We will need to reload the cache when we switch Bluetooth on / off
                // or if we crash.
                properties = updateCache(address);
                if (properties != null) {
                    return properties.get(property);
                }
            }
        }
        Log.e(TAG, "getRemoteDeviceProperty: " + property + " not present: " + address);
        return null;
    }

    Map<String, String> updateCache(String address) {
        String[] propValues = mService.getRemoteDeviceProperties(address);
        if (propValues != null) {
            return addProperties(address, propValues);
        }
        return null;
    }
}
