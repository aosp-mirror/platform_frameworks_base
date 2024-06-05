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
import android.os.Binder;
import android.os.Process;
import android.os.ServiceSpecificException;
import android.security.legacykeystore.ILegacyKeystore;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This class allows the storage and retrieval of non-standard Wifi certificate blobs.
 * @hide
 */
@SystemApi
@SuppressLint("UnflaggedApi") // Promoting from @SystemApi(MODULE_LIBRARIES)
public final class WifiKeystore {
    private static final String TAG = "WifiKeystore";

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
        // ConnectivityBlobStore uses the calling uid as a key into the DB.
        // Clear identity to ensure that callers from system apps and the Wifi framework
        // are able to access the same values.
        final long identity = Binder.clearCallingIdentity();
        try {
            Log.i(TAG, "put blob. alias " + alias);
            return WifiBlobStore.getInstance().put(alias, blob);
        } catch (Exception e) {
            Log.e(TAG, "Failed to put blob.", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Retrieves a blob by the name alias from the blob database.
     * @param alias Name of the blob to retrieve.
     * @return The unstructured blob, that is the blob that was stored using
     *         {@link android.net.wifi.WifiKeystore#put}.
     *         Returns empty byte[] if no blob was found.
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static @NonNull byte[] get(@NonNull String alias) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Log.i(TAG, "get blob. alias " + alias);
            byte[] blob = WifiBlobStore.getInstance().get(alias);
            if (blob != null) {
                return blob;
            }
            Log.i(TAG, "Searching for blob in Legacy Keystore");
            return WifiBlobStore.getLegacyKeystore().get(alias, Process.WIFI_UID);
        } catch (ServiceSpecificException e) {
            if (e.errorCode != ILegacyKeystore.ERROR_ENTRY_NOT_FOUND) {
                Log.e(TAG, "Failed to get blob.", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get blob.", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return new byte[0];
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
        boolean blobStoreSuccess = false;
        boolean legacyKsSuccess = false;
        final long identity = Binder.clearCallingIdentity();
        try {
            Log.i(TAG, "remove blob. alias " + alias);
            blobStoreSuccess = WifiBlobStore.getInstance().remove(alias);
            // Legacy Keystore will throw an exception if the alias is not found.
            WifiBlobStore.getLegacyKeystore().remove(alias, Process.WIFI_UID);
            legacyKsSuccess = true;
        } catch (ServiceSpecificException e) {
            if (e.errorCode != ILegacyKeystore.ERROR_ENTRY_NOT_FOUND) {
                Log.e(TAG, "Failed to remove blob.", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove blob.", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        Log.i(TAG, "Removal status: wifiBlobStore=" + blobStoreSuccess
                + ", legacyKeystore=" + legacyKsSuccess);
        return blobStoreSuccess || legacyKsSuccess;
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
        final long identity = Binder.clearCallingIdentity();
        try {
            // Aliases from WifiBlobStore will be pre-trimmed.
            final String[] blobStoreAliases = WifiBlobStore.getInstance().list(prefix);
            final String[] legacyAliases =
                    WifiBlobStore.getLegacyKeystore().list(prefix, Process.WIFI_UID);
            for (int i = 0; i < legacyAliases.length; ++i) {
                legacyAliases[i] = legacyAliases[i].substring(prefix.length());
            }
            // Deduplicate aliases before returning.
            Set<String> uniqueAliases = new HashSet<>();
            uniqueAliases.addAll(Arrays.asList(blobStoreAliases));
            uniqueAliases.addAll(Arrays.asList(legacyAliases));
            String[] uniqueAliasArray = new String[uniqueAliases.size()];
            return uniqueAliases.toArray(uniqueAliasArray);
        } catch (Exception e) {
            Log.e(TAG, "Failed to list blobs.", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return new String[0];
    }
}
