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

import android.accounts.Account;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.stats.devicepolicy.DevicePolicyEnums;

/**
 * Params required to provision a managed profile, see
 * {@link DevicePolicyManager#createAndProvisionManagedProfile}.
 *
 * @hide
 */
@TestApi
public final class ManagedProfileProvisioningParams implements Parcelable {
    private static final String LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM =
            "LEAVE_ALL_SYSTEM_APPS_ENABLED";
    private static final String ORGANIZATION_OWNED_PROVISIONING_PARAM =
            "ORGANIZATION_OWNED_PROVISIONING";
    private static final String ACCOUNT_TO_MIGRATE_PROVIDED_PARAM = "ACCOUNT_TO_MIGRATE_PROVIDED";
    private static final String KEEP_MIGRATED_ACCOUNT_PARAM = "KEEP_MIGRATED_ACCOUNT";

    @NonNull private final ComponentName mProfileAdminComponentName;
    @NonNull private final String mOwnerName;
    @Nullable private final String mProfileName;
    @Nullable private final Account mAccountToMigrate;
    private final boolean mLeaveAllSystemAppsEnabled;
    private final boolean mOrganizationOwnedProvisioning;
    private final boolean mKeepAccountMigrated;


    private ManagedProfileProvisioningParams(
            @NonNull ComponentName profileAdminComponentName,
            @NonNull String ownerName,
            @Nullable String profileName,
            @Nullable Account accountToMigrate,
            boolean leaveAllSystemAppsEnabled,
            boolean organizationOwnedProvisioning,
            boolean keepAccountMigrated) {
        this.mProfileAdminComponentName = requireNonNull(profileAdminComponentName);
        this.mOwnerName = requireNonNull(ownerName);
        this.mProfileName = profileName;
        this.mAccountToMigrate = accountToMigrate;
        this.mLeaveAllSystemAppsEnabled = leaveAllSystemAppsEnabled;
        this.mOrganizationOwnedProvisioning = organizationOwnedProvisioning;
        this.mKeepAccountMigrated = keepAccountMigrated;
    }

    @NonNull
    public ComponentName getProfileAdminComponentName() {
        return mProfileAdminComponentName;
    }

    @NonNull
    public String getOwnerName() {
        return mOwnerName;
    }

    @Nullable
    public String getProfileName() {
        return mProfileName;
    }

    @Nullable
    public Account getAccountToMigrate() {
        return mAccountToMigrate;
    }

    public boolean isLeaveAllSystemAppsEnabled() {
        return mLeaveAllSystemAppsEnabled;
    }

    public boolean isOrganizationOwnedProvisioning() {
        return mOrganizationOwnedProvisioning;
    }

    public boolean isKeepAccountMigrated() {
        return mKeepAccountMigrated;
    }

    /**
     * Logs the provisioning params using {@link DevicePolicyEventLogger}.
     */
    public void logParams(@NonNull String callerPackage) {
        requireNonNull(callerPackage);

        logParam(callerPackage, LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM, mLeaveAllSystemAppsEnabled);
        logParam(callerPackage, ORGANIZATION_OWNED_PROVISIONING_PARAM,
                mOrganizationOwnedProvisioning);
        logParam(callerPackage, KEEP_MIGRATED_ACCOUNT_PARAM, mKeepAccountMigrated);
        logParam(callerPackage, ACCOUNT_TO_MIGRATE_PROVIDED_PARAM,
                /* value= */ mAccountToMigrate != null);
    }

    private void logParam(String callerPackage, String param, boolean value) {
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.PLATFORM_PROVISIONING_PARAM)
                .setStrings(callerPackage)
                .setAdmin(mProfileAdminComponentName)
                .setStrings(param)
                .setBoolean(value)
                .write();
    }

    /**
     * Builder class for {@link ManagedProfileProvisioningParams} objects.
     */
    public static final class Builder {
        @NonNull private final ComponentName mProfileAdminComponentName;
        @NonNull private final String mOwnerName;
        @Nullable private String mProfileName;
        @Nullable private Account mAccountToMigrate;
        private boolean mLeaveAllSystemAppsEnabled;
        private boolean mOrganizationOwnedProvisioning;
        private boolean mKeepAccountMigrated;

        /**
         * Initialize a new {@link Builder) to construct a {@link ManagedProfileProvisioningParams}.
         * <p>
         * See {@link DevicePolicyManager#createAndProvisionManagedProfile}
         *
         * @param profileAdminComponentName The admin {@link ComponentName} to be set as the profile
         * owner.
         * @param ownerName The name of the profile owner.
         *
         * @throws NullPointerException if {@code profileAdminComponentName} or
         * {@code ownerName} are null.
         */
        public Builder(
                @NonNull ComponentName profileAdminComponentName, @NonNull String ownerName) {
            requireNonNull(profileAdminComponentName);
            requireNonNull(ownerName);
            this.mProfileAdminComponentName = profileAdminComponentName;
            this.mOwnerName = ownerName;
        }

        /**
         * Sets the profile name of the created profile when
         * {@link DevicePolicyManager#createAndProvisionManagedProfile} is called. Defaults to
         * {@code null} if not set.
         */
        @NonNull
        public Builder setProfileName(@Nullable String profileName) {
            this.mProfileName = profileName;
            return this;
        }

        /**
         * Sets the {@link Account} to migrate from the parent profile to the created profile when
         * {@link DevicePolicyManager#createAndProvisionManagedProfile} is called. If not set, or
         * set to {@code null}, no accounts will be migrated.
         */
        @NonNull
        public Builder setAccountToMigrate(@Nullable Account accountToMigrate) {
            this.mAccountToMigrate = accountToMigrate;
            return this;
        }

        /**
         * Sets whether non-required system apps should be installed on
         * the created profile when {@link DevicePolicyManager#createAndProvisionManagedProfile}
         * is called. Defaults to {@code false} if not set.
         */
        @NonNull
        public Builder setLeaveAllSystemAppsEnabled(boolean leaveAllSystemAppsEnabled) {
            this.mLeaveAllSystemAppsEnabled = leaveAllSystemAppsEnabled;
            return this;
        }

        /**
         * Sets if this device is owned by an organization. Defaults to {@code false}
         * if not set.
         */
        @NonNull
        public Builder setOrganizationOwnedProvisioning(boolean organizationOwnedProvisioning) {
            this.mOrganizationOwnedProvisioning = organizationOwnedProvisioning;
            return this;
        }

        /**
         * Sets whether to keep the account on the parent profile during account migration.
         * Defaults to {@code false}.
         */
        @NonNull
        public Builder setKeepAccountMigrated(boolean keepAccountMigrated) {
            this.mKeepAccountMigrated = keepAccountMigrated;
            return this;
        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder}.
         *
         * @return a new {@link ManagedProfileProvisioningParams} object.
         */
        @NonNull
        public ManagedProfileProvisioningParams build() {
            return new ManagedProfileProvisioningParams(
                    mProfileAdminComponentName,
                    mOwnerName,
                    mProfileName,
                    mAccountToMigrate,
                    mLeaveAllSystemAppsEnabled,
                    mOrganizationOwnedProvisioning,
                    mKeepAccountMigrated);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "ManagedProfileProvisioningParams{"
                + "mProfileAdminComponentName=" + mProfileAdminComponentName
                + ", mOwnerName=" + mOwnerName
                + ", mProfileName=" + (mProfileName == null ? "null" : mProfileName)
                + ", mAccountToMigrate=" + (mAccountToMigrate == null ? "null" : mAccountToMigrate)
                + ", mLeaveAllSystemAppsEnabled=" + mLeaveAllSystemAppsEnabled
                + ", mOrganizationOwnedProvisioning=" + mOrganizationOwnedProvisioning
                + ", mKeepAccountMigrated=" + mKeepAccountMigrated
                + '}';
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, @Nullable int flags) {
        dest.writeTypedObject(mProfileAdminComponentName, flags);
        dest.writeString(mOwnerName);
        dest.writeString(mProfileName);
        dest.writeTypedObject(mAccountToMigrate, flags);
        dest.writeBoolean(mLeaveAllSystemAppsEnabled);
        dest.writeBoolean(mOrganizationOwnedProvisioning);
        dest.writeBoolean(mKeepAccountMigrated);
    }

    public static final @NonNull Creator<ManagedProfileProvisioningParams> CREATOR =
            new Creator<ManagedProfileProvisioningParams>() {
                @Override
                public ManagedProfileProvisioningParams createFromParcel(Parcel in) {
                    ComponentName componentName = in.readTypedObject(ComponentName.CREATOR);
                    String ownerName = in.readString();
                    String profileName = in.readString();
                    Account account = in.readTypedObject(Account.CREATOR);
                    boolean leaveAllSystemAppsEnabled = in.readBoolean();
                    boolean organizationOwnedProvisioning = in.readBoolean();
                    boolean keepAccountMigrated = in.readBoolean();

                    return new ManagedProfileProvisioningParams(
                            componentName,
                            ownerName,
                            profileName,
                            account,
                            leaveAllSystemAppsEnabled,
                            organizationOwnedProvisioning,
                            keepAccountMigrated);
                }

                @Override
                public ManagedProfileProvisioningParams[] newArray(int size) {
                    return new ManagedProfileProvisioningParams[size];
                }
            };
}
