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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Settings;

/**
 * Class used to provide one time hooks for existing OEM devices to migrate their config store
 * data and other settings to the wifi mainline module.
 * @hide
 */
@SystemApi
public final class WifiMigration {

    private WifiMigration() { }

    /**
     * Container for all the wifi settings data to migrate.
     */
    public static final class SettingsMigrationData implements Parcelable {
        private final boolean mScanAlwaysAvailable;
        private final boolean mP2pFactoryResetPending;
        private final String mP2pDeviceName;
        private final boolean mSoftApTimeoutEnabled;
        private final boolean mWakeupEnabled;
        private final boolean mScanThrottleEnabled;
        private final boolean mVerboseLoggingEnabled;

        private SettingsMigrationData(boolean scanAlwaysAvailable, boolean p2pFactoryResetPending,
                @Nullable String p2pDeviceName, boolean softApTimeoutEnabled, boolean wakeupEnabled,
                boolean scanThrottleEnabled, boolean verboseLoggingEnabled) {
            mScanAlwaysAvailable = scanAlwaysAvailable;
            mP2pFactoryResetPending = p2pFactoryResetPending;
            mP2pDeviceName = p2pDeviceName;
            mSoftApTimeoutEnabled = softApTimeoutEnabled;
            mWakeupEnabled = wakeupEnabled;
            mScanThrottleEnabled = scanThrottleEnabled;
            mVerboseLoggingEnabled = verboseLoggingEnabled;
        }

        public static final @NonNull Parcelable.Creator<SettingsMigrationData> CREATOR =
                new Parcelable.Creator<SettingsMigrationData>() {
                    @Override
                    public SettingsMigrationData createFromParcel(Parcel in) {
                        boolean scanAlwaysAvailable = in.readBoolean();
                        boolean p2pFactoryResetPending = in.readBoolean();
                        String p2pDeviceName = in.readString();
                        boolean softApTimeoutEnabled = in.readBoolean();
                        boolean wakeupEnabled = in.readBoolean();
                        boolean scanThrottleEnabled = in.readBoolean();
                        boolean verboseLoggingEnabled = in.readBoolean();
                        return new SettingsMigrationData(
                                scanAlwaysAvailable, p2pFactoryResetPending,
                                p2pDeviceName, softApTimeoutEnabled, wakeupEnabled,
                                scanThrottleEnabled, verboseLoggingEnabled);
                    }

                    @Override
                    public SettingsMigrationData[] newArray(int size) {
                        return new SettingsMigrationData[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeBoolean(mScanAlwaysAvailable);
            dest.writeBoolean(mP2pFactoryResetPending);
            dest.writeString(mP2pDeviceName);
            dest.writeBoolean(mSoftApTimeoutEnabled);
            dest.writeBoolean(mWakeupEnabled);
            dest.writeBoolean(mScanThrottleEnabled);
            dest.writeBoolean(mVerboseLoggingEnabled);
        }

        /**
         * @return True if scans are allowed even when wifi is toggled off, false otherwise.
         */
        public boolean isScanAlwaysAvailable() {
            return mScanAlwaysAvailable;
        }

        /**
         * @return indicate whether factory reset request is pending.
         */
        public boolean isP2pFactoryResetPending() {
            return mP2pFactoryResetPending;
        }

        /**
         * @return the Wi-Fi peer-to-peer device name
         */
        public @Nullable String getP2pDeviceName() {
            return mP2pDeviceName;
        }

        /**
         * @return Whether soft AP will shut down after a timeout period when no devices are
         * connected.
         */
        public boolean isSoftApTimeoutEnabled() {
            return mSoftApTimeoutEnabled;
        }

        /**
         * @return whether Wi-Fi Wakeup feature is enabled.
         */
        public boolean isWakeUpEnabled() {
            return mWakeupEnabled;
        }

        /**
         * @return Whether wifi scan throttle is enabled or not.
         */
        public boolean isScanThrottleEnabled() {
            return mScanThrottleEnabled;
        }

        /**
         * @return Whether to enable verbose logging in Wi-Fi.
         */
        public boolean isVerboseLoggingEnabled() {
            return mVerboseLoggingEnabled;
        }

        /**
         * Builder to create instance of {@link SettingsMigrationData}.
         */
        public static final class Builder {
            private boolean mScanAlwaysAvailable;
            private boolean mP2pFactoryResetPending;
            private String mP2pDeviceName;
            private boolean mSoftApTimeoutEnabled;
            private boolean mWakeupEnabled;
            private boolean mScanThrottleEnabled;
            private boolean mVerboseLoggingEnabled;

            public Builder() {
            }

            /**
             * Setting to allow scans even when wifi is toggled off.
             *
             * @param available true if available, false otherwise.
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setScanAlwaysAvailable(boolean available) {
                mScanAlwaysAvailable = available;
                return this;
            }

            /**
             * Indicate whether factory reset request is pending.
             *
             * @param pending true if pending, false otherwise.
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setP2pFactoryResetPending(boolean pending) {
                mP2pFactoryResetPending = pending;
                return this;
            }

            /**
             * The Wi-Fi peer-to-peer device name
             *
             * @param name Name if set, null otherwise.
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setP2pDeviceName(@Nullable String name) {
                mP2pDeviceName = name;
                return this;
            }

            /**
             * Whether soft AP will shut down after a timeout period when no devices are connected.
             *
             * @param enabled true if enabled, false otherwise.
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setSoftApTimeoutEnabled(boolean enabled) {
                mSoftApTimeoutEnabled = enabled;
                return this;
            }

            /**
             * Value to specify if Wi-Fi Wakeup feature is enabled.
             *
             * @param enabled true if enabled, false otherwise.
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setWakeUpEnabled(boolean enabled) {
                mWakeupEnabled = enabled;
                return this;
            }

            /**
             * Whether wifi scan throttle is enabled or not.
             *
             * @param enabled true if enabled, false otherwise.
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setScanThrottleEnabled(boolean enabled) {
                mScanThrottleEnabled = enabled;
                return this;
            }

            /**
             * Setting to enable verbose logging in Wi-Fi.
             *
             * @param enabled true if enabled, false otherwise.
             * @return Instance of {@link Builder} to enable chaining of the builder method.
             */
            public @NonNull Builder setVerboseLoggingEnabled(boolean enabled) {
                mVerboseLoggingEnabled = enabled;
                return this;
            }

            /**
             * Build an instance of {@link SettingsMigrationData}.
             *
             * @return Instance of {@link SettingsMigrationData}.
             */
            public @NonNull SettingsMigrationData build() {
                return new SettingsMigrationData(mScanAlwaysAvailable, mP2pFactoryResetPending,
                        mP2pDeviceName, mSoftApTimeoutEnabled, mWakeupEnabled, mScanThrottleEnabled,
                        mVerboseLoggingEnabled);
            }
        }
    }

    /**
     * Load data from Settings.Global values.
     *
     * <p>
     * Note:
     * <li> This is method is invoked once on the first bootup. OEM can safely delete these settings
     * once the migration is complete. The first & only relevant invocation of
     * {@link #loadFromSettings(Context)} ()} occurs when a previously released
     * device upgrades to the wifi mainline module from an OEM implementation of the wifi stack.
     * </li>
     *
     * @param context Context to use for loading the settings provider.
     * @return Instance of {@link SettingsMigrationData} for migrating data.
     */
    @NonNull
    public static SettingsMigrationData loadFromSettings(@NonNull Context context) {
        return new SettingsMigrationData.Builder()
                .setScanAlwaysAvailable(
                        Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, 0) == 1)
                .setP2pFactoryResetPending(
                        Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET, 0) == 1)
                .setP2pDeviceName(
                        Settings.Global.getString(context.getContentResolver(),
                                Settings.Global.WIFI_P2P_DEVICE_NAME))
                .setSoftApTimeoutEnabled(
                        Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1) == 1)
                .setWakeUpEnabled(
                        Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1)
                .setScanThrottleEnabled(
                        Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.WIFI_SCAN_THROTTLE_ENABLED, 1) == 1)
                .setVerboseLoggingEnabled(
                        Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, 0) == 1)
                .build();
    }
}
