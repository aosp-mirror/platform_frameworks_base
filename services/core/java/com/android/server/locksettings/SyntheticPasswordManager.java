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

package com.android.server.locksettings;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.weaver.V1_0.IWeaver;
import android.hardware.weaver.V1_0.WeaverConfig;
import android.hardware.weaver.V1_0.WeaverReadResponse;
import android.hardware.weaver.V1_0.WeaverReadStatus;
import android.hardware.weaver.V1_0.WeaverStatus;
import android.os.RemoteException;
import android.os.UserManager;
import android.security.GateKeeper;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import libcore.util.HexEncoding;

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;


/**
 * A class that maintains the wrapping of synthetic password by user credentials or escrow tokens.
 * It's (mostly) a pure storage for synthetic passwords, providing APIs to creating and destroying
 * synthetic password blobs which are wrapped by user credentials or escrow tokens.
 *
 * Here is the assumptions it makes:
 *   Each user has one single synthetic password at any time.
 *   The SP has an associated password handle, which binds to the SID for that user. The password
 *   handle is persisted by SyntheticPasswordManager internally.
 *   If the user credential is null, it's treated as if the credential is DEFAULT_PASSWORD
 *
 * Information persisted on disk:
 *   for each user (stored under DEFAULT_HANDLE):
 *     SP_HANDLE_NAME: GateKeeper password handle of synthetic password. Only available if user
 *                     credential exists, cleared when user clears their credential.
 *     SP_E0_NAME, SP_P1_NAME: Secret to derive synthetic password when combined with escrow
 *                     tokens. Destroyed when escrow support is turned off for the given user.
 *
 *     for each SP blob under the user (stored under the corresponding handle):
 *       SP_BLOB_NAME: The encrypted synthetic password. Always exists.
 *       PASSWORD_DATA_NAME: Metadata about user credential. Only exists for password based SP.
 *       SECDISCARDABLE_NAME: Part of the necessary ingredient to decrypt SP_BLOB_NAME for the
 *                            purpose of secure deletion. Exists if this is a non-weaver SP
 *                            (both password and token based), or it's a token-based SP under weaver.
 *       WEAVER_SLOT: Metadata about the weaver slot used. Only exists if this is a SP under weaver.
 *
 *
 */
public class SyntheticPasswordManager {
    private static final String SP_BLOB_NAME = "spblob";
    private static final String SP_E0_NAME = "e0";
    private static final String SP_P1_NAME = "p1";
    private static final String SP_HANDLE_NAME = "handle";
    private static final String SECDISCARDABLE_NAME = "secdis";
    private static final int SECDISCARDABLE_LENGTH = 16 * 1024;
    private static final String PASSWORD_DATA_NAME = "pwd";
    private static final String WEAVER_SLOT_NAME = "weaver";

    public static final long DEFAULT_HANDLE = 0L;
    private static final byte[] DEFAULT_PASSWORD = "default-password".getBytes();

    private static final byte WEAVER_VERSION = 1;
    private static final int INVALID_WEAVER_SLOT = -1;

    private static final byte SYNTHETIC_PASSWORD_VERSION_V1 = 1;
    private static final byte SYNTHETIC_PASSWORD_VERSION_V2 = 2;
    private static final byte SYNTHETIC_PASSWORD_VERSION_V3 = 3;
    private static final byte SYNTHETIC_PASSWORD_PASSWORD_BASED = 0;
    private static final byte SYNTHETIC_PASSWORD_TOKEN_BASED = 1;

    // 256-bit synthetic password
    private static final byte SYNTHETIC_PASSWORD_LENGTH = 256 / 8;

    private static final int PASSWORD_SCRYPT_N = 11;
    private static final int PASSWORD_SCRYPT_R = 3;
    private static final int PASSWORD_SCRYPT_P = 1;
    private static final int PASSWORD_SALT_LENGTH = 16;
    private static final int PASSWORD_TOKEN_LENGTH = 32;
    private static final String TAG = "SyntheticPasswordManager";

    private static final byte[] PERSONALISATION_SECDISCARDABLE = "secdiscardable-transform".getBytes();
    private static final byte[] PERSONALIZATION_KEY_STORE_PASSWORD = "keystore-password".getBytes();
    private static final byte[] PERSONALIZATION_USER_GK_AUTH = "user-gk-authentication".getBytes();
    private static final byte[] PERSONALIZATION_SP_GK_AUTH = "sp-gk-authentication".getBytes();
    private static final byte[] PERSONALIZATION_FBE_KEY = "fbe-key".getBytes();
    private static final byte[] PERSONALIZATION_AUTHSECRET_KEY = "authsecret-hal".getBytes();
    private static final byte[] PERSONALIZATION_SP_SPLIT = "sp-split".getBytes();
    private static final byte[] PERSONALIZATION_PASSWORD_HASH = "pw-hash".getBytes();
    private static final byte[] PERSONALIZATION_E0 = "e0-encryption".getBytes();
    private static final byte[] PERSONALISATION_WEAVER_PASSWORD = "weaver-pwd".getBytes();
    private static final byte[] PERSONALISATION_WEAVER_KEY = "weaver-key".getBytes();
    private static final byte[] PERSONALISATION_WEAVER_TOKEN = "weaver-token".getBytes();
    private static final byte[] PERSONALISATION_CONTEXT =
        "android-synthetic-password-personalization-context".getBytes();

    static class AuthenticationResult {
        public AuthenticationToken authToken;
        public VerifyCredentialResponse gkResponse;
        public int credentialType;
    }

    static class AuthenticationToken {
        private final byte mVersion;
        /*
         * Here is the relationship between all three fields:
         * P0 and P1 are two randomly-generated blocks. P1 is stored on disk but P0 is not.
         * syntheticPassword = hash(P0 || P1)
         * E0 = P0 encrypted under syntheticPassword, stored on disk.
         */
        private @Nullable byte[] E0;
        private @Nullable byte[] P1;
        private @NonNull String syntheticPassword;

        AuthenticationToken(byte version) {
            mVersion = version;
        }

        private byte[] derivePassword(byte[] personalization) {
            if (mVersion == SYNTHETIC_PASSWORD_VERSION_V3) {
                return (new SP800Derive(syntheticPassword.getBytes()))
                    .withContext(personalization, PERSONALISATION_CONTEXT);
            } else {
                return SyntheticPasswordCrypto.personalisedHash(personalization,
                        syntheticPassword.getBytes());
            }
        }

        public byte[] deriveKeyStorePassword() {
            return bytesToHex(derivePassword(PERSONALIZATION_KEY_STORE_PASSWORD));
        }

        public byte[] deriveGkPassword() {
            return derivePassword(PERSONALIZATION_SP_GK_AUTH);
        }

        public byte[] deriveDiskEncryptionKey() {
            return derivePassword(PERSONALIZATION_FBE_KEY);
        }

        public byte[] deriveVendorAuthSecret() {
            return derivePassword(PERSONALIZATION_AUTHSECRET_KEY);
        }

        public byte[] derivePasswordHashFactor() {
            return derivePassword(PERSONALIZATION_PASSWORD_HASH);
        }

        private void initialize(byte[] P0, byte[] P1) {
            this.P1 = P1;
            this.syntheticPassword = String.valueOf(HexEncoding.encode(
                    SyntheticPasswordCrypto.personalisedHash(
                            PERSONALIZATION_SP_SPLIT, P0, P1)));
            this.E0 = SyntheticPasswordCrypto.encrypt(this.syntheticPassword.getBytes(),
                    PERSONALIZATION_E0, P0);
        }

        public void recreate(byte[] secret) {
            initialize(secret, this.P1);
        }

        protected static AuthenticationToken create() {
            AuthenticationToken result = new AuthenticationToken(SYNTHETIC_PASSWORD_VERSION_V3);
            result.initialize(secureRandom(SYNTHETIC_PASSWORD_LENGTH),
                    secureRandom(SYNTHETIC_PASSWORD_LENGTH));
            return result;
        }

        public byte[] computeP0() {
            if (E0 == null) {
                return null;
            }
            return SyntheticPasswordCrypto.decrypt(syntheticPassword.getBytes(), PERSONALIZATION_E0,
                    E0);
        }
    }

    static class PasswordData {
        byte scryptN;
        byte scryptR;
        byte scryptP;
        public int passwordType;
        byte[] salt;
        // For GateKeeper-based credential, this is the password handle returned by GK,
        // for weaver-based credential, this is empty.
        public byte[] passwordHandle;

        public static PasswordData create(int passwordType) {
            PasswordData result = new PasswordData();
            result.scryptN = PASSWORD_SCRYPT_N;
            result.scryptR = PASSWORD_SCRYPT_R;
            result.scryptP = PASSWORD_SCRYPT_P;
            result.passwordType = passwordType;
            result.salt = secureRandom(PASSWORD_SALT_LENGTH);
            return result;
        }

        public static PasswordData fromBytes(byte[] data) {
            PasswordData result = new PasswordData();
            ByteBuffer buffer = ByteBuffer.allocate(data.length);
            buffer.put(data, 0, data.length);
            buffer.flip();
            result.passwordType = buffer.getInt();
            result.scryptN = buffer.get();
            result.scryptR = buffer.get();
            result.scryptP = buffer.get();
            int saltLen = buffer.getInt();
            result.salt = new byte[saltLen];
            buffer.get(result.salt);
            int handleLen = buffer.getInt();
            if (handleLen > 0) {
                result.passwordHandle = new byte[handleLen];
                buffer.get(result.passwordHandle);
            } else {
                result.passwordHandle = null;
            }
            return result;
        }

        public byte[] toBytes() {

            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + 3 * Byte.BYTES
                    + Integer.BYTES + salt.length + Integer.BYTES +
                    (passwordHandle != null ? passwordHandle.length : 0));
            buffer.putInt(passwordType);
            buffer.put(scryptN);
            buffer.put(scryptR);
            buffer.put(scryptP);
            buffer.putInt(salt.length);
            buffer.put(salt);
            if (passwordHandle != null && passwordHandle.length > 0) {
                buffer.putInt(passwordHandle.length);
                buffer.put(passwordHandle);
            } else {
                buffer.putInt(0);
            }
            return buffer.array();
        }
    }

    static class TokenData {
        byte[] secdiscardableOnDisk;
        byte[] weaverSecret;
        byte[] aggregatedSecret;
    }

    private final Context mContext;
    private LockSettingsStorage mStorage;
    private IWeaver mWeaver;
    private WeaverConfig mWeaverConfig;

    private final UserManager mUserManager;

    public SyntheticPasswordManager(Context context, LockSettingsStorage storage,
            UserManager userManager) {
        mContext = context;
        mStorage = storage;
        mUserManager = userManager;
    }

    @VisibleForTesting
    protected IWeaver getWeaverService() throws RemoteException {
        try {
            return IWeaver.getService();
        } catch (NoSuchElementException e) {
            Slog.i(TAG, "Device does not support weaver");
            return null;
        }
    }

    public synchronized void initWeaverService() {
        if (mWeaver != null) {
            return;
        }
        try {
            mWeaverConfig = null;
            mWeaver = getWeaverService();
            if (mWeaver != null) {
                mWeaver.getConfig((int status, WeaverConfig config) -> {
                    if (status == WeaverStatus.OK && config.slots > 0) {
                        mWeaverConfig = config;
                    } else {
                        Slog.e(TAG, "Failed to get weaver config, status " + status
                                + " slots: " + config.slots);
                        mWeaver = null;
                    }
                });
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get weaver service", e);
        }
    }

    private synchronized boolean isWeaverAvailable() {
        if (mWeaver == null) {
            //Re-initializing weaver in case there was a transient error preventing access to it.
            initWeaverService();
        }
        return mWeaver != null && mWeaverConfig.slots > 0;
    }

    /**
     * Enroll the given key value pair into the specified weaver slot. if the given key is null,
     * a default all-zero key is used. If the value is not specified, a fresh random secret is
     * generated as the value.
     *
     * @return the value stored in the weaver slot
     * @throws RemoteException
     */
    private byte[] weaverEnroll(int slot, byte[] key, @Nullable byte[] value)
            throws RemoteException {
        if (slot == INVALID_WEAVER_SLOT || slot >= mWeaverConfig.slots) {
            throw new RuntimeException("Invalid slot for weaver");
        }
        if (key == null) {
            key = new byte[mWeaverConfig.keySize];
        } else if (key.length != mWeaverConfig.keySize) {
            throw new RuntimeException("Invalid key size for weaver");
        }
        if (value == null) {
            value = secureRandom(mWeaverConfig.valueSize);
        }
        int writeStatus = mWeaver.write(slot, toByteArrayList(key), toByteArrayList(value));
        if (writeStatus != WeaverStatus.OK) {
            Log.e(TAG, "weaver write failed, slot: " + slot + " status: " + writeStatus);
            return null;
        }
        return value;
    }

    /**
     * Verify the supplied key against a weaver slot, returning a response indicating whether
     * the verification is successful, throttled or failed. If successful, the bound secret
     * is also returned.
     * @throws RemoteException
     */
    private VerifyCredentialResponse weaverVerify(int slot, byte[] key) throws RemoteException {
        if (slot == INVALID_WEAVER_SLOT || slot >= mWeaverConfig.slots) {
            throw new RuntimeException("Invalid slot for weaver");
        }
        if (key == null) {
            key = new byte[mWeaverConfig.keySize];
        } else if (key.length != mWeaverConfig.keySize) {
            throw new RuntimeException("Invalid key size for weaver");
        }
        final VerifyCredentialResponse[] response = new VerifyCredentialResponse[1];
        mWeaver.read(slot, toByteArrayList(key), (int status, WeaverReadResponse readResponse) -> {
            switch (status) {
                case WeaverReadStatus.OK:
                    response[0] = new VerifyCredentialResponse(
                            fromByteArrayList(readResponse.value));
                    break;
                case WeaverReadStatus.THROTTLE:
                    response[0] = new VerifyCredentialResponse(readResponse.timeout);
                    Log.e(TAG, "weaver read failed (THROTTLE), slot: " + slot);
                    break;
                case WeaverReadStatus.INCORRECT_KEY:
                    if (readResponse.timeout == 0) {
                        response[0] = VerifyCredentialResponse.ERROR;
                        Log.e(TAG, "weaver read failed (INCORRECT_KEY), slot: " + slot);
                    } else {
                        response[0] = new VerifyCredentialResponse(readResponse.timeout);
                        Log.e(TAG, "weaver read failed (INCORRECT_KEY/THROTTLE), slot: " + slot);
                    }
                    break;
                case WeaverReadStatus.FAILED:
                    response[0] = VerifyCredentialResponse.ERROR;
                    Log.e(TAG, "weaver read failed (FAILED), slot: " + slot);
                    break;
               default:
                   response[0] = VerifyCredentialResponse.ERROR;
                   Log.e(TAG, "weaver read unknown status " + status + ", slot: " + slot);
                   break;
            }
        });
        return response[0];
    }

    public void removeUser(int userId) {
        for (long handle : mStorage.listSyntheticPasswordHandlesForUser(SP_BLOB_NAME, userId)) {
            destroyWeaverSlot(handle, userId);
            destroySPBlobKey(getHandleName(handle));
        }
    }

    public int getCredentialType(long handle, int userId) {
        byte[] passwordData = loadState(PASSWORD_DATA_NAME, handle, userId);
        if (passwordData == null) {
            Log.w(TAG, "getCredentialType: encountered empty password data for user " + userId);
            return LockPatternUtils.CREDENTIAL_TYPE_NONE;
        }
        return PasswordData.fromBytes(passwordData).passwordType;
    }

    /**
     * Initializing a new Authentication token, possibly from an existing credential and hash.
     *
     * The authentication token would bear a randomly-generated synthetic password.
     *
     * This method has the side effect of rebinding the SID of the given user to the
     * newly-generated SP.
     *
     * If the existing credential hash is non-null, the existing SID mill be migrated so
     * the synthetic password in the authentication token will produce the same SID
     * (the corresponding synthetic password handle is persisted by SyntheticPasswordManager
     * in a per-user data storage.)
     *
     * If the existing credential hash is null, it means the given user should have no SID so
     * SyntheticPasswordManager will nuke any SP handle previously persisted. In this case,
     * the supplied credential parameter is also ignored.
     *
     * Also saves the escrow information necessary to re-generate the synthetic password under
     * an escrow scheme. This information can be removed with {@link #destroyEscrowData} if
     * password escrow should be disabled completely on the given user.
     *
     */
    public AuthenticationToken newSyntheticPasswordAndSid(IGateKeeperService gatekeeper,
            byte[] hash, byte[] credential, int userId) throws RemoteException {
        AuthenticationToken result = AuthenticationToken.create();
        GateKeeperResponse response;
        if (hash != null) {
            response = gatekeeper.enroll(userId, hash, credential,
                    result.deriveGkPassword());
            if (response.getResponseCode() != GateKeeperResponse.RESPONSE_OK) {
                Log.w(TAG, "Fail to migrate SID, assuming no SID, user " + userId);
                clearSidForUser(userId);
            } else {
                saveSyntheticPasswordHandle(response.getPayload(), userId);
            }
        } else {
            clearSidForUser(userId);
        }
        saveEscrowData(result, userId);
        return result;
    }

    /**
     * Enroll a new password handle and SID for the given synthetic password and persist it on disk.
     * Used when adding password to previously-unsecured devices.
     */
    public void newSidForUser(IGateKeeperService gatekeeper, AuthenticationToken authToken,
            int userId) throws RemoteException {
        GateKeeperResponse response = gatekeeper.enroll(userId, null, null,
                authToken.deriveGkPassword());
        if (response.getResponseCode() != GateKeeperResponse.RESPONSE_OK) {
            Log.e(TAG, "Fail to create new SID for user " + userId);
            return;
        }
        saveSyntheticPasswordHandle(response.getPayload(), userId);
    }

    // Nuke the SP handle (and as a result, its SID) for the given user.
    public void clearSidForUser(int userId) {
        destroyState(SP_HANDLE_NAME, DEFAULT_HANDLE, userId);
    }

    public boolean hasSidForUser(int userId) {
        return hasState(SP_HANDLE_NAME, DEFAULT_HANDLE, userId);
    }

    // if null, it means there is no SID associated with the user
    // This can happen if the user is migrated to SP but currently
    // do not have a lockscreen password.
    private byte[] loadSyntheticPasswordHandle(int userId) {
        return loadState(SP_HANDLE_NAME, DEFAULT_HANDLE, userId);
    }

    private void saveSyntheticPasswordHandle(byte[] spHandle, int userId) {
        saveState(SP_HANDLE_NAME, spHandle, DEFAULT_HANDLE, userId);
    }

    private boolean loadEscrowData(AuthenticationToken authToken, int userId) {
        authToken.E0 = loadState(SP_E0_NAME, DEFAULT_HANDLE, userId);
        authToken.P1 = loadState(SP_P1_NAME, DEFAULT_HANDLE, userId);
        return authToken.E0 != null && authToken.P1 != null;
    }

    private void saveEscrowData(AuthenticationToken authToken, int userId) {
        saveState(SP_E0_NAME, authToken.E0, DEFAULT_HANDLE, userId);
        saveState(SP_P1_NAME, authToken.P1, DEFAULT_HANDLE, userId);
    }

    public boolean hasEscrowData(int userId) {
        return hasState(SP_E0_NAME, DEFAULT_HANDLE, userId)
                && hasState(SP_P1_NAME, DEFAULT_HANDLE, userId);
    }

    public void destroyEscrowData(int userId) {
        destroyState(SP_E0_NAME, DEFAULT_HANDLE, userId);
        destroyState(SP_P1_NAME, DEFAULT_HANDLE, userId);
    }

    private int loadWeaverSlot(long handle, int userId) {
        final int LENGTH = Byte.BYTES + Integer.BYTES;
        byte[] data = loadState(WEAVER_SLOT_NAME, handle, userId);
        if (data == null || data.length != LENGTH) {
            return INVALID_WEAVER_SLOT;
        }
        ByteBuffer buffer = ByteBuffer.allocate(LENGTH);
        buffer.put(data, 0, data.length);
        buffer.flip();
        if (buffer.get() != WEAVER_VERSION) {
            Log.e(TAG, "Invalid weaver slot version of handle " + handle);
            return INVALID_WEAVER_SLOT;
        }
        return buffer.getInt();
    }

    private void saveWeaverSlot(int slot, long handle, int userId) {
        ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES);
        buffer.put(WEAVER_VERSION);
        buffer.putInt(slot);
        saveState(WEAVER_SLOT_NAME, buffer.array(), handle, userId);
    }

    private void destroyWeaverSlot(long handle, int userId) {
        int slot = loadWeaverSlot(handle, userId);
        destroyState(WEAVER_SLOT_NAME, handle, userId);
        if (slot != INVALID_WEAVER_SLOT) {
            Set<Integer> usedSlots = getUsedWeaverSlots();
            if (!usedSlots.contains(slot)) {
                Log.i(TAG, "Destroy weaver slot " + slot + " for user " + userId);
                try {
                    weaverEnroll(slot, null, null);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to destroy slot", e);
                }
            } else {
                Log.w(TAG, "Skip destroying reused weaver slot " + slot + " for user " + userId);
            }
        }
    }

    /**
     * Return the set of weaver slots that are currently in use by all users on the device.
     * <p>
     * <em>Note:</em> Users who are in the process of being deleted are not tracked here
     * (due to them being marked as partial in UserManager so not visible from
     * {@link UserManager#getUsers}). As a result their weaver slots will not be considered
     * taken and can be reused by new users. Care should be taken when cleaning up the
     * deleted user in {@link #removeUser}, to prevent a reused slot from being erased
     * unintentionally.
     */
    private Set<Integer> getUsedWeaverSlots() {
        Map<Integer, List<Long>> slotHandles = mStorage.listSyntheticPasswordHandlesForAllUsers(
                WEAVER_SLOT_NAME);
        HashSet<Integer> slots = new HashSet<>();
        for (Map.Entry<Integer, List<Long>> entry : slotHandles.entrySet()) {
            for (Long handle : entry.getValue()) {
                int slot = loadWeaverSlot(handle, entry.getKey());
                slots.add(slot);
            }
        }
        return slots;
    }

    private int getNextAvailableWeaverSlot() {
        Set<Integer> usedSlots = getUsedWeaverSlots();
        for (int i = 0; i < mWeaverConfig.slots; i++) {
            if (!usedSlots.contains(i)) {
                return i;
            }
        }
        throw new RuntimeException("Run out of weaver slots.");
    }

    /**
     * Create a new password based SP blob based on the supplied authentication token, such that
     * a future successful authentication with unwrapPasswordBasedSyntheticPassword() would result
     * in the same authentication token.
     *
     * This method only creates SP blob wrapping around the given synthetic password and does not
     * handle logic around SID or SP handle. The caller should separately ensure that the user's SID
     * is consistent with the device state by calling other APIs in this class.
     *
     * @see #newSidForUser
     * @see #clearSidForUser
     */
    public long createPasswordBasedSyntheticPassword(IGateKeeperService gatekeeper,
            byte[] credential, int credentialType, AuthenticationToken authToken,
            int requestedQuality, int userId)
                    throws RemoteException {
        if (credential == null || credentialType == LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            credentialType = LockPatternUtils.CREDENTIAL_TYPE_NONE;
            credential = DEFAULT_PASSWORD;
        }

        long handle = generateHandle();
        PasswordData pwd = PasswordData.create(credentialType);
        byte[] pwdToken = computePasswordToken(credential, pwd);
        final long sid;
        final byte[] applicationId;

        if (isWeaverAvailable()) {
            // Weaver based user password
            int weaverSlot = getNextAvailableWeaverSlot();
            Log.i(TAG, "Weaver enroll password to slot " + weaverSlot + " for user " + userId);
            byte[] weaverSecret = weaverEnroll(weaverSlot, passwordTokenToWeaverKey(pwdToken), null);
            if (weaverSecret == null) {
                Log.e(TAG, "Fail to enroll user password under weaver " + userId);
                return DEFAULT_HANDLE;
            }
            saveWeaverSlot(weaverSlot, handle, userId);
            synchronizeWeaverFrpPassword(pwd, requestedQuality, userId, weaverSlot);

            pwd.passwordHandle = null;
            sid = GateKeeper.INVALID_SECURE_USER_ID;
            applicationId = transformUnderWeaverSecret(pwdToken, weaverSecret);
        } else {
            // In case GK enrollment leaves persistent state around (in RPMB), this will nuke them
            // to prevent them from accumulating and causing problems.
            gatekeeper.clearSecureUserId(fakeUid(userId));
            // GateKeeper based user password
            GateKeeperResponse response = gatekeeper.enroll(fakeUid(userId), null, null,
                    passwordTokenToGkInput(pwdToken));
            if (response.getResponseCode() != GateKeeperResponse.RESPONSE_OK) {
                Log.e(TAG, "Fail to enroll user password when creating SP for user " + userId);
                return DEFAULT_HANDLE;
            }
            pwd.passwordHandle = response.getPayload();
            sid = sidFromPasswordHandle(pwd.passwordHandle);
            applicationId = transformUnderSecdiscardable(pwdToken,
                    createSecdiscardable(handle, userId));
            synchronizeFrpPassword(pwd, requestedQuality, userId);
        }
        saveState(PASSWORD_DATA_NAME, pwd.toBytes(), handle, userId);

        createSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_PASSWORD_BASED, authToken,
                applicationId, sid, userId);
        return handle;
    }

    public VerifyCredentialResponse verifyFrpCredential(IGateKeeperService gatekeeper,
            byte[] userCredential, int credentialType,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        PersistentData persistentData = mStorage.readPersistentDataBlock();
        if (persistentData.type == PersistentData.TYPE_SP) {
            PasswordData pwd = PasswordData.fromBytes(persistentData.payload);
            byte[] pwdToken = computePasswordToken(userCredential, pwd);

            GateKeeperResponse response = gatekeeper.verifyChallenge(fakeUid(persistentData.userId),
                    0 /* challenge */, pwd.passwordHandle, passwordTokenToGkInput(pwdToken));
            return VerifyCredentialResponse.fromGateKeeperResponse(response);
        } else if (persistentData.type == PersistentData.TYPE_SP_WEAVER) {
            PasswordData pwd = PasswordData.fromBytes(persistentData.payload);
            byte[] pwdToken = computePasswordToken(userCredential, pwd);
            int weaverSlot = persistentData.userId;

            return weaverVerify(weaverSlot, passwordTokenToWeaverKey(pwdToken)).stripPayload();
        } else {
            Log.e(TAG, "persistentData.type must be TYPE_SP or TYPE_SP_WEAVER, but is "
                    + persistentData.type);
            return VerifyCredentialResponse.ERROR;
        }
    }


    public void migrateFrpPasswordLocked(long handle, UserInfo userInfo, int requestedQuality) {
        if (mStorage.getPersistentDataBlock() != null
                && LockPatternUtils.userOwnsFrpCredential(mContext, userInfo)) {
            PasswordData pwd = PasswordData.fromBytes(loadState(PASSWORD_DATA_NAME, handle,
                    userInfo.id));
            if (pwd.passwordType != LockPatternUtils.CREDENTIAL_TYPE_NONE) {
                int weaverSlot = loadWeaverSlot(handle, userInfo.id);
                if (weaverSlot != INVALID_WEAVER_SLOT) {
                    synchronizeWeaverFrpPassword(pwd, requestedQuality, userInfo.id, weaverSlot);
                } else {
                    synchronizeFrpPassword(pwd, requestedQuality, userInfo.id);
                }
            }
        }
    }

    private void synchronizeFrpPassword(PasswordData pwd,
            int requestedQuality, int userId) {
        if (mStorage.getPersistentDataBlock() != null
                && LockPatternUtils.userOwnsFrpCredential(mContext,
                mUserManager.getUserInfo(userId))) {
            if (pwd.passwordType != LockPatternUtils.CREDENTIAL_TYPE_NONE) {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_SP, userId, requestedQuality,
                        pwd.toBytes());
            } else {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_NONE, userId, 0, null);
            }
        }
    }

    private void synchronizeWeaverFrpPassword(PasswordData pwd, int requestedQuality, int userId,
            int weaverSlot) {
        if (mStorage.getPersistentDataBlock() != null
                && LockPatternUtils.userOwnsFrpCredential(mContext,
                mUserManager.getUserInfo(userId))) {
            if (pwd.passwordType != LockPatternUtils.CREDENTIAL_TYPE_NONE) {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, weaverSlot,
                        requestedQuality, pwd.toBytes());
            } else {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_NONE, 0, 0, null);
            }
        }
    }

    private ArrayMap<Integer, ArrayMap<Long, TokenData>> tokenMap = new ArrayMap<>();

    public long createTokenBasedSyntheticPassword(byte[] token, int userId) {
        long handle = generateHandle();
        if (!tokenMap.containsKey(userId)) {
            tokenMap.put(userId, new ArrayMap<>());
        }
        TokenData tokenData = new TokenData();
        final byte[] secdiscardable = secureRandom(SECDISCARDABLE_LENGTH);
        if (isWeaverAvailable()) {
            tokenData.weaverSecret = secureRandom(mWeaverConfig.valueSize);
            tokenData.secdiscardableOnDisk = SyntheticPasswordCrypto.encrypt(tokenData.weaverSecret,
                            PERSONALISATION_WEAVER_TOKEN, secdiscardable);
        } else {
            tokenData.secdiscardableOnDisk = secdiscardable;
            tokenData.weaverSecret = null;
        }
        tokenData.aggregatedSecret = transformUnderSecdiscardable(token, secdiscardable);

        tokenMap.get(userId).put(handle, tokenData);
        return handle;
    }

    public Set<Long> getPendingTokensForUser(int userId) {
        if (!tokenMap.containsKey(userId)) {
            return Collections.emptySet();
        }
        return tokenMap.get(userId).keySet();
    }

    public boolean removePendingToken(long handle, int userId) {
        if (!tokenMap.containsKey(userId)) {
            return false;
        }
        return tokenMap.get(userId).remove(handle) != null;
    }

    public boolean activateTokenBasedSyntheticPassword(long handle, AuthenticationToken authToken,
            int userId) {
        if (!tokenMap.containsKey(userId)) {
            return false;
        }
        TokenData tokenData = tokenMap.get(userId).get(handle);
        if (tokenData == null) {
            return false;
        }
        if (!loadEscrowData(authToken, userId)) {
            Log.w(TAG, "User is not escrowable");
            return false;
        }
        if (isWeaverAvailable()) {
            int slot = getNextAvailableWeaverSlot();
            try {
                Log.i(TAG, "Weaver enroll token to slot " + slot + " for user " + userId);
                weaverEnroll(slot, null, tokenData.weaverSecret);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to enroll weaver secret when activating token", e);
                return false;
            }
            saveWeaverSlot(slot, handle, userId);
        }
        saveSecdiscardable(handle, tokenData.secdiscardableOnDisk, userId);
        createSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_TOKEN_BASED, authToken,
                tokenData.aggregatedSecret, 0L, userId);
        tokenMap.get(userId).remove(handle);
        return true;
    }

    private void createSyntheticPasswordBlob(long handle, byte type, AuthenticationToken authToken,
            byte[] applicationId, long sid, int userId) {
        final byte[] secret;
        if (type == SYNTHETIC_PASSWORD_TOKEN_BASED) {
            secret = authToken.computeP0();
        } else {
            secret = authToken.syntheticPassword.getBytes();
        }
        byte[] content = createSPBlob(getHandleName(handle), secret, applicationId, sid);
        byte[] blob = new byte[content.length + 1 + 1];
        /*
         * We can upgrade from v1 to v2 because that's just a change in the way that
         * the SP is stored. However, we can't upgrade to v3 because that is a change
         * in the way that passwords are derived from the SP.
         */
        if (authToken.mVersion == SYNTHETIC_PASSWORD_VERSION_V3) {
            blob[0] = SYNTHETIC_PASSWORD_VERSION_V3;
        } else {
            blob[0] = SYNTHETIC_PASSWORD_VERSION_V2;
        }
        blob[1] = type;
        System.arraycopy(content, 0, blob, 2, content.length);
        saveState(SP_BLOB_NAME, blob, handle, userId);
    }

    /**
     * Decrypt a synthetic password by supplying the user credential and corresponding password
     * blob handle generated previously. If the decryption is successful, initiate a GateKeeper
     * verification to referesh the SID & Auth token maintained by the system.
     * Note: the credential type is not validated here since there are call sites where the type is
     * unknown. Caller might choose to validate it by examining AuthenticationResult.credentialType
     */
    public AuthenticationResult unwrapPasswordBasedSyntheticPassword(IGateKeeperService gatekeeper,
            long handle, byte[] credential, int userId,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        if (credential == null) {
            credential = DEFAULT_PASSWORD;
        }
        AuthenticationResult result = new AuthenticationResult();
        PasswordData pwd = PasswordData.fromBytes(loadState(PASSWORD_DATA_NAME, handle, userId));
        result.credentialType = pwd.passwordType;
        byte[] pwdToken = computePasswordToken(credential, pwd);

        final byte[] applicationId;
        final long sid;
        int weaverSlot = loadWeaverSlot(handle, userId);
        if (weaverSlot != INVALID_WEAVER_SLOT) {
            // Weaver based user password
            if (!isWeaverAvailable()) {
                Log.e(TAG, "No weaver service to unwrap password based SP");
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            result.gkResponse = weaverVerify(weaverSlot, passwordTokenToWeaverKey(pwdToken));
            if (result.gkResponse.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
                return result;
            }
            sid = GateKeeper.INVALID_SECURE_USER_ID;
            applicationId = transformUnderWeaverSecret(pwdToken, result.gkResponse.getPayload());
        } else {
            byte[] gkPwdToken = passwordTokenToGkInput(pwdToken);
            GateKeeperResponse response = gatekeeper.verifyChallenge(fakeUid(userId), 0L,
                    pwd.passwordHandle, gkPwdToken);
            int responseCode = response.getResponseCode();
            if (responseCode == GateKeeperResponse.RESPONSE_OK) {
                result.gkResponse = VerifyCredentialResponse.OK;
                if (response.getShouldReEnroll()) {
                    GateKeeperResponse reenrollResponse = gatekeeper.enroll(fakeUid(userId),
                            pwd.passwordHandle, gkPwdToken, gkPwdToken);
                    if (reenrollResponse.getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                        pwd.passwordHandle = reenrollResponse.getPayload();
                        saveState(PASSWORD_DATA_NAME, pwd.toBytes(), handle, userId);
                        synchronizeFrpPassword(pwd,
                                pwd.passwordType == LockPatternUtils.CREDENTIAL_TYPE_PATTERN
                                ? DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                                : DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                                /* TODO(roosa): keep the same password quality */,
                                userId);
                    } else {
                        Log.w(TAG, "Fail to re-enroll user password for user " + userId);
                        // continue the flow anyway
                    }
                }
            } else if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
                result.gkResponse = new VerifyCredentialResponse(response.getTimeout());
                return result;
            } else  {
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            sid = sidFromPasswordHandle(pwd.passwordHandle);
            applicationId = transformUnderSecdiscardable(pwdToken,
                    loadSecdiscardable(handle, userId));
        }
        // Supplied credential passes first stage weaver/gatekeeper check so it should be correct.
        // Notify the callback so the keyguard UI can proceed immediately.
        if (progressCallback != null) {
            progressCallback.onCredentialVerified();
        }
        result.authToken = unwrapSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_PASSWORD_BASED,
                applicationId, sid, userId);

        // Perform verifyChallenge to refresh auth tokens for GK if user password exists.
        result.gkResponse = verifyChallenge(gatekeeper, result.authToken, 0L, userId);
        return result;
    }

    /**
     * Decrypt a synthetic password by supplying an escrow token and corresponding token
     * blob handle generated previously. If the decryption is successful, initiate a GateKeeper
     * verification to referesh the SID & Auth token maintained by the system.
     */
    public @NonNull AuthenticationResult unwrapTokenBasedSyntheticPassword(
            IGateKeeperService gatekeeper, long handle, byte[] token, int userId)
                    throws RemoteException {
        AuthenticationResult result = new AuthenticationResult();
        byte[] secdiscardable = loadSecdiscardable(handle, userId);
        int slotId = loadWeaverSlot(handle, userId);
        if (slotId != INVALID_WEAVER_SLOT) {
            if (!isWeaverAvailable()) {
                Log.e(TAG, "No weaver service to unwrap token based SP");
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            VerifyCredentialResponse response = weaverVerify(slotId, null);
            if (response.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK ||
                    response.getPayload() == null) {
                Log.e(TAG, "Failed to retrieve weaver secret when unwrapping token");
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            secdiscardable = SyntheticPasswordCrypto.decrypt(response.getPayload(),
                    PERSONALISATION_WEAVER_TOKEN, secdiscardable);
        }
        byte[] applicationId = transformUnderSecdiscardable(token, secdiscardable);
        result.authToken = unwrapSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_TOKEN_BASED,
                applicationId, 0L, userId);
        if (result.authToken != null) {
            result.gkResponse = verifyChallenge(gatekeeper, result.authToken, 0L, userId);
            if (result.gkResponse == null) {
                // The user currently has no password. return OK with null payload so null
                // is propagated to unlockUser()
                result.gkResponse = VerifyCredentialResponse.OK;
            }
        } else {
            result.gkResponse = VerifyCredentialResponse.ERROR;
        }
        return result;
    }

    private AuthenticationToken unwrapSyntheticPasswordBlob(long handle, byte type,
            byte[] applicationId, long sid, int userId) {
        byte[] blob = loadState(SP_BLOB_NAME, handle, userId);
        if (blob == null) {
            return null;
        }
        final byte version = blob[0];
        if (version != SYNTHETIC_PASSWORD_VERSION_V3
                && version != SYNTHETIC_PASSWORD_VERSION_V2
                && version != SYNTHETIC_PASSWORD_VERSION_V1) {
            throw new RuntimeException("Unknown blob version");
        }
        if (blob[1] != type) {
            throw new RuntimeException("Invalid blob type");
        }
        final byte[] secret;
        if (version == SYNTHETIC_PASSWORD_VERSION_V1) {
            secret = SyntheticPasswordCrypto.decryptBlobV1(getHandleName(handle),
                    Arrays.copyOfRange(blob, 2, blob.length), applicationId);
        } else {
            secret = decryptSPBlob(getHandleName(handle),
                Arrays.copyOfRange(blob, 2, blob.length), applicationId);
        }
        if (secret == null) {
            Log.e(TAG, "Fail to decrypt SP for user " + userId);
            return null;
        }
        AuthenticationToken result = new AuthenticationToken(version);
        if (type == SYNTHETIC_PASSWORD_TOKEN_BASED) {
            if (!loadEscrowData(result, userId)) {
                Log.e(TAG, "User is not escrowable: " + userId);
                return null;
            }
            result.recreate(secret);
        } else {
            result.syntheticPassword = new String(secret);
        }
        if (version == SYNTHETIC_PASSWORD_VERSION_V1) {
            Log.i(TAG, "Upgrade v1 SP blob for user " + userId + ", type = " + type);
            createSyntheticPasswordBlob(handle, type, result, applicationId, sid, userId);
        }
        return result;
    }

    /**
     * performs GK verifyChallenge and returns auth token, re-enrolling SP password handle
     * if required.
     *
     * Normally performing verifyChallenge with an AuthenticationToken should always return
     * RESPONSE_OK, since user authentication failures are detected earlier when trying to
     * decrypt SP.
     */
    public @Nullable VerifyCredentialResponse verifyChallenge(IGateKeeperService gatekeeper,
            @NonNull AuthenticationToken auth, long challenge, int userId) throws RemoteException {
        byte[] spHandle = loadSyntheticPasswordHandle(userId);
        if (spHandle == null) {
            // There is no password handle associated with the given user, i.e. the user is not
            // secured by lockscreen and has no SID, so just return here;
            return null;
        }
        VerifyCredentialResponse result;
        GateKeeperResponse response = gatekeeper.verifyChallenge(userId, challenge,
                spHandle, auth.deriveGkPassword());
        int responseCode = response.getResponseCode();
        if (responseCode == GateKeeperResponse.RESPONSE_OK) {
            result = new VerifyCredentialResponse(response.getPayload());
            if (response.getShouldReEnroll()) {
                response = gatekeeper.enroll(userId, spHandle,
                        spHandle, auth.deriveGkPassword());
                if (response.getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                    spHandle = response.getPayload();
                    saveSyntheticPasswordHandle(spHandle, userId);
                    // Call self again to re-verify with updated handle
                    return verifyChallenge(gatekeeper, auth, challenge, userId);
                } else {
                    Log.w(TAG, "Fail to re-enroll SP handle for user " + userId);
                    // Fall through, return existing handle
                }
            }
        } else if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
            result = new VerifyCredentialResponse(response.getTimeout());
        } else {
            result = VerifyCredentialResponse.ERROR;
        }
        return result;
    }

    public boolean existsHandle(long handle, int userId) {
        return hasState(SP_BLOB_NAME, handle, userId);
    }

    public void destroyTokenBasedSyntheticPassword(long handle, int userId) {
        destroySyntheticPassword(handle, userId);
        destroyState(SECDISCARDABLE_NAME, handle, userId);
    }

    public void destroyPasswordBasedSyntheticPassword(long handle, int userId) {
        destroySyntheticPassword(handle, userId);
        destroyState(SECDISCARDABLE_NAME, handle, userId);
        destroyState(PASSWORD_DATA_NAME, handle, userId);
    }

    private void destroySyntheticPassword(long handle, int userId) {
        destroyState(SP_BLOB_NAME, handle, userId);
        destroySPBlobKey(getHandleName(handle));
        if (hasState(WEAVER_SLOT_NAME, handle, userId)) {
            destroyWeaverSlot(handle, userId);
        }
    }

    private byte[] transformUnderWeaverSecret(byte[] data, byte[] secret) {
        byte[] weaverSecret = SyntheticPasswordCrypto.personalisedHash(
                PERSONALISATION_WEAVER_PASSWORD, secret);
        byte[] result = new byte[data.length + weaverSecret.length];
        System.arraycopy(data, 0, result, 0, data.length);
        System.arraycopy(weaverSecret, 0, result, data.length, weaverSecret.length);
        return result;
    }

    private byte[] transformUnderSecdiscardable(byte[] data, byte[] rawSecdiscardable) {
        byte[] secdiscardable = SyntheticPasswordCrypto.personalisedHash(
                PERSONALISATION_SECDISCARDABLE, rawSecdiscardable);
        byte[] result = new byte[data.length + secdiscardable.length];
        System.arraycopy(data, 0, result, 0, data.length);
        System.arraycopy(secdiscardable, 0, result, data.length, secdiscardable.length);
        return result;
    }

    private byte[] createSecdiscardable(long handle, int userId) {
        byte[] data = secureRandom(SECDISCARDABLE_LENGTH);
        saveSecdiscardable(handle, data, userId);
        return data;
    }

    private void saveSecdiscardable(long handle, byte[] secdiscardable, int userId) {
        saveState(SECDISCARDABLE_NAME, secdiscardable, handle, userId);
    }

    private byte[] loadSecdiscardable(long handle, int userId) {
        return loadState(SECDISCARDABLE_NAME, handle, userId);
    }

    private boolean hasState(String stateName, long handle, int userId) {
        return !ArrayUtils.isEmpty(loadState(stateName, handle, userId));
    }

    private byte[] loadState(String stateName, long handle, int userId) {
        return mStorage.readSyntheticPasswordState(userId, handle, stateName);
    }

    private void saveState(String stateName, byte[] data, long handle, int userId) {
        mStorage.writeSyntheticPasswordState(userId, handle, stateName, data);
    }

    private void destroyState(String stateName, long handle, int userId) {
        mStorage.deleteSyntheticPasswordState(userId, handle, stateName);
    }

    protected byte[] decryptSPBlob(String blobKeyName, byte[] blob, byte[] applicationId) {
        return SyntheticPasswordCrypto.decryptBlob(blobKeyName, blob, applicationId);
    }

    protected byte[] createSPBlob(String blobKeyName, byte[] data, byte[] applicationId, long sid) {
        return SyntheticPasswordCrypto.createBlob(blobKeyName, data, applicationId, sid);
    }

    protected void destroySPBlobKey(String keyAlias) {
        SyntheticPasswordCrypto.destroyBlobKey(keyAlias);
    }

    public static long generateHandle() {
        SecureRandom rng = new SecureRandom();
        long result;
        do {
            result = rng.nextLong();
        } while (result == DEFAULT_HANDLE);
        return result;
    }

    private int fakeUid(int uid) {
        return 100000 + uid;
    }

    protected static byte[] secureRandom(int length) {
        try {
            return SecureRandom.getInstance("SHA1PRNG").generateSeed(length);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getHandleName(long handle) {
        return String.format("%s%x", LockPatternUtils.SYNTHETIC_PASSWORD_KEY_PREFIX, handle);
    }

    private byte[] computePasswordToken(byte[] password, PasswordData data) {
        return scrypt(password, data.salt, 1 << data.scryptN, 1 << data.scryptR, 1 << data.scryptP,
                PASSWORD_TOKEN_LENGTH);
    }

    private byte[] passwordTokenToGkInput(byte[] token) {
        return SyntheticPasswordCrypto.personalisedHash(PERSONALIZATION_USER_GK_AUTH, token);
    }

    private byte[] passwordTokenToWeaverKey(byte[] token) {
        byte[] key = SyntheticPasswordCrypto.personalisedHash(PERSONALISATION_WEAVER_KEY, token);
        if (key.length < mWeaverConfig.keySize) {
            throw new RuntimeException("weaver key length too small");
        }
        return Arrays.copyOf(key, mWeaverConfig.keySize);
    }

    protected long sidFromPasswordHandle(byte[] handle) {
        return nativeSidFromPasswordHandle(handle);
    }

    protected byte[] scrypt(byte[] password, byte[] salt, int N, int r, int p, int outLen) {
        return nativeScrypt(password, salt, N, r, p, outLen);
    }

    native long nativeSidFromPasswordHandle(byte[] handle);
    native byte[] nativeScrypt(byte[] password, byte[] salt, int N, int r, int p, int outLen);

    protected static ArrayList<Byte> toByteArrayList(byte[] data) {
        ArrayList<Byte> result = new ArrayList<Byte>(data.length);
        for (int i = 0; i < data.length; i++) {
            result.add(data[i]);
        }
        return result;
    }

    protected static byte[] fromByteArrayList(ArrayList<Byte> data) {
        byte[] result = new byte[data.size()];
        for (int i = 0; i < data.size(); i++) {
            result[i] = data.get(i);
        }
        return result;
    }

    protected static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes();
    private static byte[] bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "null".getBytes();
        }
        byte[] hexBytes = new byte[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexBytes[j * 2] = HEX_ARRAY[v >>> 4];
            hexBytes[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return hexBytes;
    }
}
