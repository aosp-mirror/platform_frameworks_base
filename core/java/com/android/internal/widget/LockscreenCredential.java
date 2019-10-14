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

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.List;

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
 * With this construct, we can garantee that there will be no copies of the password left in
 * memory when the credential goes out of scope. This should help mitigate certain class of
 * attacks where the attcker gains read-only access to full device memory (cold boot attack,
 * unsecured software/hardware memory dumping interfaces such as JTAG).
 */
public class LockscreenCredential implements Parcelable, AutoCloseable {

    private final int mType;
    // Stores raw credential bytes, or null if credential has been zeroized. An empty password
    // is represented as a byte array of length 0.
    private byte[] mCredential;
    // Store the quality of the password, this is used to distinguish between pin
    // (PASSWORD_QUALITY_NUMERIC) and password (PASSWORD_QUALITY_ALPHABETIC).
    private final int mQuality;

    /**
     * Private constructor, use static builder methods instead.
     *
     * <p> Builder methods should create a private copy of the credential bytes and pass in here.
     * LockscreenCredential will only store the reference internally without copying. This is to
     * minimize the number of extra copies introduced.
     */
    private LockscreenCredential(int type, int quality, byte[] credential) {
        Preconditions.checkNotNull(credential);
        if (type == CREDENTIAL_TYPE_NONE) {
            Preconditions.checkArgument(credential.length == 0);
        } else {
            Preconditions.checkArgument(credential.length > 0);
        }
        mType = type;
        mQuality = quality;
        mCredential = credential;
    }

    /**
     * Creates a LockscreenCredential object representing empty password.
     */
    public static LockscreenCredential createNone() {
        return new LockscreenCredential(CREDENTIAL_TYPE_NONE, PASSWORD_QUALITY_UNSPECIFIED,
                new byte[0]);
    }

    /**
     * Creates a LockscreenCredential object representing the given pattern.
     */
    public static LockscreenCredential createPattern(@NonNull List<LockPatternView.Cell> pattern) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PATTERN,
                PASSWORD_QUALITY_SOMETHING,
                LockPatternUtils.patternToByteArray(pattern));
    }

    /**
     * Creates a LockscreenCredential object representing the given alphabetic password.
     */
    public static LockscreenCredential createPassword(@NonNull CharSequence password) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PASSWORD,
                PASSWORD_QUALITY_ALPHABETIC,
                charSequenceToByteArray(password));
    }

    /**
     * Creates a LockscreenCredential object representing the given numeric PIN.
     */
    public static LockscreenCredential createPin(@NonNull CharSequence pin) {
        return new LockscreenCredential(CREDENTIAL_TYPE_PASSWORD,
                PASSWORD_QUALITY_NUMERIC,
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

    /**
     * Create a LockscreenCredential object based on raw credential and type
     * TODO: Remove once LSS.setUserPasswordMetrics accepts a LockscreenCredential
     */
    public static LockscreenCredential createRaw(int type, byte[] credential) {
        if (type == CREDENTIAL_TYPE_NONE) {
            return createNone();
        } else {
            return new LockscreenCredential(type, PASSWORD_QUALITY_UNSPECIFIED, credential);
        }
    }

    private void ensureNotZeroized() {
        Preconditions.checkState(mCredential != null, "Credential is already zeroized");
    }
    /**
     * Returns the type of this credential. Can be one of {@link #CREDENTIAL_TYPE_NONE},
     * {@link #CREDENTIAL_TYPE_PATTERN} or {@link #CREDENTIAL_TYPE_PASSWORD}.
     *
     * TODO: Remove once credential type is internal. Callers should use {@link #isNone},
     * {@link #isPattern} and {@link #isPassword} instead.
     */
    public int getType() {
        ensureNotZeroized();
        return mType;
    }

    /**
     * Returns the quality type of the credential
     */
    public int getQuality() {
        ensureNotZeroized();
        return mQuality;
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

    /** Returns whether this is a password credential */
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
        return new LockscreenCredential(mType, mQuality,
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

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mQuality);
        dest.writeByteArray(mCredential);
    }

    public static final Parcelable.Creator<LockscreenCredential> CREATOR =
            new Parcelable.Creator<LockscreenCredential>() {

        @Override
        public LockscreenCredential createFromParcel(Parcel source) {
            return new LockscreenCredential(source.readInt(), source.readInt(),
                    source.createByteArray());
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
        // Effective Java — Item 9
        return ((17 + mType) * 31 + mQuality) * 31 + mCredential.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof LockscreenCredential)) return false;
        final LockscreenCredential other = (LockscreenCredential) o;
        return mType == other.mType && mQuality == other.mQuality
                && Arrays.equals(mCredential, other.mCredential);
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
