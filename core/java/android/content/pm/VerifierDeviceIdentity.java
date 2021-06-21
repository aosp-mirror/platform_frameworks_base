/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.pm;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;
import java.util.Random;

/**
 * An identity that uniquely identifies a particular device. In this
 * implementation, the identity is represented as a 64-bit integer encoded to a
 * 13-character string using RFC 4648's Base32 encoding without the trailing
 * padding. This makes it easy for users to read and write the code without
 * confusing 'I' (letter) with '1' (one) or 'O' (letter) with '0' (zero).
 *
 * @hide
 */
public class VerifierDeviceIdentity implements Parcelable {
    /**
     * Encoded size of a long (64-bit) into Base32. This format will end up
     * looking like XXXX-XXXX-XXXX-X (length ignores hyphens) when applied with
     * the GROUP_SIZE below.
     */
    private static final int LONG_SIZE = 13;

    /**
     * Size of groupings when outputting as strings. This helps people read it
     * out and keep track of where they are.
     */
    private static final int GROUP_SIZE = 4;

    private final long mIdentity;

    private final String mIdentityString;

    /**
     * Create a verifier device identity from a long.
     *
     * @param identity device identity in a 64-bit integer.
     * @throws
     */
    public VerifierDeviceIdentity(long identity) {
        mIdentity = identity;
        mIdentityString = encodeBase32(identity);
    }

    private VerifierDeviceIdentity(Parcel source) {
        final long identity = source.readLong();

        mIdentity = identity;
        mIdentityString = encodeBase32(identity);
    }

    /**
     * Generate a new device identity.
     *
     * @return random uniformly-distributed device identity
     */
    public static VerifierDeviceIdentity generate() {
        final SecureRandom sr = new SecureRandom();
        return generate(sr);
    }

    /**
     * Generate a new device identity using a provided random number generator
     * class. This is used for testing.
     *
     * @param rng random number generator to retrieve the next long from
     * @return verifier device identity based on the input from the provided
     *         random number generator
     */
    @VisibleForTesting
    static VerifierDeviceIdentity generate(Random rng) {
        long identity = rng.nextLong();
        return new VerifierDeviceIdentity(identity);
    }

    private static final char ENCODE[] = {
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
        'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
        'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
        'Y', 'Z', '2', '3', '4', '5', '6', '7',
    };

    private static final char SEPARATOR = '-';

    private static final String encodeBase32(long input) {
        final char[] alphabet = ENCODE;

        /*
         * Make a character array with room for the separators between each
         * group.
         */
        final char encoded[] = new char[LONG_SIZE + (LONG_SIZE / GROUP_SIZE)];

        int index = encoded.length;
        for (int i = 0; i < LONG_SIZE; i++) {
            /*
             * Make sure we don't put a separator at the beginning. Since we're
             * building from the rear of the array, we use (LONG_SIZE %
             * GROUP_SIZE) to make the odd-size group appear at the end instead
             * of the beginning.
             */
            if (i > 0 && (i % GROUP_SIZE) == (LONG_SIZE % GROUP_SIZE)) {
                encoded[--index] = SEPARATOR;
            }

            /*
             * Extract 5 bits of data, then shift it out.
             */
            final int group = (int) (input & 0x1F);
            input >>>= 5;

            encoded[--index] = alphabet[group];
        }

        return String.valueOf(encoded);
    }

    // TODO move this out to its own class (android.util.Base32)
    private static final long decodeBase32(byte[] input) throws IllegalArgumentException {
        long output = 0L;
        int numParsed = 0;

        final int N = input.length;
        for (int i = 0; i < N; i++) {
            final int group = input[i];

            /*
             * This essentially does the reverse of the ENCODED alphabet above
             * without a table. A..Z are 0..25 and 2..7 are 26..31.
             */
            final int value;
            if ('A' <= group && group <= 'Z') {
                value = group - 'A';
            } else if ('2' <= group && group <= '7') {
                value = group - ('2' - 26);
            } else if (group == SEPARATOR) {
                continue;
            } else if ('a' <= group && group <= 'z') {
                /* Lowercase letters should be the same as uppercase for Base32 */
                value = group - 'a';
            } else if (group == '0') {
                /* Be nice to users that mistake O (letter) for 0 (zero) */
                value = 'O' - 'A';
            } else if (group == '1') {
                /* Be nice to users that mistake I (letter) for 1 (one) */
                value = 'I' - 'A';
            } else {
                throw new IllegalArgumentException("base base-32 character: " + group);
            }

            output = (output << 5) | value;
            numParsed++;

            if (numParsed == 1) {
                if ((value & 0xF) != value) {
                    throw new IllegalArgumentException("illegal start character; will overflow");
                }
            } else if (numParsed > 13) {
                throw new IllegalArgumentException("too long; should have 13 characters");
            }
        }

        if (numParsed != 13) {
            throw new IllegalArgumentException("too short; should have 13 characters");
        }

        return output;
    }

    @Override
    public int hashCode() {
        return (int) mIdentity;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VerifierDeviceIdentity)) {
            return false;
        }

        final VerifierDeviceIdentity o = (VerifierDeviceIdentity) other;
        return mIdentity == o.mIdentity;
    }

    @Override
    public String toString() {
        return mIdentityString;
    }

    public static VerifierDeviceIdentity parse(String deviceIdentity)
            throws IllegalArgumentException {
        final byte[] input;
        try {
            input = deviceIdentity.getBytes("US-ASCII");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException("bad base-32 characters in input");
        }

        return new VerifierDeviceIdentity(decodeBase32(input));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mIdentity);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<VerifierDeviceIdentity> CREATOR
            = new Parcelable.Creator<VerifierDeviceIdentity>() {
        public VerifierDeviceIdentity createFromParcel(Parcel source) {
            return new VerifierDeviceIdentity(source);
        }

        public VerifierDeviceIdentity[] newArray(int size) {
            return new VerifierDeviceIdentity[size];
        }
    };
}
