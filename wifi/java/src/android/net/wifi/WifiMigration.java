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

import static android.os.Environment.getDataMiscCeDirectory;
import static android.os.Environment.getDataMiscDirectory;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.net.wifi.flags.Flags;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.legacykeystore.ILegacyKeystore;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Class used to provide one time hooks for existing OEM devices to migrate their config store
 * data and other settings to the wifi apex.
 * @hide
 */
@SystemApi
public final class WifiMigration {
    private static final String TAG = "WifiMigration";

    /**
     * Directory to read the wifi config store files from under.
     */
    private static final String LEGACY_WIFI_STORE_DIRECTORY_NAME = "wifi";
    /**
     * Config store file for general shared store file.
     * AOSP Path on Android 10: /data/misc/wifi/WifiConfigStore.xml
     */
    public static final int STORE_FILE_SHARED_GENERAL = 0;
    /**
     * Config store file for softap shared store file.
     * AOSP Path on Android 10: /data/misc/wifi/softap.conf
     */
    public static final int STORE_FILE_SHARED_SOFTAP = 1;
    /**
     * Config store file for general user store file.
     * AOSP Path on Android 10: /data/misc_ce/<userId>/wifi/WifiConfigStore.xml
     */
    public static final int STORE_FILE_USER_GENERAL = 2;
    /**
     * Config store file for network suggestions user store file.
     * AOSP Path on Android 10: /data/misc_ce/<userId>/wifi/WifiConfigStoreNetworkSuggestions.xml
     */
    public static final int STORE_FILE_USER_NETWORK_SUGGESTIONS = 3;

    /** @hide */
    @IntDef(prefix = { "STORE_FILE_SHARED_" }, value = {
            STORE_FILE_SHARED_GENERAL,
            STORE_FILE_SHARED_SOFTAP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SharedStoreFileId { }

    /** @hide */
    @IntDef(prefix = { "STORE_FILE_USER_" }, value = {
            STORE_FILE_USER_GENERAL,
            STORE_FILE_USER_NETWORK_SUGGESTIONS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserStoreFileId { }

    /**
     * Keystore migration was completed successfully.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_LEGACY_KEYSTORE_TO_WIFI_BLOBSTORE_MIGRATION_READ_ONLY)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int KEYSTORE_MIGRATION_SUCCESS_MIGRATION_COMPLETE = 0;

    /**
     * Keystore migration was not needed.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_LEGACY_KEYSTORE_TO_WIFI_BLOBSTORE_MIGRATION_READ_ONLY)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED = 1;

    /**
     * Keystore migration failed because an exception was encountered.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_LEGACY_KEYSTORE_TO_WIFI_BLOBSTORE_MIGRATION_READ_ONLY)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int KEYSTORE_MIGRATION_FAILURE_ENCOUNTERED_EXCEPTION = 2;

    /** @hide */
    @IntDef(prefix = { "KEYSTORE_MIGRATION_" }, value = {
            KEYSTORE_MIGRATION_SUCCESS_MIGRATION_COMPLETE,
            KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED,
            KEYSTORE_MIGRATION_FAILURE_ENCOUNTERED_EXCEPTION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface KeystoreMigrationStatus { }

    /**
     * Mapping of Store file Id to Store file names.
     *
     * NOTE: This is the default path for the files on AOSP devices. If the OEM has modified
     * the path or renamed the files, please edit this appropriately.
     */
    private static final SparseArray<String> STORE_ID_TO_FILE_NAME =
            new SparseArray<String>() {{
                put(STORE_FILE_SHARED_GENERAL, "WifiConfigStore.xml");
                put(STORE_FILE_SHARED_SOFTAP, "WifiConfigStoreSoftAp.xml");
                put(STORE_FILE_USER_GENERAL, "WifiConfigStore.xml");
                put(STORE_FILE_USER_NETWORK_SUGGESTIONS, "WifiConfigStoreNetworkSuggestions.xml");
            }};

    /**
     * Pre-apex wifi shared folder.
     */
    private static File getLegacyWifiSharedDirectory() {
        return new File(getDataMiscDirectory(), LEGACY_WIFI_STORE_DIRECTORY_NAME);
    }

    /**
     * Pre-apex wifi user folder.
     */
    private static File getLegacyWifiUserDirectory(int userId) {
        return new File(getDataMiscCeDirectory(userId), LEGACY_WIFI_STORE_DIRECTORY_NAME);
    }

    /**
     * Legacy files were stored as AtomicFile. So, always use AtomicFile to operate on it to ensure
     * data integrity.
     */
    private static AtomicFile getSharedAtomicFile(@SharedStoreFileId int storeFileId) {
        return new AtomicFile(new File(
                getLegacyWifiSharedDirectory(),
                STORE_ID_TO_FILE_NAME.get(storeFileId)));
    }

    /**
     * Legacy files were stored as AtomicFile. So, always use AtomicFile to operate on it to ensure
     * data integrity.
     */
    private static AtomicFile getUserAtomicFile(@UserStoreFileId  int storeFileId, int userId) {
        return new AtomicFile(new File(
                getLegacyWifiUserDirectory(userId),
                STORE_ID_TO_FILE_NAME.get(storeFileId)));
    }

    private WifiMigration() { }

    /**
     * Load data from legacy shared wifi config store file.
     * <p>
     * Expected AOSP format is available in the sample files under {@code
     * frameworks/base/wifi/non-updatable/migration_samples/}.
     * </p>
     * <p>
     * Note:
     * <li>OEMs need to change the implementation of
     * {@link #convertAndRetrieveSharedConfigStoreFile(int)} only if their existing config store
     * format or file locations differs from the vanilla AOSP implementation.</li>
     * <li>The wifi apex will invoke
     * {@link #convertAndRetrieveSharedConfigStoreFile(int)}
     * method on every bootup, it is the responsibility of the OEM implementation to ensure that
     * they perform the necessary in place conversion of their config store file to conform to the
     * AOSP format. The OEM should ensure that the method should only return the
     * {@link InputStream} stream for the data to be migrated only on the first bootup.</li>
     * <li>Once the migration is done, the apex will invoke
     * {@link #removeSharedConfigStoreFile(int)} to delete the store file.</li>
     * <li>The only relevant invocation of {@link #convertAndRetrieveSharedConfigStoreFile(int)}
     * occurs when a previously released device upgrades to the wifi apex from an OEM
     * implementation of the wifi stack.
     * <li>Ensure that the legacy file paths are accessible to the wifi module (sepolicy rules, file
     * permissions, etc). Since the wifi service continues to run inside system_server process, this
     * method will be called from the same context (so ideally the file should still be accessible).
     * </li>
     *
     * @param storeFileId Identifier for the config store file. One of
     * {@link #STORE_FILE_SHARED_GENERAL} or {@link #STORE_FILE_SHARED_GENERAL}
     * @return Instance of {@link InputStream} for migrating data, null if no migration is
     * necessary.
     * @throws IllegalArgumentException on invalid storeFileId.
     */
    @Nullable
    public static InputStream convertAndRetrieveSharedConfigStoreFile(
            @SharedStoreFileId int storeFileId) {
        if (storeFileId != STORE_FILE_SHARED_GENERAL && storeFileId !=  STORE_FILE_SHARED_SOFTAP) {
            throw new IllegalArgumentException("Invalid shared store file id");
        }
        try {
            // OEMs should do conversions necessary here before returning the stream.
            return getSharedAtomicFile(storeFileId).openRead();
        } catch (FileNotFoundException e) {
            // Special handling for softap.conf.
            // Note: OEM devices upgrading from Q -> R will only have the softap.conf file.
            // Test devices running previous R builds however may have already migrated to the
            // XML format. So, check for that above before falling back to check for legacy file.
            if (storeFileId == STORE_FILE_SHARED_SOFTAP) {
                return SoftApConfToXmlMigrationUtil.convert();
            }
            return null;
        }
    }

    /**
     * Remove the legacy shared wifi config store file.
     *
     * @param storeFileId Identifier for the config store file. One of
     * {@link #STORE_FILE_SHARED_GENERAL} or {@link #STORE_FILE_SHARED_GENERAL}
     * @throws IllegalArgumentException on invalid storeFileId.
     */
    public static void removeSharedConfigStoreFile(@SharedStoreFileId int storeFileId) {
        if (storeFileId != STORE_FILE_SHARED_GENERAL && storeFileId !=  STORE_FILE_SHARED_SOFTAP) {
            throw new IllegalArgumentException("Invalid shared store file id");
        }
        AtomicFile file = getSharedAtomicFile(storeFileId);
        if (file.exists()) {
            file.delete();
            return;
        }
        // Special handling for softap.conf.
        // Note: OEM devices upgrading from Q -> R will only have the softap.conf file.
        // Test devices running previous R builds however may have already migrated to the
        // XML format. So, check for that above before falling back to check for legacy file.
        if (storeFileId == STORE_FILE_SHARED_SOFTAP) {
            SoftApConfToXmlMigrationUtil.remove();
        }
    }

    /**
     * Load data from legacy user wifi config store file.
     * <p>
     * Expected AOSP format is available in the sample files under {@code
     * frameworks/base/wifi/non-updatable/migration_samples/}.
     * </p>
     * <p>
     * Note:
     * <li>OEMs need to change the implementation of
     * {@link #convertAndRetrieveUserConfigStoreFile(int, UserHandle)} only if their existing config
     * store format or file locations differs from the vanilla AOSP implementation.</li>
     * <li>The wifi apex will invoke
     * {@link #convertAndRetrieveUserConfigStoreFile(int, UserHandle)}
     * method on every bootup, it is the responsibility of the OEM implementation to ensure that
     * they perform the necessary in place conversion of their config store file to conform to the
     * AOSP format. The OEM should ensure that the method should only return the
     * {@link InputStream} stream for the data to be migrated only on the first bootup.</li>
     * <li>Once the migration is done, the apex will invoke
     * {@link #removeUserConfigStoreFile(int, UserHandle)} to delete the store file.</li>
     * <li>The only relevant invocation of
     * {@link #convertAndRetrieveUserConfigStoreFile(int, UserHandle)} occurs when a previously
     * released device upgrades to the wifi apex from an OEM implementation of the wifi
     * stack.
     * </li>
     * <li>Ensure that the legacy file paths are accessible to the wifi module (sepolicy rules, file
     * permissions, etc). Since the wifi service continues to run inside system_server process, this
     * method will be called from the same context (so ideally the file should still be accessible).
     * </li>
     *
     * @param storeFileId Identifier for the config store file. One of
     * {@link #STORE_FILE_USER_GENERAL} or {@link #STORE_FILE_USER_NETWORK_SUGGESTIONS}
     * @param userHandle User handle.
     * @return Instance of {@link InputStream} for migrating data, null if no migration is
     * necessary.
     * @throws IllegalArgumentException on invalid storeFileId or userHandle.
     */
    @Nullable
    public static InputStream convertAndRetrieveUserConfigStoreFile(
            @UserStoreFileId int storeFileId, @NonNull UserHandle userHandle) {
        if (storeFileId != STORE_FILE_USER_GENERAL
                && storeFileId !=  STORE_FILE_USER_NETWORK_SUGGESTIONS) {
            throw new IllegalArgumentException("Invalid user store file id");
        }
        Objects.requireNonNull(userHandle);
        try {
            // OEMs should do conversions necessary here before returning the stream.
            return getUserAtomicFile(storeFileId, userHandle.getIdentifier()).openRead();
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * Remove the legacy user wifi config store file.
     *
     * @param storeFileId Identifier for the config store file. One of
     * {@link #STORE_FILE_USER_GENERAL} or {@link #STORE_FILE_USER_NETWORK_SUGGESTIONS}
     * @param userHandle User handle.
     * @throws IllegalArgumentException on invalid storeFileId or userHandle.
    */
    public static void removeUserConfigStoreFile(
            @UserStoreFileId int storeFileId, @NonNull UserHandle userHandle) {
        if (storeFileId != STORE_FILE_USER_GENERAL
                && storeFileId !=  STORE_FILE_USER_NETWORK_SUGGESTIONS) {
            throw new IllegalArgumentException("Invalid user store file id");
        }
        Objects.requireNonNull(userHandle);
        AtomicFile file = getUserAtomicFile(storeFileId, userHandle.getIdentifier());
        if (file.exists()) {
            file.delete();
        }
    }

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
     * device upgrades to the wifi apex from an OEM implementation of the wifi stack.
     * </li>
     *
     * @param context Context to use for loading the settings provider.
     * @return Instance of {@link SettingsMigrationData} for migrating data.
     */
    @NonNull
    public static SettingsMigrationData loadFromSettings(@NonNull Context context) {
        if (Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.WIFI_MIGRATION_COMPLETED, 0) == 1) {
            // migration already complete, ignore.
            return null;
        }
        SettingsMigrationData data = new SettingsMigrationData.Builder()
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
        Settings.Global.putInt(
                context.getContentResolver(), Settings.Global.WIFI_MIGRATION_COMPLETED, 1);
        return data;

    }

    /**
     * Migrate any certificates in Legacy Keystore to the newer WifiBlobstore database.
     *
     * If there are no certificates to migrate, this method will return immediately.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_LEGACY_KEYSTORE_TO_WIFI_BLOBSTORE_MIGRATION_READ_ONLY)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static @KeystoreMigrationStatus int migrateLegacyKeystoreToWifiBlobstore() {
        if (!WifiBlobStore.supplicantCanAccessBlobstore()) {
            // Supplicant cannot access WifiBlobstore, so keep the certs in Legacy Keystore
            Log.i(TAG, "Avoiding migration since supplicant cannot access WifiBlobstore");
            return KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            ILegacyKeystore legacyKeystore = WifiBlobStore.getLegacyKeystore();
            String[] legacyAliases = legacyKeystore.list("", Process.WIFI_UID);
            if (legacyAliases == null || legacyAliases.length == 0) {
                Log.i(TAG, "No aliases need to be migrated");
                return KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED;
            }

            WifiBlobStore wifiBlobStore = WifiBlobStore.getInstance();
            List<String> blobstoreAliasList = Arrays.asList(wifiBlobStore.list(""));
            Set<String> blobstoreAliases = new HashSet<>();
            blobstoreAliases.addAll(blobstoreAliasList);

            for (String legacyAlias : legacyAliases) {
                // Only migrate if the alias is not already in WifiBlobstore,
                // since WifiBlobstore should already contain the latest value.
                if (!blobstoreAliases.contains(legacyAlias)) {
                    byte[] value = legacyKeystore.get(legacyAlias, Process.WIFI_UID);
                    wifiBlobStore.put(legacyAlias, value);
                }
                legacyKeystore.remove(legacyAlias, Process.WIFI_UID);
            }
            Log.i(TAG, "Successfully migrated aliases from Legacy Keystore");
            return KEYSTORE_MIGRATION_SUCCESS_MIGRATION_COMPLETE;
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ILegacyKeystore.ERROR_SYSTEM_ERROR) {
                Log.i(TAG, "Legacy Keystore service has been deprecated");
                return KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED;
            }
            Log.e(TAG, "Encountered a ServiceSpecificException while migrating aliases. " + e);
            return KEYSTORE_MIGRATION_FAILURE_ENCOUNTERED_EXCEPTION;
        } catch (Exception e) {
            Log.e(TAG, "Encountered an exception while migrating aliases. " + e);
            return KEYSTORE_MIGRATION_FAILURE_ENCOUNTERED_EXCEPTION;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
