/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.admin;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.stats.devicepolicy.DevicePolicyEnums;

import java.util.Locale;

/**
 * Params required to provision a fully managed device, see
 * {@link DevicePolicyManager#provisionFullyManagedDevice}.
 *
 * @hide
 */
@SystemApi
public final class FullyManagedDeviceProvisioningParams implements Parcelable {
    private static final String LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM =
            "LEAVE_ALL_SYSTEM_APPS_ENABLED";
    private static final String CAN_DEVICE_OWNER_GRANT_SENSOR_PERMISSIONS_PARAM =
            "CAN_DEVICE_OWNER_GRANT_SENSOR_PERMISSIONS";
    private static final String TIME_ZONE_PROVIDED_PARAM = "TIME_ZONE_PROVIDED";
    private static final String LOCALE_PROVIDED_PARAM = "LOCALE_PROVIDED";
    private static final String DEMO_DEVICE = "DEMO_DEVICE";

    @NonNull private final ComponentName mDeviceAdminComponentName;
    @NonNull private final String mOwnerName;
    private final boolean mLeaveAllSystemAppsEnabled;
    @Nullable private final String mTimeZone;
    private final long mLocalTime;
    @SuppressLint("UseIcu")
    @Nullable private final Locale mLocale;
    private final boolean mDeviceOwnerCanGrantSensorsPermissions;
    @NonNull private final PersistableBundle mAdminExtras;
    private final boolean mDemoDevice;


    private FullyManagedDeviceProvisioningParams(
            @NonNull ComponentName deviceAdminComponentName,
            @NonNull String ownerName,
            boolean leaveAllSystemAppsEnabled,
            @Nullable String timeZone,
            long localTime,
            @Nullable @SuppressLint("UseIcu") Locale locale,
            boolean deviceOwnerCanGrantSensorsPermissions,
            @NonNull PersistableBundle adminExtras,
            boolean demoDevice) {
        this.mDeviceAdminComponentName = requireNonNull(deviceAdminComponentName);
        this.mOwnerName = requireNonNull(ownerName);
        this.mLeaveAllSystemAppsEnabled = leaveAllSystemAppsEnabled;
        this.mTimeZone = timeZone;
        this.mLocalTime = localTime;
        this.mLocale = locale;
        this.mDeviceOwnerCanGrantSensorsPermissions =
                deviceOwnerCanGrantSensorsPermissions;
        this.mAdminExtras = adminExtras;
        this.mDemoDevice = demoDevice;
    }

    private FullyManagedDeviceProvisioningParams(
            @NonNull ComponentName deviceAdminComponentName,
            @NonNull String ownerName,
            boolean leaveAllSystemAppsEnabled,
            @Nullable String timeZone,
            long localTime,
            @Nullable String localeStr,
            boolean deviceOwnerCanGrantSensorsPermissions,
            @Nullable PersistableBundle adminExtras,
            boolean demoDevice) {
        this(deviceAdminComponentName,
                ownerName,
                leaveAllSystemAppsEnabled,
                timeZone,
                localTime,
                getLocale(localeStr),
                deviceOwnerCanGrantSensorsPermissions,
                adminExtras,
                demoDevice);
    }

    @Nullable
    private static Locale getLocale(String localeStr) {
        return localeStr == null ? null : Locale.forLanguageTag(localeStr);
    }

    /**
     * Returns the device owner's {@link ComponentName}.
     */
    @NonNull
    public ComponentName getDeviceAdminComponentName() {
        return mDeviceAdminComponentName;
    }

    /**
     * Returns the device owner's name.
     */
    @NonNull
    public String getOwnerName() {
        return mOwnerName;
    }

    /**
     * Returns {@code true} if system apps should be left enabled after provisioning.
     */
    public boolean isLeaveAllSystemAppsEnabled() {
        return mLeaveAllSystemAppsEnabled;
    }

    /**
     * If set, it returns the time zone to set for the device after provisioning, otherwise returns
     * {@code null};
     */
    @Nullable
    public String getTimeZone() {
        return mTimeZone;
    }

    /**
     * If set, it returns the local time to set for the device after provisioning, otherwise returns
     * 0.
     */
    public long getLocalTime() {
        return mLocalTime;
    }

    /**
     * If set, it returns the {@link Locale} to set for the device after provisioning, otherwise
     * returns {@code null}.
     */
    @Nullable
    public @SuppressLint("UseIcu") Locale getLocale() {
        return mLocale;
    }

    /**
     * @return true if the device owner can control sensor-related permission grants, false
     * if the device owner has opted out of it.
     */
    public boolean canDeviceOwnerGrantSensorsPermissions() {
        return mDeviceOwnerCanGrantSensorsPermissions;
    }

    /**
     * Returns a copy of the admin extras bundle.
     *
     * @see DevicePolicyManager#EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
     */
    public @NonNull PersistableBundle getAdminExtras() {
        return new PersistableBundle(mAdminExtras);
    }

    /**
     * @return true if this device is being setup as a retail demo device, see
     * {@link Settings.Global#DEVICE_DEMO_MODE}.
     */
    public boolean isDemoDevice() {
        return mDemoDevice;
    }

    /**
     * Logs the provisioning params using {@link DevicePolicyEventLogger}.
     *
     * @hide
     */
    public void logParams(@NonNull String callerPackage) {
        requireNonNull(callerPackage);

        logParam(callerPackage, LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM, mLeaveAllSystemAppsEnabled);
        logParam(callerPackage, CAN_DEVICE_OWNER_GRANT_SENSOR_PERMISSIONS_PARAM,
                mDeviceOwnerCanGrantSensorsPermissions);
        logParam(callerPackage, TIME_ZONE_PROVIDED_PARAM, /* value= */ mTimeZone != null);
        logParam(callerPackage, LOCALE_PROVIDED_PARAM, /* value= */ mLocale != null);
        logParam(callerPackage, DEMO_DEVICE, mDemoDevice);
    }

    private void logParam(String callerPackage, String param, boolean value) {
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.PLATFORM_PROVISIONING_PARAM)
                .setStrings(callerPackage)
                .setAdmin(mDeviceAdminComponentName)
                .setStrings(param)
                .setBoolean(value)
                .write();
    }

    /**
     * Builder class for {@link FullyManagedDeviceProvisioningParams} objects.
     */
    public static final class Builder {
        @NonNull private final ComponentName mDeviceAdminComponentName;
        @NonNull private final String mOwnerName;
        private boolean mLeaveAllSystemAppsEnabled;
        @Nullable private String mTimeZone;
        private long mLocalTime;
        @SuppressLint("UseIcu")
        @Nullable private Locale mLocale;
        // Default to allowing control over sensor permission grants.
        boolean mDeviceOwnerCanGrantSensorsPermissions = true;
        @NonNull private PersistableBundle mAdminExtras;
        // Default is normal user devices
        boolean mDemoDevice = false;


        /**
         * Initialize a new {@link Builder} to construct a
         * {@link FullyManagedDeviceProvisioningParams}.
         * <p>
         * See {@link DevicePolicyManager#provisionFullyManagedDevice}
         *
         * @param deviceAdminComponentName The admin {@link ComponentName} to be set as the device
         * owner.
         * @param ownerName The name of the device owner.
         *
         * @throws NullPointerException if {@code deviceAdminComponentName} or
         * {@code ownerName} are null.
         */
        public Builder(
                @NonNull ComponentName deviceAdminComponentName, @NonNull String ownerName) {
            this.mDeviceAdminComponentName = requireNonNull(deviceAdminComponentName);
            this.mOwnerName = requireNonNull(ownerName);
        }

        /**
         * Sets whether non-required system apps should be installed on
         * the created profile when
         * {@link DevicePolicyManager#provisionFullyManagedDevice}
         * is called. Defaults to {@code false} if not set.
         */
        @NonNull
        public Builder setLeaveAllSystemAppsEnabled(boolean leaveAllSystemAppsEnabled) {
            this.mLeaveAllSystemAppsEnabled = leaveAllSystemAppsEnabled;
            return this;
        }

        /**
         * Sets {@code timeZone} on the device. If not set or set to {@code null},
         * {@link DevicePolicyManager#provisionFullyManagedDevice} will not set a timezone
         */
        @NonNull
        public Builder setTimeZone(@Nullable String timeZone) {
            this.mTimeZone = timeZone;
            return this;
        }

        /**
         * Sets {@code localTime} on the device, If not set or set to
         * {@code 0}, {@link DevicePolicyManager#provisionFullyManagedDevice} will not set a
         * local time.
         */
        @NonNull
        public Builder setLocalTime(long localTime) {
            this.mLocalTime = localTime;
            return this;
        }

        /**
         * Sets {@link Locale} on the device, If not set or set to {@code null},
         * {@link DevicePolicyManager#provisionFullyManagedDevice} will not set a locale.
         */
        @NonNull
        public Builder setLocale(@SuppressLint("UseIcu") @Nullable Locale locale) {
            this.mLocale = locale;
            return this;
        }

        /**
         * Marks that the Device Owner may grant permissions related to device sensors.
         * See {@link DevicePolicyManager#EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT}.
         */
        @NonNull
        public Builder setCanDeviceOwnerGrantSensorsPermissions(boolean mayGrant) {
            mDeviceOwnerCanGrantSensorsPermissions = mayGrant;
            return this;
        }

        /**
         * Sets a {@link PersistableBundle} that contains admin-specific extras.
         */
        @NonNull
        //TODO(b/235783053) The adminExtras parameter is actually @Nullable.
        public Builder setAdminExtras(@NonNull PersistableBundle adminExtras) {
            mAdminExtras = adminExtras != null
                    ? new PersistableBundle(adminExtras)
                    : new PersistableBundle();
            return this;
        }

        /**
         * Marks the device as a demo device, see {@link Settings.Global#DEVICE_DEMO_MODE}. The
         * default value if unset is {@code false}.
         */
        @NonNull
        public Builder setDemoDevice(boolean demoDevice) {
            this.mDemoDevice = demoDevice;
            return this;
        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder}
         *
         * @return a new {@link FullyManagedDeviceProvisioningParams} object.
         */
        @NonNull
        public FullyManagedDeviceProvisioningParams build() {
            return new FullyManagedDeviceProvisioningParams(
                    mDeviceAdminComponentName,
                    mOwnerName,
                    mLeaveAllSystemAppsEnabled,
                    mTimeZone,
                    mLocalTime,
                    mLocale,
                    mDeviceOwnerCanGrantSensorsPermissions,
                    mAdminExtras != null ? mAdminExtras : new PersistableBundle(),
                    mDemoDevice);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @Override
    public String toString() {
        return "FullyManagedDeviceProvisioningParams{"
                + "mDeviceAdminComponentName=" + mDeviceAdminComponentName
                + ", mOwnerName=" + mOwnerName
                + ", mLeaveAllSystemAppsEnabled=" + mLeaveAllSystemAppsEnabled
                + ", mTimeZone=" + (mTimeZone == null ? "null" : mTimeZone)
                + ", mLocalTime=" + mLocalTime
                + ", mLocale=" + (mLocale == null ? "null" : mLocale)
                + ", mDeviceOwnerCanGrantSensorsPermissions="
                + mDeviceOwnerCanGrantSensorsPermissions
                + ", mAdminExtras=" + mAdminExtras
                + ", mDemoDevice=" + mDemoDevice
                + '}';
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, @Nullable int flags) {
        dest.writeTypedObject(mDeviceAdminComponentName, flags);
        dest.writeString(mOwnerName);
        dest.writeBoolean(mLeaveAllSystemAppsEnabled);
        dest.writeString(mTimeZone);
        dest.writeLong(mLocalTime);
        dest.writeString(mLocale == null ? null : mLocale.toLanguageTag());
        dest.writeBoolean(mDeviceOwnerCanGrantSensorsPermissions);
        dest.writePersistableBundle(mAdminExtras);
        dest.writeBoolean(mDemoDevice);
    }

    @NonNull
    public static final Creator<FullyManagedDeviceProvisioningParams> CREATOR =
            new Creator<FullyManagedDeviceProvisioningParams>() {
                @Override
                public FullyManagedDeviceProvisioningParams createFromParcel(Parcel in) {
                    ComponentName componentName = in.readTypedObject(ComponentName.CREATOR);
                    String ownerName = in.readString();
                    boolean leaveAllSystemAppsEnabled = in.readBoolean();
                    String timeZone = in.readString();
                    long localtime = in.readLong();
                    String locale = in.readString();
                    boolean deviceOwnerCanGrantSensorsPermissions = in.readBoolean();
                    PersistableBundle adminExtras = in.readPersistableBundle();
                    boolean demoDevice = in.readBoolean();

                    return new FullyManagedDeviceProvisioningParams(
                            componentName,
                            ownerName,
                            leaveAllSystemAppsEnabled,
                            timeZone,
                            localtime,
                            locale,
                            deviceOwnerCanGrantSensorsPermissions,
                            adminExtras,
                            demoDevice);
                }

                @Override
                public FullyManagedDeviceProvisioningParams[] newArray(int size) {
                    return new FullyManagedDeviceProvisioningParams[size];
                }
            };
}
