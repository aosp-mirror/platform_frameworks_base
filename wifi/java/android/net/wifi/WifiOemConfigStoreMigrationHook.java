/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.wifi;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Class used to provide one time hooks for existing OEM devices to migrate their config store
 * data to the wifi mainline module.
 * <p>
 * Note:
 * <li> OEM's need to implement {@link #load()} only if their
 * existing config store format or file locations differs from the vanilla AOSP implementation (
 * which is what the wifi mainline module understands).
 * </li>
 * <li> The wifi mainline module will invoke {@link #load()}  method on every bootup, its
 * the responsibility of the OEM implementation to ensure that this method returns non-null data
 * only on the first bootup. Once the migration is done, the OEM can safely delete their config
 * store files and then return null on any subsequent reboots. The first & only relevant invocation
 * of {@link #load()} occurs when a previously released device upgrades to the wifi
 * mainline module from an OEM implementation of the wifi stack.
 * </li>
 * @hide
 */
@SystemApi
public final class WifiOemConfigStoreMigrationHook {
    /**
     * Container for all the wifi config data to migrate.
     */
    public static final class MigrationData implements Parcelable {
        /**
         * Builder to create instance of {@link MigrationData}.
         */
        public static final class Builder {
            private List<WifiConfiguration> mUserSavedNetworkConfigurations;
            private SoftApConfiguration mUserSoftApConfiguration;

            public Builder() {
                mUserSavedNetworkConfigurations = null;
                mUserSoftApConfiguration = null;
            }

            /**
             * Sets the list of all user's saved network configurations parsed from OEM config
             * store files.
             *
             * @param userSavedNetworkConfigurations List of {@link WifiConfiguration} representing
             *                                       the list of user's saved networks
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setUserSavedNetworkConfigurations(
                    @NonNull List<WifiConfiguration> userSavedNetworkConfigurations) {
                checkNotNull(userSavedNetworkConfigurations);
                mUserSavedNetworkConfigurations = userSavedNetworkConfigurations;
                return this;
            }

            /**
             * Sets the user's softap configuration parsed from OEM config store files.
             *
             * @param userSoftApConfiguration {@link SoftApConfiguration} representing user's
             *                                SoftAp configuration
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setUserSoftApConfiguration(
                    @NonNull SoftApConfiguration userSoftApConfiguration) {
                checkNotNull(userSoftApConfiguration);
                mUserSoftApConfiguration  = userSoftApConfiguration;
                return this;
            }

            /**
             * Build an instance of {@link MigrationData}.
             *
             * @return Instance of {@link MigrationData}.
             */
            public @NonNull MigrationData build() {
                return new MigrationData(mUserSavedNetworkConfigurations, mUserSoftApConfiguration);
            }
        }

        private final List<WifiConfiguration> mUserSavedNetworkConfigurations;
        private final SoftApConfiguration mUserSoftApConfiguration;

        private MigrationData(
                @Nullable List<WifiConfiguration> userSavedNetworkConfigurations,
                @Nullable SoftApConfiguration userSoftApConfiguration) {
            mUserSavedNetworkConfigurations = userSavedNetworkConfigurations;
            mUserSoftApConfiguration = userSoftApConfiguration;
        }

        public static final @NonNull Parcelable.Creator<MigrationData> CREATOR =
                new Parcelable.Creator<MigrationData>() {
                    @Override
                    public MigrationData createFromParcel(Parcel in) {
                        List<WifiConfiguration> userSavedNetworkConfigurations =
                                in.readArrayList(null);
                        SoftApConfiguration userSoftApConfiguration = in.readParcelable(null);
                        return new MigrationData(
                                userSavedNetworkConfigurations, userSoftApConfiguration);
                    }

                    @Override
                    public MigrationData[] newArray(int size) {
                        return new MigrationData[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeList(mUserSavedNetworkConfigurations);
            dest.writeParcelable(mUserSoftApConfiguration, flags);
        }

        /**
         * Returns list of all user's saved network configurations.
         *
         * Note: Only to be returned if there is any format change in how OEM persisted this info.
         * @return List of {@link WifiConfiguration} representing the list of user's saved networks,
         * or null if no migration necessary.
         */
        @Nullable
        public List<WifiConfiguration> getUserSavedNetworkConfigurations() {
            return mUserSavedNetworkConfigurations;
        }

        /**
         * Returns user's softap configuration.
         *
         * Note: Only to be returned if there is any format change in how OEM persisted this info.
         * @return {@link SoftApConfiguration} representing user's SoftAp configuration,
         * or null if no migration necessary.
         */
        @Nullable
        public SoftApConfiguration getUserSoftApConfiguration() {
            return mUserSoftApConfiguration;
        }
    }

    private WifiOemConfigStoreMigrationHook() { }

    /**
     * Load data from OEM's config store.
     *
     * @return Instance of {@link MigrationData} for migrating data, null if no
     * migration is necessary.
     */
    @Nullable
    public static MigrationData load() {
        // Note: OEM's should add code to parse data from their config store format here!
        return null;
    }
}
