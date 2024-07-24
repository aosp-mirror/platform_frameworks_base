/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Process;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.security.legacykeystore.ILegacyKeystore;
import android.util.Log;

/**
 * This class allows the storage and retrieval of non-standard Wifi certificate blobs.
 * @hide
 */
@SystemApi
@SuppressLint("UnflaggedApi") // Promoting from @SystemApi(MODULE_LIBRARIES)
public final class WifiKeystore {
    private static final String TAG = "WifiKeystore";
    private static final String LEGACY_KEYSTORE_SERVICE_NAME = "android.security.legacykeystore";

    private static ILegacyKeystore getService() {
        return ILegacyKeystore.Stub.asInterface(
                ServiceManager.checkService(LEGACY_KEYSTORE_SERVICE_NAME));
    }

    /** @hide */
    WifiKeystore() {
    }

    /**
     * Stores the blob under the alias in the keystore database. Existing blobs by the
     * same name will be replaced.
     * @param alias The name of the blob
     * @param blob The blob.
     * @return true if the blob was successfully added. False otherwise.
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static boolean put(@NonNull String alias, @NonNull byte[] blob) {
        try {
            Log.i(TAG, "put blob. alias " + alias);
            getService().put(alias, Process.WIFI_UID, blob);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to put blob.", e);
            return false;
        }
    }

    /**
     * Retrieves a blob by the name alias from the blob database.
     * @param alias Name of the blob to retrieve.
     * @return The unstructured blob, that is the blob that was stored using
     *         {@link android.net.wifi.WifiKeystore#put}.
     *         Returns null if no blob was found.
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static @NonNull byte[] get(@NonNull String alias) {
        try {
            Log.i(TAG, "get blob. alias " + alias);
            return getService().get(alias, Process.WIFI_UID);
        } catch (ServiceSpecificException e) {
            if (e.errorCode != ILegacyKeystore.ERROR_ENTRY_NOT_FOUND) {
                Log.e(TAG, "Failed to get blob.", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get blob.", e);
        }
        return null;
    }

    /**
     * Removes a blob by the name alias from the database.
     * @param alias Name of the blob to be removed.
     * @return True if a blob was removed. False if no such blob was found.
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static boolean remove(@NonNull String alias) {
        try {
            getService().remove(alias, Process.WIFI_UID);
            return true;
        } catch (ServiceSpecificException e) {
            if (e.errorCode != ILegacyKeystore.ERROR_ENTRY_NOT_FOUND) {
                Log.e(TAG, "Failed to remove blob.", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove blob.", e);
        }
        return false;
    }

    /**
     * Lists the blobs stored in the database.
     * @return An array of strings representing the aliases stored in the database.
     *         The return value may be empty but never null.
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static @NonNull String[] list(@NonNull String prefix) {
        try {
            final String[] aliases = getService().list(prefix, Process.WIFI_UID);
            for (int i = 0; i < aliases.length; ++i) {
                aliases[i] = aliases[i].substring(prefix.length());
            }
            return aliases;
        } catch (Exception e) {
            Log.e(TAG, "Failed to list blobs.", e);
        }
        return new String[0];
    }
}
