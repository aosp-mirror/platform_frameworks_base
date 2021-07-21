/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.companion;

import static com.android.internal.util.CollectionUtils.emptyIfNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.OneTimeUseBuilder;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DataClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A request for the user to select a companion device to associate with.
 *
 * You can optionally set {@link Builder#addDeviceFilter filters} for which devices to show to the
 * user to select from.
 * The exact type and fields of the filter you can set depend on the
 * medium type. See {@link Builder}'s static factory methods for specific protocols that are
 * supported.
 *
 * You can also set {@link Builder#setSingleDevice single device} to request a popup with single
 * device to be shown instead of a list to choose from
 */
@DataClass(
        genToString = true,
        genEqualsHashCode = true,
        genHiddenGetters = true,
        genParcelable = true,
        genHiddenConstructor = true,
        genBuilder = false)
public final class AssociationRequest implements Parcelable {

    private static final String LOG_TAG = AssociationRequest.class.getSimpleName();

    /**
     * Device profile: watch.
     *
     * If specified, the current request may have a modified UI to highlight that the device being
     * set up is a specific kind of device, and some extra permissions may be granted to the app
     * as a result.
     *
     * Using it requires declaring uses-permission
     * {@link android.Manifest.permission#REQUEST_COMPANION_PROFILE_WATCH} in the manifest.
     *
     * <a href="{@docRoot}about/versions/12/features#cdm-profiles">Learn more</a>
     * about device profiles.
     *
     * @see AssociationRequest.Builder#setDeviceProfile
     */
    public static final String DEVICE_PROFILE_WATCH = "android.app.role.COMPANION_DEVICE_WATCH";

    /** @hide */
    @StringDef(value = { DEVICE_PROFILE_WATCH })
    public @interface DeviceProfile {}

    /**
     * Whether only a single device should match the provided filter.
     *
     * When scanning for a single device with a specifc {@link BluetoothDeviceFilter} mac
     * address, bonded devices are also searched among. This allows to obtain the necessary app
     * privileges even if the device is already paired.
     */
    private boolean mSingleDevice = false;

    /**
     * If set, only devices matching either of the given filters will be shown to the user
     */
    @DataClass.PluralOf("deviceFilter")
    private @NonNull List<DeviceFilter<?>> mDeviceFilters = new ArrayList<>();

    /**
     * If set, association will be requested as a corresponding kind of device
     */
    private @Nullable @DeviceProfile String mDeviceProfile = null;

    /**
     * The app package making the request.
     *
     * Populated by the system.
     *
     * @hide
     */
    private @Nullable String mCallingPackage = null;

    /**
     * The user-readable description of the device profile's privileges.
     *
     * Populated by the system.
     *
     * @hide
     */
    private @Nullable String mDeviceProfilePrivilegesDescription = null;

    /**
     * The time at which his request was created
     *
     * @hide
     */
    private long mCreationTime;

    /**
     * Whether the user-prompt may be skipped once the device is found.
     *
     * Populated by the system.
     *
     * @hide
     */
    private boolean mSkipPrompt = false;

    private void onConstructed() {
        mCreationTime = System.currentTimeMillis();
    }

    /** @hide */
    public void setCallingPackage(@NonNull String pkg) {
        mCallingPackage = pkg;
    }

    /** @hide */
    public void setDeviceProfilePrivilegesDescription(@NonNull String desc) {
        mDeviceProfilePrivilegesDescription = desc;
    }

    /** @hide */
    public void setSkipPrompt(boolean value) {
        mSkipPrompt = true;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isSingleDevice() {
        return mSingleDevice;
    }

    /** @hide */
    @NonNull
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public List<DeviceFilter<?>> getDeviceFilters() {
        return mDeviceFilters;
    }

    /**
     * A builder for {@link AssociationRequest}
     */
    public static final class Builder extends OneTimeUseBuilder<AssociationRequest> {
        private boolean mSingleDevice = false;
        @Nullable private ArrayList<DeviceFilter<?>> mDeviceFilters = null;
        private @Nullable String mDeviceProfile = null;

        public Builder() {}

        /**
         * Whether only a single device should match the provided filter.
         *
         * When scanning for a single device with a specifc {@link BluetoothDeviceFilter} mac
         * address, bonded devices are also searched among. This allows to obtain the necessary app
         * privileges even if the device is already paired.
         *
         * @param singleDevice if true, scanning for a device will stop as soon as at least one
         *                     fitting device is found
         */
        @NonNull
        public Builder setSingleDevice(boolean singleDevice) {
            checkNotUsed();
            this.mSingleDevice = singleDevice;
            return this;
        }

        /**
         * @param deviceFilter if set, only devices matching the given filter will be shown to the
         *                     user
         */
        @NonNull
        public Builder addDeviceFilter(@Nullable DeviceFilter<?> deviceFilter) {
            checkNotUsed();
            if (deviceFilter != null) {
                mDeviceFilters = ArrayUtils.add(mDeviceFilters, deviceFilter);
            }
            return this;
        }

        /**
         * If set, association will be requested as a corresponding kind of device
         */
        @NonNull
        public Builder setDeviceProfile(@NonNull @DeviceProfile String deviceProfile) {
            checkNotUsed();
            mDeviceProfile = deviceProfile;
            return this;
        }

        /** @inheritDoc */
        @NonNull
        @Override
        public AssociationRequest build() {
            markUsed();
            return new AssociationRequest(
                    mSingleDevice, emptyIfNull(mDeviceFilters),
                    mDeviceProfile, null, null, -1L, false);
        }
    }





    // Code below generated by codegen v1.0.22.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/companion/AssociationRequest.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new AssociationRequest.
     *
     * @param singleDevice
     *   Whether only a single device should match the provided filter.
     *
     *   When scanning for a single device with a specifc {@link BluetoothDeviceFilter} mac
     *   address, bonded devices are also searched among. This allows to obtain the necessary app
     *   privileges even if the device is already paired.
     * @param deviceFilters
     *   If set, only devices matching either of the given filters will be shown to the user
     * @param deviceProfile
     *   If set, association will be requested as a corresponding kind of device
     * @param callingPackage
     *   The app package making the request.
     *
     *   Populated by the system.
     * @param deviceProfilePrivilegesDescription
     *   The user-readable description of the device profile's privileges.
     *
     *   Populated by the system.
     * @param creationTime
     *   The time at which his request was created
     * @param skipPrompt
     *   Whether the user-prompt may be skipped once the device is found.
     *
     *   Populated by the system.
     * @hide
     */
    @DataClass.Generated.Member
    public AssociationRequest(
            boolean singleDevice,
            @NonNull List<DeviceFilter<?>> deviceFilters,
            @Nullable @DeviceProfile String deviceProfile,
            @Nullable String callingPackage,
            @Nullable String deviceProfilePrivilegesDescription,
            long creationTime,
            boolean skipPrompt) {
        this.mSingleDevice = singleDevice;
        this.mDeviceFilters = deviceFilters;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mDeviceFilters);
        this.mDeviceProfile = deviceProfile;
        com.android.internal.util.AnnotationValidations.validate(
                DeviceProfile.class, null, mDeviceProfile);
        this.mCallingPackage = callingPackage;
        this.mDeviceProfilePrivilegesDescription = deviceProfilePrivilegesDescription;
        this.mCreationTime = creationTime;
        this.mSkipPrompt = skipPrompt;

        onConstructed();
    }

    /**
     * If set, association will be requested as a corresponding kind of device
     *
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable @DeviceProfile String getDeviceProfile() {
        return mDeviceProfile;
    }

    /**
     * The app package making the request.
     *
     * Populated by the system.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable String getCallingPackage() {
        return mCallingPackage;
    }

    /**
     * The user-readable description of the device profile's privileges.
     *
     * Populated by the system.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable String getDeviceProfilePrivilegesDescription() {
        return mDeviceProfilePrivilegesDescription;
    }

    /**
     * The time at which his request was created
     *
     * @hide
     */
    @DataClass.Generated.Member
    public long getCreationTime() {
        return mCreationTime;
    }

    /**
     * Whether the user-prompt may be skipped once the device is found.
     *
     * Populated by the system.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public boolean isSkipPrompt() {
        return mSkipPrompt;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "AssociationRequest { " +
                "singleDevice = " + mSingleDevice + ", " +
                "deviceFilters = " + mDeviceFilters + ", " +
                "deviceProfile = " + mDeviceProfile + ", " +
                "callingPackage = " + mCallingPackage + ", " +
                "deviceProfilePrivilegesDescription = " + mDeviceProfilePrivilegesDescription + ", " +
                "creationTime = " + mCreationTime + ", " +
                "skipPrompt = " + mSkipPrompt +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(AssociationRequest other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        AssociationRequest that = (AssociationRequest) o;
        //noinspection PointlessBooleanExpression
        return true
                && mSingleDevice == that.mSingleDevice
                && Objects.equals(mDeviceFilters, that.mDeviceFilters)
                && Objects.equals(mDeviceProfile, that.mDeviceProfile)
                && Objects.equals(mCallingPackage, that.mCallingPackage)
                && Objects.equals(mDeviceProfilePrivilegesDescription, that.mDeviceProfilePrivilegesDescription)
                && mCreationTime == that.mCreationTime
                && mSkipPrompt == that.mSkipPrompt;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + Boolean.hashCode(mSingleDevice);
        _hash = 31 * _hash + Objects.hashCode(mDeviceFilters);
        _hash = 31 * _hash + Objects.hashCode(mDeviceProfile);
        _hash = 31 * _hash + Objects.hashCode(mCallingPackage);
        _hash = 31 * _hash + Objects.hashCode(mDeviceProfilePrivilegesDescription);
        _hash = 31 * _hash + Long.hashCode(mCreationTime);
        _hash = 31 * _hash + Boolean.hashCode(mSkipPrompt);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mSingleDevice) flg |= 0x1;
        if (mSkipPrompt) flg |= 0x40;
        if (mDeviceProfile != null) flg |= 0x4;
        if (mCallingPackage != null) flg |= 0x8;
        if (mDeviceProfilePrivilegesDescription != null) flg |= 0x10;
        dest.writeByte(flg);
        dest.writeParcelableList(mDeviceFilters, flags);
        if (mDeviceProfile != null) dest.writeString(mDeviceProfile);
        if (mCallingPackage != null) dest.writeString(mCallingPackage);
        if (mDeviceProfilePrivilegesDescription != null) dest.writeString(mDeviceProfilePrivilegesDescription);
        dest.writeLong(mCreationTime);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ AssociationRequest(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean singleDevice = (flg & 0x1) != 0;
        boolean skipPrompt = (flg & 0x40) != 0;
        List<DeviceFilter<?>> deviceFilters = new ArrayList<>();
        in.readParcelableList(deviceFilters, DeviceFilter.class.getClassLoader());
        String deviceProfile = (flg & 0x4) == 0 ? null : in.readString();
        String callingPackage = (flg & 0x8) == 0 ? null : in.readString();
        String deviceProfilePrivilegesDescription = (flg & 0x10) == 0 ? null : in.readString();
        long creationTime = in.readLong();

        this.mSingleDevice = singleDevice;
        this.mDeviceFilters = deviceFilters;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mDeviceFilters);
        this.mDeviceProfile = deviceProfile;
        com.android.internal.util.AnnotationValidations.validate(
                DeviceProfile.class, null, mDeviceProfile);
        this.mCallingPackage = callingPackage;
        this.mDeviceProfilePrivilegesDescription = deviceProfilePrivilegesDescription;
        this.mCreationTime = creationTime;
        this.mSkipPrompt = skipPrompt;

        onConstructed();
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<AssociationRequest> CREATOR
            = new Parcelable.Creator<AssociationRequest>() {
        @Override
        public AssociationRequest[] newArray(int size) {
            return new AssociationRequest[size];
        }

        @Override
        public AssociationRequest createFromParcel(@NonNull Parcel in) {
            return new AssociationRequest(in);
        }
    };

    @DataClass.Generated(
            time = 1615252862756L,
            codegenVersion = "1.0.22",
            sourceFile = "frameworks/base/core/java/android/companion/AssociationRequest.java",
            inputSignatures = "private static final  java.lang.String LOG_TAG\npublic static final  java.lang.String DEVICE_PROFILE_WATCH\nprivate  boolean mSingleDevice\nprivate @com.android.internal.util.DataClass.PluralOf(\"deviceFilter\") @android.annotation.NonNull java.util.List<android.companion.DeviceFilter<?>> mDeviceFilters\nprivate @android.annotation.Nullable @android.companion.AssociationRequest.DeviceProfile java.lang.String mDeviceProfile\nprivate @android.annotation.Nullable java.lang.String mCallingPackage\nprivate @android.annotation.Nullable java.lang.String mDeviceProfilePrivilegesDescription\nprivate  long mCreationTime\nprivate  boolean mSkipPrompt\nprivate  void onConstructed()\npublic  void setCallingPackage(java.lang.String)\npublic  void setDeviceProfilePrivilegesDescription(java.lang.String)\npublic  void setSkipPrompt(boolean)\npublic @android.compat.annotation.UnsupportedAppUsage boolean isSingleDevice()\npublic @android.annotation.NonNull @android.compat.annotation.UnsupportedAppUsage java.util.List<android.companion.DeviceFilter<?>> getDeviceFilters()\nclass AssociationRequest extends java.lang.Object implements [android.os.Parcelable]\nprivate  boolean mSingleDevice\nprivate @android.annotation.Nullable java.util.ArrayList<android.companion.DeviceFilter<?>> mDeviceFilters\nprivate @android.annotation.Nullable java.lang.String mDeviceProfile\npublic @android.annotation.NonNull android.companion.AssociationRequest.Builder setSingleDevice(boolean)\npublic @android.annotation.NonNull android.companion.AssociationRequest.Builder addDeviceFilter(android.companion.DeviceFilter<?>)\npublic @android.annotation.NonNull android.companion.AssociationRequest.Builder setDeviceProfile(java.lang.String)\npublic @android.annotation.NonNull @java.lang.Override android.companion.AssociationRequest build()\nclass Builder extends android.provider.OneTimeUseBuilder<android.companion.AssociationRequest> implements []\n@com.android.internal.util.DataClass(genToString=true, genEqualsHashCode=true, genHiddenGetters=true, genParcelable=true, genHiddenConstructor=true, genBuilder=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
