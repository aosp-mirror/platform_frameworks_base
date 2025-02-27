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
 * Depending on the type, the corresponding getter will contain its value. All other getters will
 * return default values (boolean returns false, String returns null) so they should not be used.
 * <p>See documentation on the constants for which getter method should be used.
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class SettingsPreferenceValue implements Parcelable {

    @Type
    private final int mType;
    private final boolean mBooleanValue;
    private final int mIntValue;
    private final long mLongValue;
    private final double mDoubleValue;
    @Nullable
    private final String mStringValue;

    /**
     * Returns the type indicator for Preference value.
     */
    @Type
    public int getType() {
        return mType;
    }

    /**
     * Returns the boolean value for Preference if type is {@link #TYPE_BOOLEAN}.
     */
    public boolean getBooleanValue() {
        return mBooleanValue;
    }

    /**
     * Returns the int value for Preference if type is {@link #TYPE_INT}.
     */
    public int getIntValue() {
        return mIntValue;
    }

    /**
     * Returns the long value for Preference if type is {@link #TYPE_LONG}.
     */
    public long getLongValue() {
        return mLongValue;
    }

    /**
     * Returns the double value for Preference if type is {@link #TYPE_DOUBLE}.
     */
    public double getDoubleValue() {
        return mDoubleValue;
    }

    /**
     * Returns the string value for Preference if type is {@link #TYPE_STRING}.
     */
    @Nullable
    public String getStringValue() {
        return mStringValue;
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

    private SettingsPreferenceValue(@NonNull Builder builder) {
        mType = builder.mType;
        mBooleanValue = builder.mBooleanValue;
        mLongValue = builder.mLongValue;
        mDoubleValue = builder.mDoubleValue;
        mStringValue = builder.mStringValue;
        mIntValue = builder.mIntValue;
    }

    private SettingsPreferenceValue(@NonNull Parcel in) {
        mType = in.readInt();
        mBooleanValue = in.readBoolean();
        mLongValue = in.readLong();
        mDoubleValue = in.readDouble();
        mStringValue = in.readString8();
        mIntValue = in.readInt();
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeBoolean(mBooleanValue);
        dest.writeLong(mLongValue);
        dest.writeDouble(mDoubleValue);
        dest.writeString8(mStringValue);
        dest.writeInt(mIntValue);
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
    public static final class Builder {
        @Type
        private final int mType;
        private boolean mBooleanValue;
        private long mLongValue;
        private double mDoubleValue;
        private String mStringValue;
        private int mIntValue;

        /**
         * Create Builder instance.
         * @param type type indicator for preference value
         */
        public Builder(@Type int type) {
            mType = type;
        }

        /**
         * Sets boolean value for Preference.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setBooleanValue(boolean booleanValue) {
            mBooleanValue = booleanValue;
            return this;
        }

        /**
         * Sets the int value for Preference.
         */
        @NonNull
        public Builder setIntValue(int intValue) {
            mIntValue = intValue;
            return this;
        }

        /**
         * Sets long value for Preference.
         */
        @NonNull
        public Builder setLongValue(long longValue) {
            mLongValue = longValue;
            return this;
        }

        /**
         * Sets floating point value for Preference.
         */
        @NonNull
        public Builder setDoubleValue(double doubleValue) {
            mDoubleValue = doubleValue;
            return this;
        }

        /**
         * Sets string value for Preference.
         */
        @NonNull
        public Builder setStringValue(@Nullable String stringValue) {
            mStringValue = stringValue;
            return this;
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
