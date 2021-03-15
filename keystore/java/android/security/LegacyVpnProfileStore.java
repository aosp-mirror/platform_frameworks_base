/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.security;

import android.annotation.NonNull;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.security.keystore.AndroidKeyStoreProvider;
import android.security.vpnprofilestore.IVpnProfileStore;
import android.util.Log;

/**
 * @hide This class allows legacy VPN access to its profiles that were stored in Keystore.
 * The storage of unstructured blobs in Android Keystore is going away, because there is no
 * architectural or security benefit of storing profiles in keystore over storing them
 * in the file system. This class allows access to the blobs that still exist in keystore.
 * And it stores new blob in a database that is still owned by Android Keystore.
 */
public class LegacyVpnProfileStore {
    private static final String TAG = "LegacyVpnProfileStore";

    public static final int SYSTEM_ERROR = IVpnProfileStore.ERROR_SYSTEM_ERROR;
    public static final int PROFILE_NOT_FOUND = IVpnProfileStore.ERROR_PROFILE_NOT_FOUND;

    private static final String VPN_PROFILE_STORE_SERVICE_NAME = "android.security.vpnprofilestore";

    private static IVpnProfileStore getService() {
        return IVpnProfileStore.Stub.asInterface(
                    ServiceManager.checkService(VPN_PROFILE_STORE_SERVICE_NAME));
    }

    /**
     * Stores the profile under the alias in the profile database. Existing profiles by the
     * same name will be replaced.
     * @param alias The name of the profile
     * @param profile The profile.
     * @return true if the profile was successfully added. False otherwise.
     * @hide
     */
    public static boolean put(@NonNull String alias, @NonNull byte[] profile) {
        try {
            if (AndroidKeyStoreProvider.isKeystore2Enabled()) {
                getService().put(alias, profile);
                return true;
            } else {
                return KeyStore.getInstance().put(
                        alias, profile, KeyStore.UID_SELF, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to put vpn profile.", e);
            return false;
        }
    }

    /**
     * Retrieves a profile by the name alias from the profile database.
     * @param alias Name of the profile to retrieve.
     * @return The unstructured blob, that is the profile that was stored using
     *         LegacyVpnProfileStore#put or with
     *         android.security.Keystore.put(Credentials.VPN + alias).
     *         Returns null if no profile was found.
     * @hide
     */
    public static byte[] get(@NonNull String alias) {
        try {
            if (AndroidKeyStoreProvider.isKeystore2Enabled()) {
                return getService().get(alias);
            } else {
                return KeyStore.getInstance().get(alias, true /* suppressKeyNotFoundWarning */);
            }
        } catch (ServiceSpecificException e) {
            if (e.errorCode != PROFILE_NOT_FOUND) {
                Log.e(TAG, "Failed to get vpn profile.", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get vpn profile.", e);
        }
        return null;
    }

    /**
     * Removes a profile by the name alias from the profile database.
     * @param alias Name of the profile to be removed.
     * @return True if a profile was removed. False if no such profile was found.
     * @hide
     */
    public static boolean remove(@NonNull String alias) {
        try {
            if (AndroidKeyStoreProvider.isKeystore2Enabled()) {
                getService().remove(alias);
                return true;
            } else {
                return KeyStore.getInstance().delete(alias);
            }
        } catch (ServiceSpecificException e) {
            if (e.errorCode != PROFILE_NOT_FOUND) {
                Log.e(TAG, "Failed to remove vpn profile.", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove vpn profile.", e);
        }
        return false;
    }

    /**
     * Lists the vpn profiles stored in the database.
     * @return An array of strings representing the aliases stored in the profile database.
     *         The return value may be empty but never null.
     * @hide
     */
    public static @NonNull String[] list(@NonNull String prefix) {
        try {
            if (AndroidKeyStoreProvider.isKeystore2Enabled()) {
                final String[] aliases = getService().list(prefix);
                for (int i = 0; i < aliases.length; ++i) {
                    aliases[i] = aliases[i].substring(prefix.length());
                }
                return aliases;
            } else {
                final String[] result = KeyStore.getInstance().list(prefix);
                return result != null ? result : new String[0];
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to list vpn profiles.", e);
        }
        return new String[0];
    }
}
