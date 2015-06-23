/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
    List<KeymasterArgument> mArguments;

    public static final Parcelable.Creator<KeymasterArguments> CREATOR = new
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

    public KeymasterArguments() {
        mArguments = new ArrayList<KeymasterArgument>();
    }

    private KeymasterArguments(Parcel in) {
        mArguments = in.createTypedArrayList(KeymasterArgument.CREATOR);
    }

    public void addInt(int tag, int value) {
        mArguments.add(new KeymasterIntArgument(tag, value));
    }

    public void addInts(int tag, int... values) {
        for (int value : values) {
            addInt(tag, value);
        }
    }

    public void addLongs(int tag, long... values) {
        for (long value : values) {
            addLong(tag, value);
        }
    }

    public void addBoolean(int tag) {
        mArguments.add(new KeymasterBooleanArgument(tag));
    }

    public void addLong(int tag, long value) {
        mArguments.add(new KeymasterLongArgument(tag, value));
    }

    public void addBlob(int tag, byte[] value) {
        mArguments.add(new KeymasterBlobArgument(tag, value));
    }

    public void addDate(int tag, Date value) {
        mArguments.add(new KeymasterDateArgument(tag, value));
    }

    public void addDateIfNotNull(int tag, Date value) {
        if (value != null) {
            mArguments.add(new KeymasterDateArgument(tag, value));
        }
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

    public int getInt(int tag, int defaultValue) {
        switch (KeymasterDefs.getTagType(tag)) {
            case KeymasterDefs.KM_ENUM:
            case KeymasterDefs.KM_INT:
                break; // Accepted types
            case KeymasterDefs.KM_INT_REP:
            case KeymasterDefs.KM_ENUM_REP:
                throw new IllegalArgumentException("Repeatable tags must use getInts: " + tag);
            default:
                throw new IllegalArgumentException("Tag is not an int type: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        return ((KeymasterIntArgument) arg).value;
    }

    public long getLong(int tag, long defaultValue) {
        switch (KeymasterDefs.getTagType(tag)) {
            case KeymasterDefs.KM_LONG:
                break; // Accepted type
            case KeymasterDefs.KM_LONG_REP:
                throw new IllegalArgumentException("Repeatable tags must use getLongs: " + tag);
            default:
                throw new IllegalArgumentException("Tag is not a long type: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        return ((KeymasterLongArgument) arg).value;
    }

    public Date getDate(int tag, Date defaultValue) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_DATE) {
            throw new IllegalArgumentException("Tag is not a date type: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        return ((KeymasterDateArgument) arg).date;
    }

    public boolean getBoolean(int tag, boolean defaultValue) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_BOOL) {
            throw new IllegalArgumentException("Tag is not a boolean type: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        return true;
    }

    public byte[] getBlob(int tag, byte[] defaultValue) {
        switch (KeymasterDefs.getTagType(tag)) {
            case KeymasterDefs.KM_BYTES:
            case KeymasterDefs.KM_BIGNUM:
                break; // Allowed types.
            default:
                throw new IllegalArgumentException("Tag is not a blob type: " + tag);
        }
        KeymasterArgument arg = getArgumentByTag(tag);
        if (arg == null) {
            return defaultValue;
        }
        return ((KeymasterBlobArgument) arg).blob;
    }

    public List<Integer> getInts(int tag) {
        switch (KeymasterDefs.getTagType(tag)) {
            case KeymasterDefs.KM_INT_REP:
            case KeymasterDefs.KM_ENUM_REP:
                break; // Allowed types.
            default:
                throw new IllegalArgumentException("Tag is not a repeating type: " + tag);
        }
        List<Integer> values = new ArrayList<Integer>();
        for (KeymasterArgument arg : mArguments) {
            if (arg.tag == tag) {
                values.add(((KeymasterIntArgument) arg).value);
            }
        }
        return values;
    }

    public List<Long> getLongs(int tag) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_LONG_REP) {
            throw new IllegalArgumentException("Tag is not a repeating long: " + tag);
        }
        List<Long> values = new ArrayList<Long>();
        for (KeymasterArgument arg : mArguments) {
            if (arg.tag == tag) {
                values.add(((KeymasterLongArgument) arg).value);
            }
        }
        return values;
    }

    public int size() {
        return mArguments.size();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(mArguments);
    }

    public void readFromParcel(Parcel in) {
        in.readTypedList(mArguments, KeymasterArgument.CREATOR);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
