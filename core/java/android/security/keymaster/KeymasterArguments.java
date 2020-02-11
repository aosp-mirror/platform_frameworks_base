/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keymaster;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Utility class for the java side of user specified Keymaster arguments.
 * <p>
 * Serialization code for this and subclasses must be kept in sync with system/security/keystore
 * @hide
 */
public class KeymasterArguments implements Parcelable {

    private static final long UINT32_RANGE = 1L << 32;
    public static final long UINT32_MAX_VALUE = UINT32_RANGE - 1;

    private static final BigInteger UINT64_RANGE = BigInteger.ONE.shiftLeft(64);
    public static final BigInteger UINT64_MAX_VALUE = UINT64_RANGE.subtract(BigInteger.ONE);

    private List<KeymasterArgument> mArguments;

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<KeymasterArguments> CREATOR = new
            Parcelable.Creator<KeymasterArguments>() {
                @Override
                public KeymasterArguments createFromParcel(Parcel in) {
                    return new KeymasterArguments(in);
                }

                @Override
                public KeymasterArguments[] newArray(int size) {
                    return new KeymasterArguments[size];
                }
            };

    @UnsupportedAppUsage
    public KeymasterArguments() {
        mArguments = new ArrayList<KeymasterArgument>();
    }

    private KeymasterArguments(Parcel in) {
        mArguments = in.createTypedArrayList(KeymasterArgument.CREATOR);
    }

    /**
     * Adds an enum tag with the provided value.
     *
     * @throws IllegalArgumentException if {@code tag} is not an enum tag.
     */
    @UnsupportedAppUsage
    public void addEnum(int tag, int value) {
        int tagType = KeymasterDefs.getTagType(tag);
        if ((tagType != KeymasterDefs.KM_ENUM) && (tagType != KeymasterDefs.KM_ENUM_REP)) {
            throw new IllegalArgumentException("Not an enum or repeating enum tag: " + tag);
        }
        addEnumTag(tag, value);
    }

    /**
     * Adds a repeated enum tag with the provided values.
     *
     * @throws IllegalArgumentException if {@code tag} is not a repeating enum tag.
     */
    public void addEnums(int tag, int... values) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_ENUM_REP) {
            throw new IllegalArgumentException("Not a repeating enum tag: " + tag);
        }
        for (int value : values) {
            addEnumTag(tag, value);
        }
    }

    /**
     * Returns the value of the specified enum tag or {@code defaultValue} if the tag is not
     * present.
     *
     * @throws IllegalArgumentException if {@code tag} is not an enum tag.
     */
    public int getEnum(int tag, int defaultValue) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_ENUM) {
            throw new IllegalArgumentException("Not an enum tag: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        return getEnumTagValue(arg);
    }

    /**
     * Returns all values of the specified repeating enum tag.
     *
     * throws IllegalArgumentException if {@code tag} is not a repeating enum tag.
     */
    public List<Integer> getEnums(int tag) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_ENUM_REP) {
            throw new IllegalArgumentException("Not a repeating enum tag: " + tag);
        }
        List<Integer> values = new ArrayList<Integer>();
        for (KeymasterArgument arg : mArguments) {
            if (arg.tag == tag) {
                values.add(getEnumTagValue(arg));
            }
        }
        return values;
    }

    private void addEnumTag(int tag, int value) {
        mArguments.add(new KeymasterIntArgument(tag, value));
    }

    private int getEnumTagValue(KeymasterArgument arg) {
        return ((KeymasterIntArgument) arg).value;
    }

    /**
     * Adds an unsigned 32-bit int tag with the provided value.
     *
     * @throws IllegalArgumentException if {@code tag} is not an unsigned 32-bit int tag or if
     *         {@code value} is outside of the permitted range [0; 2^32).
     */
    @UnsupportedAppUsage
    public void addUnsignedInt(int tag, long value) {
        int tagType = KeymasterDefs.getTagType(tag);
        if ((tagType != KeymasterDefs.KM_UINT) && (tagType != KeymasterDefs.KM_UINT_REP)) {
            throw new IllegalArgumentException("Not an int or repeating int tag: " + tag);
        }
        // Keymaster's KM_UINT is unsigned 32 bit.
        if ((value < 0) || (value > UINT32_MAX_VALUE)) {
            throw new IllegalArgumentException("Int tag value out of range: " + value);
        }
        mArguments.add(new KeymasterIntArgument(tag, (int) value));
    }

    /**
     * Returns the value of the specified unsigned 32-bit int tag or {@code defaultValue} if the tag
     * is not present.
     *
     * @throws IllegalArgumentException if {@code tag} is not an unsigned 32-bit int tag.
     */
    public long getUnsignedInt(int tag, long defaultValue) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_UINT) {
            throw new IllegalArgumentException("Not an int tag: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        // Keymaster's KM_UINT is unsigned 32 bit.
        return ((KeymasterIntArgument) arg).value & 0xffffffffL;
    }

    /**
     * Adds an unsigned 64-bit long tag with the provided value.
     *
     * @throws IllegalArgumentException if {@code tag} is not an unsigned 64-bit long tag or if
     *         {@code value} is outside of the permitted range [0; 2^64).
     */
    @UnsupportedAppUsage
    public void addUnsignedLong(int tag, BigInteger value) {
        int tagType = KeymasterDefs.getTagType(tag);
        if ((tagType != KeymasterDefs.KM_ULONG) && (tagType != KeymasterDefs.KM_ULONG_REP)) {
            throw new IllegalArgumentException("Not a long or repeating long tag: " + tag);
        }
        addLongTag(tag, value);
    }

    /**
     * Returns all values of the specified repeating unsigned 64-bit long tag.
     *
     * @throws IllegalArgumentException if {@code tag} is not a repeating unsigned 64-bit long tag.
     */
    public List<BigInteger> getUnsignedLongs(int tag) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_ULONG_REP) {
            throw new IllegalArgumentException("Tag is not a repeating long: " + tag);
        }
        List<BigInteger> values = new ArrayList<BigInteger>();
        for (KeymasterArgument arg : mArguments) {
            if (arg.tag == tag) {
                values.add(getLongTagValue(arg));
            }
        }
        return values;
    }

    private void addLongTag(int tag, BigInteger value) {
        // Keymaster's KM_ULONG is unsigned 64 bit.
        if ((value.signum() == -1) || (value.compareTo(UINT64_MAX_VALUE) > 0)) {
            throw new IllegalArgumentException("Long tag value out of range: " + value);
        }
        mArguments.add(new KeymasterLongArgument(tag, value.longValue()));
    }

    private BigInteger getLongTagValue(KeymasterArgument arg) {
        // Keymaster's KM_ULONG is unsigned 64 bit. We're forced to use BigInteger for type safety
        // because there's no unsigned long type.
        return toUint64(((KeymasterLongArgument) arg).value);
    }

    /**
     * Adds the provided boolean tag. Boolean tags are considered to be set to {@code true} if
     * present and {@code false} if absent.
     *
     * @throws IllegalArgumentException if {@code tag} is not a boolean tag.
     */
    public void addBoolean(int tag) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_BOOL) {
            throw new IllegalArgumentException("Not a boolean tag: " + tag);
        }
        mArguments.add(new KeymasterBooleanArgument(tag));
    }

    /**
     * Returns {@code true} if the provided boolean tag is present, {@code false} if absent.
     *
     * @throws IllegalArgumentException if {@code tag} is not a boolean tag.
     */
    public boolean getBoolean(int tag) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_BOOL) {
            throw new IllegalArgumentException("Not a boolean tag: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return false;
        }
        return true;
    }

    /**
     * Adds a bytes tag with the provided value.
     *
     * @throws IllegalArgumentException if {@code tag} is not a bytes tag.
     */
    public void addBytes(int tag, byte[] value) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_BYTES) {
            throw new IllegalArgumentException("Not a bytes tag: " + tag);
        }
        if (value == null) {
            throw new NullPointerException("value == nulll");
        }
        mArguments.add(new KeymasterBlobArgument(tag, value));
    }

    /**
     * Returns the value of the specified bytes tag or {@code defaultValue} if the tag is not
     * present.
     *
     * @throws IllegalArgumentException if {@code tag} is not a bytes tag.
     */
    public byte[] getBytes(int tag, byte[] defaultValue) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_BYTES) {
            throw new IllegalArgumentException("Not a bytes tag: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        return ((KeymasterBlobArgument) arg).blob;
    }

    /**
     * Adds a date tag with the provided value.
     *
     * @throws IllegalArgumentException if {@code tag} is not a date tag or if {@code value} is
     *         before the start of Unix epoch.
     */
    public void addDate(int tag, Date value) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_DATE) {
            throw new IllegalArgumentException("Not a date tag: " + tag);
        }
        if (value == null) {
            throw new NullPointerException("value == nulll");
        }
        // Keymaster's KM_DATE is unsigned, but java.util.Date is signed, thus preventing us from
        // using values larger than 2^63 - 1.
        if (value.getTime() < 0) {
            throw new IllegalArgumentException("Date tag value out of range: " + value);
        }
        mArguments.add(new KeymasterDateArgument(tag, value));
    }

    /**
     * Adds a date tag with the provided value, if the value is not {@code null}. Does nothing if
     * the {@code value} is null.
     *
     * @throws IllegalArgumentException if {@code tag} is not a date tag or if {@code value} is
     *         before the start of Unix epoch.
     */
    public void addDateIfNotNull(int tag, Date value) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_DATE) {
            throw new IllegalArgumentException("Not a date tag: " + tag);
        }
        if (value != null) {
            addDate(tag, value);
        }
    }

    /**
     * Returns the value of the specified date tag or {@code defaultValue} if the tag is not
     * present.
     *
     * @throws IllegalArgumentException if {@code tag} is not a date tag or if the tag's value
     *         represents a time instant which is after {@code 2^63 - 1} milliseconds since Unix
     *         epoch.
     */
    public Date getDate(int tag, Date defaultValue) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_DATE) {
            throw new IllegalArgumentException("Tag is not a date type: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        Date result = ((KeymasterDateArgument) arg).date;
        // Keymaster's KM_DATE is unsigned, but java.util.Date is signed, thus preventing us from
        // using values larger than 2^63 - 1.
        if (result.getTime() < 0) {
            throw new IllegalArgumentException("Tag value too large. Tag: " + tag);
        }
        return result;
    }

    private KeymasterArgument getArgumentByTag(int tag) {
        for (KeymasterArgument arg : mArguments) {
            if (arg.tag == tag) {
                return arg;
            }
        }
        return null;
    }

    public boolean containsTag(int tag) {
        return getArgumentByTag(tag) != null;
    }

    public int size() {
        return mArguments.size();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(mArguments);
    }

    @UnsupportedAppUsage
    public void readFromParcel(Parcel in) {
        in.readTypedList(mArguments, KeymasterArgument.CREATOR);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Converts the provided value to non-negative {@link BigInteger}, treating the sign bit of the
     * provided value as the most significant bit of the result.
     */
    public static BigInteger toUint64(long value) {
        if (value >= 0) {
            return BigInteger.valueOf(value);
        } else {
            return BigInteger.valueOf(value).add(UINT64_RANGE);
        }
    }
}
