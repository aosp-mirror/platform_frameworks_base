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

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD_OR_PIN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.EscrowTokenStateChangeCallback;
import static com.android.internal.widget.LockPatternUtils.PIN_LENGTH_UNAVAILABLE;
import static com.android.internal.widget.LockPatternUtils.USER_FRP;
import static com.android.internal.widget.LockPatternUtils.USER_REPAIR_MODE;
import static com.android.internal.widget.LockPatternUtils.pinOrPasswordQualityToCredentialType;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.PasswordMetrics;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.weaver.IWeaver;
import android.hardware.weaver.WeaverConfig;
import android.hardware.weaver.WeaverReadResponse;
import android.hardware.weaver.WeaverReadStatus;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.UserManager;
import android.provider.Settings;
import android.security.GateKeeper;
import android.security.Scrypt;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.IWeakEscrowTokenRemovedListener;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;
import com.android.server.utils.Slogf;

import libcore.util.HexEncoding;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * A class that manages a user's synthetic password (SP) ({@link #SyntheticPassword}), along with a
 * set of SP protectors that are independent ways that the SP is protected.
 *
 * Invariants for SPs:
 *
 *  - A user's SP never changes, but SP protectors can be added and removed.  There is always a
 *    protector that protects the SP with the user's Lock Screen Knowledge Factor (LSKF), a.k.a.
 *    LockscreenCredential.  The LSKF may be empty (none).  There may be escrow token-based
 *    protectors as well, only for specific use cases such as enterprise-managed users.
 *
 *  - The user's credential-encrypted storage is always protected by the SP.
 *
 *  - The user's Keystore superencryption keys are always protected by the SP.  These in turn
 *    protect the Keystore keys that require user authentication, an unlocked device, or both.
 *
 *  - A secret derived from the synthetic password is enrolled in Gatekeeper for the user, but only
 *    while the user has a (nonempty) LSKF.  This enrollment has an associated ID called the Secure
 *    user ID or SID.  This use of Gatekeeper, which is separate from the use of GateKeeper that may
 *    be used in the LSKF-based protector, makes it so that unlocking the synthetic password
 *    generates a HardwareAuthToken (but only when the user has LSKF).  That HardwareAuthToken can
 *    be provided to KeyMint to authorize the use of the user's authentication-bound Keystore keys.
 *
 * Files stored on disk for each user:
 *   For the SP itself, stored under NULL_PROTECTOR_ID:
 *     SP_HANDLE_NAME: GateKeeper password handle of a password derived from the SP.  Only exists
 *                     while the LSKF is nonempty.
 *     SP_E0_NAME, SP_P1_NAME: Information needed to create and use escrow token-based protectors.
 *                             Deleted when escrow token support is disabled for the user.
 *     VENDOR_AUTH_SECRET_NAME: A copy of the secret passed using the IAuthSecret interface,
 *                              encrypted using a secret derived from the SP using
 *                              PERSONALIZATION_AUTHSECRET_ENCRYPTION_KEY.
 *
 *     For each protector, stored under the corresponding protector ID:
 *       SP_BLOB_NAME: The encrypted SP secret (the SP itself or the P0 value).  Always exists.
 *       PASSWORD_DATA_NAME: Data used for LSKF verification, such as the scrypt salt and
 *                           parameters.  Only exists for LSKF-based protectors.  Doesn't exist when
 *                           the LSKF is empty, except in old protectors.
 *       PASSWORD_METRICS_NAME: Metrics about the LSKF, encrypted by a key derived from the SP.
 *                              Only exists for LSKF-based protectors.  Doesn't exist when the LSKF
 *                              is empty, except in old protectors.
 *       SECDISCARDABLE_NAME: A large number of random bytes that all need to be known in order to
 *                            decrypt SP_BLOB_NAME.  When the protector is deleted, this file is
 *                            overwritten and deleted as a "best-effort" attempt to support secure
 *                            deletion when hardware support for secure deletion is unavailable.
 *                            Doesn't exist for LSKF-based protectors that use Weaver.
 *       WEAVER_SLOT: Contains the Weaver slot number used by this protector.  Only exists if the
 *                    protector uses Weaver.
 */
class SyntheticPasswordManager {
    private static final String SP_BLOB_NAME = "spblob";
    private static final String SP_E0_NAME = "e0";
    private static final String SP_P1_NAME = "p1";
    private static final String SP_HANDLE_NAME = "handle";
    private static final String SECDISCARDABLE_NAME = "secdis";
    private static final int SECDISCARDABLE_LENGTH = 16 * 1024;
    private static final String PASSWORD_DATA_NAME = "pwd";
    private static final String WEAVER_SLOT_NAME = "weaver";
    private static final String PASSWORD_METRICS_NAME = "metrics";
    private static final String VENDOR_AUTH_SECRET_NAME = "vendor_auth_secret";

    // used for files associated with the SP itself, not with a particular protector
    public static final long NULL_PROTECTOR_ID = 0L;

    private static final byte[] DEFAULT_PASSWORD = "default-password".getBytes();

    private static final byte WEAVER_VERSION = 1;
    private static final int INVALID_WEAVER_SLOT = -1;

    // Careful: the SYNTHETIC_PASSWORD_* version numbers are overloaded to identify both the version
    // of the protector and the version of the synthetic password itself.  All a user's protectors
    // must use a version that treats the synthetic password itself in a compatible way.
    private static final byte SYNTHETIC_PASSWORD_VERSION_V1 = 1;
    private static final byte SYNTHETIC_PASSWORD_VERSION_V2 = 2;
    private static final byte SYNTHETIC_PASSWORD_VERSION_V3 = 3;

    private static final byte PROTECTOR_TYPE_LSKF_BASED = 0;
    private static final byte PROTECTOR_TYPE_STRONG_TOKEN_BASED = 1;
    private static final byte PROTECTOR_TYPE_WEAK_TOKEN_BASED = 2;

    private static final String PROTECTOR_KEY_ALIAS_PREFIX = "synthetic_password_";

    // The security strength of the synthetic password, in bytes
    private static final int SYNTHETIC_PASSWORD_SECURITY_STRENGTH = 256 / 8;

    private static final int PASSWORD_SCRYPT_LOG_N = 11;
    private static final int PASSWORD_SCRYPT_LOG_R = 3;
    private static final int PASSWORD_SCRYPT_LOG_P = 1;
    private static final int PASSWORD_SALT_LENGTH = 16;
    private static final int STRETCHED_LSKF_LENGTH = 32;
    private static final String TAG = "SyntheticPasswordManager";

    private static final byte[] PERSONALIZATION_SECDISCARDABLE = "secdiscardable-transform".getBytes();
    private static final byte[] PERSONALIZATION_KEY_STORE_PASSWORD = "keystore-password".getBytes();
    private static final byte[] PERSONALIZATION_USER_GK_AUTH = "user-gk-authentication".getBytes();
    private static final byte[] PERSONALIZATION_SP_GK_AUTH = "sp-gk-authentication".getBytes();
    private static final byte[] PERSONALIZATION_FBE_KEY = "fbe-key".getBytes();
    private static final byte[] PERSONALIZATION_AUTHSECRET_KEY = "authsecret-hal".getBytes();
    private static final byte[] PERSONALIZATION_AUTHSECRET_ENCRYPTION_KEY =
            "vendor-authsecret-encryption-key".getBytes();
    private static final byte[] PERSONALIZATION_SP_SPLIT = "sp-split".getBytes();
    private static final byte[] PERSONALIZATION_PASSWORD_HASH = "pw-hash".getBytes();
    private static final byte[] PERSONALIZATION_E0 = "e0-encryption".getBytes();
    private static final byte[] PERSONALIZATION_WEAVER_PASSWORD = "weaver-pwd".getBytes();
    private static final byte[] PERSONALIZATION_WEAVER_KEY = "weaver-key".getBytes();
    private static final byte[] PERSONALIZATION_WEAVER_TOKEN = "weaver-token".getBytes();
    private static final byte[] PERSONALIZATION_PASSWORD_METRICS = "password-metrics".getBytes();
    private static final byte[] PERSONALIZATION_CONTEXT =
        "android-synthetic-password-personalization-context".getBytes();

    static class AuthenticationResult {
        // Non-null if password/token passes verification, null otherwise
        @Nullable public SyntheticPassword syntheticPassword;
        // OK:    password / token passes verification, user has a lockscreen
        // null:  user does not have a lockscreen (but password / token passes verification)
        // ERROR: password / token fails verification
        // RETRY: password / token verification is throttled at the moment.
        @Nullable public VerifyCredentialResponse gkResponse;
    }

    /**
     * A synthetic password (SP) is the main cryptographic secret for a user.  The SP is used only
     * as input to a Key Derivation Function (KDF) to derive other keys.
     *
     * SPs are created by {@link SyntheticPassword#create()} as the hash of two random values P0 and
     * P1.  E0 (P0 encrypted by an SP-derived key) and P1 can then be stored on-disk.  This approach
     * is used instead of direct random generation of the SP so that escrow token-based protectors
     * can protect P0 instead of the SP itself.  This makes it possible to cryptographically disable
     * the ability to create and use such protectors by deleting (or never storing) E0 and P1.
     *
     * When protecting the SP directly, use {@link SyntheticPassword#getSyntheticPassword()} to get
     * the raw SP, and later {@link SyntheticPassword#recreateDirectly(byte[])} to re-create the SP.
     * When protecting P0, use {@link SyntheticPassword#getEscrowSecret()} to get P0, and later
     * {@link SyntheticPassword#setEscrowData(byte[], byte[])} followed by
     * {@link SyntheticPassword#recreateFromEscrow()} to re-create the SP.
     */
    static class SyntheticPassword {
        private final byte mVersion;
        /**
         * Here is the relationship between these fields:
         * Generate two random block P0 and P1. P1 is recorded in mEscrowSplit1 but P0 is not.
         * mSyntheticPassword = hash(P0 || P1)
         * E0 = P0 encrypted under syntheticPassword, recorded in mEncryptedEscrowSplit0.
         */
        private @NonNull byte[] mSyntheticPassword;
        private @Nullable byte[] mEncryptedEscrowSplit0;
        private @Nullable byte[] mEscrowSplit1;

        SyntheticPassword(byte version) {
            mVersion = version;
        }

        /**
         * Derives a subkey from the synthetic password. For v3 and later synthetic passwords the
         * subkeys are 256-bit; for v1 and v2 they are 512-bit.
         */
        private byte[] deriveSubkey(byte[] personalization) {
            if (mVersion == SYNTHETIC_PASSWORD_VERSION_V3) {
                return (new SP800Derive(mSyntheticPassword))
                    .withContext(personalization, PERSONALIZATION_CONTEXT);
            } else {
                return SyntheticPasswordCrypto.personalizedHash(personalization,
                        mSyntheticPassword);
            }
        }

        public byte[] deriveKeyStorePassword() {
            return bytesToHex(deriveSubkey(PERSONALIZATION_KEY_STORE_PASSWORD));
        }

        public byte[] deriveGkPassword() {
            return deriveSubkey(PERSONALIZATION_SP_GK_AUTH);
        }

        public byte[] deriveFileBasedEncryptionKey() {
            return deriveSubkey(PERSONALIZATION_FBE_KEY);
        }

        public byte[] deriveVendorAuthSecret() {
            return deriveSubkey(PERSONALIZATION_AUTHSECRET_KEY);
        }

        public byte[] derivePasswordHashFactor() {
            return deriveSubkey(PERSONALIZATION_PASSWORD_HASH);
        }

        /** Derives key used to encrypt password metrics */
        public byte[] deriveMetricsKey() {
            return deriveSubkey(PERSONALIZATION_PASSWORD_METRICS);
        }

        public byte[] deriveVendorAuthSecretEncryptionKey() {
            return deriveSubkey(PERSONALIZATION_AUTHSECRET_ENCRYPTION_KEY);
        }

        /**
         * Assigns escrow data to this synthetic password. This is a prerequisite to call
         * {@link SyntheticPassword#recreateFromEscrow}.
         */
        public void setEscrowData(@Nullable byte[] encryptedEscrowSplit0,
                @Nullable byte[] escrowSplit1) {
            mEncryptedEscrowSplit0 = encryptedEscrowSplit0;
            mEscrowSplit1 = escrowSplit1;
        }

        /**
         * Re-creates a synthetic password from the escrow secret (escrowSplit0, returned from
         * {@link SyntheticPassword#getEscrowSecret}). Escrow data needs to be loaded
         * by {@link #setEscrowData} before calling this.
         */
        public void recreateFromEscrow(byte[] escrowSplit0) {
            Objects.requireNonNull(mEscrowSplit1);
            Objects.requireNonNull(mEncryptedEscrowSplit0);
            recreate(escrowSplit0, mEscrowSplit1);
        }

        /**
         * Re-creates a synthetic password from its raw bytes.
         */
        public void recreateDirectly(byte[] syntheticPassword) {
            this.mSyntheticPassword = Arrays.copyOf(syntheticPassword, syntheticPassword.length);
        }

        /**
         * Generates a new random synthetic password with escrow data.
         */
        static SyntheticPassword create() {
            SyntheticPassword result = new SyntheticPassword(SYNTHETIC_PASSWORD_VERSION_V3);
            byte[] escrowSplit0 =
                    SecureRandomUtils.randomBytes(SYNTHETIC_PASSWORD_SECURITY_STRENGTH);
            byte[] escrowSplit1 =
                    SecureRandomUtils.randomBytes(SYNTHETIC_PASSWORD_SECURITY_STRENGTH);
            result.recreate(escrowSplit0, escrowSplit1);
            byte[] encrypteEscrowSplit0 = SyntheticPasswordCrypto.encrypt(result.mSyntheticPassword,
                    PERSONALIZATION_E0, escrowSplit0);
            result.setEscrowData(encrypteEscrowSplit0,  escrowSplit1);
            return result;
        }

        /**
         * Re-creates synthetic password from both escrow splits. See javadoc for
         * SyntheticPassword.mSyntheticPassword for details on what each block means.
         */
        private void recreate(byte[] escrowSplit0, byte[] escrowSplit1) {
            mSyntheticPassword = bytesToHex(SyntheticPasswordCrypto.personalizedHash(
                    PERSONALIZATION_SP_SPLIT, escrowSplit0, escrowSplit1));
        }

        /**
         * Returns the escrow secret that can be used later to reconstruct this synthetic password
         * from {@link #recreateFromEscrow(byte[])}. Only possible if escrow is not disabled
         * (encryptedEscrowSplit0 known).
         */
        public byte[] getEscrowSecret() {
            if (mEncryptedEscrowSplit0 == null) {
                return null;
            }
            return SyntheticPasswordCrypto.decrypt(mSyntheticPassword, PERSONALIZATION_E0,
                    mEncryptedEscrowSplit0);
        }

        /**
         * Returns the raw synthetic password, for later use with {@link #recreateDirectly(byte[])}.
         */
        public byte[] getSyntheticPassword() {
            return mSyntheticPassword;
        }

        /**
         * Returns the version number of this synthetic password.  This version number determines
         * the algorithm used to derive subkeys.
         */
        public byte getVersion() {
            return mVersion;
        }
    }

    static class PasswordData {
        byte scryptLogN;
        byte scryptLogR;
        byte scryptLogP;
        public int credentialType;
        byte[] salt;
        // When Weaver is unavailable, this is the Gatekeeper password handle that resulted from
        // enrolling the stretched LSKF.
        public byte[] passwordHandle;
        /**
         * Pin length field, only stored in version 2 of the password data and when auto confirm
         * flag is enabled, otherwise this field contains PIN_LENGTH_UNAVAILABLE
         */
        public int pinLength;

        public static PasswordData create(int credentialType, int pinLength) {
            PasswordData result = new PasswordData();
            result.scryptLogN = PASSWORD_SCRYPT_LOG_N;
            result.scryptLogR = PASSWORD_SCRYPT_LOG_R;
            result.scryptLogP = PASSWORD_SCRYPT_LOG_P;
            result.credentialType = credentialType;
            result.pinLength = pinLength;
            result.salt = SecureRandomUtils.randomBytes(PASSWORD_SALT_LENGTH);
            return result;
        }

        /**
         * Returns true if the given serialized PasswordData begins with the value 2 as a short.
         * This detects the "bad" (non-forwards-compatible) PasswordData format that was temporarily
         * used during development of Android 14.  For more details, see fromBytes() below.
         */
        public static boolean isBadFormatFromAndroid14Beta(byte[] data) {
            return data != null && data.length >= 2 && data[0] == 0 && data[1] == 2;
        }

        public static PasswordData fromBytes(byte[] data) {
            PasswordData result = new PasswordData();
            ByteBuffer buffer = ByteBuffer.allocate(data.length);
            buffer.put(data, 0, data.length);
            buffer.flip();

            /*
             * The serialized PasswordData is supposed to begin with credentialType as an int.
             * However, all credentialType values fit in a short and the byte order is big endian,
             * so the first two bytes don't convey any non-redundant information.  For this reason,
             * temporarily during development of Android 14, the first two bytes were "stolen" from
             * credentialType to use for a data format version number.
             *
             * However, this change was reverted as it was a non-forwards-compatible change.  (See
             * toBytes() for why this data format needs to be forwards-compatible.)  Therefore,
             * recover from this misstep by ignoring the first two bytes.
             */
            result.credentialType = (short) buffer.getInt();
            result.scryptLogN = buffer.get();
            result.scryptLogR = buffer.get();
            result.scryptLogP = buffer.get();
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
            if (buffer.remaining() >= Integer.BYTES) {
                result.pinLength = buffer.getInt();
            } else {
                result.pinLength = PIN_LENGTH_UNAVAILABLE;
            }
            return result;
        }

        /**
         * Serializes this PasswordData into a byte array.
         * <p>
         * Careful: all changes to the format of the serialized PasswordData must be forwards
         * compatible.  I.e., older versions of Android must still accept the latest PasswordData.
         * This is because a serialized PasswordData is stored in the Factory Reset Protection (FRP)
         * persistent data block.  It's possible that a device has FRP set up on a newer version of
         * Android, is factory reset, and then is set up with an older version of Android.
         */
        public byte[] toBytes() {

            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + 3 * Byte.BYTES
                    + Integer.BYTES + salt.length + Integer.BYTES +
                    (passwordHandle != null ? passwordHandle.length : 0) + Integer.BYTES);
            // credentialType must fit in a short.  For an explanation, see fromBytes().
            if (credentialType < Short.MIN_VALUE || credentialType > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Unknown credential type: " + credentialType);
            }
            buffer.putInt(credentialType);
            buffer.put(scryptLogN);
            buffer.put(scryptLogR);
            buffer.put(scryptLogP);
            buffer.putInt(salt.length);
            buffer.put(salt);
            if (passwordHandle != null && passwordHandle.length > 0) {
                buffer.putInt(passwordHandle.length);
                buffer.put(passwordHandle);
            } else {
                buffer.putInt(0);
            }
            buffer.putInt(pinLength);
            return buffer.array();
        }
    }

    private static class SyntheticPasswordBlob {
        byte mVersion;
        byte mProtectorType;
        byte[] mContent;

        public static SyntheticPasswordBlob create(byte version, byte protectorType,
                byte[] content) {
            SyntheticPasswordBlob result = new SyntheticPasswordBlob();
            result.mVersion = version;
            result.mProtectorType = protectorType;
            result.mContent = content;
            return result;
        }

        public static SyntheticPasswordBlob fromBytes(byte[] data) {
            SyntheticPasswordBlob result = new SyntheticPasswordBlob();
            result.mVersion = data[0];
            result.mProtectorType = data[1];
            result.mContent = Arrays.copyOfRange(data, 2, data.length);
            return result;
        }

        public byte[] toByte() {
            byte[] blob = new byte[mContent.length + 1 + 1];
            blob[0] = mVersion;
            blob[1] = mProtectorType;
            System.arraycopy(mContent, 0, blob, 2, mContent.length);
            return blob;
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TOKEN_TYPE_STRONG, TOKEN_TYPE_WEAK})
    @interface TokenType {}
    static final int TOKEN_TYPE_STRONG = 0;
    static final int TOKEN_TYPE_WEAK = 1;

    private static class TokenData {
        byte[] secdiscardableOnDisk;
        byte[] weaverSecret;
        byte[] aggregatedSecret;
        @TokenType int mType;
        EscrowTokenStateChangeCallback mCallback;
    }

    private final Context mContext;
    private LockSettingsStorage mStorage;
    private volatile IWeaver mWeaver;
    private WeaverConfig mWeaverConfig;
    private PasswordSlotManager mPasswordSlotManager;

    private final UserManager mUserManager;

    private final RemoteCallbackList<IWeakEscrowTokenRemovedListener> mListeners =
            new RemoteCallbackList<>();

    public SyntheticPasswordManager(Context context, LockSettingsStorage storage,
            UserManager userManager, PasswordSlotManager passwordSlotManager) {
        mContext = context;
        mStorage = storage;
        mUserManager = userManager;
        mPasswordSlotManager = passwordSlotManager;
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    @VisibleForTesting
    protected android.hardware.weaver.V1_0.IWeaver getWeaverHidlService() throws RemoteException {
        try {
            return android.hardware.weaver.V1_0.IWeaver.getService(/* retry */ true);
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private class WeaverDiedRecipient implements IBinder.DeathRecipient {
        // Not synchronized on the outer class, since setting the pointer to null is atomic, and we
        // don't want to have to worry about any sort of deadlock here.
        @Override
        public void binderDied() {
            // Weaver died.  Try to recover by setting mWeaver to null, which makes
            // getWeaverService() look up the service again.  This is done only as a simple
            // robustness measure; it should not be relied on.  If this triggers, the root cause is
            // almost certainly a bug in the device's Weaver implementation, which must be fixed.
            Slog.wtf(TAG, "Weaver service has died");
            mWeaver.asBinder().unlinkToDeath(this, 0);
            mWeaver = null;
        }
    }

    private @Nullable IWeaver getWeaverAidlService() {
        final IWeaver aidlWeaver;
        try {
            aidlWeaver =
                    IWeaver.Stub.asInterface(
                            ServiceManager.waitForDeclaredService(IWeaver.DESCRIPTOR + "/default"));
        } catch (SecurityException e) {
            Slog.w(TAG, "Does not have permissions to get AIDL weaver service");
            return null;
        }
        if (aidlWeaver == null) {
            return null;
        }
        final int aidlVersion;
        try {
            aidlVersion = aidlWeaver.getInterfaceVersion();
        } catch (RemoteException e) {
            Slog.e(TAG, "Cannot get AIDL weaver service version", e);
            return null;
        }
        if (aidlVersion < 2) {
            Slog.w(TAG,
                    "Ignoring AIDL weaver service v"
                            + aidlVersion
                            + " because only v2 and later are supported");
            return null;
        }
        Slog.i(TAG, "Found AIDL weaver service v" + aidlVersion);
        return aidlWeaver;
    }

    private @Nullable IWeaver getWeaverServiceInternal() {
        // Try to get the AIDL service first
        IWeaver aidlWeaver = getWeaverAidlService();
        if (aidlWeaver != null) {
            Slog.i(TAG, "Using AIDL weaver service");
            try {
                aidlWeaver.asBinder().linkToDeath(new WeaverDiedRecipient(), 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to register Weaver death recipient", e);
            }
            return aidlWeaver;
        }

        // If the AIDL service can't be found, look for the HIDL service
        try {
            android.hardware.weaver.V1_0.IWeaver hidlWeaver = getWeaverHidlService();
            if (hidlWeaver != null) {
                Slog.i(TAG, "Using HIDL weaver service");
                return new WeaverHidlAdapter(hidlWeaver);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to get HIDL weaver service.", e);
        }
        Slog.w(TAG, "Device does not support weaver");
        return null;
    }

    @VisibleForTesting
    public boolean isAutoPinConfirmationFeatureAvailable() {
        return LockPatternUtils.isAutoPinConfirmFeatureAvailable();
    }

    /**
     * Returns a handle to the Weaver service, or null if Weaver is unavailable.  Note that not all
     * devices support Weaver.
     */
    private synchronized @Nullable IWeaver getWeaverService() {
        IWeaver weaver = mWeaver;
        if (weaver != null) {
            return weaver;
        }

        // Re-initialize weaver in case there was a transient error preventing access to it.
        weaver = getWeaverServiceInternal();
        if (weaver == null) {
            return null;
        }

        final WeaverConfig weaverConfig;
        try {
            weaverConfig = weaver.getConfig();
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Failed to get weaver config", e);
            return null;
        }
        if (weaverConfig == null || weaverConfig.slots <= 0) {
            Slog.e(TAG, "Invalid weaver config");
            return null;
        }

        mWeaver = weaver;
        mWeaverConfig = weaverConfig;
        mPasswordSlotManager.refreshActiveSlots(getUsedWeaverSlots());
        Slog.i(TAG, "Weaver service initialized");
        return weaver;
    }

    /**
     * Enroll the given key value pair into the specified weaver slot. if the given key is null,
     * a default all-zero key is used. If the value is not specified, a fresh random secret is
     * generated as the value.
     *
     * @return the value stored in the weaver slot, or null if the operation fails
     */
    private byte[] weaverEnroll(IWeaver weaver, int slot, byte[] key, @Nullable byte[] value) {
        if (slot == INVALID_WEAVER_SLOT || slot >= mWeaverConfig.slots) {
            throw new IllegalArgumentException("Invalid slot for weaver");
        }
        if (key == null) {
            key = new byte[mWeaverConfig.keySize];
        } else if (key.length != mWeaverConfig.keySize) {
            throw new IllegalArgumentException("Invalid key size for weaver");
        }
        if (value == null) {
            value = SecureRandomUtils.randomBytes(mWeaverConfig.valueSize);
        }
        try {
            weaver.write(slot, key, value);
        } catch (RemoteException e) {
            Slog.e(TAG, "weaver write binder call failed, slot: " + slot, e);
            return null;
        } catch (ServiceSpecificException e) {
            Slog.e(TAG, "weaver write failed, slot: " + slot, e);
            return null;
        }
        return value;
    }

    /**
     * Create a VerifyCredentialResponse from a timeout base on the WeaverReadResponse.
     * This checks the received timeout(long) to make sure it sure it fits in an int before
     * using it. If it doesn't fit, we use Integer.MAX_VALUE.
     */
    private static VerifyCredentialResponse responseFromTimeout(WeaverReadResponse response) {
        int timeout =
                response.timeout > Integer.MAX_VALUE || response.timeout < 0
                ? Integer.MAX_VALUE
                : (int) response.timeout;
        return VerifyCredentialResponse.fromTimeout(timeout);
    }

    /**
     * Verify the supplied key against a weaver slot, returning a response indicating whether
     * the verification is successful, throttled or failed. If successful, the bound secret
     * is also returned.
     */
    private VerifyCredentialResponse weaverVerify(IWeaver weaver, int slot, byte[] key) {
        if (slot == INVALID_WEAVER_SLOT || slot >= mWeaverConfig.slots) {
            throw new IllegalArgumentException("Invalid slot for weaver");
        }
        if (key == null) {
            key = new byte[mWeaverConfig.keySize];
        } else if (key.length != mWeaverConfig.keySize) {
            throw new IllegalArgumentException("Invalid key size for weaver");
        }
        final WeaverReadResponse readResponse;
        try {
            readResponse = weaver.read(slot, key);
        } catch (RemoteException e) {
            Slog.e(TAG, "weaver read failed, slot: " + slot, e);
            return VerifyCredentialResponse.ERROR;
        }

        switch (readResponse.status) {
            case WeaverReadStatus.OK:
                return new VerifyCredentialResponse.Builder()
                                      .setGatekeeperHAT(readResponse.value)
                                      .build();
            case WeaverReadStatus.THROTTLE:
                Slog.e(TAG, "weaver read failed (THROTTLE), slot: " + slot);
                return responseFromTimeout(readResponse);
            case WeaverReadStatus.INCORRECT_KEY:
                if (readResponse.timeout == 0) {
                    Slog.e(TAG, "weaver read failed (INCORRECT_KEY), slot: " + slot);
                    return VerifyCredentialResponse.ERROR;
                } else {
                    Slog.e(TAG, "weaver read failed (INCORRECT_KEY/THROTTLE), slot: " + slot);
                    return responseFromTimeout(readResponse);
                }
            case WeaverReadStatus.FAILED:
                Slog.e(TAG, "weaver read failed (FAILED), slot: " + slot);
                return VerifyCredentialResponse.ERROR;
            default:
                Slog.e(TAG,
                        "weaver read unknown status " + readResponse.status
                                + ", slot: " + slot);
                return VerifyCredentialResponse.ERROR;
        }
    }

    public void removeUser(IGateKeeperService gatekeeper, int userId) {
        for (long protectorId : mStorage.listSyntheticPasswordProtectorsForUser(SP_BLOB_NAME,
                    userId)) {
            destroyWeaverSlot(protectorId, userId);
            destroyProtectorKey(getProtectorKeyAlias(protectorId));
        }
        // Remove potential persistent state (in RPMB), to prevent them from accumulating and
        // causing problems.
        try {
            gatekeeper.clearSecureUserId(fakeUserId(userId));
        } catch (RemoteException ignore) {
            Slog.w(TAG, "Failed to clear SID from gatekeeper");
        }
    }

    int getPinLength(long protectorId, int userId) {
        byte[] passwordData = loadState(PASSWORD_DATA_NAME, protectorId, userId);
        if (passwordData == null) {
            return LockPatternUtils.PIN_LENGTH_UNAVAILABLE;
        }
        return PasswordData.fromBytes(passwordData).pinLength;
    }

    int getCredentialType(long protectorId, int userId) {
        byte[] passwordData = loadState(PASSWORD_DATA_NAME, protectorId, userId);
        if (passwordData == null) {
            return LockPatternUtils.CREDENTIAL_TYPE_NONE;
        }
        return PasswordData.fromBytes(passwordData).credentialType;
    }

    int getSpecialUserCredentialType(int userId) {
        final PersistentData data = getSpecialUserPersistentData(userId);
        if (data.type != PersistentData.TYPE_SP_GATEKEEPER
                && data.type != PersistentData.TYPE_SP_WEAVER) {
            return CREDENTIAL_TYPE_NONE;
        }
        if (data.payload == null) {
            return LockPatternUtils.CREDENTIAL_TYPE_NONE;
        }
        final int credentialType = PasswordData.fromBytes(data.payload).credentialType;
        if (credentialType != CREDENTIAL_TYPE_PASSWORD_OR_PIN) {
            return credentialType;
        }
        return pinOrPasswordQualityToCredentialType(data.qualityForUi);
    }

    private PersistentData getSpecialUserPersistentData(int userId) {
        if (userId == USER_FRP) {
            return mStorage.readPersistentDataBlock();
        }
        if (userId == USER_REPAIR_MODE) {
            return mStorage.readRepairModePersistentData();
        }
        throw new IllegalArgumentException("Unknown special user id " + userId);
    }

    /**
     * Creates a new synthetic password (SP) for the given user.
     * <p>
     * Any existing SID for the user is cleared.
     * <p>
     * Also saves the escrow information necessary to re-generate the synthetic password under
     * an escrow scheme. This information can be removed with {@link #destroyEscrowData} if
     * password escrow should be disabled completely on the given user.
     * <p>
     * {@link syncState()} is not called yet; the caller should create a protector afterwards, which
     * handles this.  This makes it so that all the user's initial SP state files, including the
     * initial LSKF-based protector, are efficiently created with only a single {@link syncState()}.
     */
    SyntheticPassword newSyntheticPassword(int userId) {
        clearSidForUser(userId);
        SyntheticPassword result = SyntheticPassword.create();
        saveEscrowData(result, userId);
        return result;
    }

    /**
     * Enroll a new password handle and SID for the given synthetic password and persist it on disk.
     * Used when the LSKF is changed from empty to nonempty.
     */
    public void newSidForUser(IGateKeeperService gatekeeper, SyntheticPassword sp, int userId) {
        GateKeeperResponse response;
        try {
            response = gatekeeper.enroll(userId, null, null, sp.deriveGkPassword());
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to create new SID for user", e);
        }
        if (response.getResponseCode() != GateKeeperResponse.RESPONSE_OK) {
            throw new IllegalStateException("Fail to create new SID for user " + userId
                    + " response: " + response.getResponseCode());
        }
        saveSyntheticPasswordHandle(response.getPayload(), userId);
    }

    // Nuke the SP handle (and as a result, its SID) for the given user.
    public void clearSidForUser(int userId) {
        destroyState(SP_HANDLE_NAME, NULL_PROTECTOR_ID, userId);
    }

    public boolean hasSidForUser(int userId) {
        return hasState(SP_HANDLE_NAME, NULL_PROTECTOR_ID, userId);
    }

    // If this returns null, it means there is no SID associated with the user.  This happens if the
    // user has an empty LSKF, but does have an SP.
    private byte[] loadSyntheticPasswordHandle(int userId) {
        return loadState(SP_HANDLE_NAME, NULL_PROTECTOR_ID, userId);
    }

    private void saveSyntheticPasswordHandle(byte[] spHandle, int userId) {
        saveState(SP_HANDLE_NAME, spHandle, NULL_PROTECTOR_ID, userId);
        syncState(userId);
    }

    private boolean loadEscrowData(SyntheticPassword sp, int userId) {
        byte[] e0 = loadState(SP_E0_NAME, NULL_PROTECTOR_ID, userId);
        byte[] p1 = loadState(SP_P1_NAME, NULL_PROTECTOR_ID, userId);
        sp.setEscrowData(e0,  p1);
        return e0 != null && p1 != null;
    }

    /**
     * Saves the escrow data for the synthetic password.  The caller is responsible for calling
     * {@link syncState()} afterwards, once the user's other initial synthetic password state files
     * have been created.
     */
    private void saveEscrowData(SyntheticPassword sp, int userId) {
        saveState(SP_E0_NAME, sp.mEncryptedEscrowSplit0, NULL_PROTECTOR_ID, userId);
        saveState(SP_P1_NAME, sp.mEscrowSplit1, NULL_PROTECTOR_ID, userId);
    }

    public boolean hasEscrowData(int userId) {
        return hasState(SP_E0_NAME, NULL_PROTECTOR_ID, userId)
                && hasState(SP_P1_NAME, NULL_PROTECTOR_ID, userId);
    }

    public boolean hasAnyEscrowData(int userId) {
        return hasState(SP_E0_NAME, NULL_PROTECTOR_ID, userId)
                || hasState(SP_P1_NAME, NULL_PROTECTOR_ID, userId);
    }

    public void destroyEscrowData(int userId) {
        destroyState(SP_E0_NAME, NULL_PROTECTOR_ID, userId);
        destroyState(SP_P1_NAME, NULL_PROTECTOR_ID, userId);
    }

    private int loadWeaverSlot(long protectorId, int userId) {
        final int LENGTH = Byte.BYTES + Integer.BYTES;
        byte[] data = loadState(WEAVER_SLOT_NAME, protectorId, userId);
        if (data == null || data.length != LENGTH) {
            return INVALID_WEAVER_SLOT;
        }
        ByteBuffer buffer = ByteBuffer.allocate(LENGTH);
        buffer.put(data, 0, data.length);
        buffer.flip();
        if (buffer.get() != WEAVER_VERSION) {
            Slog.e(TAG, "Invalid weaver slot version for protector " + protectorId);
            return INVALID_WEAVER_SLOT;
        }
        return buffer.getInt();
    }

    /**
     * Creates a file that stores the Weaver slot the protector is using.  The caller is responsible
     * for calling {@link syncState()} afterwards, once all the protector's files have been created.
     */
    private void saveWeaverSlot(int slot, long protectorId, int userId) {
        ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES);
        buffer.put(WEAVER_VERSION);
        buffer.putInt(slot);
        saveState(WEAVER_SLOT_NAME, buffer.array(), protectorId, userId);
    }

    private void destroyWeaverSlot(long protectorId, int userId) {
        int slot = loadWeaverSlot(protectorId, userId);
        destroyState(WEAVER_SLOT_NAME, protectorId, userId);
        if (slot != INVALID_WEAVER_SLOT) {
            final IWeaver weaver = getWeaverService();
            if (weaver == null) {
                Slog.e(TAG, "Cannot erase Weaver slot because Weaver is unavailable");
                return;
            }
            Set<Integer> usedSlots = getUsedWeaverSlots();
            if (!usedSlots.contains(slot)) {
                Slogf.i(TAG, "Erasing Weaver slot %d", slot);
                weaverEnroll(weaver, slot, null, null);
                mPasswordSlotManager.markSlotDeleted(slot);
            } else {
                Slogf.i(TAG, "Weaver slot %d was already reused; not erasing it", slot);
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
        Map<Integer, List<Long>> protectorIds =
            mStorage.listSyntheticPasswordProtectorsForAllUsers(WEAVER_SLOT_NAME);
        HashSet<Integer> slots = new HashSet<>();
        for (Map.Entry<Integer, List<Long>> entry : protectorIds.entrySet()) {
            for (Long protectorId : entry.getValue()) {
                int slot = loadWeaverSlot(protectorId, entry.getKey());
                slots.add(slot);
            }
        }
        return slots;
    }

    private int getNextAvailableWeaverSlot() {
        Set<Integer> usedSlots = getUsedWeaverSlots();
        usedSlots.addAll(mPasswordSlotManager.getUsedSlots());
        // If the device is not yet provisioned, then the Weaver slot used by the FRP credential may
        // be still needed and must not be reused yet.  (This *should* instead check "has FRP been
        // resolved yet?", which would allow reusing the slot a bit earlier.  However, the
        // SECURE_FRP_MODE setting gets set to 1 too late for it to be used here.)
        if (!isDeviceProvisioned()) {
            PersistentData persistentData = mStorage.readPersistentDataBlock();
            if (persistentData != null && persistentData.type == PersistentData.TYPE_SP_WEAVER) {
                int slot = persistentData.userId; // Note: field name is misleading
                usedSlots.add(slot);
            }
        }
        for (int i = 0; i < mWeaverConfig.slots; i++) {
            if (!usedSlots.contains(i)) {
                return i;
            }
        }
        throw new IllegalStateException("Run out of weaver slots.");
    }

    /**
     * Creates a protector that protects the user's SP with the given LSKF (which may be empty).
     *
     * This method only creates a new protector that isn't referenced by anything; it doesn't handle
     * any higher-level tasks involved in changing the LSKF.
     *
     * @return the ID of the new protector
     * @throws IllegalStateException on failure
     */
    public long createLskfBasedProtector(IGateKeeperService gatekeeper,
            LockscreenCredential credential, SyntheticPassword sp, int userId) {
        long protectorId = generateProtectorId();
        int pinLength = PIN_LENGTH_UNAVAILABLE;
        if (isAutoPinConfirmationFeatureAvailable()) {
            pinLength = derivePinLength(credential.size(), credential.isPin(), userId);
        }
        // There's no need to store password data about an empty LSKF.
        PasswordData pwd = credential.isNone() ? null :
                PasswordData.create(credential.getType(), pinLength);
        byte[] stretchedLskf = stretchLskf(credential, pwd);
        long sid = GateKeeper.INVALID_SECURE_USER_ID;
        final byte[] protectorSecret;

        Slogf.i(TAG, "Creating LSKF-based protector %016x for user %d", protectorId, userId);

        final IWeaver weaver = getWeaverService();
        if (weaver != null) {
            // Weaver is available, so make the protector use it to verify the LSKF.  Do this even
            // if the LSKF is empty, as that gives us support for securely deleting the protector.
            int weaverSlot = getNextAvailableWeaverSlot();
            Slogf.i(TAG, "Enrolling LSKF for user %d into Weaver slot %d", userId, weaverSlot);
            byte[] weaverSecret = weaverEnroll(weaver, weaverSlot,
                    stretchedLskfToWeaverKey(stretchedLskf), null);
            if (weaverSecret == null) {
                throw new IllegalStateException(
                        "Fail to enroll user password under weaver " + userId);
            }
            saveWeaverSlot(weaverSlot, protectorId, userId);
            mPasswordSlotManager.markSlotInUse(weaverSlot);
            // No need to pass in quality since the credential type already encodes sufficient info
            synchronizeWeaverFrpPassword(pwd, 0, userId, weaverSlot);

            protectorSecret = transformUnderWeaverSecret(stretchedLskf, weaverSecret);
        } else {
            // Weaver is unavailable, so make the protector use Gatekeeper (GK) to verify the LSKF.
            //
            // However, skip GK when the LSKF is empty.  There are two reasons for this, one
            // performance and one correctness.  The performance reason is that GK wouldn't give any
            // benefit with an empty LSKF anyway, since GK isn't expected to provide secure
            // deletion.  The correctness reason is that it is unsafe to enroll a password in the
            // 'fakeUserId' GK range on an FRP-protected device that is in the setup wizard with FRP
            // not passed yet, as that may overwrite the enrollment used by the FRP credential.
            if (!credential.isNone()) {
                // In case GK enrollment leaves persistent state around (in RPMB), this will nuke
                // them to prevent them from accumulating and causing problems.
                try {
                    gatekeeper.clearSecureUserId(fakeUserId(userId));
                } catch (RemoteException ignore) {
                    Slog.w(TAG, "Failed to clear SID from gatekeeper");
                }
                Slogf.i(TAG, "Enrolling LSKF for user %d into Gatekeeper", userId);
                GateKeeperResponse response;
                try {
                    response = gatekeeper.enroll(fakeUserId(userId), null, null,
                            stretchedLskfToGkPassword(stretchedLskf));
                } catch (RemoteException e) {
                    throw new IllegalStateException("Failed to enroll LSKF for new SP protector"
                            + " for user " + userId, e);
                }
                if (response.getResponseCode() != GateKeeperResponse.RESPONSE_OK) {
                    throw new IllegalStateException("Failed to enroll LSKF for new SP protector"
                            + " for user " + userId);
                }
                pwd.passwordHandle = response.getPayload();
                sid = sidFromPasswordHandle(pwd.passwordHandle);
            }
            protectorSecret = transformUnderSecdiscardable(stretchedLskf,
                    createSecdiscardable(protectorId, userId));
            // No need to pass in quality since the credential type already encodes sufficient info
            synchronizeGatekeeperFrpPassword(pwd, 0, userId);
        }
        if (!credential.isNone()) {
            saveState(PASSWORD_DATA_NAME, pwd.toBytes(), protectorId, userId);
            savePasswordMetrics(credential, sp, protectorId, userId);
        }
        createSyntheticPasswordBlob(protectorId, PROTECTOR_TYPE_LSKF_BASED, sp, protectorSecret,
                sid, userId);
        syncState(userId); // ensure the new files are really saved to disk
        return protectorId;
    }

    private int derivePinLength(int sizeOfCredential, boolean isPinCredential, int userId) {
        if (!isPinCredential
                || !mStorage.isAutoPinConfirmSettingEnabled(userId)
                || sizeOfCredential < LockPatternUtils.MIN_AUTO_PIN_REQUIREMENT_LENGTH) {
            return PIN_LENGTH_UNAVAILABLE;
        }
        return sizeOfCredential;
    }

    public VerifyCredentialResponse verifySpecialUserCredential(int sourceUserId,
            IGateKeeperService gatekeeper, LockscreenCredential userCredential,
            ICheckCredentialProgressCallback progressCallback) {
        final PersistentData persistentData = getSpecialUserPersistentData(sourceUserId);
        if (persistentData.type == PersistentData.TYPE_SP_GATEKEEPER) {
            PasswordData pwd = PasswordData.fromBytes(persistentData.payload);
            byte[] stretchedLskf = stretchLskf(userCredential, pwd);

            GateKeeperResponse response;
            try {
                response = gatekeeper.verifyChallenge(fakeUserId(persistentData.userId),
                        0 /* challenge */, pwd.passwordHandle,
                        stretchedLskfToGkPassword(stretchedLskf));
            } catch (RemoteException e) {
                Slog.e(TAG, "Persistent data credential verifyChallenge failed", e);
                return VerifyCredentialResponse.ERROR;
            }
            return VerifyCredentialResponse.fromGateKeeperResponse(response);
        } else if (persistentData.type == PersistentData.TYPE_SP_WEAVER) {
            final IWeaver weaver = getWeaverService();
            if (weaver == null) {
                Slog.e(TAG, "No weaver service to verify SP-based persistent data credential");
                return VerifyCredentialResponse.ERROR;
            }
            PasswordData pwd = PasswordData.fromBytes(persistentData.payload);
            byte[] stretchedLskf = stretchLskf(userCredential, pwd);
            int weaverSlot = persistentData.userId;

            return weaverVerify(weaver, weaverSlot,
                    stretchedLskfToWeaverKey(stretchedLskf)).stripPayload();
        } else {
            Slog.e(TAG, "persistentData.type must be TYPE_SP_GATEKEEPER or TYPE_SP_WEAVER, but is "
                    + persistentData.type);
            return VerifyCredentialResponse.ERROR;
        }
    }


    public void migrateFrpPasswordLocked(long protectorId, UserInfo userInfo,
            int requestedQuality) {
        if (mStorage.getPersistentDataBlockManager() != null
                && LockPatternUtils.userOwnsFrpCredential(mContext, userInfo)
                && getCredentialType(protectorId, userInfo.id) !=
                        LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            Slog.i(TAG, "Migrating FRP credential to persistent data block");
            PasswordData pwd = PasswordData.fromBytes(loadState(PASSWORD_DATA_NAME, protectorId,
                    userInfo.id));
            int weaverSlot = loadWeaverSlot(protectorId, userInfo.id);
            if (weaverSlot != INVALID_WEAVER_SLOT) {
                synchronizeWeaverFrpPassword(pwd, requestedQuality, userInfo.id, weaverSlot);
            } else {
                synchronizeGatekeeperFrpPassword(pwd, requestedQuality, userInfo.id);
            }
        }
    }

    private static boolean isNoneCredential(PasswordData pwd) {
        return pwd == null || pwd.credentialType == LockPatternUtils.CREDENTIAL_TYPE_NONE;
    }

    private boolean shouldSynchronizeFrpCredential(@Nullable PasswordData pwd, int userId) {
        if (mStorage.getPersistentDataBlockManager() == null) {
            return false;
        }
        UserInfo userInfo = mUserManager.getUserInfo(userId);
        if (!LockPatternUtils.userOwnsFrpCredential(mContext, userInfo)) {
            return false;
        }
        // When initializing the synthetic password of the user that will own the FRP credential,
        // the FRP data block must not be cleared if the device isn't provisioned yet, since in this
        // case the old value of the block may still be needed for the FRP authentication step.  The
        // FRP data block will instead be cleared later, by
        // LockSettingsService.DeviceProvisionedObserver.clearFrpCredentialIfOwnerNotSecure().
        //
        // Don't check the SECURE_FRP_MODE setting here, as it gets set to 1 too late.
        //
        // Don't delay anything for a nonempty credential.  A nonempty credential can be set before
        // the device has been provisioned, but it's guaranteed to be after FRP was resolved.
        if (isNoneCredential(pwd) && !isDeviceProvisioned()) {
            Slog.d(TAG, "Not clearing FRP credential yet because device is not yet provisioned");
            return false;
        }
        return true;
    }

    private void synchronizeGatekeeperFrpPassword(@Nullable PasswordData pwd, int requestedQuality,
            int userId) {
        if (shouldSynchronizeFrpCredential(pwd, userId)) {
            Slogf.d(TAG, "Syncing Gatekeeper-based FRP credential tied to user %d", userId);
            if (!isNoneCredential(pwd)) {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_GATEKEEPER, userId,
                        requestedQuality, pwd.toBytes());
            } else {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_NONE, userId, 0, null);
            }
        }
    }

    private void synchronizeWeaverFrpPassword(@Nullable PasswordData pwd, int requestedQuality,
            int userId, int weaverSlot) {
        if (shouldSynchronizeFrpCredential(pwd, userId)) {
            Slogf.d(TAG, "Syncing Weaver-based FRP credential tied to user %d", userId);
            if (!isNoneCredential(pwd)) {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, weaverSlot,
                        requestedQuality, pwd.toBytes());
            } else {
                mStorage.writePersistentDataBlock(PersistentData.TYPE_NONE, 0, 0, null);
            }
        }
    }

    /**
     * Writes the user's synthetic password data to the repair mode file.
     *
     * @param protectorId current LSKF based protectorId
     * @param userId user id of the user
     */
    public boolean writeRepairModeCredentialLocked(long protectorId, int userId) {
        if (!shouldWriteRepairModeCredential(userId)) {
            return false;
        }
        final byte[] data = loadState(PASSWORD_DATA_NAME, protectorId, userId);
        if (data == null) {
            Slogf.w(TAG, "Password data not found for user %d", userId);
            return false;
        }
        final PasswordData pwd = PasswordData.fromBytes(data);
        if (isNoneCredential(pwd)) {
            Slogf.w(TAG, "User %d has NONE credential", userId);
            return false;
        }
        Slogf.d(TAG, "Writing repair mode credential tied to user %d", userId);
        final int weaverSlot = loadWeaverSlot(protectorId, userId);
        if (weaverSlot != INVALID_WEAVER_SLOT) {
            // write weaver password
            mStorage.writeRepairModePersistentData(
                    PersistentData.TYPE_SP_WEAVER, weaverSlot, pwd.toBytes());
        } else {
            // write gatekeeper password
            mStorage.writeRepairModePersistentData(
                    PersistentData.TYPE_SP_GATEKEEPER, userId, pwd.toBytes());
        }
        return true;
    }

    private boolean shouldWriteRepairModeCredential(int userId) {
        final UserInfo userInfo = mUserManager.getUserInfo(userId);
        if (!LockPatternUtils.canUserEnterRepairMode(mContext, userInfo)) {
            Slogf.w(TAG, "User %d can't enter repair mode", userId);
            return false;
        }
        if (LockPatternUtils.isRepairModeActive(mContext)) {
            Slog.w(TAG, "Can't write repair mode credential while repair mode is already active");
            return false;
        }
        if (LockPatternUtils.isGsiRunning()) {
            Slog.w(TAG, "Can't write repair mode credential while GSI is running");
            return false;
        }
        return true;
    }

    private ArrayMap<Integer, ArrayMap<Long, TokenData>> tokenMap = new ArrayMap<>();

    /**
     * Caches a pending escrow token in memory and pre-allocates an ID for a new SP protector.  This
     * ID also serves as a handle for the pending token.
     *
     * This method doesn't persist any data, and it doesn't require access to the SP.
     * {@link #createTokenBasedProtector} can be called later to actually create the protector.
     *
     * @return the token handle
     */
    public long addPendingToken(byte[] token, @TokenType int type, int userId,
            @Nullable EscrowTokenStateChangeCallback changeCallback) {
        long tokenHandle = generateProtectorId(); // tokenHandle is reused as protectorId later
        if (!tokenMap.containsKey(userId)) {
            tokenMap.put(userId, new ArrayMap<>());
        }
        TokenData tokenData = new TokenData();
        tokenData.mType = type;
        final byte[] secdiscardable = SecureRandomUtils.randomBytes(SECDISCARDABLE_LENGTH);
        if (getWeaverService() != null) {
            tokenData.weaverSecret = SecureRandomUtils.randomBytes(mWeaverConfig.valueSize);
            tokenData.secdiscardableOnDisk = SyntheticPasswordCrypto.encrypt(tokenData.weaverSecret,
                            PERSONALIZATION_WEAVER_TOKEN, secdiscardable);
        } else {
            tokenData.secdiscardableOnDisk = secdiscardable;
            tokenData.weaverSecret = null;
        }
        tokenData.aggregatedSecret = transformUnderSecdiscardable(token, secdiscardable);
        tokenData.mCallback = changeCallback;

        tokenMap.get(userId).put(tokenHandle, tokenData);
        return tokenHandle;
    }

    public Set<Long> getPendingTokensForUser(int userId) {
        if (!tokenMap.containsKey(userId)) {
            return Collections.emptySet();
        }
        return new ArraySet<>(tokenMap.get(userId).keySet());
    }

    /** Remove the given pending token. */
    public boolean removePendingToken(long tokenHandle, int userId) {
        if (!tokenMap.containsKey(userId)) {
            return false;
        }
        return tokenMap.get(userId).remove(tokenHandle) != null;
    }

    public boolean createTokenBasedProtector(long tokenHandle, SyntheticPassword sp, int userId) {
        if (!tokenMap.containsKey(userId)) {
            return false;
        }
        TokenData tokenData = tokenMap.get(userId).get(tokenHandle);
        if (tokenData == null) {
            return false;
        }
        if (!loadEscrowData(sp, userId)) {
            Slog.w(TAG, "User is not escrowable");
            return false;
        }
        Slogf.i(TAG, "Creating token-based protector %016x for user %d", tokenHandle, userId);
        final IWeaver weaver = getWeaverService();
        if (weaver != null) {
            int slot = getNextAvailableWeaverSlot();
            Slogf.i(TAG, "Using Weaver slot %d for new token-based protector", slot);
            if (weaverEnroll(weaver, slot, null, tokenData.weaverSecret) == null) {
                Slog.e(TAG, "Failed to enroll weaver secret when activating token");
                return false;
            }
            saveWeaverSlot(slot, tokenHandle, userId);
            mPasswordSlotManager.markSlotInUse(slot);
        }
        saveSecdiscardable(tokenHandle, tokenData.secdiscardableOnDisk, userId);
        createSyntheticPasswordBlob(tokenHandle, getTokenBasedProtectorType(tokenData.mType), sp,
                tokenData.aggregatedSecret, 0L, userId);
        syncState(userId); // ensure the new files are really saved to disk
        tokenMap.get(userId).remove(tokenHandle);
        if (tokenData.mCallback != null) {
            tokenData.mCallback.onEscrowTokenActivated(tokenHandle, userId);
        }
        return true;
    }

    /**
     * Creates a synthetic password blob, i.e. the file that stores the encrypted synthetic password
     * (or encrypted escrow secret) for a protector.  The caller is responsible for calling
     * {@link syncState()} afterwards, once all the protector's files have been created.
     */
    private void createSyntheticPasswordBlob(long protectorId, byte protectorType,
            SyntheticPassword sp, byte[] protectorSecret, long sid, int userId) {
        final byte[] spSecret;
        if (protectorType == PROTECTOR_TYPE_STRONG_TOKEN_BASED
                || protectorType == PROTECTOR_TYPE_WEAK_TOKEN_BASED) {
            spSecret = sp.getEscrowSecret();
        } else {
            spSecret = sp.getSyntheticPassword();
        }
        byte[] content = createSpBlob(getProtectorKeyAlias(protectorId), spSecret, protectorSecret,
                sid);
        /*
         * We can upgrade from v1 to v2 because that's just a change in the way that
         * the SP is stored. However, we can't upgrade to v3 because that is a change
         * in the way that passwords are derived from the SP.
         */
        byte version = sp.mVersion == SYNTHETIC_PASSWORD_VERSION_V3
                ? SYNTHETIC_PASSWORD_VERSION_V3 : SYNTHETIC_PASSWORD_VERSION_V2;

        SyntheticPasswordBlob blob = SyntheticPasswordBlob.create(version, protectorType, content);
        saveState(SP_BLOB_NAME, blob.toByte(), protectorId, userId);
    }

    /**
     * Tries to unlock a user's LSKF-based SP protector, given its ID and the claimed LSKF (which
     * may be empty).  On success, returns the user's synthetic password, and also does a Gatekeeper
     * verification to refresh the SID and HardwareAuthToken maintained by the system.
     */
    public AuthenticationResult unlockLskfBasedProtector(IGateKeeperService gatekeeper,
            long protectorId, @NonNull LockscreenCredential credential, int userId,
            ICheckCredentialProgressCallback progressCallback) {
        AuthenticationResult result = new AuthenticationResult();

        if (protectorId == SyntheticPasswordManager.NULL_PROTECTOR_ID) {
            // This should never happen, due to the migration done in LSS.onThirdPartyAppsStarted().
            Slogf.wtf(TAG, "Synthetic password not found for user %d", userId);
            result.gkResponse = VerifyCredentialResponse.ERROR;
            return result;
        }

        // Load the PasswordData file.  If it doesn't exist, then the LSKF is empty (i.e.,
        // CREDENTIAL_TYPE_NONE), and we'll skip the scrypt and Gatekeeper steps.  If it exists,
        // then either the LSKF is nonempty, or it's an old protector that uses scrypt and
        // Gatekeeper even though the LSKF is empty.
        byte[] pwdDataBytes = loadState(PASSWORD_DATA_NAME, protectorId, userId);
        PasswordData pwd = null;
        int storedType = LockPatternUtils.CREDENTIAL_TYPE_NONE;
        if (pwdDataBytes != null) {
            pwd = PasswordData.fromBytes(pwdDataBytes);
            storedType = pwd.credentialType;
        }
        if (!credential.checkAgainstStoredType(storedType)) {
            Slogf.e(TAG, "Credential type mismatch: stored type is %s but provided type is %s",
                    LockPatternUtils.credentialTypeToString(storedType),
                    LockPatternUtils.credentialTypeToString(credential.getType()));
            result.gkResponse = VerifyCredentialResponse.ERROR;
            return result;
        }

        byte[] stretchedLskf = stretchLskf(credential, pwd);

        final byte[] protectorSecret;
        long sid = GateKeeper.INVALID_SECURE_USER_ID;
        int weaverSlot = loadWeaverSlot(protectorId, userId);
        if (weaverSlot != INVALID_WEAVER_SLOT) {
            // Protector uses Weaver to verify the LSKF
            final IWeaver weaver = getWeaverService();
            if (weaver == null) {
                Slog.e(TAG, "Protector uses Weaver, but Weaver is unavailable");
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            result.gkResponse = weaverVerify(weaver, weaverSlot,
                    stretchedLskfToWeaverKey(stretchedLskf));
            if (result.gkResponse.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
                return result;
            }
            protectorSecret = transformUnderWeaverSecret(stretchedLskf,
                    result.gkResponse.getGatekeeperHAT());
        } else {
            // Weaver is unavailable, so the protector uses Gatekeeper to verify the LSKF, unless
            // the LSKF is empty in which case Gatekeeper might not have been used at all.
            if (pwd == null || pwd.passwordHandle == null) {
                if (!credential.isNone()) {
                    Slog.e(TAG, "Missing Gatekeeper password handle for nonempty LSKF");
                    result.gkResponse = VerifyCredentialResponse.ERROR;
                    return result;
                }
            } else {
                byte[] gkPassword = stretchedLskfToGkPassword(stretchedLskf);
                GateKeeperResponse response;
                try {
                    response = gatekeeper.verifyChallenge(fakeUserId(userId), 0L,
                            pwd.passwordHandle, gkPassword);
                } catch (RemoteException e) {
                    Slog.e(TAG, "gatekeeper verify failed", e);
                    result.gkResponse = VerifyCredentialResponse.ERROR;
                    return result;
                }
                int responseCode = response.getResponseCode();
                if (responseCode == GateKeeperResponse.RESPONSE_OK) {
                    result.gkResponse = VerifyCredentialResponse.OK;
                    if (response.getShouldReEnroll()) {
                        GateKeeperResponse reenrollResponse;
                        try {
                            reenrollResponse = gatekeeper.enroll(fakeUserId(userId),
                                    pwd.passwordHandle, gkPassword, gkPassword);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Fail to invoke gatekeeper.enroll", e);
                            reenrollResponse = GateKeeperResponse.ERROR;
                            // continue the flow anyway
                        }
                        if (reenrollResponse.getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                            pwd.passwordHandle = reenrollResponse.getPayload();
                            // Use the reenrollment opportunity to update credential type
                            // (getting rid of CREDENTIAL_TYPE_PASSWORD_OR_PIN)
                            pwd.credentialType = credential.getType();
                            saveState(PASSWORD_DATA_NAME, pwd.toBytes(), protectorId, userId);
                            syncState(userId);
                            synchronizeGatekeeperFrpPassword(pwd, 0, userId);
                        } else {
                            Slog.w(TAG, "Fail to re-enroll user password for user " + userId);
                            // continue the flow anyway
                        }
                    }
                } else if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
                    result.gkResponse = VerifyCredentialResponse.fromTimeout(response.getTimeout());
                    return result;
                } else  {
                    result.gkResponse = VerifyCredentialResponse.ERROR;
                    return result;
                }
                sid = sidFromPasswordHandle(pwd.passwordHandle);
            }
            byte[] secdiscardable = loadSecdiscardable(protectorId, userId);
            if (secdiscardable == null) {
                Slog.e(TAG, "secdiscardable file not found");
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            protectorSecret = transformUnderSecdiscardable(stretchedLskf, secdiscardable);
        }
        // Supplied credential passes first stage weaver/gatekeeper check so it should be correct.
        // Notify the callback so the keyguard UI can proceed immediately.
        if (progressCallback != null) {
            try {
                progressCallback.onCredentialVerified();
            } catch (RemoteException e) {
                Slog.w(TAG, "progressCallback throws exception", e);
            }
        }
        result.syntheticPassword = unwrapSyntheticPasswordBlob(protectorId,
                PROTECTOR_TYPE_LSKF_BASED, protectorSecret, sid, userId);

        // Perform verifyChallenge to refresh auth tokens for GK if user password exists.
        result.gkResponse = verifyChallenge(gatekeeper, result.syntheticPassword, 0L, userId);

        // Upgrade case: store the metrics if the device did not have stored metrics before, should
        // only happen once on old protectors.
        if (result.syntheticPassword != null && !credential.isNone()
                && !hasPasswordMetrics(protectorId, userId)) {
            savePasswordMetrics(credential, result.syntheticPassword, protectorId, userId);
            syncState(userId); // Not strictly needed as the upgrade can be re-done, but be safe.
        }
        return result;
    }

    /**
     * {@link LockPatternUtils#refreshStoredPinLength(int)}
     * @param passwordMetrics passwordMetrics object containing the cached pin length
     * @param userId userId of the user whose pin length we want to store on disk
     * @param protectorId current LSKF based protectorId
     * @return true/false depending on whether PIN length has been saved on disk
     */
    public boolean refreshPinLengthOnDisk(PasswordMetrics passwordMetrics,
            long protectorId, int userId) {
        if (!isAutoPinConfirmationFeatureAvailable()) {
            return false;
        }

        byte[] pwdDataBytes = loadState(PASSWORD_DATA_NAME, protectorId, userId);
        if (pwdDataBytes == null) {
            return false;
        }

        PasswordData pwd = PasswordData.fromBytes(pwdDataBytes);
        int pinLength = derivePinLength(passwordMetrics.length,
                passwordMetrics.credType == CREDENTIAL_TYPE_PIN, userId);
        if (pwd.pinLength != pinLength) {
            pwd.pinLength = pinLength;
            saveState(PASSWORD_DATA_NAME, pwd.toBytes(), protectorId, userId);
            syncState(userId);
        }
        return true;
    }

    /**
     * Tries to unlock a token-based SP protector (weak or strong), given its ID and the claimed
     * token.  On success, returns the user's synthetic password, and also does a Gatekeeper
     * verification to refresh the SID and HardwareAuthToken maintained by the system.
     */
    public @NonNull AuthenticationResult unlockTokenBasedProtector(
            IGateKeeperService gatekeeper, long protectorId, byte[] token, int userId) {
        byte[] data = loadState(SP_BLOB_NAME, protectorId, userId);
        if (data == null) {
            AuthenticationResult result = new AuthenticationResult();
            result.gkResponse = VerifyCredentialResponse.ERROR;
            Slogf.w(TAG, "spblob not found for protector %016x, user %d", protectorId, userId);
            return result;
        }
        SyntheticPasswordBlob blob = SyntheticPasswordBlob.fromBytes(data);
        return unlockTokenBasedProtectorInternal(gatekeeper, protectorId, blob.mProtectorType,
                token, userId);
    }

    /**
     * Like {@link #unlockTokenBasedProtector}, but throws an exception if the protector is not for
     * a strong token specifically.
     */
    public @NonNull AuthenticationResult unlockStrongTokenBasedProtector(
            IGateKeeperService gatekeeper, long protectorId, byte[] token, int userId) {
        return unlockTokenBasedProtectorInternal(gatekeeper, protectorId,
                PROTECTOR_TYPE_STRONG_TOKEN_BASED, token, userId);
    }

    /**
     * Like {@link #unlockTokenBasedProtector}, but throws an exception if the protector is not for
     * a weak token specifically.
     */
    public @NonNull AuthenticationResult unlockWeakTokenBasedProtector(
            IGateKeeperService gatekeeper, long protectorId, byte[] token, int userId) {
        return unlockTokenBasedProtectorInternal(gatekeeper, protectorId,
                PROTECTOR_TYPE_WEAK_TOKEN_BASED, token, userId);
    }

    private @NonNull AuthenticationResult unlockTokenBasedProtectorInternal(
            IGateKeeperService gatekeeper, long protectorId, byte expectedProtectorType,
            byte[] token, int userId) {
        AuthenticationResult result = new AuthenticationResult();
        byte[] secdiscardable = loadSecdiscardable(protectorId, userId);
        if (secdiscardable == null) {
            Slog.e(TAG, "secdiscardable file not found");
            result.gkResponse = VerifyCredentialResponse.ERROR;
            return result;
        }
        int slotId = loadWeaverSlot(protectorId, userId);
        if (slotId != INVALID_WEAVER_SLOT) {
            final IWeaver weaver = getWeaverService();
            if (weaver == null) {
                Slog.e(TAG, "Protector uses Weaver, but Weaver is unavailable");
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            VerifyCredentialResponse response = weaverVerify(weaver, slotId, null);
            if (response.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK ||
                    response.getGatekeeperHAT() == null) {
                Slog.e(TAG,
                        "Failed to retrieve Weaver secret when unlocking token-based protector");
                result.gkResponse = VerifyCredentialResponse.ERROR;
                return result;
            }
            secdiscardable = SyntheticPasswordCrypto.decrypt(response.getGatekeeperHAT(),
                    PERSONALIZATION_WEAVER_TOKEN, secdiscardable);
        }
        byte[] protectorSecret = transformUnderSecdiscardable(token, secdiscardable);
        result.syntheticPassword = unwrapSyntheticPasswordBlob(protectorId, expectedProtectorType,
                protectorSecret, 0L, userId);
        if (result.syntheticPassword != null) {
            result.gkResponse = verifyChallenge(gatekeeper, result.syntheticPassword, 0L, userId);
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

    private SyntheticPassword unwrapSyntheticPasswordBlob(long protectorId,
            byte expectedProtectorType, byte[] protectorSecret, long sid, int userId) {
        byte[] data = loadState(SP_BLOB_NAME, protectorId, userId);
        if (data == null) {
            return null;
        }
        SyntheticPasswordBlob blob = SyntheticPasswordBlob.fromBytes(data);
        if (blob.mVersion != SYNTHETIC_PASSWORD_VERSION_V3
                && blob.mVersion != SYNTHETIC_PASSWORD_VERSION_V2
                && blob.mVersion != SYNTHETIC_PASSWORD_VERSION_V1) {
            throw new IllegalArgumentException("Unknown blob version: " + blob.mVersion);
        }
        if (blob.mProtectorType != expectedProtectorType) {
            throw new IllegalArgumentException("Invalid protector type: " + blob.mProtectorType);
        }
        final byte[] spSecret;
        if (blob.mVersion == SYNTHETIC_PASSWORD_VERSION_V1) {
            spSecret = SyntheticPasswordCrypto.decryptBlobV1(getProtectorKeyAlias(protectorId),
                    blob.mContent, protectorSecret);
        } else {
            spSecret = decryptSpBlob(getProtectorKeyAlias(protectorId), blob.mContent,
                    protectorSecret);
        }
        if (spSecret == null) {
            Slog.e(TAG, "Fail to decrypt SP for user " + userId);
            return null;
        }
        SyntheticPassword result = new SyntheticPassword(blob.mVersion);
        if (blob.mProtectorType == PROTECTOR_TYPE_STRONG_TOKEN_BASED
                || blob.mProtectorType == PROTECTOR_TYPE_WEAK_TOKEN_BASED) {
            if (!loadEscrowData(result, userId)) {
                Slog.e(TAG, "User is not escrowable: " + userId);
                return null;
            }
            result.recreateFromEscrow(spSecret);
        } else {
            result.recreateDirectly(spSecret);
        }
        if (blob.mVersion == SYNTHETIC_PASSWORD_VERSION_V1) {
            Slog.i(TAG, "Upgrading v1 SP blob for user " + userId + ", protectorType = "
                    + blob.mProtectorType);
            createSyntheticPasswordBlob(protectorId, blob.mProtectorType, result, protectorSecret,
                    sid, userId);
            syncState(userId); // Not strictly needed as the upgrade can be re-done, but be safe.
        }
        return result;
    }

    /**
     * performs GK verifyChallenge and returns auth token, re-enrolling SP password handle
     * if required.
     *
     * Normally performing verifyChallenge with an SP should always return RESPONSE_OK, since user
     * authentication failures are detected earlier when trying to decrypt the SP.
     */
    public @Nullable VerifyCredentialResponse verifyChallenge(IGateKeeperService gatekeeper,
            @NonNull SyntheticPassword sp, long challenge, int userId) {
        return verifyChallengeInternal(gatekeeper, sp.deriveGkPassword(), challenge, userId);
    }

    protected @Nullable VerifyCredentialResponse verifyChallengeInternal(
            IGateKeeperService gatekeeper, @NonNull byte[] gatekeeperPassword, long challenge,
            int userId) {
        byte[] spHandle = loadSyntheticPasswordHandle(userId);
        if (spHandle == null) {
            // There is no password handle associated with the given user, i.e. the user is not
            // secured by lockscreen and has no SID, so just return here;
            return null;
        }
        GateKeeperResponse response;
        try {
            response = gatekeeper.verifyChallenge(userId, challenge,
                    spHandle, gatekeeperPassword);
        } catch (RemoteException e) {
            Slog.e(TAG, "Fail to verify with gatekeeper " + userId, e);
            return VerifyCredentialResponse.ERROR;
        }
        int responseCode = response.getResponseCode();
        if (responseCode == GateKeeperResponse.RESPONSE_OK) {
            VerifyCredentialResponse result = new VerifyCredentialResponse.Builder()
                    .setGatekeeperHAT(response.getPayload()).build();
            if (response.getShouldReEnroll()) {
                try {
                    response = gatekeeper.enroll(userId, spHandle, spHandle,
                            gatekeeperPassword);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to invoke gatekeeper.enroll", e);
                    response = GateKeeperResponse.ERROR;
                }
                if (response.getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                    spHandle = response.getPayload();
                    saveSyntheticPasswordHandle(spHandle, userId);
                    // Call self again to re-verify with updated handle
                    return verifyChallengeInternal(gatekeeper, gatekeeperPassword, challenge,
                            userId);
                } else {
                    // Fall through, return result from the previous verification attempt.
                    Slog.w(TAG, "Fail to re-enroll SP handle for user " + userId);
                }
            }
            return result;
        } else if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
            Slog.e(TAG, "Gatekeeper verification of synthetic password failed with RESPONSE_RETRY");
            return VerifyCredentialResponse.fromTimeout(response.getTimeout());
        } else {
            Slog.e(TAG, "Gatekeeper verification of synthetic password failed with RESPONSE_ERROR");
            return VerifyCredentialResponse.ERROR;
        }
    }

    public boolean protectorExists(long protectorId, int userId) {
        return hasState(SP_BLOB_NAME, protectorId, userId);
    }

    /** Destroy a token-based SP protector. */
    public void destroyTokenBasedProtector(long protectorId, int userId) {
        Slogf.i(TAG, "Destroying token-based protector %016x for user %d", protectorId, userId);
        SyntheticPasswordBlob blob = SyntheticPasswordBlob.fromBytes(loadState(SP_BLOB_NAME,
                    protectorId, userId));
        destroyProtectorCommon(protectorId, userId);
        if (blob.mProtectorType == PROTECTOR_TYPE_WEAK_TOKEN_BASED) {
            notifyWeakEscrowTokenRemovedListeners(protectorId, userId);
        }
    }

    /** Destroy all weak token-based SP protectors for the given user. */
    public void destroyAllWeakTokenBasedProtectors(int userId) {
        List<Long> protectorIds =
            mStorage.listSyntheticPasswordProtectorsForUser(SP_BLOB_NAME, userId);
        for (long protectorId : protectorIds) {
            SyntheticPasswordBlob blob = SyntheticPasswordBlob.fromBytes(loadState(SP_BLOB_NAME,
                    protectorId, userId));
            if (blob.mProtectorType == PROTECTOR_TYPE_WEAK_TOKEN_BASED) {
                destroyTokenBasedProtector(protectorId, userId);
            }
        }
    }

    /**
     * Destroy an LSKF-based SP protector.  This is used when the user's LSKF is changed.
     */
    public void destroyLskfBasedProtector(long protectorId, int userId) {
        Slogf.i(TAG, "Destroying LSKF-based protector %016x for user %d", protectorId, userId);
        destroyProtectorCommon(protectorId, userId);
        destroyState(PASSWORD_DATA_NAME, protectorId, userId);
        destroyState(PASSWORD_METRICS_NAME, protectorId, userId);
    }

    private void destroyProtectorCommon(long protectorId, int userId) {
        destroyState(SP_BLOB_NAME, protectorId, userId);
        destroyProtectorKey(getProtectorKeyAlias(protectorId));
        destroyState(SECDISCARDABLE_NAME, protectorId, userId);
        if (hasState(WEAVER_SLOT_NAME, protectorId, userId)) {
            destroyWeaverSlot(protectorId, userId);
        }
    }

    private byte[] transformUnderWeaverSecret(byte[] data, byte[] secret) {
        byte[] weaverSecret = SyntheticPasswordCrypto.personalizedHash(
                PERSONALIZATION_WEAVER_PASSWORD, secret);
        return ArrayUtils.concat(data, weaverSecret);
    }

    private byte[] transformUnderSecdiscardable(byte[] data, byte[] rawSecdiscardable) {
        byte[] secdiscardable = SyntheticPasswordCrypto.personalizedHash(
                PERSONALIZATION_SECDISCARDABLE, rawSecdiscardable);
        return ArrayUtils.concat(data, secdiscardable);
    }

    /**
     * Generates and writes the secdiscardable file for the given protector.  The caller is
     * responsible for calling {@link syncState()} afterwards, once all the protector's files have
     * been created.
     */
    private byte[] createSecdiscardable(long protectorId, int userId) {
        byte[] data = SecureRandomUtils.randomBytes(SECDISCARDABLE_LENGTH);
        saveSecdiscardable(protectorId, data, userId);
        return data;
    }

    /**
     * Writes the secdiscardable file for the given protector.  The caller is responsible for
     * calling {@link syncState()} afterwards, once all the protector's files have been created.
     */
    private void saveSecdiscardable(long protectorId, byte[] secdiscardable, int userId) {
        saveState(SECDISCARDABLE_NAME, secdiscardable, protectorId, userId);
    }

    private byte[] loadSecdiscardable(long protectorId, int userId) {
        return loadState(SECDISCARDABLE_NAME, protectorId, userId);
    }

    private byte getTokenBasedProtectorType(@TokenType int type) {
        switch (type) {
            case TOKEN_TYPE_WEAK:
                return PROTECTOR_TYPE_WEAK_TOKEN_BASED;
            case TOKEN_TYPE_STRONG:
            default:
                return PROTECTOR_TYPE_STRONG_TOKEN_BASED;
        }
    }

    @VisibleForTesting
    boolean hasPasswordData(long protectorId, int userId) {
        return hasState(PASSWORD_DATA_NAME, protectorId, userId);
    }

    /**
     * Retrieves a user's saved password metrics from their LSKF-based SP protector.  The
     * SyntheticPassword itself is needed to decrypt the file containing the password metrics.
     */
    public @Nullable PasswordMetrics getPasswordMetrics(SyntheticPassword sp, long protectorId,
            int userId) {
        final byte[] encrypted = loadState(PASSWORD_METRICS_NAME, protectorId, userId);
        if (encrypted == null) {
            Slogf.e(TAG, "Failed to read password metrics file for user %d", userId);
            return null;
        }
        final byte[] decrypted = SyntheticPasswordCrypto.decrypt(sp.deriveMetricsKey(),
                /* personalization= */ new byte[0], encrypted);
        if (decrypted == null) {
            Slogf.e(TAG, "Failed to decrypt password metrics file for user %d", userId);
            return null;
        }
        return VersionedPasswordMetrics.deserialize(decrypted).getMetrics();
    }

    /**
     * Creates the password metrics file: the file associated with the LSKF-based protector that
     * contains the encrypted metrics about the LSKF.  The caller is responsible for calling
     * {@link syncState()} afterwards if needed.
     */
    private void savePasswordMetrics(LockscreenCredential credential, SyntheticPassword sp,
            long protectorId, int userId) {
        final byte[] encrypted = SyntheticPasswordCrypto.encrypt(sp.deriveMetricsKey(),
                /* personalization= */ new byte[0],
                new VersionedPasswordMetrics(credential).serialize());
        saveState(PASSWORD_METRICS_NAME, encrypted, protectorId, userId);
    }

    @VisibleForTesting
    boolean hasPasswordMetrics(long protectorId, int userId) {
        return hasState(PASSWORD_METRICS_NAME, protectorId, userId);
    }

    private boolean hasState(String stateName, long protectorId, int userId) {
        return !ArrayUtils.isEmpty(loadState(stateName, protectorId, userId));
    }

    private byte[] loadState(String stateName, long protectorId, int userId) {
        return mStorage.readSyntheticPasswordState(userId, protectorId, stateName);
    }

    /**
     * Persists the given synthetic password state for the given user ID and protector ID.
     * <p>
     * For performance reasons, this doesn't sync the user's synthetic password state directory.  As
     * a result, it doesn't guarantee that the file will really be present after a crash.  If that
     * is needed, call {@link syncState()} afterwards, preferably after batching up related updates.
     */
    private void saveState(String stateName, byte[] data, long protectorId, int userId) {
        mStorage.writeSyntheticPasswordState(userId, protectorId, stateName, data);
    }

    private void syncState(int userId) {
        mStorage.syncSyntheticPasswordState(userId);
    }

    private void destroyState(String stateName, long protectorId, int userId) {
        mStorage.deleteSyntheticPasswordState(userId, protectorId, stateName);
    }

    @VisibleForTesting
    protected byte[] decryptSpBlob(String protectorKeyAlias, byte[] blob, byte[] protectorSecret) {
        return SyntheticPasswordCrypto.decryptBlob(protectorKeyAlias, blob, protectorSecret);
    }

    @VisibleForTesting
    protected byte[] createSpBlob(String protectorKeyAlias, byte[] data, byte[] protectorSecret,
            long sid) {
        return SyntheticPasswordCrypto.createBlob(protectorKeyAlias, data, protectorSecret, sid);
    }

    @VisibleForTesting
    protected void destroyProtectorKey(String keyAlias) {
        SyntheticPasswordCrypto.destroyProtectorKey(keyAlias);
    }

    private static long generateProtectorId() {
        while (true) {
            final long result = SecureRandomUtils.randomLong();
            if (result != NULL_PROTECTOR_ID) {
                return result;
            }
        }
    }

    @VisibleForTesting
    static int fakeUserId(int userId) {
        return 100000 + userId;
    }

    private String getProtectorKeyAlias(long protectorId) {
        // Note, this arguably has a bug: %x should be %016x so that the protector ID is left-padded
        // with zeroes, like how the synthetic password state files are named.  It's too late to fix
        // this, though, and it doesn't actually matter.
        return TextUtils.formatSimple("%s%x", PROTECTOR_KEY_ALIAS_PREFIX, protectorId);
    }

    /**
     * Stretches <code>credential</code>, if needed, using the parameters from <code>data</code>.
     * <p>
     * When the credential is empty, stetching provides no security benefit.  Thus, new protectors
     * for an empty credential use <code>null</code> {@link PasswordData} and skip the stretching.
     * <p>
     * However, old protectors always stored {@link PasswordData} and did the stretching, regardless
     * of whether the credential was empty or not.  For this reason, this method also continues to
     * support stretching of empty credentials so that old protectors can still be unlocked.
     */
    @VisibleForTesting
    byte[] stretchLskf(LockscreenCredential credential, @Nullable PasswordData data) {
        final byte[] password = credential.isNone() ? DEFAULT_PASSWORD : credential.getCredential();
        if (data == null) {
            Preconditions.checkArgument(credential.isNone());
            return Arrays.copyOf(password, STRETCHED_LSKF_LENGTH);
        }
        return scrypt(password, data.salt, 1 << data.scryptLogN, 1 << data.scryptLogR,
                1 << data.scryptLogP, STRETCHED_LSKF_LENGTH);
    }

    private byte[] stretchedLskfToGkPassword(byte[] stretchedLskf) {
        return SyntheticPasswordCrypto.personalizedHash(PERSONALIZATION_USER_GK_AUTH,
                stretchedLskf);
    }

    private byte[] stretchedLskfToWeaverKey(byte[] stretchedLskf) {
        byte[] key = SyntheticPasswordCrypto.personalizedHash(PERSONALIZATION_WEAVER_KEY,
                stretchedLskf);
        if (key.length < mWeaverConfig.keySize) {
            throw new IllegalArgumentException("weaver key length too small");
        }
        return Arrays.copyOf(key, mWeaverConfig.keySize);
    }

    @VisibleForTesting
    protected long sidFromPasswordHandle(byte[] handle) {
        return nativeSidFromPasswordHandle(handle);
    }

    @VisibleForTesting
    protected byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int outLen) {
        return new Scrypt().scrypt(password, salt, n, r, p, outLen);
    }

    private native long nativeSidFromPasswordHandle(byte[] handle);

    @VisibleForTesting
    static byte[] bytesToHex(byte[] bytes) {
        return HexEncoding.encodeToString(bytes).getBytes();
    }

    /**
     * Migrates all existing SP protector keys from uid 1000 app domain to LSS selinux domain.
     */
    public boolean migrateKeyNamespace() {
        boolean success = true;
        final Map<Integer, List<Long>> allProtectors =
            mStorage.listSyntheticPasswordProtectorsForAllUsers(SP_BLOB_NAME);
        for (List<Long> userProtectors : allProtectors.values()) {
            for (long protectorId : userProtectors) {
                success &= SyntheticPasswordCrypto.migrateLockSettingsKey(
                        getProtectorKeyAlias(protectorId));
            }
        }
        return success;
    }

    /** Register the given IWeakEscrowTokenRemovedListener. */
    public boolean registerWeakEscrowTokenRemovedListener(
            IWeakEscrowTokenRemovedListener listener) {
        return mListeners.register(listener);
    }

    /** Unregister the given IWeakEscrowTokenRemovedListener. */
    public boolean unregisterWeakEscrowTokenRemovedListener(
            IWeakEscrowTokenRemovedListener listener) {
        return mListeners.unregister(listener);
    }

    private void notifyWeakEscrowTokenRemovedListeners(long protectorId, int userId) {
        int i = mListeners.beginBroadcast();
        try {
            while (i > 0) {
                i--;
                try {
                    mListeners.getBroadcastItem(i).onWeakEscrowTokenRemoved(protectorId, userId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception while notifying WeakEscrowTokenRemovedListener.",
                            e);
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }

    public void writeVendorAuthSecret(
            @NonNull final byte[] vendorAuthSecret,
            @NonNull final SyntheticPassword sp,
            @UserIdInt final int userId) {
        final byte[] encrypted =
                SyntheticPasswordCrypto.encrypt(
                        sp.deriveVendorAuthSecretEncryptionKey(), new byte[0], vendorAuthSecret);
        saveState(VENDOR_AUTH_SECRET_NAME, encrypted, NULL_PROTECTOR_ID, userId);
        syncState(userId);
    }

    public @Nullable byte[] readVendorAuthSecret(
            @NonNull final SyntheticPassword sp, @UserIdInt final int userId) {
        final byte[] encrypted = loadState(VENDOR_AUTH_SECRET_NAME, NULL_PROTECTOR_ID, userId);
        if (encrypted == null) {
            return null;
        }
        return SyntheticPasswordCrypto.decrypt(
                sp.deriveVendorAuthSecretEncryptionKey(), new byte[0], encrypted);
    }
}
