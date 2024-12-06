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

import static android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED;

import static com.android.internal.util.CollectionUtils.emptyIfNull;

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.StringDef;
import android.annotation.UserIdInt;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.OneTimeUseBuilder;

import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
public final class AssociationRequest implements Parcelable {
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

    /**
     * Device profile: glasses.
     *
     * If specified, the current request may have a modified UI to highlight that the device being
     * set up is a glasses device, and some extra permissions may be granted to the app
     * as a result.
     *
     * Using it requires declaring uses-permission
     * {@link android.Manifest.permission#REQUEST_COMPANION_PROFILE_GLASSES} in the manifest.
     *
     * @see AssociationRequest.Builder#setDeviceProfile
     */
    @RequiresPermission(Manifest.permission.REQUEST_COMPANION_PROFILE_GLASSES)
    public static final String DEVICE_PROFILE_GLASSES = "android.app.role.COMPANION_DEVICE_GLASSES";

    /**
     * Device profile: a virtual display capable of rendering Android applications, and sending back
     * input events.
     *
     * Only applications that have been granted
     * {@link android.Manifest.permission#REQUEST_COMPANION_PROFILE_APP_STREAMING} are allowed to
     * request to be associated with such devices.
     *
     * @see AssociationRequest.Builder#setDeviceProfile
     */
    @RequiresPermission(Manifest.permission.REQUEST_COMPANION_PROFILE_APP_STREAMING)
    public static final String DEVICE_PROFILE_APP_STREAMING =
            "android.app.role.COMPANION_DEVICE_APP_STREAMING";

    /**
     * Device profile: a virtual device capable of rendering content from an Android host to a
     * nearby device.
     *
     * Only applications that have been granted
     * {@link android.Manifest.permission#REQUEST_COMPANION_PROFILE_NEARBY_DEVICE_STREAMING}
     * are allowed to request to be associated with such devices.
     *
     * @see AssociationRequest.Builder#setDeviceProfile
     */
    @RequiresPermission(Manifest.permission.REQUEST_COMPANION_PROFILE_NEARBY_DEVICE_STREAMING)
    public static final String DEVICE_PROFILE_NEARBY_DEVICE_STREAMING =
            "android.app.role.COMPANION_DEVICE_NEARBY_DEVICE_STREAMING";

    /**
     * Device profile: Android Automotive Projection
     *
     * Only applications that have been granted
     * {@link android.Manifest.permission#REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION} are
     * allowed to request to be associated with such devices.
     *
     * @see AssociationRequest.Builder#setDeviceProfile
     */
    @RequiresPermission(Manifest.permission.REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION)
    public static final String DEVICE_PROFILE_AUTOMOTIVE_PROJECTION =
            "android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION";

    /**
     * Device profile: Allows the companion app to access notification, recent photos and media for
     * computer cross-device features.
     *
     * Only applications that have been granted
     * {@link android.Manifest.permission#REQUEST_COMPANION_PROFILE_COMPUTER} are allowed to
     * request to be associated with such devices.
     *
     * @see AssociationRequest.Builder#setDeviceProfile
     */
    @RequiresPermission(Manifest.permission.REQUEST_COMPANION_PROFILE_COMPUTER)
    public static final String DEVICE_PROFILE_COMPUTER =
            "android.app.role.COMPANION_DEVICE_COMPUTER";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(value = { DEVICE_PROFILE_WATCH, DEVICE_PROFILE_COMPUTER,
            DEVICE_PROFILE_AUTOMOTIVE_PROJECTION, DEVICE_PROFILE_APP_STREAMING,
            DEVICE_PROFILE_GLASSES, DEVICE_PROFILE_NEARBY_DEVICE_STREAMING })
    public @interface DeviceProfile {}

    /**
     * Whether only a single device should match the provided filter.
     *
     * When scanning for a single device with a specific {@link BluetoothDeviceFilter} mac
     * address, bonded devices are also searched among. This allows to obtain the necessary app
     * privileges even if the device is already paired.
     */
    private final boolean mSingleDevice;

    /**
     * If set, only devices matching either of the given filters will be shown to the user
     */
    @NonNull
    private final List<DeviceFilter<?>> mDeviceFilters;

    /**
     * Profile of the device.
     */
    @Nullable
    @DeviceProfile
    private final String mDeviceProfile;

    /**
     * The Display name of the device to be shown in the CDM confirmation UI. Must be non-null for
     * "self-managed" association.
     */
    @Nullable
    private CharSequence mDisplayName;

    /**
     * The device that was associated. Will be null for "self-managed" association.
     */
    @Nullable
    private AssociatedDevice mAssociatedDevice;

    /**
     * Whether the association is to be managed by the companion application.
     */
    private final boolean mSelfManaged;

    /**
     * Indicates that the application would prefer the CompanionDeviceManager to collect an explicit
     * confirmation from the user before creating an association, even if such confirmation is not
     * required.
     */
    private final boolean mForceConfirmation;

    /**
     * The app package name of the application the association will belong to.
     * Populated by the system.
     * @hide
     */
    @Nullable
    private String mPackageName;

    /**
     * The UserId of the user the association will belong to.
     * Populated by the system.
     * @hide
     */
    @UserIdInt
    private int mUserId;

    /**
     * The user-readable description of the device profile's privileges.
     * Populated by the system.
     * @hide
     */
    @Nullable
    private String mDeviceProfilePrivilegesDescription;

    /**
     * The time at which his request was created
     * @hide
     */
    private final long mCreationTime;

    /**
     * Whether the user-prompt may be skipped once the device is found.
     * Populated by the system.
     * @hide
     */
    private boolean mSkipPrompt;

    /**
     * The device icon displayed in selfManaged association dialog.
     * @hide
     */
    @Nullable
    private Icon mDeviceIcon;

    /**
     * Creates a new AssociationRequest.
     *
     * @param singleDevice
     *   Whether only a single device should match the provided filter.
     *
     *   When scanning for a single device with a specific {@link BluetoothDeviceFilter} mac
     *   address, bonded devices are also searched among. This allows to obtain the necessary app
     *   privileges even if the device is already paired.
     * @param deviceFilters
     *   If set, only devices matching either of the given filters will be shown to the user
     * @param deviceProfile
     *   Profile of the device.
     * @param displayName
     *   The Display name of the device to be shown in the CDM confirmation UI. Must be non-null for
     *   "self-managed" association.
     * @param selfManaged
     *   Whether the association is to be managed by the companion application.
     */
    private AssociationRequest(
            boolean singleDevice,
            @NonNull List<DeviceFilter<?>> deviceFilters,
            @Nullable @DeviceProfile String deviceProfile,
            @Nullable CharSequence displayName,
            boolean selfManaged,
            boolean forceConfirmation,
            @Nullable Icon deviceIcon) {
        mSingleDevice = singleDevice;
        mDeviceFilters = requireNonNull(deviceFilters);
        mDeviceProfile = deviceProfile;
        mDisplayName = displayName;
        mSelfManaged = selfManaged;
        mForceConfirmation = forceConfirmation;
        mCreationTime = System.currentTimeMillis();
        mDeviceIcon = deviceIcon;
    }

    /**
     * @return profile of the companion device.
     */
    @Nullable
    @DeviceProfile
    public String getDeviceProfile() {
        return mDeviceProfile;
    }

    /**
     * The Display name of the device to be shown in the CDM confirmation UI. Must be non-null for
     * "self-managed" association.
     */
    @Nullable
    public CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * Whether the association is to be managed by the companion application.
     *
     * @see Builder#setSelfManaged(boolean)
     */
    public boolean isSelfManaged() {
        return mSelfManaged;
    }

    /**
     * Indicates whether the application requires the {@link CompanionDeviceManager} service to
     * collect an explicit confirmation from the user before creating an association, even if
     * such confirmation is not required from the service's perspective.
     *
     * @see Builder#setForceConfirmation(boolean)
     */
    public boolean isForceConfirmation() {
        return mForceConfirmation;
    }

    /**
     * Whether only a single device should match the provided filter.
     *
     * When scanning for a single device with a specific {@link BluetoothDeviceFilter} mac
     * address, bonded devices are also searched among. This allows to obtain the necessary app
     * privileges even if the device is already paired.
     */
    public boolean isSingleDevice() {
        return mSingleDevice;
    }

    /**
     * Get the device icon of the self-managed association request.
     *
     * @return the device icon, or {@code null} if no device icon has been set.
     *
     * @see Builder#setDeviceIcon(Icon)
     */
    @FlaggedApi(Flags.FLAG_ASSOCIATION_DEVICE_ICON)
    @Nullable
    public Icon getDeviceIcon() {
        return mDeviceIcon;
    }

    /** @hide */
    public void setPackageName(@NonNull String packageName) {
        mPackageName = packageName;
    }

    /** @hide */
    public void setUserId(@UserIdInt int userId) {
        mUserId = userId;
    }

    /** @hide */
    public void setDeviceProfilePrivilegesDescription(@NonNull String desc) {
        mDeviceProfilePrivilegesDescription = desc;
    }

    /** @hide */
    public void setSkipPrompt(boolean value) {
        mSkipPrompt = value;
    }

    /** @hide */
    public void setDisplayName(CharSequence displayName) {
        mDisplayName = displayName;
    }

    /** @hide */
    public void setAssociatedDevice(AssociatedDevice associatedDevice) {
        mAssociatedDevice = associatedDevice;
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
        private ArrayList<DeviceFilter<?>> mDeviceFilters = null;
        private String mDeviceProfile;
        private CharSequence mDisplayName;
        private boolean mSelfManaged = false;
        private boolean mForceConfirmation = false;
        private Icon mDeviceIcon = null;

        public Builder() {}

        /**
         * Whether only a single device should match the provided filter.
         *
         * When scanning for a single device with a specific {@link BluetoothDeviceFilter} mac
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

        /**
         * Adds a display name.
         * Generally {@link AssociationRequest}s are not required to provide a display name, except
         * for request for creating "self-managed" associations, which MUST provide a display name.
         *
         * @param displayName the display name of the device.
         */
        @NonNull
        public Builder setDisplayName(@NonNull CharSequence displayName) {
            checkNotUsed();
            mDisplayName = requireNonNull(displayName);
            return this;
        }

        /**
         * Indicate whether the association would be managed by the companion application.
         *
         * Requests for creating "self-managed" association MUST provide a Display name.
         *
         * @see #setDisplayName(CharSequence)
         */
        @RequiresPermission(REQUEST_COMPANION_SELF_MANAGED)
        @NonNull
        public Builder setSelfManaged(boolean selfManaged) {
            checkNotUsed();
            mSelfManaged = selfManaged;
            return this;
        }

        /**
         * Indicates whether the application requires the {@link CompanionDeviceManager} service to
         * collect an explicit confirmation from the user before creating an association, even if
         * such confirmation is not required from the service's perspective.
         */
        @RequiresPermission(REQUEST_COMPANION_SELF_MANAGED)
        @NonNull
        public Builder setForceConfirmation(boolean forceConfirmation) {
            checkNotUsed();
            mForceConfirmation = forceConfirmation;
            return this;
        }

        /**
         * Set the device icon for the self-managed device and to display the icon in the
         * self-managed association dialog.
         *
         * @throws IllegalArgumentException if the icon is not exactly 24dp by 24dp
         * or if it is {@link Icon#TYPE_URI} or {@link Icon#TYPE_URI_ADAPTIVE_BITMAP}.
         * @see #setSelfManaged(boolean)
         */
        @NonNull
        @RequiresPermission(REQUEST_COMPANION_SELF_MANAGED)
        @FlaggedApi(Flags.FLAG_ASSOCIATION_DEVICE_ICON)
        public Builder setDeviceIcon(@NonNull Icon deviceIcon) {
            checkNotUsed();
            mDeviceIcon = requireNonNull(deviceIcon);
            return this;
        }

        /** @inheritDoc */
        @NonNull
        @Override
        public AssociationRequest build() {
            markUsed();
            if (mSelfManaged && mDisplayName == null) {
                throw new IllegalStateException("Request for a self-managed association MUST "
                        + "provide the display name of the device");
            }
            return new AssociationRequest(mSingleDevice, emptyIfNull(mDeviceFilters),
                    mDeviceProfile, mDisplayName, mSelfManaged, mForceConfirmation, mDeviceIcon);
        }
    }

    /**
     * The device that was associated. Will be null for "self-managed" association.
     *
     * @hide
     */
    @Nullable
    public AssociatedDevice getAssociatedDevice() {
        return mAssociatedDevice;
    }

    /**
     * The app package name of the application the association will belong to.
     * Populated by the system.
     *
     * @hide
     */
    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * The UserId of the user the association will belong to.
     * Populated by the system.
     *
     * @hide
     */
    @UserIdInt
    public int getUserId() {
        return mUserId;
    }

    /**
     * The user-readable description of the device profile's privileges.
     * Populated by the system.
     *
     * @hide
     */
    @Nullable
    public String getDeviceProfilePrivilegesDescription() {
        return mDeviceProfilePrivilegesDescription;
    }

    /**
     * The time at which his request was created
     *
     * @hide
     */
    public long getCreationTime() {
        return mCreationTime;
    }

    /**
     * Whether the user-prompt may be skipped once the device is found.
     * Populated by the system.
     *
     * @hide
     */
    public boolean isSkipPrompt() {
        return mSkipPrompt;
    }

    @Override
    public String toString() {
        return "AssociationRequest { "
                + "singleDevice = " + mSingleDevice
                + ", deviceFilters = " + mDeviceFilters
                + ", deviceProfile = " + mDeviceProfile
                + ", displayName = " + mDisplayName
                + ", associatedDevice = " + mAssociatedDevice
                + ", selfManaged = " + mSelfManaged
                + ", forceConfirmation = " + mForceConfirmation
                + ", packageName = " + mPackageName
                + ", userId = " + mUserId
                + ", deviceProfilePrivilegesDescription = " + mDeviceProfilePrivilegesDescription
                + ", creationTime = " + mCreationTime
                + ", skipPrompt = " + mSkipPrompt
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssociationRequest that = (AssociationRequest) o;
        return mSingleDevice == that.mSingleDevice
                && Objects.equals(mDeviceFilters, that.mDeviceFilters)
                && Objects.equals(mDeviceProfile, that.mDeviceProfile)
                && Objects.equals(mDisplayName, that.mDisplayName)
                && Objects.equals(mAssociatedDevice, that.mAssociatedDevice)
                && mSelfManaged == that.mSelfManaged
                && mForceConfirmation == that.mForceConfirmation
                && Objects.equals(mPackageName, that.mPackageName)
                && mUserId == that.mUserId
                && Objects.equals(mDeviceProfilePrivilegesDescription,
                        that.mDeviceProfilePrivilegesDescription)
                && mCreationTime == that.mCreationTime
                && mSkipPrompt == that.mSkipPrompt
                && (mDeviceIcon == null ? that.mDeviceIcon == null
                : mDeviceIcon.sameAs(that.mDeviceIcon));
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + Boolean.hashCode(mSingleDevice);
        _hash = 31 * _hash + Objects.hashCode(mDeviceFilters);
        _hash = 31 * _hash + Objects.hashCode(mDeviceProfile);
        _hash = 31 * _hash + Objects.hashCode(mDisplayName);
        _hash = 31 * _hash + Objects.hashCode(mAssociatedDevice);
        _hash = 31 * _hash + Boolean.hashCode(mSelfManaged);
        _hash = 31 * _hash + Boolean.hashCode(mForceConfirmation);
        _hash = 31 * _hash + Objects.hashCode(mPackageName);
        _hash = 31 * _hash + mUserId;
        _hash = 31 * _hash + Objects.hashCode(mDeviceProfilePrivilegesDescription);
        _hash = 31 * _hash + Long.hashCode(mCreationTime);
        _hash = 31 * _hash + Boolean.hashCode(mSkipPrompt);
        _hash = 31 * _hash + Objects.hashCode(mDeviceIcon);

        return _hash;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        int flg = 0;
        if (mSingleDevice) flg |= 0x1;
        if (mSelfManaged) flg |= 0x2;
        if (mForceConfirmation) flg |= 0x4;
        if (mSkipPrompt) flg |= 0x8;
        if (mDeviceProfile != null) flg |= 0x10;
        if (mDisplayName != null) flg |= 0x20;
        if (mAssociatedDevice != null) flg |= 0x40;
        if (mPackageName != null) flg |= 0x80;
        if (mDeviceProfilePrivilegesDescription != null) flg |= 0x100;

        dest.writeInt(flg);
        dest.writeParcelableList(mDeviceFilters, flags);
        if (mDeviceProfile != null) dest.writeString(mDeviceProfile);
        if (mDisplayName != null) dest.writeCharSequence(mDisplayName);
        if (mAssociatedDevice != null) dest.writeTypedObject(mAssociatedDevice, flags);
        if (mPackageName != null) dest.writeString(mPackageName);
        dest.writeInt(mUserId);
        if (mDeviceProfilePrivilegesDescription != null) {
            dest.writeString8(mDeviceProfilePrivilegesDescription);
        }
        dest.writeLong(mCreationTime);
        if (mDeviceIcon != null) {
            dest.writeInt(1);
            mDeviceIcon.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings("unchecked")
    /* package-private */ AssociationRequest(@NonNull Parcel in) {
        int flg = in.readInt();
        boolean singleDevice = (flg & 0x1) != 0;
        boolean selfManaged = (flg & 0x2) != 0;
        boolean forceConfirmation = (flg & 0x4) != 0;
        boolean skipPrompt = (flg & 0x8) != 0;
        List<DeviceFilter<?>> deviceFilters = new ArrayList<>();
        in.readParcelableList(deviceFilters, DeviceFilter.class.getClassLoader(),
                (Class<android.companion.DeviceFilter<?>>) (Class<?>)
                        android.companion.DeviceFilter.class);
        String deviceProfile = (flg & 0x10) == 0 ? null : in.readString();
        CharSequence displayName = (flg & 0x20) == 0 ? null : in.readCharSequence();
        AssociatedDevice associatedDevice = (flg & 0x40) == 0 ? null
                : in.readTypedObject(AssociatedDevice.CREATOR);
        String packageName = (flg & 0x80) == 0 ? null : in.readString();
        int userId = in.readInt();
        String deviceProfilePrivilegesDescription = (flg & 0x100) == 0 ? null : in.readString8();
        long creationTime = in.readLong();

        this.mSingleDevice = singleDevice;
        this.mDeviceFilters = deviceFilters;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mDeviceFilters);
        this.mDeviceProfile = deviceProfile;
        this.mDisplayName = displayName;
        this.mAssociatedDevice = associatedDevice;
        this.mSelfManaged = selfManaged;
        this.mForceConfirmation = forceConfirmation;
        this.mPackageName = packageName;
        this.mUserId = userId;
        com.android.internal.util.AnnotationValidations.validate(
                UserIdInt.class, null, mUserId);
        this.mDeviceProfilePrivilegesDescription = deviceProfilePrivilegesDescription;
        this.mCreationTime = creationTime;
        this.mSkipPrompt = skipPrompt;
        if (in.readInt() == 1) {
            mDeviceIcon = Icon.CREATOR.createFromParcel(in);
        } else {
            mDeviceIcon = null;
        }
    }

    @NonNull
    public static final Parcelable.Creator<AssociationRequest> CREATOR =
            new Parcelable.Creator<AssociationRequest>() {
        @Override
        public AssociationRequest[] newArray(int size) {
            return new AssociationRequest[size];
        }

        @Override
        public AssociationRequest createFromParcel(@NonNull Parcel in) {
            return new AssociationRequest(in);
        }
    };
}
