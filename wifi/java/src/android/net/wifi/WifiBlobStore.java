/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net.wifi;

import android.os.ServiceManager;
import android.security.legacykeystore.ILegacyKeystore;

import com.android.internal.net.ConnectivityBlobStore;

/**
 * Database blob store for Wifi.
 * @hide
 */
public class WifiBlobStore extends ConnectivityBlobStore {
    private static final String DB_NAME = "WifiBlobStore.db";
    private static final String LEGACY_KEYSTORE_SERVICE_NAME = "android.security.legacykeystore";
    private static WifiBlobStore sInstance;
    private WifiBlobStore() {
        super(DB_NAME);
    }

    /** Returns an instance of WifiBlobStore. */
    public static WifiBlobStore getInstance() {
        if (sInstance == null) {
            sInstance = new WifiBlobStore();
        }
        return sInstance;
    }

    /** Returns an interface to access the Legacy Keystore service. */
    public static ILegacyKeystore getLegacyKeystore() {
        return ILegacyKeystore.Stub.asInterface(
                ServiceManager.checkService(LEGACY_KEYSTORE_SERVICE_NAME));
    }
}
