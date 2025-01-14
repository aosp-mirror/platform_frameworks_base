/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.service.settings.preferences;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This objects represents a value that can be used for a particular settings preference.
 * <p>The data type for the value will correspond to {@link #getType}. For possible types, see
 * constants below, such as {@link #TYPE_BOOLEAN} and {@link #TYPE_STRING}.
 * Depending on the type, the corresponding getter will contain its value.
 * <p>See documentation on the constants for which getter method should be used.
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class SettingsPreferenceValue implements Parcelable {

    @Type
    private final int mType;
    private final @Nullable Object mValue;

    /**
     * Returns the type indicator for Preference value.
     */
    @Type
    public int getType() {
        return mType;
    }

    /**
     * Returns the boolean value for Preference, the type must be {@link #TYPE_BOOLEAN}.
     */
    public boolean getBooleanValue() {
        return (boolean) mValue;
    }

    /**
     * Returns the int value for Preference, the type must be {@link #TYPE_INT}.
     */
    public int getIntValue() {
        return (int) mValue;
    }

    /**
     * Returns the long value for Preference, the type must be {@link #TYPE_LONG}.
     */
    public long getLongValue() {
        return (long) mValue;
    }

    /**
     * Returns the double value for Preference, the type must be {@link #TYPE_DOUBLE}.
     */
    public double getDoubleValue() {
        return (double) mValue;
    }

    /**
     * Returns the string value for Preference, the type must be {@link #TYPE_STRING}.
     */
    @Nullable
    public String getStringValue() {
        return (String) mValue;
    }

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_BOOLEAN,
            TYPE_LONG,
            TYPE_DOUBLE,
            TYPE_STRING,
            TYPE_INT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /** Value is of type boolean. Access via {@link #getBooleanValue}. */
    public static final int TYPE_BOOLEAN = 0;
    /** Value is of type long. Access via {@link #getLongValue()}. */
    public static final int TYPE_LONG = 1;
    /** Value is of type double. Access via {@link #getDoubleValue()}. */
    public static final int TYPE_DOUBLE = 2;
    /** Value is of type string. Access via {@link #getStringValue}. */
    public static final int TYPE_STRING = 3;
    /** Value is of type int. Access via {@link #getIntValue}. */
    public static final int TYPE_INT = 4;
    /** Max type value. */
    private static final int MAX_TYPE_VALUE = TYPE_INT;

    private SettingsPreferenceValue(@NonNull Builder builder) {
        mType = builder.mType;
        mValue = builder.mValue;
    }

    private SettingsPreferenceValue(@NonNull Parcel in) {
        mType = in.readInt();
        if (mType == TYPE_BOOLEAN) {
            mValue = in.readBoolean();
        } else if (mType == TYPE_LONG) {
            mValue = in.readLong();
        } else if (mType == TYPE_DOUBLE) {
            mValue = in.readDouble();
        } else if (mType == TYPE_STRING) {
            mValue = in.readString();
        } else if (mType == TYPE_INT) {
            mValue = in.readInt();
        } else {
            // throw exception immediately, further read to Parcel may be invalid
            throw new IllegalStateException("Unknown type: " + mType);
        }
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        if (mType == TYPE_BOOLEAN) {
            dest.writeBoolean(getBooleanValue());
        } else if (mType == TYPE_LONG) {
            dest.writeLong(getLongValue());
        } else if (mType == TYPE_DOUBLE) {
            dest.writeDouble(getDoubleValue());
        } else if (mType == TYPE_STRING) {
            dest.writeString(getStringValue());
        } else if (mType == TYPE_INT) {
            dest.writeInt(getIntValue());
        }
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable Creator for {@link SettingsPreferenceValue}.
     */
    @NonNull
    public static final Creator<SettingsPreferenceValue> CREATOR = new Creator<>() {
        @Override
        public SettingsPreferenceValue createFromParcel(@NonNull Parcel in) {
            return new SettingsPreferenceValue(in);
        }

        @Override
        public SettingsPreferenceValue[] newArray(int size) {
            return new SettingsPreferenceValue[size];
        }
    };

    /**
     * Builder to construct {@link SettingsPreferenceValue}.
     */
    @FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
    public static final class Builder {
        @Type
        private final int mType;
        private @Nullable Object mValue;

        /**
         * Create Builder instance.
         * @param type type indicator for preference value
         */
        public Builder(@Type int type) {
            if (type < 0 || type > MAX_TYPE_VALUE) {
                throw new IllegalArgumentException("Unknown type: " + type);
            }
            mType = type;
        }

        /**
         * Sets boolean value for Preference.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setBooleanValue(boolean booleanValue) {
            checkType(TYPE_BOOLEAN);
            mValue = booleanValue;
            return this;
        }

        /**
         * Sets the int value for Preference.
         */
        @NonNull
        public Builder setIntValue(int intValue) {
            checkType(TYPE_INT);
            mValue = intValue;
            return this;
        }

        /**
         * Sets long value for Preference.
         */
        @NonNull
        public Builder setLongValue(long longValue) {
            checkType(TYPE_LONG);
            mValue = longValue;
            return this;
        }

        /**
         * Sets floating point value for Preference.
         */
        @NonNull
        public Builder setDoubleValue(double doubleValue) {
            checkType(TYPE_DOUBLE);
            mValue = doubleValue;
            return this;
        }

        /**
         * Sets string value for Preference.
         */
        @NonNull
        public Builder setStringValue(@Nullable String stringValue) {
            checkType(TYPE_STRING);
            mValue = stringValue;
            return this;
        }

        private void checkType(int type) {
            if (mType != type) {
                throw new IllegalArgumentException("Type is: " + mType);
            }
        }

        /**
         * Constructs an immutable {@link SettingsPreferenceValue} object.
         */
        @NonNull
        public SettingsPreferenceValue build() {
            return new SettingsPreferenceValue(this);
        }
    }
}
