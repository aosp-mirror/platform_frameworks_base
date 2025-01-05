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

package android.media.quality;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.StringDef;
import android.media.tv.flags.Flags;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Capability info of media quality parameters
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public final class ParameterCapability implements Parcelable {

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_NONE,
            TYPE_INT,
            TYPE_LONG,
            TYPE_DOUBLE,
            TYPE_STRING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ParameterType {}

    /**
     * None parameter type. It's used when a parameter is not supported.
     */
    public static final int TYPE_NONE = 0;

    /**
     * Integer parameter type
     */
    public static final int TYPE_INT = 1;

    /**
     * Long integer parameter type
     */
    public static final int TYPE_LONG = 2;

    /**
     * Double parameter type
     */
    public static final int TYPE_DOUBLE = 3;

    /**
     * String parameter type
     */
    public static final int TYPE_STRING = 4;

    /** @hide */
    @StringDef(prefix = { "CAPABILITY_" }, value = {
            CAPABILITY_MAX,
            CAPABILITY_MIN,
            CAPABILITY_DEFAULT,
            CAPABILITY_ENUM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Capability {}

    /**
     * The key for the max possible value of this parameter.
     */
    public static final String CAPABILITY_MAX = "max";

    /**
     * The key for the min possible value of this parameter.
     */
    public static final String CAPABILITY_MIN = "min";

    /**
     * The key for the default value of this parameter.
     */
    public static final String CAPABILITY_DEFAULT = "default";

    /**
     * The key for the enumeration of this parameter.
     */
    public static final String CAPABILITY_ENUM = "enum";

    @NonNull
    private final String mName;
    private final boolean mIsSupported;
    @ParameterType
    private final int mType;
    @NonNull
    private final Bundle mCaps;

    /** @hide */
    protected ParameterCapability(Parcel in) {
        mName = in.readString();
        mIsSupported = in.readBoolean();
        mType = in.readInt();
        mCaps = in.readBundle();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeBoolean(mIsSupported);
        dest.writeInt(mType);
        dest.writeBundle(mCaps);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<ParameterCapability> CREATOR = new Creator<ParameterCapability>() {
        @Override
        public ParameterCapability createFromParcel(Parcel in) {
            return new ParameterCapability(in);
        }

        @Override
        public ParameterCapability[] newArray(int size) {
            return new ParameterCapability[size];
        }
    };


    /**
     * Creates a new ParameterCapability.
     *
     * @hide
     */
    public ParameterCapability(
            @NonNull String name,
            boolean isSupported,
            int type,
            @NonNull Bundle caps) {
        this.mName = name;
        this.mIsSupported = isSupported;
        this.mType = type;
        this.mCaps = caps;
    }

    /**
     * Gets parameter name.
     */
    @NonNull
    public String getParameterName() {
        return mName;
    }

    /**
     * Returns whether this parameter is supported or not.
     */
    public boolean isSupported() {
        return mIsSupported;
    }

    /**
     * Gets parameter type.
     *
     * <p>It's {@link #TYPE_NONE} if {@link #isSupported()} is {@code false}.
     */
    @ParameterType
    public int getParameterType() {
        return mType;
    }

    /**
     * Gets capability information.
     * <p>e.g. use the key {@link #CAPABILITY_MAX} to get the max value.
     */
    @NonNull
    public Bundle getCapabilities() {
        return new Bundle(mCaps);
    }
}
