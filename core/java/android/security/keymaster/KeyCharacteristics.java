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

import android.os.Parcel;
import android.os.Parcelable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @hide
 */
public class KeyCharacteristics implements Parcelable {
    public KeymasterArguments swEnforced;
    public KeymasterArguments hwEnforced;

    public static final Parcelable.Creator<KeyCharacteristics> CREATOR =
            new Parcelable.Creator<KeyCharacteristics>() {
                @Override
                public KeyCharacteristics createFromParcel(Parcel in) {
                    return new KeyCharacteristics(in);
                }

                @Override
                public KeyCharacteristics[] newArray(int length) {
                    return new KeyCharacteristics[length];
                }
            };

    public KeyCharacteristics() {}

    protected KeyCharacteristics(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        swEnforced.writeToParcel(out, flags);
        hwEnforced.writeToParcel(out, flags);
    }

    public void readFromParcel(Parcel in) {
        swEnforced = KeymasterArguments.CREATOR.createFromParcel(in);
        hwEnforced = KeymasterArguments.CREATOR.createFromParcel(in);
    }

    /**
     * Returns the value of the specified enum tag or {@code defaultValue} if the tag is not
     * present.
     *
     * @throws IllegalArgumentException if {@code tag} is not an enum tag.
     */
    public Integer getEnum(int tag) {
        if (hwEnforced.containsTag(tag)) {
            return hwEnforced.getEnum(tag, -1);
        } else if (swEnforced.containsTag(tag)) {
            return swEnforced.getEnum(tag, -1);
        } else {
            return null;
        }
    }

    /**
     * Returns all values of the specified repeating enum tag.
     *
     * throws IllegalArgumentException if {@code tag} is not a repeating enum tag.
     */
    public List<Integer> getEnums(int tag) {
        List<Integer> result = new ArrayList<Integer>();
        result.addAll(hwEnforced.getEnums(tag));
        result.addAll(swEnforced.getEnums(tag));
        return result;
    }

    /**
     * Returns the value of the specified unsigned 32-bit int tag or {@code defaultValue} if the tag
     * is not present.
     *
     * @throws IllegalArgumentException if {@code tag} is not an unsigned 32-bit int tag.
     */
    public long getUnsignedInt(int tag, long defaultValue) {
        if (hwEnforced.containsTag(tag)) {
            return hwEnforced.getUnsignedInt(tag, defaultValue);
        } else {
            return swEnforced.getUnsignedInt(tag, defaultValue);
        }
    }

    /**
     * Returns all values of the specified repeating unsigned 64-bit long tag.
     *
     * @throws IllegalArgumentException if {@code tag} is not a repeating unsigned 64-bit long tag.
     */
    public List<BigInteger> getUnsignedLongs(int tag) {
        List<BigInteger> result = new ArrayList<BigInteger>();
        result.addAll(hwEnforced.getUnsignedLongs(tag));
        result.addAll(swEnforced.getUnsignedLongs(tag));
        return result;
    }

    /**
     * Returns the value of the specified date tag or {@code null} if the tag is not present.
     *
     * @throws IllegalArgumentException if {@code tag} is not a date tag or if the tag's value
     *         represents a time instant which is after {@code 2^63 - 1} milliseconds since Unix
     *         epoch.
     */
    public Date getDate(int tag) {
        Date result = swEnforced.getDate(tag, null);
        if (result != null) {
            return result;
        }
        return hwEnforced.getDate(tag, null);
    }

    /**
     * Returns {@code true} if the provided boolean tag is present, {@code false} if absent.
     *
     * @throws IllegalArgumentException if {@code tag} is not a boolean tag.
     */
    public boolean getBoolean(int tag) {
        if (hwEnforced.containsTag(tag)) {
            return hwEnforced.getBoolean(tag);
        } else {
            return swEnforced.getBoolean(tag);
        }
    }
}

