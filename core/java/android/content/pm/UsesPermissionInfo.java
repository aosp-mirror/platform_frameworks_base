/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.RetentionPolicy;
/**
 * Information you can retrive about a particular application requested permission. This
 * corresponds to information collected from the AndroidManifest.xml's &lt;uses-permission&gt;
 * tags.
 */
public final class UsesPermissionInfo extends PackageItemInfo implements Parcelable {

    /**
     * Flag for {@link #getFlags()}: the requested permission is currently granted to the
     * application.
     */
    public static final int FLAG_REQUESTED_PERMISSION_GRANTED = 1 << 1;

    /** @hide */
    @IntDef(flag = true, prefix = {"FLAG_"}, value = {FLAG_REQUESTED_PERMISSION_GRANTED})
    @java.lang.annotation.Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /** An unset value for {@link #getDataSentOffDevice()},
     * {@link #getDataSharedWithThirdParty()}, and {@link #getDataUsedForMonetization()}
     */
    public static final int USAGE_UNDEFINED = 0;

    /**
     * A yes value for {@link #getDataSentOffDevice()}, {@link #getDataSharedWithThirdParty()},
     * and {@link #getDataUsedForMonetization()} corresponding to the <code>yes</code> value of
     * {@link android.R.attr#dataSentOffDevice}, {@link android.R.attr#dataSharedWithThirdParty},
     * and {@link android.R.attr#dataUsedForMonetization} attributes.
     */
    public static final int USAGE_YES = 1;

    /**
     * A user triggered only value for {@link #getDataSentOffDevice()},
     * {@link #getDataSharedWithThirdParty()}, and {@link #getDataUsedForMonetization()}
     * corresponding to the <code>userTriggered</code> value of
     * {@link android.R.attr#dataSentOffDevice}, {@link android.R.attr#dataSharedWithThirdParty},
     * and {@link android.R.attr#dataUsedForMonetization} attributes.
     */
    public static final int USAGE_USER_TRIGGERED = 2;

    /**
     * A no value for {@link #getDataSentOffDevice()}, {@link #getDataSharedWithThirdParty()},
     * and {@link #getDataUsedForMonetization()} corresponding to the <code>no</code> value of
     * {@link android.R.attr#dataSentOffDevice}, {@link android.R.attr#dataSharedWithThirdParty},
     * and {@link android.R.attr#dataUsedForMonetization} attributes.
     */
    public static final int USAGE_NO = 3;

    /** @hide */
    @IntDef(prefix = {"USAGE_"}, value = {
        USAGE_UNDEFINED,
        USAGE_YES,
        USAGE_USER_TRIGGERED,
        USAGE_NO})
    @java.lang.annotation.Retention(RetentionPolicy.SOURCE)
    public @interface Usage {}

    /**
     * An unset value for {@link #getDataRetention}.
     */
    public static final int RETENTION_UNDEFINED = 0;

    /**
     * A data not retained value for {@link #getDataRetention()} corresponding to the
     * <code>notRetained</code> value of {@link android.R.attr#dataRetentionTime}.
     */
    public static final int RETENTION_NOT_RETAINED = 1;

    /**
     * A user selected value for {@link #getDataRetention()} corresponding to the
     * <code>userSelected</code> value of {@link android.R.attr#dataRetentionTime}.
     */
    public static final int RETENTION_USER_SELECTED = 2;

    /**
     * An unlimited value for {@link #getDataRetention()} corresponding to the
     * <code>unlimited</code> value of {@link android.R.attr#dataRetentionTime}.
     */
    public static final int RETENTION_UNLIMITED = 3;

    /**
     * A specified value for {@link #getDataRetention()} corresponding to providing the number of
     * weeks data is retained in {@link android.R.attr#dataRetentionTime}. The number of weeks
     * is available in {@link #getDataRetentionWeeks()}.
     */
    public static final int RETENTION_SPECIFIED = 4;

    /** @hide */
    @IntDef(prefix = {"RETENTION_"}, value = {
        RETENTION_UNDEFINED,
        RETENTION_NOT_RETAINED,
        RETENTION_USER_SELECTED,
        RETENTION_UNLIMITED,
        RETENTION_SPECIFIED})
    @java.lang.annotation.Retention(RetentionPolicy.SOURCE)
    public @interface Retention {}

    private final String mPermission;
    private final @Flags int mFlags;
    private final @Usage int mDataSentOffDevice;
    private final @Usage int mDataSharedWithThirdParty;
    private final @Usage int mDataUsedForMonetization;
    private final @Retention int mDataRetention;
    private final int mDataRetentionWeeks;

    /** @hide */
    public UsesPermissionInfo(String permission) {
        mPermission = permission;
        mDataSentOffDevice = USAGE_UNDEFINED;
        mDataSharedWithThirdParty = USAGE_UNDEFINED;
        mDataUsedForMonetization = USAGE_UNDEFINED;
        mDataRetention = RETENTION_UNDEFINED;
        mDataRetentionWeeks = -1;
        mFlags = 0;
    }

    /** @hide */
    public UsesPermissionInfo(String permission,
            @Usage int dataSentOffDevice, @Usage int dataSharedWithThirdParty,
            @Usage int dataUsedForMonetization, @Retention int dataRetention,
            int dataRetentionWeeks) {
        mPermission = permission;
        mDataSentOffDevice = dataSentOffDevice;
        mDataSharedWithThirdParty = dataSharedWithThirdParty;
        mDataUsedForMonetization = dataUsedForMonetization;
        mDataRetention = dataRetention;
        mDataRetentionWeeks = dataRetentionWeeks;
        mFlags = 0;
    }

    /** @hide */
    public UsesPermissionInfo(UsesPermissionInfo orig) {
        this(orig, orig.mFlags);
    }

    /** @hide */
    public UsesPermissionInfo(UsesPermissionInfo orig, int flags) {
        super(orig);
        mPermission = orig.mPermission;
        mFlags = flags;
        mDataSentOffDevice = orig.mDataSentOffDevice;
        mDataSharedWithThirdParty = orig.mDataSharedWithThirdParty;
        mDataUsedForMonetization = orig.mDataUsedForMonetization;
        mDataRetention = orig.mDataRetention;
        mDataRetentionWeeks = orig.mDataRetentionWeeks;
    }

    /**
     * The name of the requested permission.
     */
    public String getPermission() {
        return mPermission;
    }

    public @Flags int getFlags() {
        return mFlags;
    }

    /**
     * If the application sends the data guarded by this permission off the device.
     *
     * See {@link android.R.attr#dataSentOffDevice}
     */
    public @Usage int getDataSentOffDevice() {
        return mDataSentOffDevice;
    }

    /**
     * If the application or its services shares the data guarded by this permission with third
     * parties.
     *
     * See {@link android.R.attr#dataSharedWithThirdParty}
     */
    public @Usage int getDataSharedWithThirdParty() {
        return mDataSharedWithThirdParty;
    }

    /**
     * If the application or its services use the data guarded by this permission for monetization
     * purposes.
     *
     * See {@link android.R.attr#dataUsedForMonetization}
     */
    public @Usage int getDataUsedForMonetization() {
        return mDataUsedForMonetization;
    }

    /**
     * How long the application or its services store the data guarded by this permission.
     * If set to {@link #RETENTION_SPECIFIED} {@link #getDataRetentionWeeks()} will contain the
     * number of weeks the data is stored.
     *
     * See {@link android.R.attr#dataRetentionTime}
     */
    public @Retention int getDataRetention() {
        return mDataRetention;
    }

    /**
     * If {@link #getDataRetention()} is {@link #RETENTION_SPECIFIED} the number of weeks the
     * application or its services store data guarded by this permission.
     *
     * @throws IllegalStateException if {@link #getDataRetention} is not
     * {@link #RETENTION_SPECIFIED}.
     */
    public int getDataRetentionWeeks() {
        if (mDataRetention != RETENTION_SPECIFIED) {
            throw new IllegalStateException("Data retention weeks not specified");
        }
        return mDataRetentionWeeks;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mPermission);
        dest.writeInt(mFlags);
        dest.writeInt(mDataSentOffDevice);
        dest.writeInt(mDataSharedWithThirdParty);
        dest.writeInt(mDataUsedForMonetization);
        dest.writeInt(mDataRetention);
        dest.writeInt(mDataRetentionWeeks);
    }

    private UsesPermissionInfo(Parcel source) {
        super(source);
        mPermission = source.readString();
        mFlags = source.readInt();
        mDataSentOffDevice = source.readInt();
        mDataSharedWithThirdParty = source.readInt();
        mDataUsedForMonetization = source.readInt();
        mDataRetention = source.readInt();
        mDataRetentionWeeks = source.readInt();
    }

    public static final Creator<UsesPermissionInfo> CREATOR =
            new Creator<UsesPermissionInfo>() {
                @Override
                public UsesPermissionInfo createFromParcel(Parcel source) {
                    return new UsesPermissionInfo(source);
                }
                @Override
                public UsesPermissionInfo[] newArray(int size) {
                    return new UsesPermissionInfo[size];
                }
            };
}
