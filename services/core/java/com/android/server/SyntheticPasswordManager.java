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
package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;

import libcore.util.HexEncoding;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
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
 *                            purpose of secure deletion.
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

    public static final long DEFAULT_HANDLE = 0;
    private static final String DEFAULT_PASSWORD = "default-password";

    private static final byte SYNTHETIC_PASSWORD_VERSION = 1;
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
    private static final byte[] PERSONALIZATION_SP_SPLIT = "sp-split".getBytes();
    private static final byte[] PERSONALIZATION_E0 = "e0-encryption".getBytes();

    static class AuthenticationResult {
        public AuthenticationToken authToken;
        public VerifyCredentialResponse gkResponse;
    }

    static class AuthenticationToken {
        /*
         * Here is the relationship between all three fields:
         * P0 and P1 are two randomly-generated blocks. P1 is stored on disk but P0 is not.
         * syntheticPassword = hash(P0 || P1)
         * E0 = P0 encrypted under syntheticPassword, stored on disk.
         */
        private @Nullable byte[] E0;
        private @Nullable byte[] P1;
        private @NonNull String syntheticPassword;

        public String deriveKeyStorePassword() {
            return bytesToHex(SyntheticPasswordCrypto.personalisedHash(
                    PERSONALIZATION_KEY_STORE_PASSWORD, syntheticPassword.getBytes()));
        }

        public byte[] deriveGkPassword() {
            return SyntheticPasswordCrypto.personalisedHash(PERSONALIZATION_SP_GK_AUTH,
                    syntheticPassword.getBytes());
        }

        public byte[] deriveDiskEncryptionKey() {
            return SyntheticPasswordCrypto.personalisedHash(PERSONALIZATION_FBE_KEY,
                    syntheticPassword.getBytes());
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
            AuthenticationToken result = new AuthenticationToken();
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
            result.passwordHandle = new byte[handleLen];
            buffer.get(result.passwordHandle);
            return result;
        }

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + 3 * Byte.BYTES
                    + Integer.BYTES + salt.length + Integer.BYTES + passwordHandle.length);
            buffer.putInt(passwordType);
            buffer.put(scryptN);
            buffer.put(scryptR);
            buffer.put(scryptP);
            buffer.putInt(salt.length);
            buffer.put(salt);
            buffer.putInt(passwordHandle.length);
            buffer.put(passwordHandle);
            return buffer.array();
        }
    }

    private LockSettingsStorage mStorage;

    public SyntheticPasswordManager(LockSettingsStorage storage) {
        mStorage = storage;
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
            byte[] hash, String credential, int userId) throws RemoteException {
        AuthenticationToken result = AuthenticationToken.create();
        GateKeeperResponse response;
        if (hash != null) {
            response = gatekeeper.enroll(userId, hash, credential.getBytes(),
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
            String credential, int credentialType, AuthenticationToken authToken, int userId)
                    throws RemoteException {
        if (credential == null || credentialType == LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            credentialType = LockPatternUtils.CREDENTIAL_TYPE_NONE;
            credential = DEFAULT_PASSWORD;
        }

        long handle = generateHandle();
        PasswordData pwd = PasswordData.create(credentialType);
        byte[] pwdToken = computePasswordToken(credential, pwd);

        // In case GK enrollment leaves persistent state around (in RPMB), this will nuke them
        // to prevent them from accumulating and causing problems.
        gatekeeper.clearSecureUserId(fakeUid(userId));
        GateKeeperResponse response = gatekeeper.enroll(fakeUid(userId), null, null,
                passwordTokenToGkInput(pwdToken));
        if (response.getResponseCode() != GateKeeperResponse.RESPONSE_OK) {
            Log.e(TAG, "Fail to enroll user password when creating SP for user " + userId);
            return DEFAULT_HANDLE;
        }
        pwd.passwordHandle = response.getPayload();
        long sid = sidFromPasswordHandle(pwd.passwordHandle);
        saveState(PASSWORD_DATA_NAME, pwd.toBytes(), handle, userId);

        byte[] applicationId = transformUnderSecdiscardable(pwdToken,
                createSecdiscardable(handle, userId));
        createSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_PASSWORD_BASED, authToken,
                applicationId, sid, userId);
        return handle;
    }

    private ArrayMap<Integer, ArrayMap<Long, byte[]>> tokenMap = new ArrayMap<>();

    public long createTokenBasedSyntheticPassword(byte[] token, int userId) {
        long handle = generateHandle();
        byte[] applicationId = transformUnderSecdiscardable(token,
                createSecdiscardable(handle, userId));
        if (!tokenMap.containsKey(userId)) {
            tokenMap.put(userId, new ArrayMap<>());
        }
        tokenMap.get(userId).put(handle, applicationId);
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
        byte[] applicationId = tokenMap.get(userId).get(handle);
        if (applicationId == null) {
            return false;
        }
        if (!loadEscrowData(authToken, userId)) {
            Log.w(TAG, "User is not escrowable");
            return false;
        }
        createSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_TOKEN_BASED, authToken,
                applicationId, 0L, userId);
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
        blob[0] = SYNTHETIC_PASSWORD_VERSION;
        blob[1] = type;
        System.arraycopy(content, 0, blob, 2, content.length);
        saveState(SP_BLOB_NAME, blob, handle, userId);
    }

    /**
     * Decrypt a synthetic password by supplying the user credential and corresponding password
     * blob handle generated previously. If the decryption is successful, initiate a GateKeeper
     * verification to referesh the SID & Auth token maintained by the system.
     */
    public AuthenticationResult unwrapPasswordBasedSyntheticPassword(IGateKeeperService gatekeeper,
            long handle, String credential, int userId) throws RemoteException {
        if (credential == null) {
            credential = DEFAULT_PASSWORD;
        }
        AuthenticationResult result = new AuthenticationResult();
        PasswordData pwd = PasswordData.fromBytes(loadState(PASSWORD_DATA_NAME, handle, userId));
        byte[] pwdToken = computePasswordToken(credential, pwd);
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


        byte[] applicationId = transformUnderSecdiscardable(pwdToken,
                loadSecdiscardable(handle, userId));
        result.authToken = unwrapSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_PASSWORD_BASED,
                applicationId, userId);

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
        byte[] applicationId = transformUnderSecdiscardable(token,
                loadSecdiscardable(handle, userId));
        result.authToken = unwrapSyntheticPasswordBlob(handle, SYNTHETIC_PASSWORD_TOKEN_BASED,
                applicationId, userId);
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
            byte[] applicationId, int userId) {
        byte[] blob = loadState(SP_BLOB_NAME, handle, userId);
        if (blob == null) {
            return null;
        }
        if (blob[0] != SYNTHETIC_PASSWORD_VERSION) {
            throw new RuntimeException("Unknown blob version");
        }
        if (blob[1] != type) {
            throw new RuntimeException("Invalid blob type");
        }
        byte[] secret = decryptSPBlob(getHandleName(handle),
                Arrays.copyOfRange(blob, 2, blob.length), applicationId);
        if (secret == null) {
            Log.e(TAG, "Fail to decrypt SP for user " + userId);
            return null;
        }
        AuthenticationToken result = new AuthenticationToken();
        if (type == SYNTHETIC_PASSWORD_TOKEN_BASED) {
            if (!loadEscrowData(result, userId)) {
                Log.e(TAG, "User is not escrowable: " + userId);
                return null;
            }
            result.recreate(secret);
        } else {
            result.syntheticPassword = new String(secret);
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
        saveState(SECDISCARDABLE_NAME, data, handle, userId);
        return data;
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

    private byte[] computePasswordToken(String password, PasswordData data) {
        return scrypt(password, data.salt, 1 << data.scryptN, 1 << data.scryptR, 1 << data.scryptP,
                PASSWORD_TOKEN_LENGTH);
    }

    private byte[] passwordTokenToGkInput(byte[] token) {
        return SyntheticPasswordCrypto.personalisedHash(PERSONALIZATION_USER_GK_AUTH, token);
    }

    protected long sidFromPasswordHandle(byte[] handle) {
        return nativeSidFromPasswordHandle(handle);
    }

    protected byte[] scrypt(String password, byte[] salt, int N, int r, int p, int outLen) {
        return nativeScrypt(password.getBytes(), salt, N, r, p, outLen);
    }

    native long nativeSidFromPasswordHandle(byte[] handle);
    native byte[] nativeScrypt(byte[] password, byte[] salt, int N, int r, int p, int outLen);

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
