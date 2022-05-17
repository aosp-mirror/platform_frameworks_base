/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.widget;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD_OR_PIN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.storage.StorageManager;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import libcore.util.HexEncoding;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class representing a lockscreen credential. It can be either an empty password, a pattern
 * or a password (or PIN).
 *
 * <p> As required by some security certification, the framework tries its best to
 * remove copies of the lockscreen credential bytes from memory. In this regard, this class
 * abuses the {@link AutoCloseable} interface for sanitizing memory. This
 * presents a nice syntax to auto-zeroize memory with the try-with-resource statement:
 * <pre>
 * try {LockscreenCredential credential = LockscreenCredential.createPassword(...) {
 *     // Process the credential in some way
 * }
 * </pre>
 * With this construct, we can guarantee that there will be no copies of the password left in
 * memory when the credential goes out of scope. This should help mitigate certain class of
 * attacks where the attcker gains read-only access to full device memory (cold boot attack,
 * unsecured software/hardware memory dumping interfaces such as JTAG).
 */
public class LockscreenCredential implements Parcelable, AutoCloseable {

    private final int mType;
    // Stores raw credential bytes, or null if credential has been zeroized. An empty password
    // is represented as a byte array of length 0.
    private byte[] mCredential;

    /**
     * Private constructor, use static builder methods instead.
     *
     * <p> Builder methods should create a private copy of the credential bytes and pass in here.
     * LockscreenCredential will only store the reference internally without copying. This is to
     * minimize the number of extra copies introduced.
     */
    private LockscreenCredential(int type, byte[] credential) {
        Objects.requireNonNull(credential);
        if (type == CREDENTIAL_TYPE_NONE) {
            Preconditions.checkArgument(credential.length == 0);
        } else {
            // Do not allow constructing a CREDENTIAL_TYPE_PASSWORD_OR_PIN object.
            Preconditions.checkArgument(type == CREDENTIAL_TYPE_PIN
                    || type == CREDENTIAL_TYPE_PASSWORD
                    || type == CREDENTIAL_TYPE_PATTERN);
            Preconditions.checkArgument(credential.length > 0);
        }
        mType = type;
        mCredential = credential;
    }

    /**
     * Creates a LockscreenCredential object representing empty password.
     */
    public static LockscreenCredential createNone() {
        return new LockscreenCredential(CREDENTIAL_TYPE_NONE, new byte[0]);
    }

    /**
     * Creates a LockscreenCredential object representing the given pattern.
     */
    public static LockscreenCredential createPattern(@NonNull List<LockPatternView.Cell> pattern) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PATTERN,
                LockPatternUtils.patternToByteArray(pattern));
    }

    /**
     * Creates a LockscreenCredential object representing the given alphabetic password.
     */
    public static LockscreenCredential createPassword(@NonNull CharSequence password) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PASSWORD,
                charSequenceToByteArray(password));
    }

    /**
     * Creates a LockscreenCredential object representing a managed password for profile with
     * unified challenge. This credentiall will have type {@code CREDENTIAL_TYPE_PASSWORD} for now.
     * TODO: consider add a new credential type for this. This can then supersede the
     * isLockTiedToParent argument in various places in LSS.
     */
    public static LockscreenCredential createManagedPassword(@NonNull byte[] password) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PASSWORD,
                Arrays.copyOf(password, password.length));
    }

    /**
     * Creates a LockscreenCredential object representing the given numeric PIN.
     */
    public static LockscreenCredential createPin(@NonNull CharSequence pin) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PIN,
                charSequenceToByteArray(pin));
    }

    /**
     * Creates a LockscreenCredential object representing the given alphabetic password.
     * If the supplied password is empty, create an empty credential object.
     */
    public static LockscreenCredential createPasswordOrNone(@Nullable CharSequence password) {
        if (TextUtils.isEmpty(password)) {
            return createNone();
        } else {
            return createPassword(password);
        }
    }

    /**
     * Creates a LockscreenCredential object representing the given numeric PIN.
     * If the supplied password is empty, create an empty credential object.
     */
    public static LockscreenCredential createPinOrNone(@Nullable CharSequence pin) {
        if (TextUtils.isEmpty(pin)) {
            return createNone();
        } else {
            return createPin(pin);
        }
    }

    private void ensureNotZeroized() {
        Preconditions.checkState(mCredential != null, "Credential is already zeroized");
    }
    /**
     * Returns the type of this credential. Can be one of {@link #CREDENTIAL_TYPE_NONE},
     * {@link #CREDENTIAL_TYPE_PATTERN}, {@link #CREDENTIAL_TYPE_PIN} or
     * {@link #CREDENTIAL_TYPE_PASSWORD}.
     */
    public int getType() {
        ensureNotZeroized();
        return mType;
    }

    /**
     * Returns the credential bytes. This is a direct reference of the internal field so
     * callers should not modify it.
     *
     */
    public byte[] getCredential() {
        ensureNotZeroized();
        return mCredential;
    }

    /** Returns whether this is an empty credential */
    public boolean isNone() {
        ensureNotZeroized();
        return mType == CREDENTIAL_TYPE_NONE;
    }

    /** Returns whether this is a pattern credential */
    public boolean isPattern() {
        ensureNotZeroized();
        return mType == CREDENTIAL_TYPE_PATTERN;
    }

    /** Returns whether this is a numeric pin credential */
    public boolean isPin() {
        ensureNotZeroized();
        return mType == CREDENTIAL_TYPE_PIN;
    }

    /** Returns whether this is an alphabetic password credential */
    public boolean isPassword() {
        ensureNotZeroized();
        return mType == CREDENTIAL_TYPE_PASSWORD;
    }

    /** Returns the length of the credential */
    public int size() {
        ensureNotZeroized();
        return mCredential.length;
    }

    /** Create a copy of the credential */
    public LockscreenCredential duplicate() {
        return new LockscreenCredential(mType,
                mCredential != null ? Arrays.copyOf(mCredential, mCredential.length) : null);
    }

    /**
     * Zeroize the credential bytes.
     */
    public void zeroize() {
        if (mCredential != null) {
            Arrays.fill(mCredential, (byte) 0);
            mCredential = null;
        }
    }

    /**
     * Check if the credential meets minimal length requirement.
     *
     * @throws IllegalArgumentException if the credential is too short.
     */
    public void checkLength() {
        if (isNone()) {
            return;
        }
        if (isPattern()) {
            if (size() < LockPatternUtils.MIN_LOCK_PATTERN_SIZE) {
                throw new IllegalArgumentException("pattern must not be null and at least "
                        + LockPatternUtils.MIN_LOCK_PATTERN_SIZE + " dots long.");
            }
            return;
        }
        if (isPassword() || isPin()) {
            if (size() < LockPatternUtils.MIN_LOCK_PASSWORD_SIZE) {
                throw new IllegalArgumentException("password must not be null and at least "
                        + "of length " + LockPatternUtils.MIN_LOCK_PASSWORD_SIZE);
            }
            return;
        }
    }

    /**
     * Check if this credential's type matches one that's retrieved from disk. The nuance here is
     * that the framework used to not distinguish between PIN and password, so this method will
     * allow a PIN/Password LockscreenCredential to match against the legacy
     * {@link #CREDENTIAL_TYPE_PASSWORD_OR_PIN} stored on disk.
     */
    public boolean checkAgainstStoredType(int storedCredentialType) {
        if (storedCredentialType == CREDENTIAL_TYPE_PASSWORD_OR_PIN) {
            return getType() == CREDENTIAL_TYPE_PASSWORD || getType() == CREDENTIAL_TYPE_PIN;
        }
        return getType() == storedCredentialType;
    }

    /**
     * Hash the password for password history check purpose.
     */
    public String passwordToHistoryHash(byte[] salt, byte[] hashFactor) {
        return passwordToHistoryHash(mCredential, salt, hashFactor);
    }

    /**
     * Hash the password for password history check purpose.
     */
    public static String passwordToHistoryHash(
            byte[] passwordToHash, byte[] salt, byte[] hashFactor) {
        if (passwordToHash == null || passwordToHash.length == 0
                || hashFactor == null || salt == null) {
            return null;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(hashFactor);
            byte[] saltedPassword = Arrays.copyOf(passwordToHash, passwordToHash.length
                    + salt.length);
            System.arraycopy(salt, 0, saltedPassword, passwordToHash.length, salt.length);
            sha256.update(saltedPassword);
            Arrays.fill(saltedPassword, (byte) 0);
            return new String(HexEncoding.encode(sha256.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Missing digest algorithm: ", e);
        }
    }

    /**
     * Generate a hash for the given password. To avoid brute force attacks, we use a salted hash.
     * Not the most secure, but it is at least a second level of protection. First level is that
     * the file is in a location only readable by the system process.
     *
     * @return the hash of the pattern in a byte array.
     */
    public String legacyPasswordToHash(byte[] salt) {
        return legacyPasswordToHash(mCredential, salt);
    }

    /**
     * Generate a hash for the given password. To avoid brute force attacks, we use a salted hash.
     * Not the most secure, but it is at least a second level of protection. First level is that
     * the file is in a location only readable by the system process.
     *
     * @param password the gesture pattern.
     *
     * @return the hash of the pattern in a byte array.
     */
    public static String legacyPasswordToHash(byte[] password, byte[] salt) {
        if (password == null || password.length == 0 || salt == null) {
            return null;
        }

        try {
            // Previously the password was passed as a String with the following code:
            // byte[] saltedPassword = (password + salt).getBytes();
            // The code below creates the identical digest preimage using byte arrays:
            byte[] saltedPassword = Arrays.copyOf(password, password.length + salt.length);
            System.arraycopy(salt, 0, saltedPassword, password.length, salt.length);
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(saltedPassword);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(saltedPassword);

            byte[] combined = new byte[sha1.length + md5.length];
            System.arraycopy(sha1, 0, combined, 0, sha1.length);
            System.arraycopy(md5, 0, combined, sha1.length, md5.length);

            final char[] hexEncoded = HexEncoding.encode(combined);
            Arrays.fill(saltedPassword, (byte) 0);
            return new String(hexEncoded);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Missing digest algorithm: ", e);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeByteArray(mCredential);
    }

    public static final Parcelable.Creator<LockscreenCredential> CREATOR =
            new Parcelable.Creator<LockscreenCredential>() {

        @Override
        public LockscreenCredential createFromParcel(Parcel source) {
            return new LockscreenCredential(source.readInt(), source.createByteArray());
        }

        @Override
        public LockscreenCredential[] newArray(int size) {
            return new LockscreenCredential[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void close() {
        zeroize();
    }

    @Override
    public int hashCode() {
        // Effective Java — Always override hashCode when you override equals
        return (17 + mType) * 31 + mCredential.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof LockscreenCredential)) return false;
        final LockscreenCredential other = (LockscreenCredential) o;
        return mType == other.mType && Arrays.equals(mCredential, other.mCredential);
    }

    /**
     * Converts a CharSequence to a byte array without requiring a toString(), which creates an
     * additional copy.
     *
     * @param chars The CharSequence to convert
     * @return A byte array representing the input
     */
    private static byte[] charSequenceToByteArray(CharSequence chars) {
        if (chars == null) {
            return new byte[0];
        }
        byte[] bytes = new byte[chars.length()];
        for (int i = 0; i < chars.length(); i++) {
            bytes[i] = (byte) chars.charAt(i);
        }
        return bytes;
    }
}
