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

package android.provider;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * E2eeContactKeysManager provides access to the provider of end-to-end encryption contact keys.
 * It manages two types of keys - {@link E2eeContactKey} and {@link E2eeSelfKey}.
 * <ul>
 * <li>
 * A {@link E2eeContactKey} is a public key associated with a contact. It's used to end-to-end
 * encrypt the communications between a user and the contact. This API allows operations on
 * {@link E2eeContactKey}s to insert/update, remove, change the verification state, and retrieving
 * keys (either created by or visible to the caller app).
 * </li>
 * <li>
 * A {@link E2eeSelfKey} is a key for this device, so the key represents the owner of the device.
 * This API allows operations on {@link E2eeSelfKey}s to insert/update, remove, and retrieving
 * self keys (either created by or visible to the caller app).
 * </li>
 * </ul>
 * Keys are uniquely identified by:
 * <ul>
 * <li>
 * ownerPackageName - package name of an app that the key belongs to.
 * </li>
 * <li>
 * deviceId - an app-specified identifier for the device, defined by the app. Within that app,
 * the deviceID should be unique among devices belonging to that user.
 * </li>
 * <li>
 * accountId - the app-specified identifier for the account for which the contact key can be used.
 * Using different account IDs allows for multiple key entries representing the same user.
 * For most apps this would be a phone number.
 * </li>
 * </ul>
 * Contact keys also use lookupKey which is an opaque value used to identify a contact in
 * ContactsProvider.
 */
@FlaggedApi(Flags.FLAG_USER_KEYS)
public final class E2eeContactKeysManager {
    /**
     * The authority for the end-to-end encryption contact keys provider.
     *
     * @hide
     */
    public static final String AUTHORITY = "com.android.contactkeys.contactkeysprovider";

    /**
     * A content:// style uri to the authority for the end-to-end encryption contact keys provider.
     *
     * @hide
     */
    @NonNull
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * Maximum size of an end-to-end encryption contact key.
     */
    private static final int MAX_KEY_SIZE_BYTES = 5000;

    /**
     * Special value to distinguish a null array in a parcelable object.
     */
    private static final int ARRAY_IS_NULL = -1;

    @NonNull
    private final ContentResolver mContentResolver;

    /** @hide */
    public E2eeContactKeysManager(@NonNull Context context) {
        Objects.requireNonNull(context);
        mContentResolver = context.getContentResolver();
    }

    /**
     * Inserts a new entry into the end-to-end encryption contact keys table or updates one if it
     * already exists.
     * The inserted/updated end-to-end encryption contact key is owned by the caller app.
     *
     * @param lookupKey value that references the contact
     * @param deviceId  an app-specified identifier for the device
     * @param accountId an app-specified identifier for the account
     * @param keyValue  the raw bytes for the key (max size is {@link #getMaxKeySizeBytes} bytes)
     */
    @RequiresPermission(android.Manifest.permission.WRITE_CONTACTS)
    public void updateOrInsertE2eeContactKey(@NonNull String lookupKey,
            @NonNull String deviceId,
            @NonNull String accountId,
            @NonNull byte[] keyValue) {
        validateKeyLength(keyValue);

        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putByteArray(E2eeContactKeys.KEY_VALUE, Objects.requireNonNull(keyValue));

        nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_OR_INSERT_CONTACT_KEY_METHOD, extras);
    }

    /**
     * Retrieves an end-to-end encryption contact key entry given the lookup key, device ID,
     * accountId and inferred caller package name.
     *
     * @param lookupKey the value that references the contact
     * @param deviceId  an app-specified identifier for the device
     * @param accountId an app-specified identifier for the account
     * @return a {@link E2eeContactKey} object containing the contact key information,
     * or null if no contact key is found.
     */
    @RequiresPermission(android.Manifest.permission.READ_CONTACTS)
    @Nullable
    public E2eeContactKey getE2eeContactKey(
            @NonNull String lookupKey,
            @NonNull String deviceId,
            @NonNull String accountId) {
        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.GET_CONTACT_KEY_METHOD, extras);

        if (response == null) {
            return null;
        }
        return response.getParcelable(E2eeContactKeys.KEY_CONTACT_KEY, E2eeContactKey.class);
    }

    /**
     * Retrieves all end-to-end encryption contact key entries that belong to apps visible to
     * the caller.
     * The keys will be stripped of deviceId, timeUpdated and keyValue data.
     *
     * @param lookupKey the value that references the contact
     * @return a list of {@link E2eeContactKey} objects containing the contact key
     * information, or an empty list if no keys are found.
     */
    @RequiresPermission(android.Manifest.permission.READ_CONTACTS)
    @NonNull
    public List<E2eeContactKey> getAllE2eeContactKeys(@NonNull String lookupKey) {
        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.GET_ALL_CONTACT_KEYS_METHOD, extras);

        if (response == null) {
            return new ArrayList<>();
        }
        List<E2eeContactKey> value = response.getParcelableArrayList(
                E2eeContactKeys.KEY_CONTACT_KEYS, E2eeContactKey.class);
        if (value == null) {
            return new ArrayList<>();
        }
        return value;
    }

    /**
     * Retrieves all end-to-end encryption contact key entries for a given lookupKey that belong to
     * the caller app.
     *
     * @param lookupKey the value that references the contact
     * @return a list of {@link E2eeContactKey} objects containing the end-to-end encryption
     * contact key information, or an empty list if no keys are found.
     */
    @RequiresPermission(android.Manifest.permission.READ_CONTACTS)
    @NonNull
    public List<E2eeContactKey> getOwnerE2eeContactKeys(@NonNull String lookupKey) {
        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.GET_OWNER_CONTACT_KEYS_METHOD, extras);

        if (response == null) {
            return new ArrayList<>();
        }
        List<E2eeContactKey> value = response.getParcelableArrayList(
                E2eeContactKeys.KEY_CONTACT_KEYS, E2eeContactKey.class);
        if (value == null) {
            return new ArrayList<>();
        }
        return value;
    }

    /**
     * Updates an end-to-end encryption contact key entry's local verification state that belongs
     * to the caller app.
     *
     * @param lookupKey              the value that references the contact
     * @param deviceId               an app-specified identifier for the device
     * @param accountId              an app-specified identifier for the account
     * @param localVerificationState the new local verification state
     * @return true if the entry was updated, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_CONTACTS)
    public boolean updateE2eeContactKeyLocalVerificationState(@NonNull String lookupKey,
            @NonNull String deviceId,
            @NonNull String accountId,
            @VerificationState int localVerificationState) {
        validateVerificationState(localVerificationState);

        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putInt(E2eeContactKeys.LOCAL_VERIFICATION_STATE, localVerificationState);

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_CONTACT_KEY_LOCAL_VERIFICATION_STATE_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    /**
     * Updates an end-to-end encryption contact key entry's local verification state that belongs
     * to the app identified by ownerPackageName.
     *
     * @param lookupKey              the value that references the contact
     * @param deviceId               an app-specified identifier for the device
     * @param accountId              an app-specified identifier for the account
     * @param ownerPackageName       the package name of the app that owns the key
     * @param localVerificationState the new local verification state
     * @return true if the entry was updated, false otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.WRITE_VERIFICATION_STATE_E2EE_CONTACT_KEYS,
            android.Manifest.permission.WRITE_CONTACTS})
    public boolean updateE2eeContactKeyLocalVerificationState(@NonNull String lookupKey,
            @NonNull String deviceId,
            @NonNull String accountId,
            @NonNull String ownerPackageName,
            @VerificationState int localVerificationState) {
        validateVerificationState(localVerificationState);

        final Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putString(E2eeContactKeys.OWNER_PACKAGE_NAME,
                Objects.requireNonNull(ownerPackageName));
        extras.putInt(E2eeContactKeys.LOCAL_VERIFICATION_STATE, localVerificationState);

        final Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_CONTACT_KEY_LOCAL_VERIFICATION_STATE_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    /**
     * Updates an end-to-end encryption contact key entry's remote verification state that belongs
     * to the caller app.
     *
     * @param lookupKey               the value that references the contact
     * @param deviceId                an app-specified identifier for the device
     * @param accountId               an app-specified identifier for the account
     * @param remoteVerificationState the new remote verification state
     * @return true if the entry was updated, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_CONTACTS)
    public boolean updateE2eeContactKeyRemoteVerificationState(@NonNull String lookupKey,
            @NonNull String deviceId,
            @NonNull String accountId,
            @VerificationState int remoteVerificationState) {
        validateVerificationState(remoteVerificationState);

        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putInt(E2eeContactKeys.REMOTE_VERIFICATION_STATE, remoteVerificationState);

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_CONTACT_KEY_REMOTE_VERIFICATION_STATE_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    /**
     * Updates an end-to-end encryption contact key entry's remote verification state that belongs
     * to the app identified by ownerPackageName.
     *
     * @param lookupKey               the value that references the contact
     * @param deviceId                an app-specified identifier for the device
     * @param accountId               an app-specified identifier for the account
     * @param ownerPackageName        the package name of the app that owns the key
     * @param remoteVerificationState the new remote verification state
     * @return true if the entry was updated, false otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.WRITE_VERIFICATION_STATE_E2EE_CONTACT_KEYS,
            android.Manifest.permission.WRITE_CONTACTS})
    public boolean updateE2eeContactKeyRemoteVerificationState(@NonNull String lookupKey,
            @NonNull String deviceId,
            @NonNull String accountId,
            @NonNull String ownerPackageName,
            @VerificationState int remoteVerificationState) {
        validateVerificationState(remoteVerificationState);

        final Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putString(E2eeContactKeys.OWNER_PACKAGE_NAME,
                Objects.requireNonNull(ownerPackageName));
        extras.putInt(E2eeContactKeys.REMOTE_VERIFICATION_STATE, remoteVerificationState);

        final Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_CONTACT_KEY_REMOTE_VERIFICATION_STATE_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    private static void validateVerificationState(int verificationState) {
        if (verificationState != VERIFICATION_STATE_UNVERIFIED
                && verificationState != VERIFICATION_STATE_VERIFICATION_FAILED
                && verificationState != VERIFICATION_STATE_VERIFIED) {
            throw new IllegalArgumentException("Verification state value "
                    + verificationState + " is not supported");
        }
    }

    /**
     * Removes an end-to-end encryption contact key entry that belongs to the caller app.
     *
     * @param lookupKey the value that references the contact
     * @param deviceId  an app-specified identifier for the device
     * @param accountId an app-specified identifier for the account
     * @return true if the entry was removed, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_CONTACTS)
    public boolean removeE2eeContactKey(@NonNull String lookupKey,
            @NonNull String deviceId,
            @NonNull String accountId) {
        final Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.LOOKUP_KEY, Objects.requireNonNull(lookupKey));
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));

        final Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.REMOVE_CONTACT_KEY_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    /**
     * Inserts a new entry into the end-to-end encryption self keys table or updates one if it
     * already exists.
     *
     * @param deviceId  an app-specified identifier for the device
     * @param accountId an app-specified identifier for the account
     * @param keyValue  the raw bytes for the key (max size is {@link #getMaxKeySizeBytes} bytes)
     * @return true if the entry was added or updated, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_CONTACTS)
    public boolean updateOrInsertE2eeSelfKey(@NonNull String deviceId,
            @NonNull String accountId,
            @NonNull byte[] keyValue) {
        validateKeyLength(keyValue);

        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putByteArray(E2eeContactKeys.KEY_VALUE, Objects.requireNonNull(keyValue));

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_OR_INSERT_SELF_KEY_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    private static void validateKeyLength(byte[] keyValue) {
        Objects.requireNonNull(keyValue);
        if (keyValue.length == 0 || keyValue.length > getMaxKeySizeBytes()) {
            throw new IllegalArgumentException("Key value length is " + keyValue.length + "."
                    + " Should be more than 0 and less than " + getMaxKeySizeBytes());
        }
    }

    /**
     * Updates an end-to-end encryption self key entry's remote verification state.
     *
     * @param deviceId                an app-specified identifier for the device
     * @param accountId               an app-specified identifier for the account
     * @param remoteVerificationState the new remote verification state
     * @return true if the entry was updated, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_CONTACTS)
    public boolean updateE2eeSelfKeyRemoteVerificationState(@NonNull String deviceId,
            @NonNull String accountId,
            @VerificationState int remoteVerificationState) {
        validateVerificationState(remoteVerificationState);

        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putInt(E2eeContactKeys.REMOTE_VERIFICATION_STATE, remoteVerificationState);

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_SELF_KEY_REMOTE_VERIFICATION_STATE_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    /**
     * Updates an end-to-end encryption self key entry's remote verification state that belongs to
     * the app identified by ownerPackageName.
     *
     * @param deviceId                an app-specified identifier for the device
     * @param accountId               an app-specified identifier for the account
     * @param ownerPackageName        the package name of the app that owns the key
     * @param remoteVerificationState the new remote verification state
     * @return true if the entry was updated, false otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.WRITE_VERIFICATION_STATE_E2EE_CONTACT_KEYS,
            android.Manifest.permission.WRITE_CONTACTS})
    public boolean updateE2eeSelfKeyRemoteVerificationState(@NonNull String deviceId,
            @NonNull String accountId,
            @NonNull String ownerPackageName,
            @VerificationState int remoteVerificationState) {
        validateVerificationState(remoteVerificationState);

        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));
        extras.putString(E2eeContactKeys.OWNER_PACKAGE_NAME,
                Objects.requireNonNull(ownerPackageName));
        extras.putInt(E2eeContactKeys.REMOTE_VERIFICATION_STATE, remoteVerificationState);

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.UPDATE_SELF_KEY_REMOTE_VERIFICATION_STATE_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    /**
     * Maximum size of an end-to-end encryption contact key.
     */
    public static int getMaxKeySizeBytes() {
        return MAX_KEY_SIZE_BYTES;
    }

    /**
     * Returns an end-to-end encryption self key entry given the deviceId and the inferred package
     * name of the caller.
     *
     * @param deviceId  an app-specified identifier for the device
     * @param accountId an app-specified identifier for the account
     * @return a {@link E2eeSelfKey} object containing the end-to-end encryption self key
     * information, or null if no self key is found.
     */
    @RequiresPermission(android.Manifest.permission.READ_CONTACTS)
    @Nullable
    public E2eeSelfKey getE2eeSelfKey(@NonNull String deviceId,
            @NonNull String accountId) {
        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.GET_SELF_KEY_METHOD, extras);

        if (response == null) {
            return null;
        }
        return response.getParcelable(E2eeContactKeys.KEY_CONTACT_KEY, E2eeSelfKey.class);
    }

    /**
     * Returns all end-to-end encryption self key entries that belong to apps visible to the caller.
     * The keys will be stripped of deviceId, timeUpdated and keyValue data.
     *
     * @return a list of {@link E2eeSelfKey} objects containing the end-to-end encryption self key
     * information, or an empty list if no self keys are found.
     */
    @RequiresPermission(android.Manifest.permission.READ_CONTACTS)
    @NonNull
    public List<E2eeSelfKey> getAllE2eeSelfKeys() {
        Bundle extras = new Bundle();

        Bundle response = nullSafeCall(mContentResolver, E2eeContactKeys.GET_ALL_SELF_KEYS_METHOD,
                extras);

        if (response == null) {
            return new ArrayList<>();
        }
        List<E2eeSelfKey> value = response.getParcelableArrayList(E2eeContactKeys.KEY_CONTACT_KEYS,
                E2eeSelfKey.class);
        if (value == null) {
            return new ArrayList<>();
        }
        return value;
    }

    /**
     * Returns all end-to-end encryption self key entries that are owned by the caller app.
     *
     * @return a list of {@link E2eeSelfKey} objects containing the end-to-end encryption self key
     * information, or an empty list if no self keys are found.
     */
    @RequiresPermission(android.Manifest.permission.READ_CONTACTS)
    @NonNull
    public List<E2eeSelfKey> getOwnerE2eeSelfKeys() {
        Bundle extras = new Bundle();

        Bundle response = nullSafeCall(mContentResolver, E2eeContactKeys.GET_OWNER_SELF_KEYS_METHOD,
                extras);

        if (response == null) {
            return new ArrayList<>();
        }
        List<E2eeSelfKey> value = response.getParcelableArrayList(E2eeContactKeys.KEY_CONTACT_KEYS,
                E2eeSelfKey.class);
        if (value == null) {
            return new ArrayList<>();
        }
        return value;
    }

    /**
     * Removes an end-to-end encryption self key entry given the deviceId and the inferred
     * package name of the caller.
     *
     * @param deviceId  an app-specified identifier for the device
     * @param accountId an app-specified identifier for the account
     * @return true if the entry was removed, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_CONTACTS)
    public boolean removeE2eeSelfKey(@NonNull String deviceId,
            @NonNull String accountId) {
        Bundle extras = new Bundle();
        extras.putString(E2eeContactKeys.DEVICE_ID, Objects.requireNonNull(deviceId));
        extras.putString(E2eeContactKeys.ACCOUNT_ID, Objects.requireNonNull(accountId));

        Bundle response = nullSafeCall(mContentResolver,
                E2eeContactKeys.REMOVE_SELF_KEY_METHOD, extras);

        return response != null && response.getBoolean(E2eeContactKeys.KEY_UPDATED_ROWS);
    }

    private Bundle nullSafeCall(@NonNull ContentResolver resolver, @NonNull String method,
            @Nullable Bundle extras) {
        try (ContentProviderClient client = resolver.acquireContentProviderClient(AUTHORITY_URI)) {
            return client.call(method, null, extras);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Possible values of verification state.
     *
     * @hide
     */
    @IntDef(prefix = {"VERIFICATION_STATE_"}, value = {
            VERIFICATION_STATE_UNVERIFIED,
            VERIFICATION_STATE_VERIFICATION_FAILED,
            VERIFICATION_STATE_VERIFIED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VerificationState {
    }

    /**
     * Unverified state of a contact end to end encrypted key.
     */
    public static final int VERIFICATION_STATE_UNVERIFIED = 0;
    /**
     * Failed verification state of a contact end to end encrypted key.
     */
    public static final int VERIFICATION_STATE_VERIFICATION_FAILED = 1;
    /**
     * Verified state of a contact end to end encrypted key.
     */
    public static final int VERIFICATION_STATE_VERIFIED = 2;

    /** @hide */
    public static final class E2eeContactKeys {

        private E2eeContactKeys() {
        }

        /**
         * <p>
         * An opaque value that contains hints on how to find the contact if
         * its row id changed as a result of a sync or aggregation.
         * </p>
         */
        public static final String LOOKUP_KEY = "lookup";

        /**
         * <p>
         * An app-specified identifier for the device for which the end-to-end encryption
         * contact key can be used.
         * </p>
         */
        public static final String DEVICE_ID = "device_id";

        /**
         * <p>
         * An app-specified identifier for the account for which the end-to-end encryption
         * contact key can be used.
         * Usually a phone number.
         * </p>
         */
        public static final String ACCOUNT_ID = "account_id";

        /**
         * <p>
         * The display name for the contact.
         * </p>
         */
        public static final String DISPLAY_NAME = "display_name";

        /**
         * <p>
         * The phone number as the user entered it.
         * </p>
         */
        public static final String PHONE_NUMBER = "number";

        /**
         * <p>
         * The email address.
         * </p>
         */
        public static final String EMAIL_ADDRESS = "address";

        /**
         * <p>
         * Timestamp at which the key was updated.
         * </p>
         */
        public static final String TIME_UPDATED = "time_updated";

        /**
         * <p>
         * The raw bytes for the key.
         * </p>
         */
        public static final String KEY_VALUE = "key_value";

        /**
         * <p>
         * The package name of the package that created the key.
         * </p>
         */
        public static final String OWNER_PACKAGE_NAME = "owner_package_name";

        /**
         * <p>
         * Describes the local verification state for the key, for instance QR-code based
         * verification.
         * </p>
         */
        public static final String LOCAL_VERIFICATION_STATE = "local_verification_state";

        /**
         * <p>
         * Describes the remote verification state for the key, for instance through a key
         * transparency server.
         * </p>
         */
        public static final String REMOTE_VERIFICATION_STATE = "remote_verification_state";

        /**
         * The method to invoke in order to add a new key for a contact.
         */
        public static final String UPDATE_OR_INSERT_CONTACT_KEY_METHOD = "updateOrInsertContactKey";

        /**
         * The method to invoke in order to retrieve key for a single contact.
         */
        public static final String GET_CONTACT_KEY_METHOD = "getContactKey";

        /**
         * The method to invoke in order to retrieve all end-to-end encryption contact keys.
         */
        public static final String GET_ALL_CONTACT_KEYS_METHOD = "getAllContactKeys";

        /**
         * The method to invoke in order to retrieve end-to-end encryption contact keys that belong
         * to the caller.
         */
        public static final String GET_OWNER_CONTACT_KEYS_METHOD = "getOwnerContactKeys";

        /**
         * The method to invoke in order to update an end-to-end encryption contact key local
         * verification state.
         */
        public static final String UPDATE_CONTACT_KEY_LOCAL_VERIFICATION_STATE_METHOD =
                "updateContactKeyLocalVerificationState";

        /**
         * The method to invoke in order to update an end-to-end encryption contact key remote
         * verification state.
         */
        public static final String UPDATE_CONTACT_KEY_REMOTE_VERIFICATION_STATE_METHOD =
                "updateContactKeyRemoteVerificationState";

        /**
         * The method to invoke in order to remove a end-to-end encryption contact key.
         */
        public static final String REMOVE_CONTACT_KEY_METHOD = "removeContactKey";

        /**
         * The method to invoke in order to add a new self key.
         */
        public static final String UPDATE_OR_INSERT_SELF_KEY_METHOD = "updateOrInsertSelfKey";

        /**
         * The method to invoke in order to update a self key remote verification state.
         */
        public static final String UPDATE_SELF_KEY_REMOTE_VERIFICATION_STATE_METHOD =
                "updateSelfKeyRemoteVerificationState";

        /**
         * The method to invoke in order to retrieve a self key.
         */
        public static final String GET_SELF_KEY_METHOD = "getSelfKey";

        /**
         * The method to invoke in order to retrieve all self keys.
         */
        public static final String GET_ALL_SELF_KEYS_METHOD = "getAllSelfKeys";

        /**
         * The method to invoke in order to retrieve self keys that belong to the caller.
         */
        public static final String GET_OWNER_SELF_KEYS_METHOD = "getOwnerSelfKeys";

        /**
         * The method to invoke in order to remove a new self key.
         */
        public static final String REMOVE_SELF_KEY_METHOD = "removeSelfKey";

        /**
         * Key in the incoming Bundle for all the end-to-end encryption contact keys.
         */
        public static final String KEY_CONTACT_KEYS = "key_contact_keys";

        /**
         * Key in the incoming Bundle for a single end-to-end encryption contact key.
         */
        public static final String KEY_CONTACT_KEY = "key_contact_key";

        /**
         * Key in the incoming Bundle for a number of modified rows.
         */
        public static final String KEY_UPDATED_ROWS = "key_updated_rows";
    }
    /**
     * A parcelable class encapsulating other users' end to end encrypted contact key.
     */
    public static final class E2eeContactKey extends E2eeBaseKey implements Parcelable {

        /**
         * Describes the local verification state for the key, for instance QR-code based
         * verification.
         */
        private final int mLocalVerificationState;

        /**
         * The display name for the contact.
         */
        private final String mDisplayName;

        /**
         * The phone number as the user entered it.
         */
        private final String mPhoneNumber;

        /**
         * The email address.
         */
        private final String mEmailAddress;

        /**
         * @hide
         */
        public E2eeContactKey(@Nullable String deviceId, @NonNull String accountId,
                @NonNull String ownerPackageName, long timeUpdated, @Nullable byte[] keyValue,
                @VerificationState int localVerificationState,
                @VerificationState int remoteVerificationState,
                @Nullable String displayName,
                @Nullable String phoneNumber, @Nullable String emailAddress) {
            super(deviceId, accountId, ownerPackageName, timeUpdated, keyValue,
                    remoteVerificationState);
            this.mLocalVerificationState = localVerificationState;
            this.mDisplayName = displayName;
            this.mPhoneNumber = phoneNumber;
            this.mEmailAddress = emailAddress;
        }

        /**
         * Gets the local verification state for the key, for instance QR-code based verification.
         *
         * @return The local verification state for the key.
         */
        public @VerificationState int getLocalVerificationState() {
            return mLocalVerificationState;
        }

        /**
         * Gets the display name for the contact.
         *
         * @return The display name for the contact.
         */
        @Nullable
        public String getDisplayName() {
            return mDisplayName;
        }

        /**
         * Gets the phone number as the user entered it.
         *
         * @return The phone number as the user entered it.
         */
        @Nullable
        public String getPhoneNumber() {
            return mPhoneNumber;
        }

        /**
         * Gets the email address.
         *
         * @return The email address.
         */
        @Nullable
        public String getEmailAddress() {
            return mEmailAddress;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDeviceId, mAccountId, mOwnerPackageName, mTimeUpdated,
                    Arrays.hashCode(mKeyValue), mLocalVerificationState, mRemoteVerificationState,
                    mDisplayName, mPhoneNumber, mEmailAddress);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) return false;
            if (obj == this) return true;

            if (!(obj instanceof E2eeContactKey toCompare)) {
                return false;
            }

            return Objects.equals(mDeviceId, toCompare.mDeviceId)
                    && Objects.equals(mAccountId, toCompare.mAccountId)
                    && Objects.equals(mOwnerPackageName, toCompare.mOwnerPackageName)
                    && mTimeUpdated == toCompare.mTimeUpdated
                    && Arrays.equals(mKeyValue, toCompare.mKeyValue)
                    && mLocalVerificationState == toCompare.mLocalVerificationState
                    && mRemoteVerificationState == toCompare.mRemoteVerificationState
                    && Objects.equals(mDisplayName, toCompare.mDisplayName)
                    && Objects.equals(mPhoneNumber, toCompare.mPhoneNumber)
                    && Objects.equals(mEmailAddress, toCompare.mEmailAddress);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString8(mDeviceId);
            dest.writeString8(mAccountId);
            dest.writeString8(mOwnerPackageName);
            dest.writeLong(mTimeUpdated);
            dest.writeInt(mKeyValue != null ? mKeyValue.length : ARRAY_IS_NULL);
            if (mKeyValue != null) {
                dest.writeByteArray(mKeyValue);
            }
            dest.writeInt(mLocalVerificationState);
            dest.writeInt(mRemoteVerificationState);
            dest.writeString8(mDisplayName);
            dest.writeString8(mPhoneNumber);
            dest.writeString8(mEmailAddress);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        public static final Creator<E2eeContactKey> CREATOR =
                new Creator<>() {
                    @Override
                    public E2eeContactKey createFromParcel(Parcel source) {
                        String deviceId = source.readString8();
                        String accountId = source.readString8();
                        String ownerPackageName = source.readString8();
                        long timeUpdated = source.readLong();
                        int keyValueLength = source.readInt();
                        byte[] keyValue;
                        if (keyValueLength > 0) {
                            keyValue = new byte[keyValueLength];
                            source.readByteArray(keyValue);
                        } else {
                            keyValue = null;
                        }
                        int localVerificationState = source.readInt();
                        int remoteVerificationState = source.readInt();
                        String displayName = source.readString8();
                        String number = source.readString8();
                        String address = source.readString8();
                        return new E2eeContactKey(deviceId, accountId, ownerPackageName,
                                timeUpdated, keyValue, localVerificationState,
                                remoteVerificationState, displayName, number, address);
                    }

                    @Override
                    public E2eeContactKey[] newArray(int size) {
                        return new E2eeContactKey[size];
                    }
                };
    }

    /**
     * A parcelable class encapsulating self end to end encrypted contact key.
     */
    public static final class E2eeSelfKey extends E2eeBaseKey implements Parcelable {
        /**
         * @hide
         */
        public E2eeSelfKey(@Nullable String deviceId, @NonNull String accountId,
                @NonNull String ownerPackageName, long timeUpdated, @Nullable byte[] keyValue,
                @VerificationState int remoteVerificationState) {
            super(deviceId, accountId, ownerPackageName, timeUpdated, keyValue,
                    remoteVerificationState);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDeviceId, mAccountId, mOwnerPackageName, mTimeUpdated,
                    Arrays.hashCode(mKeyValue), mRemoteVerificationState);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) return false;
            if (obj == this) return true;

            if (!(obj instanceof E2eeSelfKey toCompare)) {
                return false;
            }

            return Objects.equals(mDeviceId, toCompare.mDeviceId)
                    && Objects.equals(mAccountId, toCompare.mAccountId)
                    && Objects.equals(mOwnerPackageName, toCompare.mOwnerPackageName)
                    && mTimeUpdated == toCompare.mTimeUpdated
                    && Arrays.equals(mKeyValue, toCompare.mKeyValue)
                    && mRemoteVerificationState == toCompare.mRemoteVerificationState;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString8(mDeviceId);
            dest.writeString8(mAccountId);
            dest.writeString8(mOwnerPackageName);
            dest.writeLong(mTimeUpdated);
            dest.writeInt(mKeyValue != null ? mKeyValue.length : ARRAY_IS_NULL);
            if (mKeyValue != null) {
                dest.writeByteArray(mKeyValue);
            }
            dest.writeInt(mRemoteVerificationState);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        public static final Creator<E2eeSelfKey> CREATOR =
                new Creator<>() {
                    @Override
                    public E2eeSelfKey createFromParcel(Parcel source) {
                        String deviceId = source.readString8();
                        String accountId = source.readString8();
                        String ownerPackageName = source.readString8();
                        long timeUpdated = source.readLong();
                        int keyValueLength = source.readInt();
                        byte[] keyValue;
                        if (keyValueLength > 0) {
                            keyValue = new byte[keyValueLength];
                            source.readByteArray(keyValue);
                        } else {
                            keyValue = null;
                        }
                        int remoteVerificationState = source.readInt();
                        return new E2eeSelfKey(deviceId, accountId, ownerPackageName,
                                timeUpdated, keyValue, remoteVerificationState);
                    }

                    @Override
                    public E2eeSelfKey[] newArray(int size) {
                        return new E2eeSelfKey[size];
                    }
                };
    }

    /**
     * An abstract class that's extended by self and contact key classes.
     *
     * @hide
     */
    abstract static class E2eeBaseKey {
        /**
         * An app-specified identifier for the device for which the key can be used.
         */
        protected final String mDeviceId;

        /**
         * An app-specified identifier for the account for which the key can be used.
         * Usually a phone number.
         */
        protected final String mAccountId;

        /**
         * Owner application package name.
         */
        protected final String mOwnerPackageName;

        /**
         * Timestamp at which the key was updated.
         */
        protected final long mTimeUpdated;

        /**
         * The raw bytes for the key.
         */
        protected final byte[] mKeyValue;

        /**
         * Describes the remote verification state for the end-to-end encryption key, for instance
         * through a key transparency server.
         */
        protected final int mRemoteVerificationState;

        protected E2eeBaseKey(@Nullable String deviceId, @NonNull String accountId,
                @NonNull String ownerPackageName, long timeUpdated, @Nullable byte[] keyValue,
                @VerificationState int remoteVerificationState) {
            this.mDeviceId = deviceId;
            this.mAccountId = accountId;
            this.mOwnerPackageName = ownerPackageName;
            this.mTimeUpdated = timeUpdated;
            this.mKeyValue = keyValue == null ? null : Arrays.copyOf(keyValue, keyValue.length);
            this.mRemoteVerificationState = remoteVerificationState;
        }

        /**
         * Gets the app-specified identifier for the device for which the end-to-end encryption
         * key can be used.
         * Returns null if the app doesn't have the required visibility into
         * the end-to-end encryption key.
         *
         * @return An app-specified identifier for the device.
         */
        @Nullable
        public String getDeviceId() {
            return mDeviceId;
        }

        /**
         * Gets the app-specified identifier for the account for which the end-to-end encryption
         * key can be used.
         * Usually a phone number.
         *
         * @return An app-specified identifier for the account.
         */
        @NonNull
        public String getAccountId() {
            return mAccountId;
        }

        /**
         * Gets the owner application package name.
         *
         * @return The owner application package name.
         */
        @NonNull
        public String getOwnerPackageName() {
            return mOwnerPackageName;
        }

        /**
         * Gets the timestamp at which the end-to-end encryption key was updated. Returns -1 if
         * the app doesn't have the required visibility into the key.
         *
         * @return The timestamp at which the key was updated in the System.currentTimeMillis()
         * base.
         */
        public long getTimeUpdated() {
            return mTimeUpdated;
        }

        /**
         * Gets the raw bytes for the end-to-end encryption key.
         * Returns null if the app doesn't have the required visibility into
         * the end-to-end encryption key.
         *
         * @return A copy of the raw bytes for the end-to-end encryption key.
         */
        @Nullable
        public byte[] getKeyValue() {
            return mKeyValue == null ? null : Arrays.copyOf(mKeyValue, mKeyValue.length);
        }

        /**
         * Gets the remote verification state for the end-to-end encryption key, for instance
         * through a key transparency server.
         *
         * @return The remote verification state for the end-to-end encryption key.
         */
        public @VerificationState int getRemoteVerificationState() {
            return mRemoteVerificationState;
        }
    }
}
