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
import android.text.TextUtils;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import libcore.util.HexEncoding;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class representing a lockscreen credential, also called a Lock Screen Knowledge Factor (LSKF).
 * It can be a PIN, pattern, password, or none (a.k.a. empty).
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
 * With this construct, we can guarantee that there will be no copies of the credential left in
 * memory when the object goes out of scope. This should help mitigate certain class of attacks
 * where the attacker gains read-only access to full device memory (cold boot attack, unsecured
 * software/hardware memory dumping interfaces such as JTAG).
 */
public class LockscreenCredential implements Parcelable, AutoCloseable {

    private final int mType;
    // Stores raw credential bytes, or null if credential has been zeroized. A none credential
    // is represented as a byte array of length 0.
    private byte[] mCredential;

    // This indicates that the credential is a password that used characters outside ASCII 32–127.
    //
    // Such passwords were never intended to be allowed.  However, Android 10–14 had a bug where
    // conversion from the chars the user entered to the credential bytes used a simple truncation.
    // Thus, any 'char' whose remainder mod 256 was in the range 32–127 was accepted and was
    // equivalent to some ASCII character.  For example, ™, which is U+2122, was truncated to ASCII
    // 0x22 which is the double-quote character ".
    //
    // We have to continue to allow a LockscreenCredential to be constructed with this bug, so that
    // existing devices can be unlocked if their password used this bug.  However, we prevent new
    // passwords that use this bug from being set.  The boolean below keeps track of the information
    // needed to do that check, since the conversion to mCredential may have been lossy.
    private final boolean mHasInvalidChars;

    /**
     * Private constructor, use static builder methods instead.
     *
     * <p> Builder methods should create a private copy of the credential bytes and pass in here.
     * LockscreenCredential will only store the reference internally without copying. This is to
     * minimize the number of extra copies introduced.
     */
    private LockscreenCredential(int type, byte[] credential, boolean hasInvalidChars) {
        Objects.requireNonNull(credential);
        if (type == CREDENTIAL_TYPE_NONE) {
            Preconditions.checkArgument(credential.length == 0);
        } else {
            // Do not allow constructing a CREDENTIAL_TYPE_PASSWORD_OR_PIN object.
            Preconditions.checkArgument(type == CREDENTIAL_TYPE_PIN
                    || type == CREDENTIAL_TYPE_PASSWORD
                    || type == CREDENTIAL_TYPE_PATTERN);
            // Do not validate credential.length yet.  All non-none credentials have a minimum
            // length requirement; however, one of the uses of LockscreenCredential is to represent
            // a proposed credential that might be too short.  For example, a LockscreenCredential
            // with type CREDENTIAL_TYPE_PIN and length 0 represents an attempt to set an empty PIN.
            // This differs from an actual attempt to set a none credential.  We have to allow the
            // LockscreenCredential object to be constructed so that the validation logic can run,
            // even though the validation logic will ultimately reject the credential as too short.
        }
        Preconditions.checkArgument(!hasInvalidChars || type == CREDENTIAL_TYPE_PASSWORD);
        mType = type;
        mCredential = credential;
        mHasInvalidChars = hasInvalidChars;
    }

    private LockscreenCredential(int type, CharSequence credential) {
        this(type, charsToBytesTruncating(credential), hasInvalidChars(credential));
    }

    /**
     * Creates a LockscreenCredential object representing a none credential.
     */
    public static LockscreenCredential createNone() {
        return new LockscreenCredential(CREDENTIAL_TYPE_NONE, new byte[0], false);
    }

    /**
     * Creates a LockscreenCredential object representing the given pattern.
     */
    public static LockscreenCredential createPattern(@NonNull List<LockPatternView.Cell> pattern) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PATTERN,
                LockPatternUtils.patternToByteArray(pattern), /* hasInvalidChars= */ false);
    }

    /**
     * Creates a LockscreenCredential object representing the given alphabetic password.
     */
    public static LockscreenCredential createPassword(@NonNull CharSequence password) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PASSWORD, password);
    }

    /**
     * Creates a LockscreenCredential object representing a managed password for profile with
     * unified challenge. This credentiall will have type {@code CREDENTIAL_TYPE_PASSWORD} for now.
     * TODO: consider add a new credential type for this. This can then supersede the
     * isLockTiedToParent argument in various places in LSS.
     */
    public static LockscreenCredential createManagedPassword(@NonNull byte[] password) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PASSWORD,
                Arrays.copyOf(password, password.length), /* hasInvalidChars= */ false);
    }

    /**
     * Creates a LockscreenCredential object representing the given numeric PIN.
     */
    public static LockscreenCredential createPin(@NonNull CharSequence pin) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PIN, pin);
    }

    /**
     * Creates a LockscreenCredential object representing the given alphabetic password.
     * If the supplied password is empty, create a none credential object.
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
     * If the supplied password is empty, create a none credential object.
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

    /** Returns whether this is a none credential */
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

    /** Returns true if this credential was constructed with any chars outside the allowed range */
    public boolean hasInvalidChars() {
        ensureNotZeroized();
        return mHasInvalidChars;
    }

    /** Create a copy of the credential */
    public LockscreenCredential duplicate() {
        return new LockscreenCredential(mType,
                mCredential != null ? Arrays.copyOf(mCredential, mCredential.length) : null,
                mHasInvalidChars);
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
     * Checks whether the credential meets basic requirements for setting it as a new credential.
     *
     * This is redundant if {@link android.app.admin.PasswordMetrics#validateCredential()}, which
     * does more comprehensive checks, is correctly called first (which it should be).
     *
     * @throws IllegalArgumentException if the credential contains invalid characters or is too
     * short
     */
    public void validateBasicRequirements() {
        switch (getType()) {
            case CREDENTIAL_TYPE_PATTERN:
                if (size() < LockPatternUtils.MIN_LOCK_PATTERN_SIZE) {
                    throw new IllegalArgumentException("pattern must be at least "
                            + LockPatternUtils.MIN_LOCK_PATTERN_SIZE + " dots long.");
                }
                break;
            case CREDENTIAL_TYPE_PIN:
                if (size() < LockPatternUtils.MIN_LOCK_PASSWORD_SIZE) {
                    throw new IllegalArgumentException("PIN must be at least "
                            + LockPatternUtils.MIN_LOCK_PASSWORD_SIZE + " digits long.");
                }
                break;
            case CREDENTIAL_TYPE_PASSWORD:
                if (mHasInvalidChars) {
                    throw new IllegalArgumentException("password contains invalid characters");
                }
                if (size() < LockPatternUtils.MIN_LOCK_PASSWORD_SIZE) {
                    throw new IllegalArgumentException("password must be at least "
                            + LockPatternUtils.MIN_LOCK_PASSWORD_SIZE + " characters long.");
                }
                break;
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
            sha256.update(passwordToHash);
            sha256.update(salt);
            return HexEncoding.encodeToString(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Missing digest algorithm: ", e);
        }
    }

    /**
     * Hash the given password for the password history, using the legacy algorithm.
     *
     * @deprecated This algorithm is insecure because the password can be easily bruteforced, given
     *             the hash and salt.  Use {@link #passwordToHistoryHash(byte[], byte[], byte[])}
     *             instead, which incorporates an SP-derived secret into the hash.
     *
     * @return the legacy password hash
     */
    @Deprecated
    public static String legacyPasswordToHash(byte[] password, byte[] salt) {
        if (password == null || password.length == 0 || salt == null) {
            return null;
        }

        try {
            byte[] saltedPassword = ArrayUtils.concat(password, salt);
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(saltedPassword);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(saltedPassword);

            Arrays.fill(saltedPassword, (byte) 0);
            return HexEncoding.encodeToString(ArrayUtils.concat(sha1, md5));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Missing digest algorithm: ", e);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeByteArray(mCredential);
        dest.writeBoolean(mHasInvalidChars);
    }

    public static final Parcelable.Creator<LockscreenCredential> CREATOR =
            new Parcelable.Creator<LockscreenCredential>() {

        @Override
        public LockscreenCredential createFromParcel(Parcel source) {
            return new LockscreenCredential(source.readInt(), source.createByteArray(),
                    source.readBoolean());
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
        return Objects.hash(mType, Arrays.hashCode(mCredential), mHasInvalidChars);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof LockscreenCredential)) return false;
        final LockscreenCredential other = (LockscreenCredential) o;
        return mType == other.mType && Arrays.equals(mCredential, other.mCredential)
            && mHasInvalidChars == other.mHasInvalidChars;
    }

    private static boolean hasInvalidChars(CharSequence chars) {
        //
        // Consider the password to have invalid characters if it contains any non-ASCII characters
        // or control characters.  There are multiple reasons for this restriction:
        //
        // - Non-ASCII characters might only be possible to enter on a third-party keyboard app
        //   (IME) that is available when setting the password but not when verifying it after a
        //   reboot.  This can happen if the keyboard is not direct boot aware or gets uninstalled.
        //
        // - Unicode strings that look identical to the user can map to different byte[].  Yet, only
        //   one byte[] can be accepted.  Unicode normalization can solve this problem to some
        //   extent, but still many Unicode characters look similar and could cause confusion.
        //
        // - For backwards compatibility reasons, the upper 8 bits of the 16-bit 'chars' are
        //   discarded by charsToBytesTruncating().  Thus, as-is passwords with characters above
        //   U+00FF (255) are not as secure as they should be.  IMPORTANT: Do not change the below
        //   code to allow characters above U+00FF (255) without fixing this issue!
        //
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            if (c < 32 || c > 127) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a CharSequence to a byte array, intentionally truncating chars greater than 255 for
     * backwards compatibility reasons.  See {@link #mHasInvalidChars}.
     *
     * @param chars The CharSequence to convert
     * @return A byte array representing the input
     */
    private static byte[] charsToBytesTruncating(CharSequence chars) {
        byte[] bytes = new byte[chars.length()];
        for (int i = 0; i < chars.length(); i++) {
            bytes[i] = (byte) chars.charAt(i);
        }
        return bytes;
    }
}
