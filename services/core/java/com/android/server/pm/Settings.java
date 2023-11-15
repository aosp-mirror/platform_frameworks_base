/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_UID_CHANGED;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.content.pm.PackageManager.UNINSTALL_REASON_USER_TYPE;
import static android.os.Process.INVALID_UID;
import static android.os.Process.PACKAGE_INFO_GID;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.WRITE_USER_PACKAGE_RESTRICTIONS;
import static com.android.server.pm.SharedUidMigration.BEST_EFFORT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.compat.ChangeIdStateCache;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackagePartitions;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.overlay.OverlayPaths;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.CreateAppDataArgs;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Message;
import android.os.PatternMatcher;
import android.os.PersistableBundle;
import android.os.SELinux;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.service.pm.PackageServiceDumpProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IntArray;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.pm.pkg.component.ParsedComponent;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedProcess;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.permission.persistence.RuntimePermissionsState;
import com.android.server.LocalServices;
import com.android.server.backup.PreferredActivityBackupHelper;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.permission.LegacyPermissionSettings;
import com.android.server.pm.permission.LegacyPermissionState;
import com.android.server.pm.permission.LegacyPermissionState.PermissionState;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.SuspendParams;
import com.android.server.pm.resolution.ComponentResolver;
import com.android.server.pm.verify.domain.DomainVerificationLegacySettings;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationPersistence;
import com.android.server.utils.Slogf;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedArrayList;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedArraySet;
import com.android.server.utils.WatchedSparseArray;
import com.android.server.utils.WatchedSparseIntArray;
import com.android.server.utils.Watcher;

import dalvik.annotation.optimization.NeverCompile;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Holds information about dynamic settings.
 */
public final class Settings implements Watchable, Snappable, ResilientAtomicFile.ReadEventLogger {
    private static final String TAG = "PackageSettings";

    /**
     * Watchable machinery
     */
    private final WatchableImpl mWatchable = new WatchableImpl();

    /**
     * Ensures an observer is in the list, exactly once. The observer cannot be null.  The
     * function quietly returns if the observer is already in the list.
     *
     * @param observer The {@link Watcher} to be notified when the {@link Watchable} changes.
     */
    public void registerObserver(@NonNull Watcher observer) {
        mWatchable.registerObserver(observer);
    }

    /**
     * Ensures an observer is not in the list. The observer must not be null.  The function
     * quietly returns if the objserver is not in the list.
     *
     * @param observer The {@link Watcher} that should not be in the notification list.
     */
    public void unregisterObserver(@NonNull Watcher observer) {
        mWatchable.unregisterObserver(observer);
    }

    /**
     * Return true if the {@link Watcher) is a registered observer.
     * @param observer A {@link Watcher} that might be registered
     * @return true if the observer is registered with this {@link Watchable}.
     */
    @Override
    public boolean isRegisteredObserver(@NonNull Watcher observer) {
        return mWatchable.isRegisteredObserver(observer);
    }

    /**
     * Invokes {@link Watcher#onChange} on each registered observer.  The method can be called
     * with the {@link Watchable} that generated the event.  In a tree of {@link Watchable}s, this
     * is generally the first (deepest) {@link Watchable} to detect a change.
     *
     * @param what The {@link Watchable} that generated the event.
     */
    public void dispatchChange(@Nullable Watchable what) {
        mWatchable.dispatchChange(what);
    }
    /**
     * Notify listeners that this object has changed.
     */
    protected void onChanged() {
        dispatchChange(this);
    }

    /**
     * Current version of the package database. Set it to the latest version in
     * the {@link DatabaseVersion} class below to ensure the database upgrade
     * doesn't happen repeatedly.
     * <p>
     * Note that care should be taken to make sure all database upgrades are
     * idempotent.
     */
    public static final int CURRENT_DATABASE_VERSION = DatabaseVersion.SIGNATURE_MALFORMED_RECOVER;

    /**
     * This class contains constants that can be referred to from upgrade code.
     * Insert constant values here that describe the upgrade reason. The version
     * code must be monotonically increasing.
     */
    public static class DatabaseVersion {
        /**
         * The initial version of the database.
         */
        public static final int FIRST_VERSION = 1;

        /**
         * Migrating the Signature array from the entire certificate chain to
         * just the signing certificate.
         */
        public static final int SIGNATURE_END_ENTITY = 2;

        /**
         * There was a window of time in
         * {@link android.os.Build.VERSION_CODES#LOLLIPOP} where we persisted
         * certificates after potentially mutating them. To switch back to the
         * original untouched certificates, we need to force a collection pass.
         */
        public static final int SIGNATURE_MALFORMED_RECOVER = 3;
    }

    static final boolean DEBUG_STOPPED = false;
    private static final boolean DEBUG_MU = false;
    private static final boolean DEBUG_KERNEL = false;
    private static final boolean DEBUG_PARSER = false;

    private static final String RUNTIME_PERMISSIONS_FILE_NAME = "runtime-permissions.xml";

    private static final String TAG_READ_EXTERNAL_STORAGE = "read-external-storage";
    private static final String ATTR_ENFORCEMENT = "enforcement";

    public static final String TAG_ITEM = "item";
    private static final String TAG_DISABLED_COMPONENTS = "disabled-components";
    private static final String TAG_ENABLED_COMPONENTS = "enabled-components";
    private static final String TAG_PACKAGE_RESTRICTIONS = "package-restrictions";
    private static final String TAG_PACKAGE = "pkg";
    private static final String TAG_SHARED_USER = "shared-user";
    private static final String TAG_RUNTIME_PERMISSIONS = "runtime-permissions";
    private static final String TAG_PERMISSIONS = "perms";
    private static final String TAG_CHILD_PACKAGE = "child-package";
    private static final String TAG_USES_SDK_LIB = "uses-sdk-lib";
    private static final String TAG_USES_STATIC_LIB = "uses-static-lib";
    private static final String TAG_BLOCK_UNINSTALL_PACKAGES = "block-uninstall-packages";
    private static final String TAG_BLOCK_UNINSTALL = "block-uninstall";

    private static final String TAG_PERSISTENT_PREFERRED_ACTIVITIES =
            "persistent-preferred-activities";
    static final String TAG_CROSS_PROFILE_INTENT_FILTERS =
            "crossProfile-intent-filters";
    public static final String TAG_DOMAIN_VERIFICATION = "domain-verification";
    private static final String TAG_DEFAULT_APPS = "default-apps";
    public static final String TAG_ALL_INTENT_FILTER_VERIFICATION =
            "all-intent-filter-verifications";
    private static final String TAG_DEFAULT_BROWSER = "default-browser";
    private static final String TAG_DEFAULT_DIALER = "default-dialer";
    private static final String TAG_VERSION = "version";
    /**
     * @deprecated Moved to {@link SuspendParams}
     */
    @Deprecated
    private static final String TAG_SUSPENDED_DIALOG_INFO = "suspended-dialog-info";
    /**
     * @deprecated Moved to {@link SuspendParams}
     */
    @Deprecated
    private static final String TAG_SUSPENDED_APP_EXTRAS = "suspended-app-extras";
    /**
     * @deprecated Moved to {@link SuspendParams}
     */
    @Deprecated
    private static final String TAG_SUSPENDED_LAUNCHER_EXTRAS = "suspended-launcher-extras";
    private static final String TAG_SUSPEND_PARAMS = "suspend-params";
    private static final String TAG_MIME_GROUP = "mime-group";
    private static final String TAG_MIME_TYPE = "mime-type";
    private static final String TAG_ARCHIVE_STATE = "archive-state";
    private static final String TAG_ARCHIVE_ACTIVITY_INFO = "archive-activity-info";

    public static final String ATTR_NAME = "name";
    public static final String ATTR_PACKAGE = "package";
    private static final String ATTR_GRANTED = "granted";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_VERSION = "version";

    private static final String ATTR_CE_DATA_INODE = "ceDataInode";
    private static final String ATTR_DE_DATA_INODE = "deDataInode";
    private static final String ATTR_INSTALLED = "inst";
    private static final String ATTR_STOPPED = "stopped";
    private static final String ATTR_NOT_LAUNCHED = "nl";
    // Legacy, here for reading older versions of the package-restrictions.
    private static final String ATTR_BLOCKED = "blocked";
    // New name for the above attribute.
    private static final String ATTR_HIDDEN = "hidden";
    private static final String ATTR_DISTRACTION_FLAGS = "distraction_flags";
    private static final String ATTR_SUSPENDED = "suspended";
    private static final String ATTR_SUSPENDING_PACKAGE = "suspending-package";
    /**
     * @deprecated Legacy attribute, kept only for upgrading from P builds.
     */
    @Deprecated
    private static final String ATTR_SUSPEND_DIALOG_MESSAGE = "suspend_dialog_message";
    // Legacy, uninstall blocks are stored separately.
    @Deprecated
    private static final String ATTR_BLOCK_UNINSTALL = "blockUninstall";
    private static final String ATTR_ENABLED = "enabled";
    private static final String ATTR_ENABLED_CALLER = "enabledCaller";
    private static final String ATTR_DOMAIN_VERIFICATION_STATE = "domainVerificationStatus";
    private static final String ATTR_APP_LINK_GENERATION = "app-link-generation";
    private static final String ATTR_INSTALL_REASON = "install-reason";
    private static final String ATTR_UNINSTALL_REASON = "uninstall-reason";
    private static final String ATTR_INSTANT_APP = "instant-app";
    private static final String ATTR_VIRTUAL_PRELOAD = "virtual-preload";
    private static final String ATTR_HARMFUL_APP_WARNING = "harmful-app-warning";
    private static final String ATTR_SPLASH_SCREEN_THEME = "splash-screen-theme";
    private static final String ATTR_MIN_ASPECT_RATIO = "min-aspect-ratio";

    private static final String ATTR_PACKAGE_NAME = "packageName";
    private static final String ATTR_BUILD_FINGERPRINT = "buildFingerprint";
    private static final String ATTR_FINGERPRINT = "fingerprint";
    private static final String ATTR_VOLUME_UUID = "volumeUuid";
    private static final String ATTR_SDK_VERSION = "sdkVersion";
    private static final String ATTR_DATABASE_VERSION = "databaseVersion";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_FIRST_INSTALL_TIME = "first-install-time";
    private static final String ATTR_ARCHIVE_ACTIVITY_TITLE = "activity-title";
    private static final String ATTR_ARCHIVE_ORIGINAL_COMPONENT_NAME = "original-component-name";
    private static final String ATTR_ARCHIVE_INSTALLER_TITLE = "installer-title";
    private static final String ATTR_ARCHIVE_ICON_PATH = "icon-path";
    private static final String ATTR_ARCHIVE_MONOCHROME_ICON_PATH = "monochrome-icon-path";

    private final Handler mHandler;

    private final PackageManagerTracedLock mLock;

    @Watched(manual = true)
    private final RuntimePermissionPersistence mRuntimePermissionsPersistence;

    // Current settings file.
    private final File mSettingsFilename;
    // Reserve copy of the current settings file.
    private final File mSettingsReserveCopyFilename;
    // Previous settings file.
    // Removed when the current settings file successfully stored.
    private final File mPreviousSettingsFilename;

    private final File mPackageListFilename;
    private final File mStoppedPackagesFilename;
    private final File mBackupStoppedPackagesFilename;
    /** The top level directory in configfs for sdcardfs to push the package->uid,userId mappings */
    private final File mKernelMappingFilename;

    // Lock for user package restrictions operations.
    private final Object mPackageRestrictionsLock = new Object();

    // Pending write operations.
    @GuardedBy("mPackageRestrictionsLock")
    private final SparseIntArray mPendingAsyncPackageRestrictionsWrites = new SparseIntArray();

    /** Map from package name to settings */
    @Watched
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    final WatchedArrayMap<String, PackageSetting> mPackages;
    private final SnapshotCache<WatchedArrayMap<String, PackageSetting>> mPackagesSnapshot;

    /**
     * List of packages that were involved in installing other packages, i.e. packages that created
     * new sessions or are listed in at least one app's InstallSource.
     */
    @Watched
    private final WatchedArraySet<String> mInstallerPackages;
    private final SnapshotCache<WatchedArraySet<String>> mInstallerPackagesSnapshot;

    /** Map from package name to appId and excluded userids */
    @Watched
    private final WatchedArrayMap<String, KernelPackageState> mKernelMapping;
    private final SnapshotCache<WatchedArrayMap<String, KernelPackageState>> mKernelMappingSnapshot;

    // List of replaced system applications
    @Watched
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    final WatchedArrayMap<String, PackageSetting> mDisabledSysPackages = new WatchedArrayMap<>();

    /** List of packages that are blocked for uninstall for specific users */
    @Watched
    private final WatchedSparseArray<ArraySet<String>> mBlockUninstallPackages =
            new WatchedSparseArray<>();

    private static final class KernelPackageState {
        int appId;
        int[] excludedUserIds;
    }

    /** Map from volume UUID to {@link VersionInfo} */
    @Watched
    private final WatchedArrayMap<String, VersionInfo> mVersion = new WatchedArrayMap<>();

    /**
     * Version details for a storage volume that may hold apps.
     */
    public static class VersionInfo {
        /**
         * These are the last platform API version we were using for the apps
         * installed on internal and external storage. It is used to grant newer
         * permissions one time during a system upgrade.
         */
        int sdkVersion;

        /**
         * The current database version for apps on internal storage. This is
         * used to upgrade the format of the packages.xml database not
         * necessarily tied to an SDK version.
         */
        int databaseVersion;

        /**
         * Last known value of {@link Build#FINGERPRINT}. Stored for debug purposes.
         */
        String buildFingerprint;

        /**
         * Last known value of {@link PackagePartitions#FINGERPRINT}. Used to determine when
         * an system update has occurred, meaning we need to clear code caches.
         */
        String fingerprint;

        /**
         * Force all version information to match current system values,
         * typically after resolving any required upgrade steps.
         */
        public void forceCurrent() {
            sdkVersion = Build.VERSION.SDK_INT;
            databaseVersion = CURRENT_DATABASE_VERSION;
            buildFingerprint = Build.FINGERPRINT;
            fingerprint = PackagePartitions.FINGERPRINT;
        }
    }

    /** Device identity for the purpose of package verification. */
    @Watched(manual = true)
    private VerifierDeviceIdentity mVerifierDeviceIdentity;

    // The user's preferred activities associated with particular intent
    // filters.
    @Watched
    private final WatchedSparseArray<PreferredIntentResolver> mPreferredActivities;
    private final SnapshotCache<WatchedSparseArray<PreferredIntentResolver>>
            mPreferredActivitiesSnapshot;

    // The persistent preferred activities of the user's profile/device owner
    // associated with particular intent filters.
    @Watched
    private final WatchedSparseArray<PersistentPreferredIntentResolver>
            mPersistentPreferredActivities;
    private final SnapshotCache<WatchedSparseArray<PersistentPreferredIntentResolver>>
            mPersistentPreferredActivitiesSnapshot;


    // For every user, it is used to find to which other users the intent can be forwarded.
    @Watched
    private final WatchedSparseArray<CrossProfileIntentResolver> mCrossProfileIntentResolvers;
    private final SnapshotCache<WatchedSparseArray<CrossProfileIntentResolver>>
            mCrossProfileIntentResolversSnapshot;

    @Watched
    final WatchedArrayMap<String, SharedUserSetting> mSharedUsers = new WatchedArrayMap<>();
    @Watched(manual = true)
    private final AppIdSettingMap mAppIds;

    // For reading/writing settings file.
    @Watched
    private final WatchedArrayList<Signature> mPastSignatures;
    private final SnapshotCache<WatchedArrayList<Signature>> mPastSignaturesSnapshot;

    @Watched
    private final WatchedArrayMap<Long, Integer> mKeySetRefs;
    private final SnapshotCache<WatchedArrayMap<Long, Integer>> mKeySetRefsSnapshot;

    // Packages that have been renamed since they were first installed.
    // Keys are the new names of the packages, values are the original
    // names.  The packages appear everywhere else under their original
    // names.
    @Watched
    private final WatchedArrayMap<String, String> mRenamedPackages =
            new WatchedArrayMap<String, String>();

    // For every user, it is used to find the package name of the default browser app pending to be
    // applied, either on first boot after upgrade, or after backup & restore but before app is
    // installed.
    @Watched
    final WatchedSparseArray<String> mPendingDefaultBrowser = new WatchedSparseArray<>();

    // TODO(b/161161364): This seems unused, and is probably not relevant in the new API, but should
    //  verify.
    // App-link priority tracking, per-user
    @NonNull
    @Watched
    private final WatchedSparseIntArray mNextAppLinkGeneration = new WatchedSparseIntArray();

    final StringBuilder mReadMessages = new StringBuilder();

    /**
     * Used to track packages that have a shared user ID that hasn't been read
     * in yet.
     * <p>
     * TODO: make this just a local variable that is passed in during package
     * scanning to make it less confusing.
     */
    @Watched
    private final WatchedArrayList<PackageSetting> mPendingPackages;
    private final SnapshotCache<WatchedArrayList<PackageSetting>> mPendingPackagesSnapshot;

    private final File mSystemDir;

    private final KeySetManagerService mKeySetManagerService;

    /** Settings and other information about permissions */
    @Watched(manual = true)
    final LegacyPermissionSettings mPermissions;

    @Watched(manual = true)
    private final LegacyPermissionDataProvider mPermissionDataProvider;

    @Watched(manual = true)
    private final DomainVerificationManagerInternal mDomainVerificationManager;

    /**
     * The observer that watches for changes from array members
     */
    private final Watcher mObserver = new Watcher() {
            @Override
            public void onChange(@Nullable Watchable what) {
                Settings.this.dispatchChange(what);
            }
        };

    private final SnapshotCache<Settings> mSnapshot;

    // Create a snapshot cache
    private SnapshotCache<Settings> makeCache() {
        return new SnapshotCache<Settings>(this, this) {
            @Override
            public Settings createSnapshot() {
                Settings s = new Settings(mSource);
                s.mWatchable.seal();
                return s;
            }};
    }

    private void registerObservers() {
        mPackages.registerObserver(mObserver);
        mInstallerPackages.registerObserver(mObserver);
        mKernelMapping.registerObserver(mObserver);
        mDisabledSysPackages.registerObserver(mObserver);
        mBlockUninstallPackages.registerObserver(mObserver);
        mVersion.registerObserver(mObserver);
        mPreferredActivities.registerObserver(mObserver);
        mPersistentPreferredActivities.registerObserver(mObserver);
        mCrossProfileIntentResolvers.registerObserver(mObserver);
        mSharedUsers.registerObserver(mObserver);
        mAppIds.registerObserver(mObserver);
        mRenamedPackages.registerObserver(mObserver);
        mNextAppLinkGeneration.registerObserver(mObserver);
        mPendingDefaultBrowser.registerObserver(mObserver);
        mPendingPackages.registerObserver(mObserver);
        mPastSignatures.registerObserver(mObserver);
        mKeySetRefs.registerObserver(mObserver);
    }

    // CONSTRUCTOR
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public Settings(Map<String, PackageSetting> pkgSettings) {
        mPackages = new WatchedArrayMap<>();
        mPackagesSnapshot =
                new SnapshotCache.Auto<>(mPackages, mPackages, "Settings.mPackages");
        mKernelMapping = new WatchedArrayMap<>();
        mKernelMappingSnapshot =
                new SnapshotCache.Auto<>(mKernelMapping, mKernelMapping, "Settings.mKernelMapping");
        mInstallerPackages = new WatchedArraySet<>();
        mInstallerPackagesSnapshot =
                new SnapshotCache.Auto<>(mInstallerPackages, mInstallerPackages,
                                         "Settings.mInstallerPackages");
        mPreferredActivities = new WatchedSparseArray<>();
        mPreferredActivitiesSnapshot = new SnapshotCache.Auto<>(mPreferredActivities,
                mPreferredActivities, "Settings.mPreferredActivities");
        mPersistentPreferredActivities = new WatchedSparseArray<>();
        mPersistentPreferredActivitiesSnapshot = new SnapshotCache.Auto<>(
                mPersistentPreferredActivities, mPersistentPreferredActivities,
                "Settings.mPersistentPreferredActivities");
        mCrossProfileIntentResolvers = new WatchedSparseArray<>();
        mCrossProfileIntentResolversSnapshot = new SnapshotCache.Auto<>(
                mCrossProfileIntentResolvers, mCrossProfileIntentResolvers,
                "Settings.mCrossProfileIntentResolvers");
        mPastSignatures = new WatchedArrayList<>();
        mPastSignaturesSnapshot = new SnapshotCache.Auto<>(mPastSignatures, mPastSignatures,
                "Settings.mPastSignatures");
        mKeySetRefs = new WatchedArrayMap<>();
        mKeySetRefsSnapshot = new SnapshotCache.Auto<>(mKeySetRefs, mKeySetRefs,
                "Settings.mKeySetRefs");
        mPendingPackages = new WatchedArrayList<>();
        mPendingPackagesSnapshot = new SnapshotCache.Auto<>(mPendingPackages, mPendingPackages,
                "Settings.mPendingPackages");
        mKeySetManagerService = new KeySetManagerService(mPackages);

        // Test-only handler working on background thread.
        mHandler = new Handler(BackgroundThread.getHandler().getLooper());
        mLock = new PackageManagerTracedLock();
        mPackages.putAll(pkgSettings);
        mAppIds = new AppIdSettingMap();
        mSystemDir = null;
        mPermissions = null;
        mRuntimePermissionsPersistence = null;
        mPermissionDataProvider = null;
        mSettingsFilename = null;
        mSettingsReserveCopyFilename = null;
        mPreviousSettingsFilename = null;
        mPackageListFilename = null;
        mStoppedPackagesFilename = null;
        mBackupStoppedPackagesFilename = null;
        mKernelMappingFilename = null;
        mDomainVerificationManager = null;

        registerObservers();
        Watchable.verifyWatchedAttributes(this, mObserver);

        mSnapshot = makeCache();
    }

    Settings(File dataDir, RuntimePermissionsPersistence runtimePermissionsPersistence,
            LegacyPermissionDataProvider permissionDataProvider,
            @NonNull DomainVerificationManagerInternal domainVerificationManager,
            @NonNull Handler handler,
            @NonNull PackageManagerTracedLock lock)  {
        mPackages = new WatchedArrayMap<>();
        mPackagesSnapshot  =
                new SnapshotCache.Auto<>(mPackages, mPackages, "Settings.mPackages");
        mKernelMapping = new WatchedArrayMap<>();
        mKernelMappingSnapshot =
                new SnapshotCache.Auto<>(mKernelMapping, mKernelMapping, "Settings.mKernelMapping");
        mInstallerPackages = new WatchedArraySet<>();
        mInstallerPackagesSnapshot =
                new SnapshotCache.Auto<>(mInstallerPackages, mInstallerPackages,
                                         "Settings.mInstallerPackages");
        mPreferredActivities = new WatchedSparseArray<>();
        mPreferredActivitiesSnapshot = new SnapshotCache.Auto<>(mPreferredActivities,
                mPreferredActivities, "Settings.mPreferredActivities");
        mPersistentPreferredActivities = new WatchedSparseArray<>();
        mPersistentPreferredActivitiesSnapshot = new SnapshotCache.Auto<>(
                mPersistentPreferredActivities, mPersistentPreferredActivities,
                "Settings.mPersistentPreferredActivities");
        mCrossProfileIntentResolvers = new WatchedSparseArray<>();
        mCrossProfileIntentResolversSnapshot = new SnapshotCache.Auto<>(
                mCrossProfileIntentResolvers, mCrossProfileIntentResolvers,
                "Settings.mCrossProfileIntentResolvers");
        mPastSignatures = new WatchedArrayList<>();
        mPastSignaturesSnapshot = new SnapshotCache.Auto<>(mPastSignatures, mPastSignatures,
                "Settings.mPastSignatures");
        mKeySetRefs = new WatchedArrayMap<>();
        mKeySetRefsSnapshot = new SnapshotCache.Auto<>(mKeySetRefs, mKeySetRefs,
                "Settings.mKeySetRefs");
        mPendingPackages = new WatchedArrayList<>();
        mPendingPackagesSnapshot = new SnapshotCache.Auto<>(mPendingPackages, mPendingPackages,
                "Settings.mPendingPackages");
        mKeySetManagerService = new KeySetManagerService(mPackages);

        mHandler = handler;
        mLock = lock;
        mAppIds = new AppIdSettingMap();
        mPermissions = new LegacyPermissionSettings();
        mRuntimePermissionsPersistence = new RuntimePermissionPersistence(
                runtimePermissionsPersistence, new Consumer<Integer>() {
            @Override
            public void accept(Integer userId) {
                mRuntimePermissionsPersistence.writeStateForUser(userId, mPermissionDataProvider,
                        mPackages, mSharedUsers, mHandler, mLock, /*sync=*/false);
            }
        });
        mPermissionDataProvider = permissionDataProvider;

        mSystemDir = new File(dataDir, "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG
                |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                -1, -1);
        mSettingsFilename = new File(mSystemDir, "packages.xml");
        mSettingsReserveCopyFilename = new File(mSystemDir, "packages.xml.reservecopy");
        mPreviousSettingsFilename = new File(mSystemDir, "packages-backup.xml");
        mPackageListFilename = new File(mSystemDir, "packages.list");
        FileUtils.setPermissions(mPackageListFilename, 0640, SYSTEM_UID, PACKAGE_INFO_GID);

        final File kernelDir = new File("/config/sdcardfs");
        mKernelMappingFilename = kernelDir.exists() ? kernelDir : null;

        // Deprecated: Needed for migration
        mStoppedPackagesFilename = new File(mSystemDir, "packages-stopped.xml");
        mBackupStoppedPackagesFilename = new File(mSystemDir, "packages-stopped-backup.xml");

        mDomainVerificationManager = domainVerificationManager;

        registerObservers();
        Watchable.verifyWatchedAttributes(this, mObserver);

        mSnapshot = makeCache();
    }

    /**
     * A copy constructor used in snapshot().  Attributes that are supposed to be
     * immutable in the PackageManagerService application are referenced.  Attributes that
     * are changed by PackageManagerService APIs are deep-copied
     */
    private Settings(Settings r) {
        mPackages = r.mPackagesSnapshot.snapshot();
        mPackagesSnapshot  = new SnapshotCache.Sealed<>();
        mKernelMapping = r.mKernelMappingSnapshot.snapshot();
        mKernelMappingSnapshot = new SnapshotCache.Sealed<>();
        mInstallerPackages = r.mInstallerPackagesSnapshot.snapshot();
        mInstallerPackagesSnapshot = new SnapshotCache.Sealed<>();
        mKeySetManagerService = new KeySetManagerService(r.mKeySetManagerService, mPackages);

        // The following assignments satisfy Java requirements but are not
        // needed by the read-only methods.  Note especially that the lock
        // is not required because this clone is meant to support lock-free
        // read-only methods.
        mHandler = null;
        mLock = null;
        mRuntimePermissionsPersistence = r.mRuntimePermissionsPersistence;
        mSettingsFilename = null;
        mSettingsReserveCopyFilename = null;
        mPreviousSettingsFilename = null;
        mPackageListFilename = null;
        mStoppedPackagesFilename = null;
        mBackupStoppedPackagesFilename = null;
        mKernelMappingFilename = null;

        mDomainVerificationManager = r.mDomainVerificationManager;

        mDisabledSysPackages.snapshot(r.mDisabledSysPackages);
        mBlockUninstallPackages.snapshot(r.mBlockUninstallPackages);
        mVersion.putAll(r.mVersion);
        mVerifierDeviceIdentity = r.mVerifierDeviceIdentity;
        mPreferredActivities = r.mPreferredActivitiesSnapshot.snapshot();
        mPreferredActivitiesSnapshot = new SnapshotCache.Sealed<>();
        mPersistentPreferredActivities = r.mPersistentPreferredActivitiesSnapshot.snapshot();
        mPersistentPreferredActivitiesSnapshot = new SnapshotCache.Sealed<>();
        mCrossProfileIntentResolvers = r.mCrossProfileIntentResolversSnapshot.snapshot();
        mCrossProfileIntentResolversSnapshot = new SnapshotCache.Sealed<>();

        mSharedUsers.snapshot(r.mSharedUsers);
        mAppIds = r.mAppIds.snapshot();

        mPastSignatures = r.mPastSignaturesSnapshot.snapshot();
        mPastSignaturesSnapshot = new SnapshotCache.Sealed<>();
        mKeySetRefs = r.mKeySetRefsSnapshot.snapshot();
        mKeySetRefsSnapshot = new SnapshotCache.Sealed<>();

        mRenamedPackages.snapshot(r.mRenamedPackages);
        mNextAppLinkGeneration.snapshot(r.mNextAppLinkGeneration);
        mPendingDefaultBrowser.snapshot(r.mPendingDefaultBrowser);
        // mReadMessages
        mPendingPackages = r.mPendingPackagesSnapshot.snapshot();
        mPendingPackagesSnapshot = new SnapshotCache.Sealed<>();
        mSystemDir = null;
        // mKeySetManagerService;
        mPermissions = r.mPermissions;
        mPermissionDataProvider = r.mPermissionDataProvider;

        // Do not register any Watchables and do not create a snapshot cache.
        mSnapshot = new SnapshotCache.Sealed();
    }

    /**
     * Return a snapshot.
     */
    public Settings snapshot() {
        return mSnapshot.snapshot();
    }

    private void invalidatePackageCache() {
        PackageManagerService.invalidatePackageInfoCache();
        ChangeIdStateCache.invalidate();
        onChanged();
    }

    PackageSetting getPackageLPr(String pkgName) {
        return mPackages.get(pkgName);
    }

    WatchedArrayMap<String, PackageSetting> getPackagesLocked() {
        return mPackages;
    }

    WatchedArrayMap<String, PackageSetting> getDisabledSystemPackagesLocked() {
        return mDisabledSysPackages;
    }

    KeySetManagerService getKeySetManagerService() {
        return mKeySetManagerService;
    }

    String getRenamedPackageLPr(String pkgName) {
        return mRenamedPackages.get(pkgName);
    }

    String addRenamedPackageLPw(String pkgName, String origPkgName) {
        return mRenamedPackages.put(pkgName, origPkgName);
    }

    void removeRenamedPackageLPw(String pkgName) {
        mRenamedPackages.remove(pkgName);
    }

    void pruneRenamedPackagesLPw() {
        for (int i = mRenamedPackages.size() - 1; i >= 0; i--) {
            PackageSetting ps = mPackages.get(mRenamedPackages.valueAt(i));
            if (ps == null) {
                mRenamedPackages.removeAt(i);
            }
        }
    }

    /** Gets and optionally creates a new shared user id. */
    SharedUserSetting getSharedUserLPw(String name, int pkgFlags, int pkgPrivateFlags,
            boolean create) throws PackageManagerException {
        SharedUserSetting s = mSharedUsers.get(name);
        if (s == null && create) {
            s = new SharedUserSetting(name, pkgFlags, pkgPrivateFlags);
            s.mAppId = mAppIds.acquireAndRegisterNewAppId(s);
            if (s.mAppId < 0) {
                // < 0 means we couldn't assign a userid; throw exception
                throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                        "Creating shared user " + name + " failed");
            }
            Log.i(PackageManagerService.TAG, "New shared user " + name + ": id=" + s.mAppId);
            mSharedUsers.put(name, s);
        }
        return s;
    }

    WatchedArrayMap<String, ? extends SharedUserApi> getSharedUsersLocked() {
        return mSharedUsers;
    }

    Collection<SharedUserSetting> getAllSharedUsersLPw() {
        return mSharedUsers.values();
    }

    boolean disableSystemPackageLPw(String name, boolean replaced) {
        final PackageSetting p = mPackages.get(name);
        if(p == null) {
            Log.w(PackageManagerService.TAG, "Package " + name + " is not an installed package");
            return false;
        }
        final PackageSetting dp = mDisabledSysPackages.get(name);
        // always make sure the system package code and resource paths dont change
        if (dp == null && p.getPkg() != null && p.isSystem()
                && !p.isUpdatedSystemApp()) {
            final PackageSetting disabled;
            if (replaced) {
                // a little trick...  when we install the new package, we don't
                // want to modify the existing PackageSetting for the built-in
                // version.  so at this point we make a copy to place into the
                // disabled set.
                disabled = new PackageSetting(p);
            } else {
                disabled = p;
            }
            p.getPkgState().setUpdatedSystemApp(true);
            mDisabledSysPackages.put(name, disabled);
            SharedUserSetting sharedUserSetting = getSharedUserSettingLPr(disabled);
            if (sharedUserSetting != null) {
                sharedUserSetting.mDisabledPackages.add(disabled);
            }
            return true;
        }
        return false;
    }

    PackageSetting enableSystemPackageLPw(String name) {
        PackageSetting p = mDisabledSysPackages.get(name);
        if(p == null) {
            Log.w(PackageManagerService.TAG, "Package " + name + " is not disabled");
            return null;
        }
        SharedUserSetting sharedUserSetting = getSharedUserSettingLPr(p);
        if (sharedUserSetting != null) {
            sharedUserSetting.mDisabledPackages.remove(p);
        }
        p.getPkgState().setUpdatedSystemApp(false);
        PackageSetting ret = addPackageLPw(name, p.getRealName(), p.getPath(), p.getAppId(),
                p.getFlags(), p.getPrivateFlags(), mDomainVerificationManager.generateNewId());
        if (ret != null) {
            ret.setLegacyNativeLibraryPath(p.getLegacyNativeLibraryPath());
            ret.setPrimaryCpuAbi(p.getPrimaryCpuAbiLegacy());
            ret.setSecondaryCpuAbi(p.getSecondaryCpuAbiLegacy());
            ret.setCpuAbiOverride(p.getCpuAbiOverride());
            ret.setLongVersionCode(p.getVersionCode());
            ret.setUsesSdkLibraries(p.getUsesSdkLibraries());
            ret.setUsesSdkLibrariesVersionsMajor(p.getUsesSdkLibrariesVersionsMajor());
            ret.setUsesStaticLibraries(p.getUsesStaticLibraries());
            ret.setUsesStaticLibrariesVersions(p.getUsesStaticLibrariesVersions());
            ret.setMimeGroups(p.getMimeGroups());
            ret.setAppMetadataFilePath(p.getAppMetadataFilePath());
            ret.getPkgState().setUpdatedSystemApp(false);
            ret.setTargetSdkVersion(p.getTargetSdkVersion());
            ret.setRestrictUpdateHash(p.getRestrictUpdateHash());
        }
        mDisabledSysPackages.remove(name);
        return ret;
    }

    boolean isDisabledSystemPackageLPr(String name) {
        return mDisabledSysPackages.containsKey(name);
    }

    void removeDisabledSystemPackageLPw(String name) {
        final PackageSetting p = mDisabledSysPackages.remove(name);
        if (p != null) {
            SharedUserSetting sharedUserSetting = getSharedUserSettingLPr(p);
            if (sharedUserSetting != null) {
                sharedUserSetting.mDisabledPackages.remove(p);
                checkAndPruneSharedUserLPw(sharedUserSetting, false);
            }
        }
    }

    PackageSetting addPackageLPw(String name, String realName, File codePath, int uid, int pkgFlags,
                                 int pkgPrivateFlags, @NonNull UUID domainSetId) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (p.getAppId() == uid) {
                return p;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate package, keeping first: " + name);
            return null;
        }
        p = new PackageSetting(name, realName, codePath, pkgFlags, pkgPrivateFlags, domainSetId)
                .setAppId(uid);
        if (mAppIds.registerExistingAppId(uid, p, name)) {
            mPackages.put(name, p);
            return p;
        }
        return null;
    }

    SharedUserSetting addSharedUserLPw(String name, int uid, int pkgFlags, int pkgPrivateFlags) {
        SharedUserSetting s = mSharedUsers.get(name);
        if (s != null) {
            if (s.mAppId == uid) {
                return s;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate shared user, keeping first: " + name);
            return null;
        }
        s = new SharedUserSetting(name, pkgFlags, pkgPrivateFlags);
        s.mAppId = uid;
        if (mAppIds.registerExistingAppId(uid, s, name)) {
            mSharedUsers.put(name, s);
            return s;
        }
        return null;
    }

    void pruneSharedUsersLPw() {
        List<String> removeKeys = new ArrayList<>();
        List<SharedUserSetting> removeValues = new ArrayList<>();
        for (Map.Entry<String, SharedUserSetting> entry : mSharedUsers.entrySet()) {
            final SharedUserSetting sus = entry.getValue();
            if (sus == null) {
                removeKeys.add(entry.getKey());
                continue;
            }
            boolean changed = false;
            // remove packages that are no longer installed
            WatchedArraySet<PackageSetting> sharedUserPackageSettings = sus.getPackageSettings();
            for (int i = sharedUserPackageSettings.size() - 1; i >= 0; i--) {
                PackageSetting ps = sharedUserPackageSettings.valueAt(i);
                if (mPackages.get(ps.getPackageName()) == null) {
                    sharedUserPackageSettings.removeAt(i);
                    changed = true;
                }
            }
            WatchedArraySet<PackageSetting> sharedUserDisabledPackageSettings =
                    sus.getDisabledPackageSettings();
            for (int i = sharedUserDisabledPackageSettings.size() - 1; i >= 0; i--) {
                PackageSetting ps = sharedUserDisabledPackageSettings.valueAt(i);
                if (mDisabledSysPackages.get(ps.getPackageName()) == null) {
                    sharedUserDisabledPackageSettings.removeAt(i);
                    changed = true;
                }
            }
            if (changed) {
                sus.onChanged();
            }
            if (sharedUserPackageSettings.isEmpty()
                    && sharedUserDisabledPackageSettings.isEmpty()) {
                removeValues.add(sus);
            }
        }
        removeKeys.forEach(mSharedUsers::remove);
        removeValues.forEach(sus -> checkAndPruneSharedUserLPw(sus, true));
    }

    /**
     * Creates a new {@code PackageSetting} object.
     * Use this method instead of the constructor to ensure a settings object is created
     * with the correct base.
     */
    static @NonNull PackageSetting createNewSetting(String pkgName, PackageSetting originalPkg,
            PackageSetting disabledPkg, String realPkgName, SharedUserSetting sharedUser,
            File codePath, String legacyNativeLibraryPath, String primaryCpuAbi,
            String secondaryCpuAbi, long versionCode, int pkgFlags, int pkgPrivateFlags,
            UserHandle installUser, boolean allowInstall, boolean instantApp,
            boolean virtualPreload, boolean isStoppedSystemApp, UserManagerService userManager,
            String[] usesSdkLibraries, long[] usesSdkLibrariesVersions,
            String[] usesStaticLibraries, long[] usesStaticLibrariesVersions,
            Set<String> mimeGroupNames, @NonNull UUID domainSetId,
            int targetSdkVersion, byte[] restrictUpdatedHash) {
        final PackageSetting pkgSetting;
        if (originalPkg != null) {
            if (PackageManagerService.DEBUG_UPGRADE) Log.v(PackageManagerService.TAG, "Package "
                    + pkgName + " is adopting original package " + originalPkg.getPackageName());
            pkgSetting = new PackageSetting(originalPkg, pkgName /*realPkgName*/)
                    .setPath(codePath)
                    .setLegacyNativeLibraryPath(legacyNativeLibraryPath)
                    .setPrimaryCpuAbi(primaryCpuAbi)
                    .setSecondaryCpuAbi(secondaryCpuAbi)
                    // NOTE: Create a deeper copy of the package signatures so we don't
                    // overwrite the signatures in the original package setting.
                    .setSignatures(new PackageSignatures())
                    .setLongVersionCode(versionCode)
                    .setUsesSdkLibraries(usesSdkLibraries)
                    .setUsesSdkLibrariesVersionsMajor(usesSdkLibrariesVersions)
                    .setUsesStaticLibraries(usesStaticLibraries)
                    .setUsesStaticLibrariesVersions(usesStaticLibrariesVersions)
                    // Update new package state.
                    .setLastModifiedTime(codePath.lastModified())
                    .setDomainSetId(domainSetId)
                    .setTargetSdkVersion(targetSdkVersion)
                    .setRestrictUpdateHash(restrictUpdatedHash);
            pkgSetting.setFlags(pkgFlags)
                    .setPrivateFlags(pkgPrivateFlags);
        } else {
            int installUserId = installUser != null ? installUser.getIdentifier()
                    : UserHandle.USER_SYSTEM;

            pkgSetting = new PackageSetting(pkgName, realPkgName, codePath, pkgFlags,
                    pkgPrivateFlags, domainSetId)
                    .setUsesSdkLibraries(usesSdkLibraries)
                    .setUsesSdkLibrariesVersionsMajor(usesSdkLibrariesVersions)
                    .setUsesStaticLibraries(usesStaticLibraries)
                    .setUsesStaticLibrariesVersions(usesStaticLibrariesVersions)
                    .setLegacyNativeLibraryPath(legacyNativeLibraryPath)
                    .setPrimaryCpuAbi(primaryCpuAbi)
                    .setSecondaryCpuAbi(secondaryCpuAbi)
                    .setLongVersionCode(versionCode)
                    .setMimeGroups(createMimeGroups(mimeGroupNames))
                    .setTargetSdkVersion(targetSdkVersion)
                    .setRestrictUpdateHash(restrictUpdatedHash)
                    .setLastModifiedTime(codePath.lastModified());
            if (sharedUser != null) {
                pkgSetting.setSharedUserAppId(sharedUser.mAppId);
            }
            // If this is not a system app, it starts out stopped.
            if ((pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                if (DEBUG_STOPPED) {
                    RuntimeException e = new RuntimeException("here");
                    e.fillInStackTrace();
                    Slog.i(PackageManagerService.TAG, "Stopping package " + pkgName, e);
                }
                List<UserInfo> users = getAllUsers(userManager);
                if (users != null && allowInstall) {
                    for (UserInfo user : users) {
                        // By default we consider this app to be installed
                        // for the user if no user has been specified (which
                        // means to leave it at its original value, and the
                        // original default value is true), or we are being
                        // asked to install for all users, or this is the
                        // user we are installing for.
                        final boolean installed = installUser == null
                                || (installUserId == UserHandle.USER_ALL
                                    && !isAdbInstallDisallowed(userManager, user.id)
                                    && !user.preCreated)
                                || installUserId == user.id;
                        if (DEBUG_MU) {
                            Slogf.d(TAG, "createNewSetting(pkg=%s, installUserId=%s, user=%s, "
                                    + "installed=%b)",
                                    pkgName, installUserId, user.toFullString(), installed);
                        }
                        pkgSetting.setUserState(user.id, 0, 0, COMPONENT_ENABLED_STATE_DEFAULT,
                                installed,
                                true /*stopped*/,
                                true /*notLaunched*/,
                                false /*hidden*/,
                                0 /*distractionFlags*/,
                                null /*suspendParams*/,
                                instantApp,
                                virtualPreload,
                                null /*lastDisableAppCaller*/,
                                null /*enabledComponents*/,
                                null /*disabledComponents*/,
                                PackageManager.INSTALL_REASON_UNKNOWN,
                                PackageManager.UNINSTALL_REASON_UNKNOWN,
                                null /*harmfulAppWarning*/,
                                null /*splashscreenTheme*/,
                                0 /*firstInstallTime*/,
                                PackageManager.USER_MIN_ASPECT_RATIO_UNSET,
                                null /*archiveState*/
                        );
                    }
                }
            } else if (isStoppedSystemApp) {
                if (DEBUG_STOPPED) {
                    RuntimeException e = new RuntimeException("here");
                    e.fillInStackTrace();
                    Slog.i(PackageManagerService.TAG, "Stopping system package " + pkgName, e);
                }
                pkgSetting.setStopped(true, installUserId);
            }
            if (sharedUser != null) {
                pkgSetting.setAppId(sharedUser.mAppId);
            } else {
                // Clone the setting here for disabled system packages
                if (disabledPkg != null) {
                    // For disabled packages a new setting is created
                    // from the existing user id. This still has to be
                    // added to list of user id's
                    // Copy signatures from previous setting
                    pkgSetting.setSignatures(new PackageSignatures(disabledPkg.getSignatures()));
                    pkgSetting.setAppId(disabledPkg.getAppId());
                    // Clone permissions
                    pkgSetting.getLegacyPermissionState()
                            .copyFrom(disabledPkg.getLegacyPermissionState());
                    // Clone component info
                    List<UserInfo> users = getAllUsers(userManager);
                    if (users != null) {
                        for (UserInfo user : users) {
                            final int userId = user.id;
                            pkgSetting.setDisabledComponentsCopy(
                                    disabledPkg.getDisabledComponents(userId), userId);
                            pkgSetting.setEnabledComponentsCopy(
                                    disabledPkg.getEnabledComponents(userId), userId);
                        }
                    }
                }
            }
        }
        return pkgSetting;
    }

    private static Map<String, Set<String>> createMimeGroups(Set<String> mimeGroupNames) {
        if (mimeGroupNames == null) {
            return null;
        }

        return new KeySetToValueMap<>(mimeGroupNames, new ArraySet<>());
    }

    /**
     * Updates the given package setting using the provided information.
     * <p>
     * WARNING: The provided PackageSetting object may be mutated.
     */
    static void updatePackageSetting(@NonNull PackageSetting pkgSetting,
            @Nullable PackageSetting disabledPkg,
            @Nullable SharedUserSetting existingSharedUserSetting,
            @Nullable SharedUserSetting sharedUser,
            @NonNull File codePath, @Nullable String legacyNativeLibraryPath,
            @Nullable String primaryCpuAbi, @Nullable String secondaryCpuAbi, int pkgFlags,
            int pkgPrivateFlags, @NonNull UserManagerService userManager,
            @Nullable String[] usesSdkLibraries, @Nullable long[] usesSdkLibrariesVersions,
            @Nullable String[] usesStaticLibraries, @Nullable long[] usesStaticLibrariesVersions,
            @Nullable Set<String> mimeGroupNames, @NonNull UUID domainSetId,
            int targetSdkVersion, byte[] restrictUpdatedHash)
                    throws PackageManagerException {
        final String pkgName = pkgSetting.getPackageName();
        if (sharedUser != null) {
            if (!Objects.equals(existingSharedUserSetting, sharedUser)) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Package " + pkgName + " shared user changed from "
                                + (existingSharedUserSetting != null
                                ? existingSharedUserSetting.name : "<nothing>")
                                + " to " + sharedUser.name);
                throw new PackageManagerException(INSTALL_FAILED_UID_CHANGED,
                        "Updating application package " + pkgName + " failed");
            }
            pkgSetting.setSharedUserAppId(sharedUser.mAppId);
        } else {
            // migrating off shared user
            pkgSetting.setSharedUserAppId(INVALID_UID);
        }

        if (!pkgSetting.getPath().equals(codePath)) {
            final boolean isSystem = pkgSetting.isSystem();
            Slog.i(PackageManagerService.TAG,
                    "Update" + (isSystem ? " system" : "")
                    + " package " + pkgName
                    + " code path from " + pkgSetting.getPathString()
                    + " to " + codePath.toString()
                    + "; Retain data and using new");
            if (!isSystem) {
                // The package isn't considered as installed if the application was
                // first installed by another user. Update the installed flag when the
                // application ever becomes part of the system.
                if ((pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0 && disabledPkg == null) {
                    final List<UserInfo> allUserInfos = getAllUsers(userManager);
                    if (allUserInfos != null) {
                        for (UserInfo userInfo : allUserInfos) {
                            pkgSetting.setInstalled(true, userInfo.id);
                            pkgSetting.setUninstallReason(UNINSTALL_REASON_UNKNOWN, userInfo.id);
                        }
                    }
                }

                // Since we've changed paths, prefer the new native library path over
                // the one stored in the package settings since we might have moved from
                // internal to external storage or vice versa.
                pkgSetting.setLegacyNativeLibraryPath(legacyNativeLibraryPath);
            }
            pkgSetting.setPath(codePath);
        }

        pkgSetting.setPrimaryCpuAbi(primaryCpuAbi)
                .setSecondaryCpuAbi(secondaryCpuAbi)
                .updateMimeGroups(mimeGroupNames)
                .setDomainSetId(domainSetId)
                .setTargetSdkVersion(targetSdkVersion)
                .setRestrictUpdateHash(restrictUpdatedHash);
        // Update SDK library dependencies if needed.
        if (usesSdkLibraries != null && usesSdkLibrariesVersions != null
                && usesSdkLibraries.length == usesSdkLibrariesVersions.length) {
            pkgSetting.setUsesSdkLibraries(usesSdkLibraries)
                    .setUsesSdkLibrariesVersionsMajor(usesSdkLibrariesVersions);
        } else {
            pkgSetting.setUsesSdkLibraries(null)
                    .setUsesSdkLibrariesVersionsMajor(null);
        }

        // Update static shared library dependencies if needed.
        if (usesStaticLibraries != null && usesStaticLibrariesVersions != null
                && usesStaticLibraries.length == usesStaticLibrariesVersions.length) {
            pkgSetting.setUsesStaticLibraries(usesStaticLibraries)
                    .setUsesStaticLibrariesVersions(usesStaticLibrariesVersions);
        } else {
            pkgSetting.setUsesStaticLibraries(null)
                    .setUsesStaticLibrariesVersions(null);
        }

        // If what we are scanning is a system (and possibly privileged) package,
        // then make it so, regardless of whether it was previously installed only
        // in the data partition. Reset first.
        int newPkgFlags = pkgSetting.getFlags();
        newPkgFlags &= ~ApplicationInfo.FLAG_SYSTEM;
        newPkgFlags |= pkgFlags & ApplicationInfo.FLAG_SYSTEM;
        // Only set pkgFlags.
        pkgSetting.setFlags(newPkgFlags);

        boolean wasRequiredForSystemUser = (pkgSetting.getPrivateFlags()
                & ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER) != 0;
        if (wasRequiredForSystemUser) {
            pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER;
        } else {
            pkgPrivateFlags &= ~ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER;
        }
        pkgSetting.setPrivateFlags(pkgPrivateFlags);
    }

    /**
     * Registers a user ID with the system. Potentially allocates a new user ID.
     * @return {@code true} if a new app ID was created in the process. {@code false} can be
     *         returned in the case that a shared user ID already exists or the explicit app ID is
     *         already registered.
     * @throws PackageManagerException If a user ID could not be allocated.
     */
    boolean registerAppIdLPw(PackageSetting p, boolean forceNew) throws PackageManagerException {
        final boolean createdNew;
        if (p.getAppId() == 0 || forceNew) {
            // Assign new user ID
            p.setAppId(mAppIds.acquireAndRegisterNewAppId(p));
            createdNew = true;
        } else {
            // Add new setting to list of user IDs
            createdNew = mAppIds.registerExistingAppId(p.getAppId(), p, p.getPackageName());
        }
        if (p.getAppId() < 0) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Package " + p.getPackageName() + " could not be assigned a valid UID");
            throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                    "Package " + p.getPackageName() + " could not be assigned a valid UID");
        }
        return createdNew;
    }

    /**
     * Writes per-user package restrictions if the user state has changed. If the user
     * state has not changed, this does nothing.
     */
    void writeUserRestrictionsLPw(PackageSetting newPackage, PackageSetting oldPackage) {
        // package doesn't exist; do nothing
        if (getPackageLPr(newPackage.getPackageName()) == null) {
            return;
        }
        // no users defined; do nothing
        final List<UserInfo> allUsers = getAllUsers(UserManagerService.getInstance());
        if (allUsers == null) {
            return;
        }
        for (UserInfo user : allUsers) {
            final PackageUserState oldUserState = oldPackage == null
                    ? PackageUserState.DEFAULT
                    : oldPackage.readUserState(user.id);
            if (!oldUserState.equals(newPackage.readUserState(user.id))) {
                writePackageRestrictionsLPr(user.id);
            }
        }
    }

    static boolean isAdbInstallDisallowed(UserManagerService userManager, int userId) {
        return userManager.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES,
                userId);
    }

    // TODO: Move to scanPackageOnlyLI() after verifying signatures are setup correctly
    // by that time.
    void insertPackageSettingLPw(PackageSetting p, AndroidPackage pkg) {
        // Update signatures if needed.
        if (p.getSigningDetails().getSignatures() == null) {
            p.setSigningDetails(pkg.getSigningDetails());
        }
        // If this app defines a shared user id initialize
        // the shared user signatures as well.
        SharedUserSetting sharedUserSetting = getSharedUserSettingLPr(p);
        if (sharedUserSetting != null) {
            if (sharedUserSetting.signatures.mSigningDetails.getSignatures() == null) {
                sharedUserSetting.signatures.mSigningDetails = pkg.getSigningDetails();
            }
        }
        addPackageSettingLPw(p, sharedUserSetting);
    }

    // Utility method that adds a PackageSetting to mPackages and
    // completes updating the shared user attributes and any restored
    // app link verification state
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    void addPackageSettingLPw(PackageSetting p, SharedUserSetting sharedUser) {
        mPackages.put(p.getPackageName(), p);
        if (sharedUser != null) {
            SharedUserSetting existingSharedUserSetting = getSharedUserSettingLPr(p);
            if (existingSharedUserSetting != null && existingSharedUserSetting != sharedUser) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Package " + p.getPackageName() + " was user "
                        + existingSharedUserSetting + " but is now " + sharedUser
                        + "; I am not changing its files so it will probably fail!");
                existingSharedUserSetting.removePackage(p);
            } else if (p.getAppId() != 0 && p.getAppId() != sharedUser.mAppId) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Package " + p.getPackageName() + " was app id " + p.getAppId()
                                + " but is now user " + sharedUser
                                + " with app id " + sharedUser.mAppId
                                + "; I am not changing its files so it will probably fail!");
            }

            sharedUser.addPackage(p);
            p.setSharedUserAppId(sharedUser.mAppId);
            p.setAppId(sharedUser.mAppId);
        }

        // If we know about this app id, we have to update it as it
        // has to point to the same PackageSetting instance as the package.
        Object appIdPs = getSettingLPr(p.getAppId());
        if (sharedUser == null) {
            if (appIdPs != null && appIdPs != p) {
                mAppIds.replaceSetting(p.getAppId(), p);
            }
        } else {
            if (appIdPs != null && appIdPs != sharedUser) {
                mAppIds.replaceSetting(p.getAppId(), sharedUser);
            }
        }
    }

    boolean checkAndPruneSharedUserLPw(SharedUserSetting s, boolean skipCheck) {
        if (skipCheck || (s.getPackageStates().isEmpty()
                && s.getDisabledPackageStates().isEmpty())) {
            if (mSharedUsers.remove(s.name) != null) {
                removeAppIdLPw(s.mAppId);
                return true;
            }
        }
        return false;
    }

    int removePackageLPw(String name) {
        final PackageSetting p = mPackages.remove(name);
        if (p != null) {
            removeInstallerPackageStatus(name);
            SharedUserSetting sharedUserSetting = getSharedUserSettingLPr(p);
            if (sharedUserSetting != null) {
                sharedUserSetting.removePackage(p);
                if (checkAndPruneSharedUserLPw(sharedUserSetting, false)) {
                    return sharedUserSetting.mAppId;
                }
            } else {
                removeAppIdLPw(p.getAppId());
                return p.getAppId();
            }
        }
        return -1;
    }

    /**
     * Checks if {@param packageName} is an installer package and if so, clear the installer
     * package name of the packages that are installed by this.
     */
    private void removeInstallerPackageStatus(String packageName) {
        // Check if the package to be removed is an installer package.
        if (!mInstallerPackages.contains(packageName)) {
            return;
        }
        for (int i = 0; i < mPackages.size(); i++) {
            mPackages.valueAt(i).removeInstallerPackage(packageName);
        }
        mInstallerPackages.remove(packageName);
    }

    /** Gets the setting associated with the provided App ID */
    public SettingBase getSettingLPr(int appId) {
        return mAppIds.getSetting(appId);
    }

    /** Unregisters the provided app ID. */
    void removeAppIdLPw(int appId) {
        mAppIds.removeSetting(appId);
    }
    /**
     * Transparently convert a SharedUserSetting into PackageSettings without changing appId.
     * The sharedUser passed to this method has to be {@link SharedUserSetting#isSingleUser()}.
     */
    void convertSharedUserSettingsLPw(SharedUserSetting sharedUser) {
        final PackageSetting ps = sharedUser.getPackageSettings().valueAt(0);
        mAppIds.replaceSetting(sharedUser.getAppId(), ps);

        // Unlink the SharedUserSetting
        ps.setSharedUserAppId(INVALID_UID);
        if (!sharedUser.getDisabledPackageSettings().isEmpty()) {
            final PackageSetting disabledPs = sharedUser.getDisabledPackageSettings().valueAt(0);
            disabledPs.setSharedUserAppId(INVALID_UID);
        }
        mSharedUsers.remove(sharedUser.getName());
    }

    /**
     * Check and convert eligible SharedUserSettings to PackageSettings.
     */
    void checkAndConvertSharedUserSettingsLPw(SharedUserSetting sharedUser) {
        if (!sharedUser.isSingleUser()) return;
        final AndroidPackage pkg = sharedUser.getPackageSettings().valueAt(0).getPkg();
        if (pkg != null && pkg.isLeavingSharedUser()
                && SharedUidMigration.applyStrategy(BEST_EFFORT)) {
            convertSharedUserSettingsLPw(sharedUser);
        }
    }

    PreferredIntentResolver editPreferredActivitiesLPw(int userId) {
        PreferredIntentResolver pir = mPreferredActivities.get(userId);
        if (pir == null) {
            pir = new PreferredIntentResolver();
            mPreferredActivities.put(userId, pir);
        }
        return pir;
    }

    PersistentPreferredIntentResolver editPersistentPreferredActivitiesLPw(int userId) {
        PersistentPreferredIntentResolver ppir = mPersistentPreferredActivities.get(userId);
        if (ppir == null) {
            ppir = new PersistentPreferredIntentResolver();
            mPersistentPreferredActivities.put(userId, ppir);
        }
        return ppir;
    }

    CrossProfileIntentResolver editCrossProfileIntentResolverLPw(int userId) {
        CrossProfileIntentResolver cpir = mCrossProfileIntentResolvers.get(userId);
        if (cpir == null) {
            cpir = new CrossProfileIntentResolver();
            mCrossProfileIntentResolvers.put(userId, cpir);
        }
        return cpir;
    }

    String getPendingDefaultBrowserLPr(int userId) {
        return mPendingDefaultBrowser.get(userId);
    }

    void setPendingDefaultBrowserLPw(String defaultBrowser, int userId) {
        mPendingDefaultBrowser.put(userId, defaultBrowser);
    }

    String removePendingDefaultBrowserLPw(int userId) {
        return mPendingDefaultBrowser.removeReturnOld(userId);
    }

    private File getUserSystemDirectory(int userId) {
        // This instead of Environment.getUserSystemDirectory(userId) to support testing.
        return new File(new File(mSystemDir, "users"), Integer.toString(userId));
    }

    private ResilientAtomicFile getUserPackagesStateFile(int userId) {
        File mainFile = new File(getUserSystemDirectory(userId), "package-restrictions.xml");
        File temporaryBackup = new File(getUserSystemDirectory(userId),
                "package-restrictions-backup.xml");
        File reserveCopy = new File(getUserSystemDirectory(userId),
                "package-restrictions.xml.reservecopy");
        return new ResilientAtomicFile(mainFile, temporaryBackup, reserveCopy,
                FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                "package restrictions", this);
    }

    private ResilientAtomicFile getSettingsFile() {
        return new ResilientAtomicFile(mSettingsFilename, mPreviousSettingsFilename,
                mSettingsReserveCopyFilename,
                FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                "package manager settings", this);
    }

    private File getUserRuntimePermissionsFile(int userId) {
        return new File(getUserSystemDirectory(userId), RUNTIME_PERMISSIONS_FILE_NAME);
    }

    // Default version is writing restrictions asynchronously.
    void writeAllUsersPackageRestrictionsLPr() {
        writeAllUsersPackageRestrictionsLPr(/*sync=*/false);
    }

    void writeAllUsersPackageRestrictionsLPr(boolean sync) {
        List<UserInfo> users = getAllUsers(UserManagerService.getInstance());
        if (users == null) return;

        if (sync) {
            // Cancel all pending per-user writes.
            synchronized (mPackageRestrictionsLock) {
                mPendingAsyncPackageRestrictionsWrites.clear();
            }
            mHandler.removeMessages(WRITE_USER_PACKAGE_RESTRICTIONS);
        }

        for (UserInfo user : users) {
            writePackageRestrictionsLPr(user.id, sync);
        }
    }

    void writeAllRuntimePermissionsLPr() {
        for (int userId : UserManagerService.getInstance().getUserIds()) {
            mRuntimePermissionsPersistence.writeStateForUserAsync(userId);
        }
    }

    boolean isPermissionUpgradeNeeded(int userId) {
        return mRuntimePermissionsPersistence.isPermissionUpgradeNeeded(userId);
    }

    void updateRuntimePermissionsFingerprint(@UserIdInt int userId) {
        mRuntimePermissionsPersistence.updateRuntimePermissionsFingerprint(userId);
    }

    int getDefaultRuntimePermissionsVersion(int userId) {
        return mRuntimePermissionsPersistence.getVersion(userId);
    }

    void setDefaultRuntimePermissionsVersion(int version, int userId) {
        mRuntimePermissionsPersistence.setVersion(version, userId);
    }

    void setPermissionControllerVersion(long version) {
        mRuntimePermissionsPersistence.setPermissionControllerVersion(version);
    }

    public VersionInfo findOrCreateVersion(String volumeUuid) {
        VersionInfo ver = mVersion.get(volumeUuid);
        if (ver == null) {
            ver = new VersionInfo();
            mVersion.put(volumeUuid, ver);
        }
        return ver;
    }

    public VersionInfo getInternalVersion() {
        return mVersion.get(StorageManager.UUID_PRIVATE_INTERNAL);
    }

    public VersionInfo getExternalVersion() {
        return mVersion.get(StorageManager.UUID_PRIMARY_PHYSICAL);
    }

    public void onVolumeForgotten(String fsUuid) {
        mVersion.remove(fsUuid);
    }

    /**
     * Applies the preferred activity state described by the given XML.  This code
     * also supports the restore-from-backup code path.
     *
     * @see PreferredActivityBackupHelper
     */
    void readPreferredActivitiesLPw(TypedXmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                PreferredActivity pa = new PreferredActivity(parser);
                if (pa.mPref.getParseError() == null) {
                    final PreferredIntentResolver resolver = editPreferredActivitiesLPw(userId);
                    if (resolver.shouldAddPreferredActivity(pa)) {
                        resolver.addFilter(null, pa);
                    }
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <preferred-activity> "
                                    + pa.mPref.getParseError() + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <preferred-activities>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readPersistentPreferredActivitiesLPw(TypedXmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                PersistentPreferredActivity ppa = new PersistentPreferredActivity(parser);
                editPersistentPreferredActivitiesLPw(userId).addFilter(null, ppa);
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <" + TAG_PERSISTENT_PREFERRED_ACTIVITIES + ">: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readCrossProfileIntentFiltersLPw(TypedXmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            final String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                CrossProfileIntentFilter cpif = new CrossProfileIntentFilter(parser);
                editCrossProfileIntentResolverLPw(userId).addFilter(null, cpif);
            } else {
                String msg = "Unknown element under " +  TAG_CROSS_PROFILE_INTENT_FILTERS + ": " +
                        tagName;
                PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void readDefaultAppsLPw(XmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        String defaultBrowser = readDefaultApps(parser);
        if (defaultBrowser != null) {
            mPendingDefaultBrowser.put(userId, defaultBrowser);
        }
    }

    /**
     * @return the package name for the default browser app, or {@code null} if none.
     */
    @Nullable
    static String readDefaultApps(@NonNull XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String defaultBrowser = null;
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals(TAG_DEFAULT_BROWSER)) {
                defaultBrowser = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
            } else if (tagName.equals(TAG_DEFAULT_DIALER)) {
                // Ignored.
            } else {
                String msg = "Unknown element under " +  TAG_DEFAULT_APPS + ": " +
                        parser.getName();
                PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                XmlUtils.skipCurrentTag(parser);
            }
        }
        return defaultBrowser;
    }

    void readBlockUninstallPackagesLPw(TypedXmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        ArraySet<String> packages = new ArraySet<>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals(TAG_BLOCK_UNINSTALL)) {
                String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                packages.add(packageName);
            } else {
                String msg = "Unknown element under " +  TAG_BLOCK_UNINSTALL_PACKAGES + ": " +
                        parser.getName();
                PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                XmlUtils.skipCurrentTag(parser);
            }
        }
        if (packages.isEmpty()) {
            mBlockUninstallPackages.remove(userId);
        } else {
            mBlockUninstallPackages.put(userId, packages);
        }
    }

    @Override
    public void logEvent(int priority, String msg) {
        mReadMessages.append(msg + "\n");
        PackageManagerService.reportSettingsProblem(priority, msg);
    }


    void readPackageRestrictionsLPr(int userId,
            @NonNull ArrayMap<String, Long> origFirstInstallTimes) {
        if (DEBUG_MU) {
            Log.i(TAG, "Reading package restrictions for user=" + userId);
        }

        try (ResilientAtomicFile atomicFile = getUserPackagesStateFile(userId)) {
            FileInputStream str = null;
            try {
                synchronized (mPackageRestrictionsLock) {
                    str = atomicFile.openRead();
                    if (str == null) {
                        // At first boot, make sure no packages are stopped.
                        // We usually want to have third party apps initialize
                        // in the stopped state, but not at first boot.  Also
                        // consider all applications to be installed.
                        for (PackageSetting pkg : mPackages.values()) {
                            pkg.setUserState(userId, pkg.getCeDataInode(userId),
                                    pkg.getDeDataInode(userId), COMPONENT_ENABLED_STATE_DEFAULT,
                                    true  /*installed*/,
                                    false /*stopped*/,
                                    false /*notLaunched*/,
                                    false /*hidden*/,
                                    0 /*distractionFlags*/,
                                    null /*suspendParams*/,
                                    false /*instantApp*/,
                                    false /*virtualPreload*/,
                                    null /*lastDisableAppCaller*/,
                                    null /*enabledComponents*/,
                                    null /*disabledComponents*/,
                                    PackageManager.INSTALL_REASON_UNKNOWN,
                                    PackageManager.UNINSTALL_REASON_UNKNOWN,
                                    null /*harmfulAppWarning*/,
                                    null /* splashScreenTheme*/,
                                    0 /*firstInstallTime*/,
                                    PackageManager.USER_MIN_ASPECT_RATIO_UNSET,
                                    null /*archiveState*/
                            );
                        }
                        return;
                    }
                }

                final TypedXmlPullParser parser = Xml.resolvePullParser(str);

                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    // nothing
                }

                if (type != XmlPullParser.START_TAG) {
                    mReadMessages.append("No start tag found in package restrictions file\n");
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "No start tag found in package manager package restrictions file");
                    return;
                }

                int outerDepth = parser.getDepth();
                PackageSetting ps = null;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG
                        || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG
                            || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    String tagName = parser.getName();
                    if (tagName.equals(TAG_PACKAGE)) {
                        String name = parser.getAttributeValue(null, ATTR_NAME);
                        ps = mPackages.get(name);
                        if (ps == null) {
                            Slog.w(PackageManagerService.TAG,
                                    "No package known for package restrictions " + name);
                            XmlUtils.skipCurrentTag(parser);
                            continue;
                        }

                        final long ceDataInode =
                                parser.getAttributeLong(null, ATTR_CE_DATA_INODE, 0);
                        final long deDataInode =
                                parser.getAttributeLong(null, ATTR_DE_DATA_INODE, 0);
                        final boolean installed =
                                parser.getAttributeBoolean(null, ATTR_INSTALLED, true);
                        final boolean stopped =
                                parser.getAttributeBoolean(null, ATTR_STOPPED, false);
                        final boolean notLaunched =
                                parser.getAttributeBoolean(null, ATTR_NOT_LAUNCHED, false);

                        // For backwards compatibility with the previous name of "blocked", which
                        // now means hidden, read the old attribute as well.
                        boolean hidden = parser.getAttributeBoolean(null, ATTR_HIDDEN, false);
                        if (!hidden) {
                            hidden = parser.getAttributeBoolean(null, ATTR_BLOCKED, false);
                        }

                        final int distractionFlags = parser.getAttributeInt(null,
                                ATTR_DISTRACTION_FLAGS, 0);
                        final boolean suspended = parser.getAttributeBoolean(null, ATTR_SUSPENDED,
                                false);
                        String oldSuspendingPackage = parser.getAttributeValue(null,
                                ATTR_SUSPENDING_PACKAGE);
                        final String dialogMessage = parser.getAttributeValue(null,
                                ATTR_SUSPEND_DIALOG_MESSAGE);
                        if (suspended && oldSuspendingPackage == null) {
                            oldSuspendingPackage = PLATFORM_PACKAGE_NAME;
                        }

                        final boolean blockUninstall =
                                parser.getAttributeBoolean(null, ATTR_BLOCK_UNINSTALL, false);
                        final boolean instantApp =
                                parser.getAttributeBoolean(null, ATTR_INSTANT_APP, false);
                        final boolean virtualPreload =
                                parser.getAttributeBoolean(null, ATTR_VIRTUAL_PRELOAD, false);
                        final int enabled = parser.getAttributeInt(null, ATTR_ENABLED,
                                COMPONENT_ENABLED_STATE_DEFAULT);
                        final String enabledCaller = parser.getAttributeValue(null,
                                ATTR_ENABLED_CALLER);
                        final String harmfulAppWarning =
                                parser.getAttributeValue(null, ATTR_HARMFUL_APP_WARNING);
                        final int verifState = parser.getAttributeInt(null,
                                ATTR_DOMAIN_VERIFICATION_STATE,
                                PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED);
                        final int installReason = parser.getAttributeInt(null, ATTR_INSTALL_REASON,
                                PackageManager.INSTALL_REASON_UNKNOWN);
                        final int uninstallReason = parser.getAttributeInt(null,
                                ATTR_UNINSTALL_REASON,
                                PackageManager.UNINSTALL_REASON_UNKNOWN);
                        final String splashScreenTheme = parser.getAttributeValue(null,
                                ATTR_SPLASH_SCREEN_THEME);
                        final long firstInstallTime = parser.getAttributeLongHex(null,
                                ATTR_FIRST_INSTALL_TIME, 0);
                        final int minAspectRatio = parser.getAttributeInt(null,
                                ATTR_MIN_ASPECT_RATIO,
                                PackageManager.USER_MIN_ASPECT_RATIO_UNSET);

                        ArraySet<String> enabledComponents = null;
                        ArraySet<String> disabledComponents = null;
                        SuspendDialogInfo oldSuspendDialogInfo = null;
                        PersistableBundle oldSuspendedAppExtras = null;
                        PersistableBundle oldSuspendedLauncherExtras = null;
                        ArchiveState archiveState = null;

                        int packageDepth = parser.getDepth();
                        ArrayMap<String, SuspendParams> suspendParamsMap = null;
                        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                                && (type != XmlPullParser.END_TAG
                                || parser.getDepth() > packageDepth)) {
                            if (type == XmlPullParser.END_TAG
                                    || type == XmlPullParser.TEXT) {
                                continue;
                            }
                            switch (parser.getName()) {
                                case TAG_ENABLED_COMPONENTS:
                                    enabledComponents = readComponentsLPr(parser);
                                    break;
                                case TAG_DISABLED_COMPONENTS:
                                    disabledComponents = readComponentsLPr(parser);
                                    break;
                                case TAG_SUSPENDED_DIALOG_INFO:
                                    oldSuspendDialogInfo = SuspendDialogInfo.restoreFromXml(parser);
                                    break;
                                case TAG_SUSPENDED_APP_EXTRAS:
                                    oldSuspendedAppExtras = PersistableBundle.restoreFromXml(
                                            parser);
                                    break;
                                case TAG_SUSPENDED_LAUNCHER_EXTRAS:
                                    oldSuspendedLauncherExtras = PersistableBundle.restoreFromXml(
                                            parser);
                                    break;
                                case TAG_SUSPEND_PARAMS:
                                    final String suspendingPackage = parser.getAttributeValue(null,
                                            ATTR_SUSPENDING_PACKAGE);
                                    if (suspendingPackage == null) {
                                        Slog.wtf(TAG, "No suspendingPackage found inside tag "
                                                + TAG_SUSPEND_PARAMS);
                                        continue;
                                    }
                                    if (suspendParamsMap == null) {
                                        suspendParamsMap = new ArrayMap<>();
                                    }
                                    suspendParamsMap.put(suspendingPackage,
                                            SuspendParams.restoreFromXml(parser));
                                    break;
                                case TAG_ARCHIVE_STATE:
                                    archiveState = parseArchiveState(parser);
                                    break;
                                default:
                                    Slog.wtf(TAG, "Unknown tag " + parser.getName() + " under tag "
                                            + TAG_PACKAGE);
                            }
                        }
                        if (oldSuspendDialogInfo == null && !TextUtils.isEmpty(dialogMessage)) {
                            oldSuspendDialogInfo = new SuspendDialogInfo.Builder()
                                    .setMessage(dialogMessage)
                                    .build();
                        }
                        if (suspended && suspendParamsMap == null) {
                            final SuspendParams suspendParams = new SuspendParams(
                                    oldSuspendDialogInfo,
                                    oldSuspendedAppExtras,
                                    oldSuspendedLauncherExtras,
                                    false /* quarantined */);
                            suspendParamsMap = new ArrayMap<>();
                            suspendParamsMap.put(oldSuspendingPackage, suspendParams);
                        }

                        if (blockUninstall) {
                            setBlockUninstallLPw(userId, name, true);
                        }
                        ps.setUserState(
                                userId, ceDataInode, deDataInode, enabled, installed, stopped,
                                notLaunched, hidden, distractionFlags, suspendParamsMap, instantApp,
                                virtualPreload, enabledCaller, enabledComponents,
                                disabledComponents, installReason, uninstallReason,
                                harmfulAppWarning, splashScreenTheme,
                                firstInstallTime != 0 ? firstInstallTime
                                        : origFirstInstallTimes.getOrDefault(name, 0L),
                                minAspectRatio, archiveState);

                        mDomainVerificationManager.setLegacyUserState(name, userId, verifState);
                    } else if (tagName.equals("preferred-activities")) {
                        readPreferredActivitiesLPw(parser, userId);
                    } else if (tagName.equals(TAG_PERSISTENT_PREFERRED_ACTIVITIES)) {
                        readPersistentPreferredActivitiesLPw(parser, userId);
                    } else if (tagName.equals(TAG_CROSS_PROFILE_INTENT_FILTERS)) {
                        readCrossProfileIntentFiltersLPw(parser, userId);
                    } else if (tagName.equals(TAG_DEFAULT_APPS)) {
                        readDefaultAppsLPw(parser, userId);
                    } else if (tagName.equals(TAG_BLOCK_UNINSTALL_PACKAGES)) {
                        readBlockUninstallPackagesLPw(parser, userId);
                    } else {
                        Slog.w(PackageManagerService.TAG,
                                "Unknown element under <stopped-packages>: "
                                        + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }
            } catch (IOException | XmlPullParserException e) {
                // Remove corrupted file and retry.
                atomicFile.failRead(str, e);

                readPackageRestrictionsLPr(userId, origFirstInstallTimes);
            }
        }
    }

    private static ArchiveState parseArchiveState(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String installerTitle = parser.getAttributeValue(null,
                ATTR_ARCHIVE_INSTALLER_TITLE);
        List<ArchiveState.ArchiveActivityInfo> activityInfos =
                parseArchiveActivityInfos(parser);

        if (installerTitle == null) {
            Slog.wtf(TAG, "parseArchiveState: installerTitle is null");
            return null;
        }

        if (activityInfos.size() < 1) {
            Slog.wtf(TAG, "parseArchiveState: activityInfos is empty");
            return null;
        }

        return new ArchiveState(activityInfos, installerTitle);
    }

    private static List<ArchiveState.ArchiveActivityInfo> parseArchiveActivityInfos(
            TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        List<ArchiveState.ArchiveActivityInfo> activityInfos = new ArrayList<>();
        int type;
        int outerDepth = parser.getDepth();
        String tagName;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }
            tagName = parser.getName();
            if (tagName.equals(TAG_ARCHIVE_ACTIVITY_INFO)) {
                String title = parser.getAttributeValue(null,
                        ATTR_ARCHIVE_ACTIVITY_TITLE);
                String originalComponentName =
                        parser.getAttributeValue(null, ATTR_ARCHIVE_ORIGINAL_COMPONENT_NAME);
                String iconAttribute = parser.getAttributeValue(null,
                        ATTR_ARCHIVE_ICON_PATH);
                Path iconPath = iconAttribute == null ? null : Path.of(iconAttribute);
                String monochromeAttribute = parser.getAttributeValue(null,
                        ATTR_ARCHIVE_MONOCHROME_ICON_PATH);
                Path monochromeIconPath = monochromeAttribute == null ? null : Path.of(
                        monochromeAttribute);

                if (title == null || originalComponentName == null || iconPath == null) {
                    Slog.wtf(
                            TAG,
                            TextUtils.formatSimple(
                                    "Missing attributes in tag %s. %s: %s, %s: %s, %s: %s",
                                    TAG_ARCHIVE_ACTIVITY_INFO,
                                    ATTR_ARCHIVE_ACTIVITY_TITLE,
                                    title,
                                    ATTR_ARCHIVE_ORIGINAL_COMPONENT_NAME,
                                    originalComponentName,
                                    ATTR_ARCHIVE_ICON_PATH,
                                    iconPath));
                    continue;
                }

                activityInfos.add(
                        new ArchiveState.ArchiveActivityInfo(
                                title,
                                ComponentName.unflattenFromString(originalComponentName),
                                iconPath,
                                monochromeIconPath));
            }
        }
        return activityInfos;
    }

    void setBlockUninstallLPw(int userId, String packageName, boolean blockUninstall) {
        ArraySet<String> packages = mBlockUninstallPackages.get(userId);
        if (blockUninstall) {
            if (packages == null) {
                packages = new ArraySet<String>();
                mBlockUninstallPackages.put(userId, packages);
            }
            packages.add(packageName);
        } else if (packages != null) {
            packages.remove(packageName);
            if (packages.isEmpty()) {
                mBlockUninstallPackages.remove(userId);
            }
        }
    }

    void clearBlockUninstallLPw(int userId) {
        mBlockUninstallPackages.remove(userId);
    }

    boolean getBlockUninstallLPr(int userId, String packageName) {
        ArraySet<String> packages = mBlockUninstallPackages.get(userId);
        if (packages == null) {
            return false;
        }
        return packages.contains(packageName);
    }

    private ArraySet<String> readComponentsLPr(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        ArraySet<String> components = null;
        int type;
        int outerDepth = parser.getDepth();
        String tagName;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }
            tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String componentName = parser.getAttributeValue(null, ATTR_NAME);
                if (componentName != null) {
                    if (components == null) {
                        components = new ArraySet<String>();
                    }
                    components.add(componentName);
                }
            }
        }
        return components;
    }

    /**
     * Record the state of preferred activity configuration into XML.  This is used both
     * for recording packages.xml internally and for supporting backup/restore of the
     * preferred activity configuration.
     */
    void writePreferredActivitiesLPr(TypedXmlSerializer serializer, int userId, boolean full)
            throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, "preferred-activities");
        PreferredIntentResolver pir = mPreferredActivities.get(userId);
        if (pir != null) {
            for (final PreferredActivity pa : pir.filterSet()) {
                serializer.startTag(null, TAG_ITEM);
                pa.writeToXml(serializer, full);
                serializer.endTag(null, TAG_ITEM);
            }
        }
        serializer.endTag(null, "preferred-activities");
    }

    void writePersistentPreferredActivitiesLPr(TypedXmlSerializer serializer, int userId)
            throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_PERSISTENT_PREFERRED_ACTIVITIES);
        PersistentPreferredIntentResolver ppir = mPersistentPreferredActivities.get(userId);
        if (ppir != null) {
            for (final PersistentPreferredActivity ppa : ppir.filterSet()) {
                serializer.startTag(null, TAG_ITEM);
                ppa.writeToXml(serializer);
                serializer.endTag(null, TAG_ITEM);
            }
        }
        serializer.endTag(null, TAG_PERSISTENT_PREFERRED_ACTIVITIES);
    }

    void writeCrossProfileIntentFiltersLPr(TypedXmlSerializer serializer, int userId)
            throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_CROSS_PROFILE_INTENT_FILTERS);
        CrossProfileIntentResolver cpir = mCrossProfileIntentResolvers.get(userId);
        if (cpir != null) {
            for (final CrossProfileIntentFilter cpif : cpir.filterSet()) {
                serializer.startTag(null, TAG_ITEM);
                cpif.writeToXml(serializer);
                serializer.endTag(null, TAG_ITEM);
            }
        }
        serializer.endTag(null, TAG_CROSS_PROFILE_INTENT_FILTERS);
    }

    void writeDefaultAppsLPr(XmlSerializer serializer, int userId)
            throws IllegalArgumentException, IllegalStateException, IOException {
        String defaultBrowser = mPendingDefaultBrowser.get(userId);
        writeDefaultApps(serializer, defaultBrowser);
    }

    static void writeDefaultApps(@NonNull XmlSerializer serializer, @Nullable String defaultBrowser)
            throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_DEFAULT_APPS);
        if (!TextUtils.isEmpty(defaultBrowser)) {
            serializer.startTag(null, TAG_DEFAULT_BROWSER);
            serializer.attribute(null, ATTR_PACKAGE_NAME, defaultBrowser);
            serializer.endTag(null, TAG_DEFAULT_BROWSER);
        }
        serializer.endTag(null, TAG_DEFAULT_APPS);
    }

    void writeBlockUninstallPackagesLPr(TypedXmlSerializer serializer, int userId)
            throws IOException  {
        ArraySet<String> packages = mBlockUninstallPackages.get(userId);
        if (packages != null) {
            serializer.startTag(null, TAG_BLOCK_UNINSTALL_PACKAGES);
            for (int i = 0; i < packages.size(); i++) {
                 serializer.startTag(null, TAG_BLOCK_UNINSTALL);
                 serializer.attribute(null, ATTR_PACKAGE_NAME, packages.valueAt(i));
                 serializer.endTag(null, TAG_BLOCK_UNINSTALL);
            }
            serializer.endTag(null, TAG_BLOCK_UNINSTALL_PACKAGES);
        }
    }

    // Default version is writing restrictions asynchronously.
    void writePackageRestrictionsLPr(int userId) {
        writePackageRestrictionsLPr(userId, /*sync=*/false);
    }

    void writePackageRestrictionsLPr(int userId, boolean sync) {
        invalidatePackageCache();

        final long startTime = SystemClock.uptimeMillis();

        if (sync) {
            writePackageRestrictions(userId, startTime, sync);
        } else {
            if (DEBUG_MU) {
                Log.i(TAG, "Scheduling deferred IO sync for user=" + userId);
            }
            synchronized (mPackageRestrictionsLock) {
                int pending = mPendingAsyncPackageRestrictionsWrites.get(userId, 0) + 1;
                mPendingAsyncPackageRestrictionsWrites.put(userId, pending);
            }
            Runnable r = () -> writePackageRestrictions(userId, startTime, sync);
            mHandler.obtainMessage(WRITE_USER_PACKAGE_RESTRICTIONS, r).sendToTarget();
        }
    }

    void writePackageRestrictions(Integer[] userIds) {
        invalidatePackageCache();
        final long startTime = SystemClock.uptimeMillis();
        for (int userId : userIds) {
            writePackageRestrictions(userId, startTime, /*sync=*/true);
        }
    }

    void writePackageRestrictions(int userId, long startTime, boolean sync) {
        if (DEBUG_MU) {
            Log.i(TAG, "Writing package restrictions for user=" + userId);
        }

        FileOutputStream str = null;
        try (ResilientAtomicFile atomicFile = getUserPackagesStateFile(userId)) {
            try {
                synchronized (mPackageRestrictionsLock) {
                    if (!sync) {
                        int pending = mPendingAsyncPackageRestrictionsWrites.get(userId, 0) - 1;
                        if (pending < 0) {
                            Log.i(TAG, "Cancel writing package restrictions for user=" + userId);
                            return;
                        }
                        mPendingAsyncPackageRestrictionsWrites.put(userId, pending);
                    }

                    try {
                        str = atomicFile.startWrite();
                    } catch (java.io.IOException e) {
                        Slog.wtf(PackageManagerService.TAG,
                                "Unable to write package manager package restrictions, "
                                        + " current changes will be lost at reboot", e);
                        return;
                    }
                }

                synchronized (mLock) {
                    final TypedXmlSerializer serializer = Xml.resolveSerializer(str);
                    serializer.startDocument(null, true);
                    serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output",
                            true);

                    serializer.startTag(null, TAG_PACKAGE_RESTRICTIONS);

                    if (DEBUG_MU) {
                        Slogf.i(TAG, "Writing %s (%d packages)", atomicFile,
                                mPackages.values().size());
                    }
                    for (final PackageSetting pkg : mPackages.values()) {
                        final PackageUserStateInternal ustate = pkg.readUserState(userId);
                        if (DEBUG_MU) {
                            Log.v(TAG, "  pkg=" + pkg.getPackageName()
                                    + ", installed=" + ustate.isInstalled()
                                    + ", state=" + ustate.getEnabledState());
                        }

                        serializer.startTag(null, TAG_PACKAGE);
                        serializer.attribute(null, ATTR_NAME, pkg.getPackageName());
                        if (ustate.getCeDataInode() != 0) {
                            serializer.attributeLong(null, ATTR_CE_DATA_INODE,
                                    ustate.getCeDataInode());
                        }
                        if (ustate.getDeDataInode() != 0) {
                            serializer.attributeLong(null, ATTR_DE_DATA_INODE,
                                    ustate.getDeDataInode());
                        }
                        if (!ustate.isInstalled()) {
                            serializer.attributeBoolean(null, ATTR_INSTALLED, false);
                        }
                        if (ustate.isStopped()) {
                            serializer.attributeBoolean(null, ATTR_STOPPED, true);
                        }
                        if (ustate.isNotLaunched()) {
                            serializer.attributeBoolean(null, ATTR_NOT_LAUNCHED, true);
                        }
                        if (ustate.isHidden()) {
                            serializer.attributeBoolean(null, ATTR_HIDDEN, true);
                        }
                        if (ustate.getDistractionFlags() != 0) {
                            serializer.attributeInt(null, ATTR_DISTRACTION_FLAGS,
                                    ustate.getDistractionFlags());
                        }
                        if (ustate.isSuspended()) {
                            serializer.attributeBoolean(null, ATTR_SUSPENDED, true);
                        }
                        if (ustate.isInstantApp()) {
                            serializer.attributeBoolean(null, ATTR_INSTANT_APP, true);
                        }
                        if (ustate.isVirtualPreload()) {
                            serializer.attributeBoolean(null, ATTR_VIRTUAL_PRELOAD, true);
                        }
                        if (ustate.getEnabledState() != COMPONENT_ENABLED_STATE_DEFAULT) {
                            serializer.attributeInt(null, ATTR_ENABLED, ustate.getEnabledState());
                        }
                        if (ustate.getLastDisableAppCaller() != null) {
                            serializer.attribute(null, ATTR_ENABLED_CALLER,
                                    ustate.getLastDisableAppCaller());
                        }
                        if (ustate.getInstallReason() != PackageManager.INSTALL_REASON_UNKNOWN) {
                            serializer.attributeInt(null, ATTR_INSTALL_REASON,
                                    ustate.getInstallReason());
                        }
                        serializer.attributeLongHex(null, ATTR_FIRST_INSTALL_TIME,
                                ustate.getFirstInstallTimeMillis());
                        if (ustate.getUninstallReason()
                                != PackageManager.UNINSTALL_REASON_UNKNOWN) {
                            serializer.attributeInt(null, ATTR_UNINSTALL_REASON,
                                    ustate.getUninstallReason());
                        }
                        if (ustate.getHarmfulAppWarning() != null) {
                            serializer.attribute(null, ATTR_HARMFUL_APP_WARNING,
                                    ustate.getHarmfulAppWarning());
                        }
                        if (ustate.getSplashScreenTheme() != null) {
                            serializer.attribute(null, ATTR_SPLASH_SCREEN_THEME,
                                    ustate.getSplashScreenTheme());
                        }
                        if (ustate.getMinAspectRatio()
                                != PackageManager.USER_MIN_ASPECT_RATIO_UNSET) {
                            serializer.attributeInt(null, ATTR_MIN_ASPECT_RATIO,
                                    ustate.getMinAspectRatio());
                        }
                        if (ustate.isSuspended()) {
                            for (int i = 0; i < ustate.getSuspendParams().size(); i++) {
                                final String suspendingPackage = ustate.getSuspendParams().keyAt(i);
                                serializer.startTag(null, TAG_SUSPEND_PARAMS);
                                serializer.attribute(null, ATTR_SUSPENDING_PACKAGE,
                                        suspendingPackage);
                                final SuspendParams params =
                                        ustate.getSuspendParams().valueAt(i);
                                if (params != null) {
                                    params.saveToXml(serializer);
                                }
                                serializer.endTag(null, TAG_SUSPEND_PARAMS);
                            }
                        }
                        final ArraySet<String> enabledComponents = ustate.getEnabledComponents();
                        if (enabledComponents != null && enabledComponents.size() > 0) {
                            serializer.startTag(null, TAG_ENABLED_COMPONENTS);
                            for (int i = 0; i < enabledComponents.size(); i++) {
                                serializer.startTag(null, TAG_ITEM);
                                serializer.attribute(null, ATTR_NAME,
                                        enabledComponents.valueAt(i));
                                serializer.endTag(null, TAG_ITEM);
                            }
                            serializer.endTag(null, TAG_ENABLED_COMPONENTS);
                        }
                        final ArraySet<String> disabledComponents = ustate.getDisabledComponents();
                        if (disabledComponents != null && disabledComponents.size() > 0) {
                            serializer.startTag(null, TAG_DISABLED_COMPONENTS);
                            for (int i = 0; i < disabledComponents.size(); i++) {
                                serializer.startTag(null, TAG_ITEM);
                                serializer.attribute(null, ATTR_NAME,
                                        disabledComponents.valueAt(i));
                                serializer.endTag(null, TAG_ITEM);
                            }
                            serializer.endTag(null, TAG_DISABLED_COMPONENTS);
                        }
                        writeArchiveStateLPr(serializer, ustate.getArchiveState());

                        serializer.endTag(null, TAG_PACKAGE);
                    }

                    writePreferredActivitiesLPr(serializer, userId, true);
                    writePersistentPreferredActivitiesLPr(serializer, userId);
                    writeCrossProfileIntentFiltersLPr(serializer, userId);
                    writeDefaultAppsLPr(serializer, userId);
                    writeBlockUninstallPackagesLPr(serializer, userId);

                    serializer.endTag(null, TAG_PACKAGE_RESTRICTIONS);

                    serializer.endDocument();
                }

                atomicFile.finishWrite(str);

                if (DEBUG_MU) {
                    Log.i(TAG, "New package restrictions successfully written for user=" + userId
                            + ": " + atomicFile);
                }

                com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                        "package-user-" + userId, SystemClock.uptimeMillis() - startTime);

                // Done, all is good!
                return;
            } catch (java.io.IOException e) {
                Slog.wtf(PackageManagerService.TAG,
                        "Unable to write package manager package restrictions, "
                                + " current changes will be lost at reboot", e);
                if (str != null) {
                    atomicFile.failWrite(str);
                }
            }
        }
    }

    private void writeArchiveStateLPr(TypedXmlSerializer serializer, ArchiveState archiveState)
            throws IOException {
        if (archiveState == null) {
            return;
        }

        serializer.startTag(null, TAG_ARCHIVE_STATE);
        serializer.attribute(null, ATTR_ARCHIVE_INSTALLER_TITLE, archiveState.getInstallerTitle());
        for (ArchiveState.ArchiveActivityInfo activityInfo : archiveState.getActivityInfos()) {
            serializer.startTag(null, TAG_ARCHIVE_ACTIVITY_INFO);
            serializer.attribute(null, ATTR_ARCHIVE_ACTIVITY_TITLE, activityInfo.getTitle());
            serializer.attribute(
                    null,
                    ATTR_ARCHIVE_ORIGINAL_COMPONENT_NAME,
                    activityInfo.getOriginalComponentName().flattenToString());
            if (activityInfo.getIconBitmap() != null) {
                serializer.attribute(null, ATTR_ARCHIVE_ICON_PATH,
                        activityInfo.getIconBitmap().toAbsolutePath().toString());
            }
            if (activityInfo.getMonochromeIconBitmap() != null) {
                serializer.attribute(null, ATTR_ARCHIVE_MONOCHROME_ICON_PATH,
                        activityInfo.getMonochromeIconBitmap().toAbsolutePath().toString());
            }
            serializer.endTag(null, TAG_ARCHIVE_ACTIVITY_INFO);
        }
        serializer.endTag(null, TAG_ARCHIVE_STATE);
    }

    void readInstallPermissionsLPr(TypedXmlPullParser parser,
            LegacyPermissionState permissionsState, List<UserInfo> users)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                final boolean granted = parser.getAttributeBoolean(null, ATTR_GRANTED, true);
                final int flags = parser.getAttributeIntHex(null, ATTR_FLAGS, 0);
                for (final UserInfo user : users) {
                    permissionsState.putPermissionState(new PermissionState(name, false, granted,
                            flags), user.id);
                }
            } else {
                Slog.w(PackageManagerService.TAG, "Unknown element under <permissions>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void readUsesSdkLibLPw(TypedXmlPullParser parser, PackageSetting outPs)
            throws IOException, XmlPullParserException {
        String libName = parser.getAttributeValue(null, ATTR_NAME);
        long libVersion = parser.getAttributeLong(null, ATTR_VERSION, -1);

        if (libName != null && libVersion >= 0) {
            outPs.setUsesSdkLibraries(ArrayUtils.appendElement(String.class,
                    outPs.getUsesSdkLibraries(), libName));
            outPs.setUsesSdkLibrariesVersionsMajor(ArrayUtils.appendLong(
                    outPs.getUsesSdkLibrariesVersionsMajor(), libVersion));
        }

        XmlUtils.skipCurrentTag(parser);
    }

    void readUsesStaticLibLPw(TypedXmlPullParser parser, PackageSetting outPs)
            throws IOException, XmlPullParserException {
        String libName = parser.getAttributeValue(null, ATTR_NAME);
        long libVersion = parser.getAttributeLong(null, ATTR_VERSION, -1);

        if (libName != null && libVersion >= 0) {
            outPs.setUsesStaticLibraries(ArrayUtils.appendElement(String.class,
                    outPs.getUsesStaticLibraries(), libName));
            outPs.setUsesStaticLibrariesVersions(ArrayUtils.appendLong(
                    outPs.getUsesStaticLibrariesVersions(), libVersion));
        }

        XmlUtils.skipCurrentTag(parser);
    }

    void writeUsesSdkLibLPw(TypedXmlSerializer serializer, String[] usesSdkLibraries,
            long[] usesSdkLibraryVersions) throws IOException {
        if (ArrayUtils.isEmpty(usesSdkLibraries) || ArrayUtils.isEmpty(usesSdkLibraryVersions)
                || usesSdkLibraries.length != usesSdkLibraryVersions.length) {
            return;
        }
        final int libCount = usesSdkLibraries.length;
        for (int i = 0; i < libCount; i++) {
            final String libName = usesSdkLibraries[i];
            final long libVersion = usesSdkLibraryVersions[i];
            serializer.startTag(null, TAG_USES_SDK_LIB);
            serializer.attribute(null, ATTR_NAME, libName);
            serializer.attributeLong(null, ATTR_VERSION, libVersion);
            serializer.endTag(null, TAG_USES_SDK_LIB);
        }
    }

    void writeUsesStaticLibLPw(TypedXmlSerializer serializer, String[] usesStaticLibraries,
            long[] usesStaticLibraryVersions) throws IOException {
        if (ArrayUtils.isEmpty(usesStaticLibraries) || ArrayUtils.isEmpty(usesStaticLibraryVersions)
                || usesStaticLibraries.length != usesStaticLibraryVersions.length) {
            return;
        }
        final int libCount = usesStaticLibraries.length;
        for (int i = 0; i < libCount; i++) {
            final String libName = usesStaticLibraries[i];
            final long libVersion = usesStaticLibraryVersions[i];
            serializer.startTag(null, TAG_USES_STATIC_LIB);
            serializer.attribute(null, ATTR_NAME, libName);
            serializer.attributeLong(null, ATTR_VERSION, libVersion);
            serializer.endTag(null, TAG_USES_STATIC_LIB);
        }
    }

    // Note: assumed "stopped" field is already cleared in all packages.
    // Legacy reader, used to read in the old file format after an upgrade. Not used after that.
    void readStoppedLPw() {
        FileInputStream str = null;
        if (mBackupStoppedPackagesFilename.exists()) {
            try {
                str = new FileInputStream(mBackupStoppedPackagesFilename);
                mReadMessages.append("Reading from backup stopped packages file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO,
                        "Need to read from backup stopped packages file");
                if (mStoppedPackagesFilename.exists()) {
                    // If both the backup and normal file exist, we
                    // ignore the normal one since it might have been
                    // corrupted.
                    Slog.w(PackageManagerService.TAG, "Cleaning up stopped packages file "
                            + mStoppedPackagesFilename);
                    mStoppedPackagesFilename.delete();
                }
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        try {
            if (str == null) {
                if (!mStoppedPackagesFilename.exists()) {
                    mReadMessages.append("No stopped packages file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No stopped packages file file; assuming all started");
                    // At first boot, make sure no packages are stopped.
                    // We usually want to have third party apps initialize
                    // in the stopped state, but not at first boot.
                    for (PackageSetting pkg : mPackages.values()) {
                        pkg.setStopped(false, 0);
                        pkg.setNotLaunched(false, 0);
                    }
                    return;
                }
                str = new FileInputStream(mStoppedPackagesFilename);
            }
            final TypedXmlPullParser parser = Xml.resolvePullParser(str);

            int type;
            while ((type=parser.next()) != XmlPullParser.START_TAG
                       && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                mReadMessages.append("No start tag found in stopped packages file\n");
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "No start tag found in package manager stopped packages");
                return;
            }

            int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG
                           || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG
                        || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals(TAG_PACKAGE)) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    PackageSetting ps = mPackages.get(name);
                    if (ps != null) {
                        ps.setStopped(true, 0);
                        if ("1".equals(parser.getAttributeValue(null, ATTR_NOT_LAUNCHED))) {
                            ps.setNotLaunched(true, 0);
                        }
                    } else {
                        Slog.w(PackageManagerService.TAG,
                                "No package known for stopped package " + name);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else {
                    Slog.w(PackageManagerService.TAG, "Unknown element under <stopped-packages>: "
                          + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

            str.close();

        } catch (XmlPullParserException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Error reading stopped packages: " + e);
            Slog.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages",
                    e);

        } catch (java.io.IOException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading settings: " + e);
            Slog.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages",
                    e);

        }
    }

    void writeLPr(@NonNull Computer computer, boolean sync) {
        //Debug.startMethodTracing("/data/system/packageprof", 8 * 1024 * 1024);

        final long startTime = SystemClock.uptimeMillis();

        // Whenever package manager changes something on the system, it writes out whatever it
        // changed in the form of a settings object change, and it does so under its internal
        // lock --- so if we invalidate the package cache here, we end up invalidating at the
        // right time.
        invalidatePackageCache();

        mPastSignatures.clear();

        try (ResilientAtomicFile atomicFile = getSettingsFile()) {
            FileOutputStream str = null;
            try {
                str = atomicFile.startWrite();

                final TypedXmlSerializer serializer = Xml.resolveSerializer(str);
                serializer.startDocument(null, true);
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output",
                        true);

                serializer.startTag(null, "packages");

                for (int i = 0; i < mVersion.size(); i++) {
                    final String volumeUuid = mVersion.keyAt(i);
                    final VersionInfo ver = mVersion.valueAt(i);

                    serializer.startTag(null, TAG_VERSION);
                    XmlUtils.writeStringAttribute(serializer, ATTR_VOLUME_UUID, volumeUuid);
                    serializer.attributeInt(null, ATTR_SDK_VERSION, ver.sdkVersion);
                    serializer.attributeInt(null, ATTR_DATABASE_VERSION, ver.databaseVersion);
                    XmlUtils.writeStringAttribute(serializer, ATTR_BUILD_FINGERPRINT,
                            ver.buildFingerprint);
                    XmlUtils.writeStringAttribute(serializer, ATTR_FINGERPRINT, ver.fingerprint);
                    serializer.endTag(null, TAG_VERSION);
                }

                if (mVerifierDeviceIdentity != null) {
                    serializer.startTag(null, "verifier");
                    serializer.attribute(null, "device", mVerifierDeviceIdentity.toString());
                    serializer.endTag(null, "verifier");
                }

                serializer.startTag(null, "permission-trees");
                mPermissions.writePermissionTrees(serializer);
                serializer.endTag(null, "permission-trees");

                serializer.startTag(null, "permissions");
                mPermissions.writePermissions(serializer);
                serializer.endTag(null, "permissions");

                for (final PackageSetting pkg : mPackages.values()) {
                    if (pkg.getPkg() != null && pkg.getPkg().isApex()) {
                        // Don't persist APEX which doesn't have a valid app id and will fail to
                        // load
                        continue;
                    }
                    writePackageLPr(serializer, pkg);
                }

                for (final PackageSetting pkg : mDisabledSysPackages.values()) {
                    if (pkg.getPkg() != null && pkg.getPkg().isApex()) {
                        // Don't persist APEX which doesn't have a valid app id and will fail to
                        // load
                        continue;
                    }
                    writeDisabledSysPackageLPr(serializer, pkg);
                }

                for (final SharedUserSetting usr : mSharedUsers.values()) {
                    serializer.startTag(null, "shared-user");
                    serializer.attribute(null, ATTR_NAME, usr.name);
                    serializer.attributeInt(null, "userId", usr.mAppId);
                    usr.signatures.writeXml(serializer, "sigs", mPastSignatures.untrackedStorage());
                    serializer.endTag(null, "shared-user");
                }

                if (mRenamedPackages.size() > 0) {
                    for (Map.Entry<String, String> e : mRenamedPackages.entrySet()) {
                        serializer.startTag(null, "renamed-package");
                        serializer.attribute(null, "new", e.getKey());
                        serializer.attribute(null, "old", e.getValue());
                        serializer.endTag(null, "renamed-package");
                    }
                }

                mDomainVerificationManager.writeSettings(computer, serializer,
                        false /* includeSignatures */, UserHandle.USER_ALL);

                mKeySetManagerService.writeKeySetManagerServiceLPr(serializer);

                serializer.endTag(null, "packages");

                serializer.endDocument();

                atomicFile.finishWrite(str);

                writeKernelMappingLPr();
                writePackageListLPr();
                writeAllUsersPackageRestrictionsLPr(sync);
                writeAllRuntimePermissionsLPr();
                com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                        "package", SystemClock.uptimeMillis() - startTime);
                return;

            } catch (java.io.IOException e) {
                Slog.wtf(PackageManagerService.TAG, "Unable to write package manager settings, "
                        + "current changes will be lost at reboot", e);
                if (str != null) {
                    atomicFile.failWrite(str);
                }
            }
        }
        //Debug.stopMethodTracing();
    }

    private void writeKernelRemoveUserLPr(int userId) {
        if (mKernelMappingFilename == null) return;

        File removeUserIdFile = new File(mKernelMappingFilename, "remove_userid");
        if (DEBUG_KERNEL) Slog.d(TAG, "Writing " + userId + " to " + removeUserIdFile
                .getAbsolutePath());
        writeIntToFile(removeUserIdFile, userId);
    }

    void writeKernelMappingLPr() {
        if (mKernelMappingFilename == null) return;

        final String[] known = mKernelMappingFilename.list();
        final ArraySet<String> knownSet = new ArraySet<>(known.length);
        for (String name : known) {
            knownSet.add(name);
        }

        for (final PackageSetting ps : mPackages.values()) {
            // Package is actively claimed
            knownSet.remove(ps.getPackageName());
            writeKernelMappingLPr(ps);
        }

        // Remove any unclaimed mappings
        for (int i = 0; i < knownSet.size(); i++) {
            final String name = knownSet.valueAt(i);
            if (DEBUG_KERNEL) Slog.d(TAG, "Dropping mapping " + name);

            mKernelMapping.remove(name);
            new File(mKernelMappingFilename, name).delete();
        }
    }

    void writeKernelMappingLPr(PackageSetting ps) {
        if (mKernelMappingFilename == null || ps == null || ps.getPackageName() == null) return;

        writeKernelMappingLPr(ps.getPackageName(), ps.getAppId(), ps.getNotInstalledUserIds());
    }

    void writeKernelMappingLPr(String name, int appId, int[] excludedUserIds) {
        KernelPackageState cur = mKernelMapping.get(name);
        final boolean firstTime = cur == null;
        final boolean userIdsChanged = firstTime
                || !Arrays.equals(excludedUserIds, cur.excludedUserIds);

        // Package directory
        final File dir = new File(mKernelMappingFilename, name);

        if (firstTime) {
            dir.mkdir();
            // Create a new mapping state
            cur = new KernelPackageState();
            mKernelMapping.put(name, cur);
        }

        // If mapping is incorrect or non-existent, write the appid file
        if (cur.appId != appId) {
            final File appIdFile = new File(dir, "appid");
            writeIntToFile(appIdFile, appId);
            if (DEBUG_KERNEL) Slog.d(TAG, "Mapping " + name + " to " + appId);
        }

        if (userIdsChanged) {
            // Build the exclusion list -- the ids to add to the exclusion list
            for (int i = 0; i < excludedUserIds.length; i++) {
                if (cur.excludedUserIds == null || !ArrayUtils.contains(cur.excludedUserIds,
                        excludedUserIds[i])) {
                    writeIntToFile(new File(dir, "excluded_userids"), excludedUserIds[i]);
                    if (DEBUG_KERNEL) Slog.d(TAG, "Writing " + excludedUserIds[i] + " to "
                            + name + "/excluded_userids");
                }
            }
            // Build the inclusion list -- the ids to remove from the exclusion list
            if (cur.excludedUserIds != null) {
                for (int i = 0; i < cur.excludedUserIds.length; i++) {
                    if (!ArrayUtils.contains(excludedUserIds, cur.excludedUserIds[i])) {
                        writeIntToFile(new File(dir, "clear_userid"),
                                cur.excludedUserIds[i]);
                        if (DEBUG_KERNEL) Slog.d(TAG, "Writing " + cur.excludedUserIds[i] + " to "
                                + name + "/clear_userid");

                    }
                }
            }
            cur.excludedUserIds = excludedUserIds;
        }
    }

    private void writeIntToFile(File file, int value) {
        try {
            FileUtils.bytesToFile(file.getAbsolutePath(),
                    Integer.toString(value).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException ignored) {
            Slog.w(TAG, "Couldn't write " + value + " to " + file.getAbsolutePath());
        }
    }

    void writePackageListLPr() {
        writePackageListLPr(-1);
    }

    void writePackageListLPr(int creatingUserId) {
        String filename = mPackageListFilename.getAbsolutePath();
        String ctx = SELinux.fileSelabelLookup(filename);
        if (ctx == null) {
            Slog.wtf(TAG, "Failed to get SELinux context for " +
                mPackageListFilename.getAbsolutePath());
        }

        if (!SELinux.setFSCreateContext(ctx)) {
            Slog.wtf(TAG, "Failed to set packages.list SELinux context");
        }
        try {
            writePackageListLPrInternal(creatingUserId);
        } finally {
            SELinux.setFSCreateContext(null);
        }
    }

    private void writePackageListLPrInternal(int creatingUserId) {
        // Only derive GIDs for active users (not dying)
        final List<UserInfo> users = getActiveUsers(UserManagerService.getInstance(), true);
        int[] userIds = new int[users.size()];
        for (int i = 0; i < userIds.length; i++) {
            userIds[i] = users.get(i).id;
        }
        if (creatingUserId != -1) {
            userIds = ArrayUtils.appendInt(userIds, creatingUserId);
        }

        // Write package list file now, use a JournaledFile.
        File tempFile = new File(mPackageListFilename.getAbsolutePath() + ".tmp");
        JournaledFile journal = new JournaledFile(mPackageListFilename, tempFile);

        final File writeTarget = journal.chooseForWrite();
        FileOutputStream fstr;
        BufferedWriter writer = null;
        try {
            fstr = new FileOutputStream(writeTarget);
            writer = new BufferedWriter(new OutputStreamWriter(fstr, Charset.defaultCharset()));
            FileUtils.setPermissions(fstr.getFD(), 0640, SYSTEM_UID, PACKAGE_INFO_GID);

            StringBuilder sb = new StringBuilder();
            for (final PackageSetting ps : mPackages.values()) {
                if (ps.getPkg() == null) {
                    if (!"android".equals(ps.getPackageName())) {
                        Slog.w(TAG, "Skipping " + ps + " due to missing metadata");
                    }
                    continue;
                }
                if (ps.getPkg().isApex()) {
                    // Don't persist APEX which doesn't have a valid app id and will cause parsing
                    // error in libpackagelistparser
                    continue;
                }

                // TODO(b/135203078): This doesn't handle multiple users
                final File dataDir = PackageInfoUtils.getDataDir(ps, UserHandle.USER_SYSTEM);
                final String dataPath = dataDir == null ? "null" : dataDir.getAbsolutePath();

                final boolean isDebug = ps.getPkg().isDebuggable();
                final IntArray gids = new IntArray();
                for (final int userId : userIds) {
                    gids.addAll(mPermissionDataProvider.getGidsForUid(UserHandle.getUid(userId,
                            ps.getAppId())));
                }

                // Avoid any application that has a space in its path.
                if (dataPath.indexOf(' ') >= 0)
                    continue;

                // we store on each line the following information for now:
                //
                // pkgName    - package name
                // userId     - application-specific user id
                // debugFlag  - 0 or 1 if the package is debuggable.
                // dataPath   - path to package's data path
                // seinfo     - seinfo label for the app (assigned at install time)
                // gids       - supplementary gids this app launches with
                // profileableFromShellFlag  - 0 or 1 if the package is profileable from shell.
                // longVersionCode - integer version of the package.
                // profileable - 0 or 1 if the package is profileable by the platform.
                // packageInstaller - the package that installed this app, or @system, @product or
                //                    @null.
                //
                // NOTE: We prefer not to expose all ApplicationInfo flags for now.
                //
                // DO NOT MODIFY THIS FORMAT UNLESS YOU CAN ALSO MODIFY ITS USERS
                // FROM NATIVE CODE. AT THE MOMENT, LOOK AT THE FOLLOWING SOURCES:
                //   system/core/libpackagelistparser
                //
                sb.setLength(0);
                sb.append(ps.getPkg().getPackageName());
                sb.append(" ");
                sb.append(ps.getPkg().getUid());
                sb.append(isDebug ? " 1 " : " 0 ");
                sb.append(dataPath);
                sb.append(" ");
                sb.append(ps.getSeInfo());
                sb.append(" ");
                final int gidsSize = gids.size();
                if (gids.size() > 0) {
                    sb.append(gids.get(0));
                    for (int i = 1; i < gidsSize; i++) {
                        sb.append(",");
                        sb.append(gids.get(i));
                    }
                } else {
                    sb.append("none");
                }
                sb.append(" ");
                sb.append(ps.getPkg().isProfileableByShell() ? "1" : "0");
                sb.append(" ");
                sb.append(ps.getPkg().getLongVersionCode());
                sb.append(" ");
                sb.append(ps.getPkg().isProfileable() ? "1" : "0");
                sb.append(" ");
                if (ps.isSystem()) {
                    sb.append("@system");
                } else if (ps.isProduct()) {
                    sb.append("@product");
                } else if (ps.getInstallSource().mInstallerPackageName != null
                           && !ps.getInstallSource().mInstallerPackageName.isEmpty()) {
                    sb.append(ps.getInstallSource().mInstallerPackageName);
                } else {
                    sb.append("@null");
                }
                sb.append("\n");
                writer.append(sb);
            }
            writer.flush();
            FileUtils.sync(fstr);
            writer.close();
            journal.commit();
        } catch (Exception e) {
            Slog.wtf(TAG, "Failed to write packages.list", e);
            IoUtils.closeQuietly(writer);
            journal.rollback();
        }
    }

    void writeDisabledSysPackageLPr(TypedXmlSerializer serializer, final PackageSetting pkg)
            throws java.io.IOException {
        serializer.startTag(null, "updated-package");
        serializer.attribute(null, ATTR_NAME, pkg.getPackageName());
        if (pkg.getRealName() != null) {
            serializer.attribute(null, "realName", pkg.getRealName());
        }
        serializer.attribute(null, "codePath", pkg.getPathString());
        serializer.attributeLongHex(null, "ft", pkg.getLastModifiedTime());
        serializer.attributeLongHex(null, "ut", pkg.getLastUpdateTime());
        serializer.attributeLong(null, "version", pkg.getVersionCode());
        serializer.attributeInt(null, "targetSdkVersion", pkg.getTargetSdkVersion());
        if (pkg.getRestrictUpdateHash() != null) {
            serializer.attributeBytesBase64(null, "restrictUpdateHash",
                    pkg.getRestrictUpdateHash());
        }
        if (pkg.getLegacyNativeLibraryPath() != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.getLegacyNativeLibraryPath());
        }
        if (pkg.getPrimaryCpuAbiLegacy() != null) {
           serializer.attribute(null, "primaryCpuAbi", pkg.getPrimaryCpuAbiLegacy());
        }
        if (pkg.getSecondaryCpuAbiLegacy() != null) {
            serializer.attribute(null, "secondaryCpuAbi", pkg.getSecondaryCpuAbiLegacy());
        }
        if (pkg.getCpuAbiOverride() != null) {
            serializer.attribute(null, "cpuAbiOverride", pkg.getCpuAbiOverride());
        }

        if (!pkg.hasSharedUser()) {
            serializer.attributeInt(null, "userId", pkg.getAppId());
        } else {
            serializer.attributeInt(null, "sharedUserId", pkg.getAppId());
        }
        serializer.attributeFloat(null, "loadingProgress", pkg.getLoadingProgress());
        serializer.attributeLongHex(null, "loadingCompletedTime",
                pkg.getLoadingCompletedTime());

        if (pkg.getAppMetadataFilePath() != null) {
            serializer.attribute(null, "appMetadataFilePath",
                    pkg.getAppMetadataFilePath());
        }

        writeUsesSdkLibLPw(serializer, pkg.getUsesSdkLibraries(),
                pkg.getUsesSdkLibrariesVersionsMajor());

        writeUsesStaticLibLPw(serializer, pkg.getUsesStaticLibraries(),
                pkg.getUsesStaticLibrariesVersions());

        serializer.endTag(null, "updated-package");
    }

    void writePackageLPr(TypedXmlSerializer serializer, final PackageSetting pkg)
            throws java.io.IOException {
        serializer.startTag(null, "package");
        serializer.attribute(null, ATTR_NAME, pkg.getPackageName());
        if (pkg.getRealName() != null) {
            serializer.attribute(null, "realName", pkg.getRealName());
        }
        serializer.attribute(null, "codePath", pkg.getPathString());

        if (pkg.getLegacyNativeLibraryPath() != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.getLegacyNativeLibraryPath());
        }
        if (pkg.getPrimaryCpuAbiLegacy() != null) {
            serializer.attribute(null, "primaryCpuAbi", pkg.getPrimaryCpuAbiLegacy());
        }
        if (pkg.getSecondaryCpuAbiLegacy() != null) {
            serializer.attribute(null, "secondaryCpuAbi", pkg.getSecondaryCpuAbiLegacy());
        }
        if (pkg.getCpuAbiOverride() != null) {
            serializer.attribute(null, "cpuAbiOverride", pkg.getCpuAbiOverride());
        }

        serializer.attributeInt(null, "publicFlags", pkg.getFlags());
        serializer.attributeInt(null, "privateFlags", pkg.getPrivateFlags());
        serializer.attributeLongHex(null, "ft", pkg.getLastModifiedTime());
        serializer.attributeLongHex(null, "ut", pkg.getLastUpdateTime());
        serializer.attributeLong(null, "version", pkg.getVersionCode());
        serializer.attributeInt(null, "targetSdkVersion", pkg.getTargetSdkVersion());
        if (pkg.getRestrictUpdateHash() != null) {
            serializer.attributeBytesBase64(null, "restrictUpdateHash",
                    pkg.getRestrictUpdateHash());
        }
        if (!pkg.hasSharedUser()) {
            serializer.attributeInt(null, "userId", pkg.getAppId());
        } else {
            serializer.attributeInt(null, "sharedUserId", pkg.getAppId());
        }
        InstallSource installSource = pkg.getInstallSource();
        if (installSource.mInstallerPackageName != null) {
            serializer.attribute(null, "installer", installSource.mInstallerPackageName);
        }
        if (installSource.mInstallerPackageUid != INVALID_UID) {
            serializer.attributeInt(null, "installerUid", installSource.mInstallerPackageUid);
        }
        if (installSource.mUpdateOwnerPackageName != null) {
            serializer.attribute(null, "updateOwner", installSource.mUpdateOwnerPackageName);
        }
        if (installSource.mInstallerAttributionTag != null) {
            serializer.attribute(null, "installerAttributionTag",
                    installSource.mInstallerAttributionTag);
        }
        serializer.attributeInt(null, "packageSource",
                installSource.mPackageSource);
        if (installSource.mIsOrphaned) {
            serializer.attributeBoolean(null, "isOrphaned", true);
        }
        if (installSource.mInitiatingPackageName != null) {
            serializer.attribute(null, "installInitiator", installSource.mInitiatingPackageName);
        }
        if (installSource.mIsInitiatingPackageUninstalled) {
            serializer.attributeBoolean(null, "installInitiatorUninstalled", true);
        }
        if (installSource.mOriginatingPackageName != null) {
            serializer.attribute(null, "installOriginator", installSource.mOriginatingPackageName);
        }
        if (pkg.getVolumeUuid() != null) {
            serializer.attribute(null, "volumeUuid", pkg.getVolumeUuid());
        }
        if (pkg.getCategoryOverride() != ApplicationInfo.CATEGORY_UNDEFINED) {
            serializer.attributeInt(null, "categoryHint", pkg.getCategoryOverride());
        }
        if (pkg.isUpdateAvailable()) {
            serializer.attributeBoolean(null, "updateAvailable", true);
        }
        if (pkg.isForceQueryableOverride()) {
            serializer.attributeBoolean(null, "forceQueryable", true);
        }
        if (pkg.isLoading()) {
            serializer.attributeBoolean(null, "isLoading", true);
        }
        serializer.attributeFloat(null, "loadingProgress", pkg.getLoadingProgress());
        serializer.attributeLongHex(null, "loadingCompletedTime", pkg.getLoadingCompletedTime());

        serializer.attribute(null, "domainSetId", pkg.getDomainSetId().toString());

        if (pkg.getAppMetadataFilePath() != null) {
            serializer.attribute(null, "appMetadataFilePath", pkg.getAppMetadataFilePath());
        }

        writeUsesSdkLibLPw(serializer, pkg.getUsesSdkLibraries(),
                pkg.getUsesSdkLibrariesVersionsMajor());

        writeUsesStaticLibLPw(serializer, pkg.getUsesStaticLibraries(),
                pkg.getUsesStaticLibrariesVersions());

        pkg.getSignatures().writeXml(serializer, "sigs", mPastSignatures.untrackedStorage());

        if (installSource.mInitiatingPackageSignatures != null) {
            installSource.mInitiatingPackageSignatures.writeXml(
                    serializer, "install-initiator-sigs", mPastSignatures.untrackedStorage());
        }

        writeSigningKeySetLPr(serializer, pkg.getKeySetData());
        writeUpgradeKeySetsLPr(serializer, pkg.getKeySetData());
        writeKeySetAliasesLPr(serializer, pkg.getKeySetData());
        writeMimeGroupLPr(serializer, pkg.getMimeGroups());

        serializer.endTag(null, "package");
    }

    void writeSigningKeySetLPr(TypedXmlSerializer serializer,
            PackageKeySetData data) throws IOException {
        serializer.startTag(null, "proper-signing-keyset");
        serializer.attributeLong(null, "identifier", data.getProperSigningKeySet());
        serializer.endTag(null, "proper-signing-keyset");
    }

    void writeUpgradeKeySetsLPr(TypedXmlSerializer serializer,
            PackageKeySetData data) throws IOException {
        if (data.isUsingUpgradeKeySets()) {
            for (long id : data.getUpgradeKeySets()) {
                serializer.startTag(null, "upgrade-keyset");
                serializer.attributeLong(null, "identifier", id);
                serializer.endTag(null, "upgrade-keyset");
            }
        }
    }

    void writeKeySetAliasesLPr(TypedXmlSerializer serializer,
            PackageKeySetData data) throws IOException {
        for (Map.Entry<String, Long> e: data.getAliases().entrySet()) {
            serializer.startTag(null, "defined-keyset");
            serializer.attribute(null, "alias", e.getKey());
            serializer.attributeLong(null, "identifier", e.getValue());
            serializer.endTag(null, "defined-keyset");
        }
    }

    boolean readSettingsLPw(@NonNull Computer computer, @NonNull List<UserInfo> users,
            ArrayMap<String, Long> originalFirstInstallTimes) {
        mPendingPackages.clear();
        mPastSignatures.clear();
        mKeySetRefs.clear();
        mInstallerPackages.clear();
        originalFirstInstallTimes.clear();

        try (ResilientAtomicFile atomicFile = getSettingsFile()) {
            FileInputStream str = null;
            try {
                str = atomicFile.openRead();
                if (str == null) {
                    // Not necessary, but will avoid wtf-s in the "finally" section.
                    findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL).forceCurrent();
                    findOrCreateVersion(StorageManager.UUID_PRIMARY_PHYSICAL).forceCurrent();
                    return false;
                }
                final TypedXmlPullParser parser = Xml.resolvePullParser(str);

                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    // nothing
                }

                if (type != XmlPullParser.START_TAG) {
                    mReadMessages.append("No start tag found in settings file\n");
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "No start tag found in package manager settings");
                    Slog.wtf(PackageManagerService.TAG,
                            "No start tag found in package manager settings");
                    return false;
                }

                int outerDepth = parser.getDepth();
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    String tagName = parser.getName();
                    if (tagName.equals("package")) {
                        readPackageLPw(parser, users, originalFirstInstallTimes);
                    } else if (tagName.equals("permissions")) {
                        mPermissions.readPermissions(parser);
                    } else if (tagName.equals("permission-trees")) {
                        mPermissions.readPermissionTrees(parser);
                    } else if (tagName.equals("shared-user")) {
                        readSharedUserLPw(parser, users);
                    } else if (tagName.equals("preferred-packages")) {
                        // no longer used.
                    } else if (tagName.equals("preferred-activities")) {
                        // Upgrading from old single-user implementation;
                        // these are the preferred activities for user 0.
                        readPreferredActivitiesLPw(parser, 0);
                    } else if (tagName.equals(TAG_PERSISTENT_PREFERRED_ACTIVITIES)) {
                        // TODO: check whether this is okay! as it is very
                        // similar to how preferred-activities are treated
                        readPersistentPreferredActivitiesLPw(parser, 0);
                    } else if (tagName.equals(TAG_CROSS_PROFILE_INTENT_FILTERS)) {
                        // TODO: check whether this is okay! as it is very
                        // similar to how preferred-activities are treated
                        readCrossProfileIntentFiltersLPw(parser, 0);
                    } else if (tagName.equals(TAG_DEFAULT_BROWSER)) {
                        readDefaultAppsLPw(parser, 0);
                    } else if (tagName.equals("updated-package")) {
                        readDisabledSysPackageLPw(parser, users);
                    } else if (tagName.equals("renamed-package")) {
                        String nname = parser.getAttributeValue(null, "new");
                        String oname = parser.getAttributeValue(null, "old");
                        if (nname != null && oname != null) {
                            mRenamedPackages.put(nname, oname);
                        }
                    } else if (tagName.equals("last-platform-version")) {
                        // Upgrade from older XML schema
                        final VersionInfo internal = findOrCreateVersion(
                                StorageManager.UUID_PRIVATE_INTERNAL);
                        final VersionInfo external = findOrCreateVersion(
                                StorageManager.UUID_PRIMARY_PHYSICAL);

                        internal.sdkVersion = parser.getAttributeInt(null, "internal", 0);
                        external.sdkVersion = parser.getAttributeInt(null, "external", 0);
                        internal.buildFingerprint = external.buildFingerprint =
                                XmlUtils.readStringAttribute(parser, "buildFingerprint");
                        internal.fingerprint = external.fingerprint =
                                XmlUtils.readStringAttribute(parser, "fingerprint");

                    } else if (tagName.equals("database-version")) {
                        // Upgrade from older XML schema
                        final VersionInfo internal = findOrCreateVersion(
                                StorageManager.UUID_PRIVATE_INTERNAL);
                        final VersionInfo external = findOrCreateVersion(
                                StorageManager.UUID_PRIMARY_PHYSICAL);

                        internal.databaseVersion = parser.getAttributeInt(null, "internal", 0);
                        external.databaseVersion = parser.getAttributeInt(null, "external", 0);

                    } else if (tagName.equals("verifier")) {
                        final String deviceIdentity = parser.getAttributeValue(null, "device");
                        try {
                            mVerifierDeviceIdentity = VerifierDeviceIdentity.parse(deviceIdentity);
                        } catch (IllegalArgumentException e) {
                            Slog.w(PackageManagerService.TAG, "Discard invalid verifier device id: "
                                    + e.getMessage());
                        }
                    } else if (TAG_READ_EXTERNAL_STORAGE.equals(tagName)) {
                        // No longer used.
                    } else if (tagName.equals("keyset-settings")) {
                        mKeySetManagerService.readKeySetsLPw(parser,
                                mKeySetRefs.untrackedStorage());
                    } else if (TAG_VERSION.equals(tagName)) {
                        final String volumeUuid = XmlUtils.readStringAttribute(parser,
                                ATTR_VOLUME_UUID);
                        final VersionInfo ver = findOrCreateVersion(volumeUuid);
                        ver.sdkVersion = parser.getAttributeInt(null, ATTR_SDK_VERSION);
                        ver.databaseVersion = parser.getAttributeInt(null, ATTR_DATABASE_VERSION);
                        ver.buildFingerprint = XmlUtils.readStringAttribute(parser,
                                ATTR_BUILD_FINGERPRINT);
                        ver.fingerprint = XmlUtils.readStringAttribute(parser, ATTR_FINGERPRINT);
                    } else if (tagName.equals(
                            DomainVerificationPersistence.TAG_DOMAIN_VERIFICATIONS)) {
                        mDomainVerificationManager.readSettings(computer, parser);
                    } else if (tagName.equals(
                            DomainVerificationLegacySettings.TAG_DOMAIN_VERIFICATIONS_LEGACY)) {
                        mDomainVerificationManager.readLegacySettings(parser);
                    } else {
                        Slog.w(PackageManagerService.TAG, "Unknown element under <packages>: "
                                + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }

                str.close();
            } catch (IOException | XmlPullParserException e) {
                // Remove corrupted file and retry.
                atomicFile.failRead(str, e);

                // Ignore the result to not mark this as a "first boot".
                readSettingsLPw(computer, users, originalFirstInstallTimes);
            }
        }

        return true;
    }

    /**
     * @return false if settings file is missing (i.e. during first boot), true otherwise
     */
    boolean readLPw(@NonNull Computer computer, @NonNull List<UserInfo> users) {
        // If any user state doesn't have a first install time, e.g., after an OTA,
        // use the pre OTA firstInstallTime timestamp. This is because we migrated from per package
        // firstInstallTime to per user-state. Without this, OTA can cause this info to be lost.
        final ArrayMap<String, Long> originalFirstInstallTimes = new ArrayMap<>();

        try {
            if (!readSettingsLPw(computer, users, originalFirstInstallTimes)) {
                return false;
            }
        } finally {
            if (!mVersion.containsKey(StorageManager.UUID_PRIVATE_INTERNAL)) {
                Slog.wtf(PackageManagerService.TAG,
                        "No internal VersionInfo found in settings, using current.");
                findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL).forceCurrent();
            }
            if (!mVersion.containsKey(StorageManager.UUID_PRIMARY_PHYSICAL)) {
                Slog.wtf(PackageManagerService.TAG,
                        "No external VersionInfo found in settings, using current.");
                findOrCreateVersion(StorageManager.UUID_PRIMARY_PHYSICAL).forceCurrent();
            }
        }

        final int N = mPendingPackages.size();

        for (int i = 0; i < N; i++) {
            final PackageSetting p = mPendingPackages.get(i);
            final int sharedUserAppId = p.getSharedUserAppId();
            if (sharedUserAppId <= 0) {
                continue;
            }
            final Object idObj = getSettingLPr(sharedUserAppId);
            if (idObj instanceof SharedUserSetting) {
                final SharedUserSetting sharedUser = (SharedUserSetting) idObj;
                addPackageSettingLPw(p, sharedUser);
            } else if (idObj != null) {
                String msg = "Bad package setting: package " + p.getPackageName()
                        + " has shared uid " + sharedUserAppId + " that is not a shared uid\n";
                mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(Log.ERROR, msg);
            } else {
                String msg = "Bad package setting: package " + p.getPackageName()
                        + " has shared uid " + sharedUserAppId + " that is not defined\n";
                mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(Log.ERROR, msg);
            }
        }
        mPendingPackages.clear();

        if (mBackupStoppedPackagesFilename.exists()
                || mStoppedPackagesFilename.exists()) {
            // Read old file
            readStoppedLPw();
            mBackupStoppedPackagesFilename.delete();
            mStoppedPackagesFilename.delete();
            // Migrate to new file format
            writePackageRestrictionsLPr(UserHandle.USER_SYSTEM, /*sync=*/true);
        } else {
            for (UserInfo user : users) {
                readPackageRestrictionsLPr(user.id, originalFirstInstallTimes);
            }
        }

        for (UserInfo user : users) {
            mRuntimePermissionsPersistence.readStateForUserSync(user.id, getInternalVersion(),
                    mPackages, mSharedUsers, getUserRuntimePermissionsFile(user.id));
        }

        /*
         * Make sure all the updated system packages have their shared users
         * associated with them.
         */
        for (PackageSetting disabledPs : mDisabledSysPackages.values()) {
            final Object id = getSettingLPr(disabledPs.getAppId());
            if (id instanceof SharedUserSetting) {
                SharedUserSetting sharedUserSetting = (SharedUserSetting) id;
                sharedUserSetting.mDisabledPackages.add(disabledPs);
                disabledPs.setSharedUserAppId(sharedUserSetting.mAppId);
            }
        }

        mReadMessages.append("Read completed successfully: ").append(mPackages.size())
                .append(" packages, ").append(mSharedUsers.size()).append(" shared uids\n");

        writeKernelMappingLPr();

        return true;
    }

    void readPermissionStateForUserSyncLPr(@UserIdInt int userId) {
        mRuntimePermissionsPersistence.readStateForUserSync(userId, getInternalVersion(),
                mPackages, mSharedUsers, getUserRuntimePermissionsFile(userId));
    }

    RuntimePermissionsState getLegacyPermissionsState(@UserIdInt int userId) {
        return mRuntimePermissionsPersistence.getLegacyPermissionsState(
                userId, mPackages, mSharedUsers);
    }

    void applyDefaultPreferredAppsLPw(int userId) {
        // First pull data from any pre-installed apps.
        final PackageManagerInternal pmInternal =
                LocalServices.getService(PackageManagerInternal.class);
        for (PackageSetting ps : mPackages.values()) {
            if ((ps.getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0 && ps.getPkg() != null
                    && !ps.getPkg().getPreferredActivityFilters().isEmpty()) {
                List<Pair<String, ParsedIntentInfo>> intents
                        = ps.getPkg().getPreferredActivityFilters();
                for (int i=0; i<intents.size(); i++) {
                    Pair<String, ParsedIntentInfo> pair = intents.get(i);
                    applyDefaultPreferredActivityLPw(pmInternal,
                            pair.second.getIntentFilter(),
                            new ComponentName(ps.getPackageName(), pair.first), userId);
                }
            }
        }

        // Read preferred apps from .../etc/preferred-apps directories.
        int size = PackageManagerService.SYSTEM_PARTITIONS.size();
        for (int index = 0; index < size; index++) {
            ScanPartition partition = PackageManagerService.SYSTEM_PARTITIONS.get(index);

            File preferredDir = new File(partition.getFolder(), "etc/preferred-apps");
            if (!preferredDir.exists() || !preferredDir.isDirectory()) {
                continue;
            }

            if (!preferredDir.canRead()) {
                Slog.w(TAG, "Directory " + preferredDir + " cannot be read");
                continue;
            }

            // Iterate over the files in the directory and scan .xml files
            File[] files = preferredDir.listFiles();
            if (ArrayUtils.isEmpty(files)) {
                continue;
            }

            for (File f : files) {
                if (!f.getPath().endsWith(".xml")) {
                    Slog.i(TAG, "Non-xml file " + f + " in " + preferredDir
                            + " directory, ignoring");
                    continue;
                }
                if (!f.canRead()) {
                    Slog.w(TAG, "Preferred apps file " + f + " cannot be read");
                    continue;
                }
                if (PackageManagerService.DEBUG_PREFERRED) {
                    Log.d(TAG, "Reading default preferred " + f);
                }

                try (InputStream str = new FileInputStream(f)) {
                    final TypedXmlPullParser parser = Xml.resolvePullParser(str);

                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        ;
                    }

                    if (type != XmlPullParser.START_TAG) {
                        Slog.w(TAG, "Preferred apps file " + f + " does not have start tag");
                        continue;
                    }
                    if (!"preferred-activities".equals(parser.getName())) {
                        Slog.w(TAG, "Preferred apps file " + f
                                + " does not start with 'preferred-activities'");
                        continue;
                    }
                    readDefaultPreferredActivitiesLPw(parser, userId);
                } catch (XmlPullParserException e) {
                    Slog.w(TAG, "Error reading apps file " + f, e);
                } catch (IOException e) {
                    Slog.w(TAG, "Error reading apps file " + f, e);
                }
            }
        }
    }

    static void removeFilters(@NonNull PreferredIntentResolver pir,
            @NonNull WatchedIntentFilter filter, @NonNull List<PreferredActivity> existing) {
        if (PackageManagerService.DEBUG_PREFERRED) {
            Slog.i(TAG, existing.size() + " preferred matches for:");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
        }
        for (int i = existing.size() - 1; i >= 0; --i) {
            final PreferredActivity pa = existing.get(i);
            if (PackageManagerService.DEBUG_PREFERRED) {
                Slog.i(TAG, "Removing preferred activity " + pa.mPref.mComponent + ":");
                pa.dump(new LogPrinter(Log.INFO, TAG), "  ");
            }
            pir.removeFilter(pa);
        }
    }

    private void applyDefaultPreferredActivityLPw(PackageManagerInternal pmInternal,
            IntentFilter tmpPa, ComponentName cn, int userId) {
        // The initial preferences only specify the target activity
        // component and intent-filter, not the set of matches.  So we
        // now need to query for the matches to build the correct
        // preferred activity entry.
        if (PackageManagerService.DEBUG_PREFERRED) {
            Log.d(TAG, "Processing preferred:");
            tmpPa.dump(new LogPrinter(Log.DEBUG, TAG), "  ");
        }
        Intent intent = new Intent();
        int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        intent.setAction(tmpPa.getAction(0));
        for (int i=0; i<tmpPa.countCategories(); i++) {
            String cat = tmpPa.getCategory(i);
            if (cat.equals(Intent.CATEGORY_DEFAULT)) {
                flags |= MATCH_DEFAULT_ONLY;
            } else {
                intent.addCategory(cat);
            }
        }

        boolean doNonData = true;
        boolean hasSchemes = false;

        final int dataSchemesCount = tmpPa.countDataSchemes();
        for (int ischeme = 0; ischeme < dataSchemesCount; ischeme++) {
            boolean doScheme = true;
            final String scheme = tmpPa.getDataScheme(ischeme);
            if (scheme != null && !scheme.isEmpty()) {
                hasSchemes = true;
            }
            final int dataSchemeSpecificPartsCount = tmpPa.countDataSchemeSpecificParts();
            for (int issp = 0; issp < dataSchemeSpecificPartsCount; issp++) {
                Uri.Builder builder = new Uri.Builder();
                builder.scheme(scheme);
                PatternMatcher ssp = tmpPa.getDataSchemeSpecificPart(issp);
                builder.opaquePart(ssp.getPath());
                Intent finalIntent = new Intent(intent);
                finalIntent.setData(builder.build());
                applyDefaultPreferredActivityLPw(pmInternal, finalIntent, flags, cn,
                        scheme, ssp, null, null, userId);
                doScheme = false;
            }
            final int dataAuthoritiesCount = tmpPa.countDataAuthorities();
            for (int iauth = 0; iauth < dataAuthoritiesCount; iauth++) {
                boolean doAuth = true;
                final IntentFilter.AuthorityEntry auth = tmpPa.getDataAuthority(iauth);
                final int dataPathsCount = tmpPa.countDataPaths();
                for (int ipath = 0; ipath < dataPathsCount; ipath++) {
                    Uri.Builder builder = new Uri.Builder();
                    builder.scheme(scheme);
                    if (auth.getHost() != null) {
                        builder.authority(auth.getHost());
                    }
                    PatternMatcher path = tmpPa.getDataPath(ipath);
                    builder.path(path.getPath());
                    Intent finalIntent = new Intent(intent);
                    finalIntent.setData(builder.build());
                    applyDefaultPreferredActivityLPw(pmInternal, finalIntent, flags, cn,
                            scheme, null, auth, path, userId);
                    doAuth = doScheme = false;
                }
                if (doAuth) {
                    Uri.Builder builder = new Uri.Builder();
                    builder.scheme(scheme);
                    if (auth.getHost() != null) {
                        builder.authority(auth.getHost());
                    }
                    Intent finalIntent = new Intent(intent);
                    finalIntent.setData(builder.build());
                    applyDefaultPreferredActivityLPw(pmInternal, finalIntent, flags, cn,
                            scheme, null, auth, null, userId);
                    doScheme = false;
                }
            }
            if (doScheme) {
                Uri.Builder builder = new Uri.Builder();
                builder.scheme(scheme);
                Intent finalIntent = new Intent(intent);
                finalIntent.setData(builder.build());
                applyDefaultPreferredActivityLPw(pmInternal, finalIntent, flags, cn,
                        scheme, null, null, null, userId);
            }
            doNonData = false;
        }

        for (int idata=0; idata<tmpPa.countDataTypes(); idata++) {
            String mimeType = tmpPa.getDataType(idata);
            if (hasSchemes) {
                Uri.Builder builder = new Uri.Builder();
                for (int ischeme=0; ischeme<tmpPa.countDataSchemes(); ischeme++) {
                    String scheme = tmpPa.getDataScheme(ischeme);
                    if (scheme != null && !scheme.isEmpty()) {
                        Intent finalIntent = new Intent(intent);
                        builder.scheme(scheme);
                        finalIntent.setDataAndType(builder.build(), mimeType);
                        applyDefaultPreferredActivityLPw(pmInternal, finalIntent, flags, cn,
                                scheme, null, null, null, userId);
                    }
                }
            } else {
                Intent finalIntent = new Intent(intent);
                finalIntent.setType(mimeType);
                applyDefaultPreferredActivityLPw(pmInternal, finalIntent, flags, cn,
                        null, null, null, null, userId);
            }
            doNonData = false;
        }

        if (doNonData) {
            applyDefaultPreferredActivityLPw(pmInternal, intent, flags, cn,
                    null, null, null, null, userId);
        }
    }

    private void applyDefaultPreferredActivityLPw(PackageManagerInternal pmInternal, Intent intent,
            int flags, ComponentName cn, String scheme, PatternMatcher ssp,
            IntentFilter.AuthorityEntry auth, PatternMatcher path, int userId) {
        final List<ResolveInfo> ri =
                pmInternal.queryIntentActivities(
                        intent, intent.getType(), flags, Binder.getCallingUid(), userId);
        if (PackageManagerService.DEBUG_PREFERRED) {
            Log.d(TAG, "Queried " + intent + " results: " + ri);
        }
        int systemMatch = 0;
        int thirdPartyMatch = 0;
        final int numMatches = (ri == null ? 0 : ri.size());
        if (numMatches < 1) {
            Slog.w(TAG, "No potential matches found for " + intent
                    + " while setting preferred " + cn.flattenToShortString());
            return;
        }
        boolean haveAct = false;
        ComponentName haveNonSys = null;
        ComponentName[] set = new ComponentName[ri.size()];
        for (int i = 0; i < numMatches; i++) {
            final ActivityInfo ai = ri.get(i).activityInfo;
            set[i] = new ComponentName(ai.packageName, ai.name);
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                if (ri.get(i).match >= thirdPartyMatch) {
                    // Keep track of the best match we find of all third
                    // party apps, for use later to determine if we actually
                    // want to set a preferred app for this intent.
                    if (PackageManagerService.DEBUG_PREFERRED) {
                        Log.d(TAG, "Result " + ai.packageName + "/" + ai.name + ": non-system!");
                    }
                    haveNonSys = set[i];
                    break;
                }
            } else if (cn.getPackageName().equals(ai.packageName)
                    && cn.getClassName().equals(ai.name)) {
                if (PackageManagerService.DEBUG_PREFERRED) {
                    Log.d(TAG, "Result " + ai.packageName + "/" + ai.name + ": default!");
                }
                haveAct = true;
                systemMatch = ri.get(i).match;
            } else {
                if (PackageManagerService.DEBUG_PREFERRED) {
                    Log.d(TAG, "Result " + ai.packageName + "/" + ai.name + ": skipped");
                }
            }
        }
        if (haveNonSys != null && thirdPartyMatch < systemMatch) {
            // If we have a matching third party app, but its match is not as
            // good as the built-in system app, then we don't want to actually
            // consider it a match because presumably the built-in app is still
            // the thing we want users to see by default.
            haveNonSys = null;
        }
        if (haveAct && haveNonSys == null) {
            WatchedIntentFilter filter = new WatchedIntentFilter();
            if (intent.getAction() != null) {
                filter.addAction(intent.getAction());
            }
            if (intent.getCategories() != null) {
                for (String cat : intent.getCategories()) {
                    filter.addCategory(cat);
                }
            }
            if ((flags & MATCH_DEFAULT_ONLY) != 0) {
                filter.addCategory(Intent.CATEGORY_DEFAULT);
            }
            if (scheme != null) {
                filter.addDataScheme(scheme);
            }
            if (ssp != null) {
                filter.addDataSchemeSpecificPart(ssp.getPath(), ssp.getType());
            }
            if (auth != null) {
                filter.addDataAuthority(auth);
            }
            if (path != null) {
                filter.addDataPath(path);
            }
            if (intent.getType() != null) {
                try {
                    filter.addDataType(intent.getType());
                } catch (IntentFilter.MalformedMimeTypeException ex) {
                    Slog.w(TAG, "Malformed mimetype " + intent.getType() + " for " + cn);
                }
            }
            final PreferredIntentResolver pir = editPreferredActivitiesLPw(userId);
            final List<PreferredActivity> existing = pir.findFilters(filter);
            if (existing != null) {
                removeFilters(pir, filter, existing);
            }
            PreferredActivity pa = new PreferredActivity(filter, systemMatch, set, cn, true);
            pir.addFilter(null, pa);
        } else if (haveNonSys == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("No component ");
            sb.append(cn.flattenToShortString());
            sb.append(" found setting preferred ");
            sb.append(intent);
            sb.append("; possible matches are ");
            for (int i = 0; i < set.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(set[i].flattenToShortString());
            }
            Slog.w(TAG, sb.toString());
        } else {
            Slog.i(TAG, "Not setting preferred " + intent + "; found third party match "
                    + haveNonSys.flattenToShortString());
        }
    }

    private void readDefaultPreferredActivitiesLPw(TypedXmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        final PackageManagerInternal pmInternal =
                LocalServices.getService(PackageManagerInternal.class);
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                PreferredActivity tmpPa = new PreferredActivity(parser);
                if (tmpPa.mPref.getParseError() == null) {
                    applyDefaultPreferredActivityLPw(
                            pmInternal, tmpPa.getIntentFilter(), tmpPa.mPref.mComponent, userId);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <preferred-activity> "
                                    + tmpPa.mPref.getParseError() + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <preferred-activities>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readDisabledSysPackageLPw(TypedXmlPullParser parser, List<UserInfo> users)
            throws XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, ATTR_NAME);
        String realName = parser.getAttributeValue(null, "realName");
        String codePathStr = parser.getAttributeValue(null, "codePath");

        String legacyCpuAbiStr = parser.getAttributeValue(null, "requiredCpuAbi");
        String legacyNativeLibraryPathStr = parser.getAttributeValue(null, "nativeLibraryPath");

        String primaryCpuAbiStr = parser.getAttributeValue(null, "primaryCpuAbi");
        String secondaryCpuAbiStr = parser.getAttributeValue(null, "secondaryCpuAbi");
        String cpuAbiOverrideStr = parser.getAttributeValue(null, "cpuAbiOverride");

        if (primaryCpuAbiStr == null && legacyCpuAbiStr != null) {
            primaryCpuAbiStr = legacyCpuAbiStr;
        }

        long versionCode = parser.getAttributeLong(null, "version", 0);
        int targetSdkVersion = parser.getAttributeInt(null, "targetSdkVersion", 0);
        byte[] restrictUpdateHash = parser.getAttributeBytesBase64(null, "restrictUpdateHash",
                null);

        int pkgFlags = 0;
        int pkgPrivateFlags = 0;
        pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
        if (codePathStr.contains("/priv-app/")) {
            pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        }

        // When reading a disabled setting, use a disabled domainSetId, which makes it easier to
        // debug invalid entries. The actual logic for migrating to a new ID is done in other
        // methods that use DomainVerificationManagerInternal#generateNewId
        UUID domainSetId = DomainVerificationManagerInternal.DISABLED_ID;
        PackageSetting ps = new PackageSetting(name, realName, new File(codePathStr), pkgFlags,
                pkgPrivateFlags, domainSetId)
                .setLegacyNativeLibraryPath(legacyNativeLibraryPathStr)
                .setPrimaryCpuAbi(primaryCpuAbiStr)
                .setSecondaryCpuAbi(secondaryCpuAbiStr)
                .setCpuAbiOverride(cpuAbiOverrideStr)
                .setLongVersionCode(versionCode)
                .setTargetSdkVersion(targetSdkVersion)
                .setRestrictUpdateHash(restrictUpdateHash);
        long timeStamp = parser.getAttributeLongHex(null, "ft", 0);
        if (timeStamp == 0) {
            timeStamp = parser.getAttributeLong(null, "ts", 0);
        }
        ps.setLastModifiedTime(timeStamp);
        ps.setLastUpdateTime(parser.getAttributeLongHex(null, "ut", 0));
        ps.setAppId(parseAppId(parser));
        if (ps.getAppId() <= 0) {
            final int sharedUserAppId = parseSharedUserAppId(parser);
            ps.setAppId(sharedUserAppId);
            ps.setSharedUserAppId(sharedUserAppId);
        }

        ps.setAppMetadataFilePath(parser.getAttributeValue(null, "appMetadataFilePath"));

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals(TAG_PERMISSIONS)) {
                final LegacyPermissionState legacyState;
                if (ps.hasSharedUser()) {
                    final SettingBase sharedUserSettings = getSettingLPr(
                            ps.getSharedUserAppId());
                    legacyState = sharedUserSettings != null
                            ? sharedUserSettings.getLegacyPermissionState() : null;
                } else {
                    legacyState = ps.getLegacyPermissionState();
                }
                if (legacyState != null) {
                    readInstallPermissionsLPr(parser, legacyState, users);
                }
            } else if (parser.getName().equals(TAG_USES_STATIC_LIB)) {
                readUsesStaticLibLPw(parser, ps);
            } else if (parser.getName().equals(TAG_USES_SDK_LIB)) {
                readUsesSdkLibLPw(parser, ps);
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <updated-package>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }

        mDisabledSysPackages.put(name, ps);
    }

    private static final int PRE_M_APP_INFO_FLAG_HIDDEN = 1 << 27;
    private static final int PRE_M_APP_INFO_FLAG_CANT_SAVE_STATE = 1 << 28;
    private static final int PRE_M_APP_INFO_FLAG_PRIVILEGED = 1 << 30;

    private void readPackageLPw(TypedXmlPullParser parser, List<UserInfo> users,
            ArrayMap<String, Long> originalFirstInstallTimes)
            throws XmlPullParserException, IOException {
        String name = null;
        String realName = null;
        int appId = 0;
        int sharedUserAppId = 0;
        String codePathStr = null;
        String legacyCpuAbiString = null;
        String legacyNativeLibraryPathStr = null;
        String primaryCpuAbiString = null;
        String secondaryCpuAbiString = null;
        String cpuAbiOverrideString = null;
        String systemStr = null;
        String installerPackageName = null;
        int installerPackageUid = INVALID_UID;
        String updateOwnerPackageName = null;
        String installerAttributionTag = null;
        int packageSource = PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED;
        boolean isOrphaned = false;
        String installOriginatingPackageName = null;
        String installInitiatingPackageName = null;
        boolean installInitiatorUninstalled = false;
        String volumeUuid = null;
        boolean updateAvailable = false;
        int categoryHint = ApplicationInfo.CATEGORY_UNDEFINED;
        int pkgFlags = 0;
        int pkgPrivateFlags = 0;
        long timeStamp = 0;
        long firstInstallTime = 0;
        long lastUpdateTime = 0;
        PackageSetting packageSetting = null;
        long versionCode = 0;
        boolean installedForceQueryable = false;
        float loadingProgress = 0;
        long loadingCompletedTime = 0;
        UUID domainSetId;
        String appMetadataFilePath = null;
        int targetSdkVersion = 0;
        byte[] restrictUpdateHash = null;
        try {
            name = parser.getAttributeValue(null, ATTR_NAME);
            realName = parser.getAttributeValue(null, "realName");
            appId = parseAppId(parser);
            sharedUserAppId = parseSharedUserAppId(parser);
            codePathStr = parser.getAttributeValue(null, "codePath");

            legacyCpuAbiString = parser.getAttributeValue(null, "requiredCpuAbi");

            legacyNativeLibraryPathStr = parser.getAttributeValue(null, "nativeLibraryPath");
            primaryCpuAbiString = parser.getAttributeValue(null, "primaryCpuAbi");
            secondaryCpuAbiString = parser.getAttributeValue(null, "secondaryCpuAbi");
            cpuAbiOverrideString = parser.getAttributeValue(null, "cpuAbiOverride");
            updateAvailable = parser.getAttributeBoolean(null, "updateAvailable", false);
            installedForceQueryable = parser.getAttributeBoolean(null, "forceQueryable", false);
            loadingProgress = parser.getAttributeFloat(null, "loadingProgress", 0);
            loadingCompletedTime = parser.getAttributeLongHex(null, "loadingCompletedTime", 0);

            if (primaryCpuAbiString == null && legacyCpuAbiString != null) {
                primaryCpuAbiString = legacyCpuAbiString;
            }

            versionCode = parser.getAttributeLong(null, "version", 0);
            targetSdkVersion = parser.getAttributeInt(null, "targetSdkVersion", 0);
            restrictUpdateHash = parser.getAttributeBytesBase64(null, "restrictUpdateHash", null);
            installerPackageName = parser.getAttributeValue(null, "installer");
            installerPackageUid = parser.getAttributeInt(null, "installerUid", INVALID_UID);
            updateOwnerPackageName = parser.getAttributeValue(null, "updateOwner");
            installerAttributionTag = parser.getAttributeValue(null, "installerAttributionTag");
            packageSource = parser.getAttributeInt(null, "packageSource",
                    PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
            isOrphaned = parser.getAttributeBoolean(null, "isOrphaned", false);
            installInitiatingPackageName = parser.getAttributeValue(null, "installInitiator");
            installOriginatingPackageName = parser.getAttributeValue(null, "installOriginator");
            installInitiatorUninstalled = parser.getAttributeBoolean(null,
                    "installInitiatorUninstalled", false);
            volumeUuid = parser.getAttributeValue(null, "volumeUuid");
            categoryHint = parser.getAttributeInt(null, "categoryHint",
                    ApplicationInfo.CATEGORY_UNDEFINED);
            appMetadataFilePath = parser.getAttributeValue(null, "appMetadataFilePath");

            String domainSetIdString = parser.getAttributeValue(null, "domainSetId");

            if (TextUtils.isEmpty(domainSetIdString)) {
                // If empty, assume restoring from previous platform version and generate an ID
                domainSetId = mDomainVerificationManager.generateNewId();
            } else {
                domainSetId = UUID.fromString(domainSetIdString);
            }

            systemStr = parser.getAttributeValue(null, "publicFlags");
            if (systemStr != null) {
                try {
                    pkgFlags = Integer.parseInt(systemStr);
                } catch (NumberFormatException e) {
                }
                systemStr = parser.getAttributeValue(null, "privateFlags");
                if (systemStr != null) {
                    try {
                        pkgPrivateFlags = Integer.parseInt(systemStr);
                    } catch (NumberFormatException e) {
                    }
                }
            } else {
                // Pre-M -- both public and private flags were stored in one "flags" field.
                systemStr = parser.getAttributeValue(null, "flags");
                if (systemStr != null) {
                    try {
                        pkgFlags = Integer.parseInt(systemStr);
                    } catch (NumberFormatException e) {
                    }
                    if ((pkgFlags & PRE_M_APP_INFO_FLAG_HIDDEN) != 0) {
                        pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_HIDDEN;
                    }
                    if ((pkgFlags & PRE_M_APP_INFO_FLAG_CANT_SAVE_STATE) != 0) {
                        pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE;
                    }
                    if ((pkgFlags & PRE_M_APP_INFO_FLAG_PRIVILEGED) != 0) {
                        pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
                    }
                    pkgFlags &= ~(PRE_M_APP_INFO_FLAG_HIDDEN
                            | PRE_M_APP_INFO_FLAG_CANT_SAVE_STATE
                            | PRE_M_APP_INFO_FLAG_PRIVILEGED);
                } else {
                    // For backward compatibility
                    systemStr = parser.getAttributeValue(null, "system");
                    if (systemStr != null) {
                        pkgFlags |= ("true".equalsIgnoreCase(systemStr)) ? ApplicationInfo.FLAG_SYSTEM
                                : 0;
                    } else {
                        // Old settings that don't specify system... just treat
                        // them as system, good enough.
                        pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
                    }
                }
            }
            timeStamp = parser.getAttributeLongHex(null, "ft", 0);
            if (timeStamp == 0) {
                timeStamp = parser.getAttributeLong(null, "ts", 0);
            }
            firstInstallTime = parser.getAttributeLongHex(null, "it", 0);
            lastUpdateTime = parser.getAttributeLongHex(null, "ut", 0);
            if (PackageManagerService.DEBUG_SETTINGS)
                Log.v(PackageManagerService.TAG, "Reading package: " + name + " appId=" + appId
                        + " sharedUserAppId=" + sharedUserAppId);
            if (realName != null) {
                realName = realName.intern();
            }
            if (name == null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <package> has no name at "
                                + parser.getPositionDescription());
            } else if (codePathStr == null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <package> has no codePath at "
                                + parser.getPositionDescription());
            } else if (appId > 0) {
                packageSetting = addPackageLPw(name.intern(), realName, new File(codePathStr),
                        appId, pkgFlags, pkgPrivateFlags, domainSetId);
                if (PackageManagerService.DEBUG_SETTINGS)
                    Log.i(PackageManagerService.TAG, "Reading package " + name + ": appId="
                            + appId + " pkg=" + packageSetting);
                if (packageSetting == null) {
                    PackageManagerService.reportSettingsProblem(Log.ERROR, "Failure adding appId "
                            + appId + " while parsing settings at "
                            + parser.getPositionDescription());
                } else {
                    packageSetting.setLegacyNativeLibraryPath(legacyNativeLibraryPathStr);
                    packageSetting.setPrimaryCpuAbi(primaryCpuAbiString);
                    packageSetting.setSecondaryCpuAbi(secondaryCpuAbiString);
                    packageSetting.setCpuAbiOverride(cpuAbiOverrideString);
                    packageSetting.setLongVersionCode(versionCode);
                    packageSetting.setLastModifiedTime(timeStamp);
                    packageSetting.setLastUpdateTime(lastUpdateTime);
                }
            } else if (sharedUserAppId != 0) {
                if (sharedUserAppId > 0) {
                    packageSetting = new PackageSetting(name.intern(), realName,
                            new File(codePathStr), pkgFlags, pkgPrivateFlags, domainSetId)
                            .setLegacyNativeLibraryPath(legacyNativeLibraryPathStr)
                            .setPrimaryCpuAbi(primaryCpuAbiString)
                            .setSecondaryCpuAbi(secondaryCpuAbiString)
                            .setCpuAbiOverride(cpuAbiOverrideString)
                            .setLongVersionCode(versionCode)
                            .setSharedUserAppId(sharedUserAppId)
                            .setLastModifiedTime(timeStamp)
                            .setLastUpdateTime(lastUpdateTime);
                    mPendingPackages.add(packageSetting);
                    if (PackageManagerService.DEBUG_SETTINGS)
                        Log.i(PackageManagerService.TAG, "Reading package " + name
                                + ": sharedUserAppId=" + sharedUserAppId + " pkg="
                                + packageSetting);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: package " + name
                                    + " has bad sharedUserAppId " + sharedUserAppId + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: package " + name + " has bad appId "
                                + appId + " at " + parser.getPositionDescription());
            }
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: package " + name + " has bad appId "
                            + appId + " at " + parser.getPositionDescription());
        }
        if (packageSetting != null) {
            InstallSource installSource = InstallSource.create(
                    installInitiatingPackageName, installOriginatingPackageName,
                    installerPackageName, installerPackageUid, updateOwnerPackageName,
                    installerAttributionTag, packageSource, isOrphaned,
                    installInitiatorUninstalled);
            packageSetting.setInstallSource(installSource)
                    .setVolumeUuid(volumeUuid)
                    .setCategoryOverride(categoryHint)
                    .setLegacyNativeLibraryPath(legacyNativeLibraryPathStr)
                    .setPrimaryCpuAbi(primaryCpuAbiString)
                    .setSecondaryCpuAbi(secondaryCpuAbiString)
                    .setUpdateAvailable(updateAvailable)
                    .setForceQueryableOverride(installedForceQueryable)
                    .setLoadingProgress(loadingProgress)
                    .setLoadingCompletedTime(loadingCompletedTime)
                    .setAppMetadataFilePath(appMetadataFilePath)
                    .setTargetSdkVersion(targetSdkVersion)
                    .setRestrictUpdateHash(restrictUpdateHash);
            // Handle legacy string here for single-user mode
            final String enabledStr = parser.getAttributeValue(null, ATTR_ENABLED);
            if (enabledStr != null) {
                try {
                    packageSetting.setEnabled(Integer.parseInt(enabledStr), 0 /* userId */,
                            "settings");
                } catch (NumberFormatException e) {
                    if (enabledStr.equalsIgnoreCase("true")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 0, "settings");
                    } else if (enabledStr.equalsIgnoreCase("false")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0, "settings");
                    } else if (enabledStr.equalsIgnoreCase("default")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0, "settings");
                    } else {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: package " + name
                                        + " has bad enabled value: " + enabledStr + " at "
                                        + parser.getPositionDescription());
                    }
                }
            } else {
                packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0, "settings");
            }

            addInstallerPackageNames(installSource);

            int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                // Legacy
                if (tagName.equals(TAG_DISABLED_COMPONENTS)) {
                    readDisabledComponentsLPw(packageSetting, parser, 0);
                } else if (tagName.equals(TAG_ENABLED_COMPONENTS)) {
                    readEnabledComponentsLPw(packageSetting, parser, 0);
                } else if (tagName.equals("sigs")) {
                    packageSetting.getSignatures()
                            .readXml(parser,mPastSignatures.untrackedStorage());
                } else if (tagName.equals(TAG_PERMISSIONS)) {
                    final LegacyPermissionState legacyState;
                    if (packageSetting.hasSharedUser()) {
                        final SettingBase sharedUserSettings = getSettingLPr(
                                packageSetting.getSharedUserAppId());
                        legacyState = sharedUserSettings != null
                                ? sharedUserSettings.getLegacyPermissionState() : null;
                    } else {
                        legacyState = packageSetting.getLegacyPermissionState();
                    }
                    if (legacyState != null) {
                        readInstallPermissionsLPr(parser, legacyState, users);
                        packageSetting.setInstallPermissionsFixed(true);
                    }
                } else if (tagName.equals("proper-signing-keyset")) {
                    long id = parser.getAttributeLong(null, "identifier");
                    Integer refCt = mKeySetRefs.get(id);
                    if (refCt != null) {
                        mKeySetRefs.put(id, refCt + 1);
                    } else {
                        mKeySetRefs.put(id, 1);
                    }
                    packageSetting.getKeySetData().setProperSigningKeySet(id);
                } else if (tagName.equals("signing-keyset")) {
                    // from v1 of keysetmanagerservice - no longer used
                } else if (tagName.equals("upgrade-keyset")) {
                    long id = parser.getAttributeLong(null, "identifier");
                    packageSetting.getKeySetData().addUpgradeKeySetById(id);
                } else if (tagName.equals("defined-keyset")) {
                    long id = parser.getAttributeLong(null, "identifier");
                    String alias = parser.getAttributeValue(null, "alias");
                    Integer refCt = mKeySetRefs.get(id);
                    if (refCt != null) {
                        mKeySetRefs.put(id, refCt + 1);
                    } else {
                        mKeySetRefs.put(id, 1);
                    }
                    packageSetting.getKeySetData().addDefinedKeySet(id, alias);
                } else if (tagName.equals("install-initiator-sigs")) {
                    final PackageSignatures signatures = new PackageSignatures();
                    signatures.readXml(parser, mPastSignatures.untrackedStorage());
                    packageSetting.setInstallSource(
                            packageSetting.getInstallSource()
                                    .setInitiatingPackageSignatures(signatures));
                } else if (tagName.equals(TAG_DOMAIN_VERIFICATION)) {
                    IntentFilterVerificationInfo ivi = new IntentFilterVerificationInfo(parser);
                    mDomainVerificationManager.addLegacySetting(packageSetting.getPackageName(),
                            ivi);
                    if (DEBUG_PARSER) {
                        Log.d(TAG, "Read domain verification for package: " + ivi.getPackageName());
                    }
                } else if (tagName.equals(TAG_MIME_GROUP)) {
                    final Pair<String, Set<String>> groupToMimeTypes = readMimeGroupLPw(parser);
                    if (groupToMimeTypes != null) {
                        packageSetting.addMimeTypes(groupToMimeTypes.first,
                                groupToMimeTypes.second);
                    }
                } else if (tagName.equals(TAG_USES_STATIC_LIB)) {
                    readUsesStaticLibLPw(parser, packageSetting);
                } else if (tagName.equals(TAG_USES_SDK_LIB)) {
                    readUsesSdkLibLPw(parser, packageSetting);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element under <package>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
            if (firstInstallTime != 0) {
                originalFirstInstallTimes.put(packageSetting.getPackageName(), firstInstallTime);
            }
        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }

    /**
     * The attribute "appId" was historically called "userId".
     * TODO(b/235381248): Fix it when we solve tooling compatibility issues
     */
    private static int parseAppId(TypedXmlPullParser parser) {
        return parser.getAttributeInt(null, "userId", 0);
    }

    /**
     * The attribute "sharedUserAppId" was historically called "sharedUserId".
     * TODO(b/235381248): Fix it when we solve tooling compatibility issues
     */
    private static int parseSharedUserAppId(TypedXmlPullParser parser) {
        return parser.getAttributeInt(null, "sharedUserId", 0);
    }

    void addInstallerPackageNames(InstallSource installSource) {
        if (installSource.mInstallerPackageName != null) {
            mInstallerPackages.add(installSource.mInstallerPackageName);
        }
        if (installSource.mInitiatingPackageName != null) {
            mInstallerPackages.add(installSource.mInitiatingPackageName);
        }
        if (installSource.mOriginatingPackageName != null) {
            mInstallerPackages.add(installSource.mOriginatingPackageName);
        }
    }

    @Nullable
    private Pair<String, Set<String>> readMimeGroupLPw(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String groupName = parser.getAttributeValue(null, ATTR_NAME);
        if (groupName == null) {
            XmlUtils.skipCurrentTag(parser);
            return null;
        }

        Set<String> mimeTypes = new ArraySet<>();
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_MIME_TYPE)) {
                String typeName = parser.getAttributeValue(null, ATTR_VALUE);
                if (typeName != null) {
                    mimeTypes.add(typeName);
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <mime-group>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }

        return Pair.create(groupName, mimeTypes);
    }

    private void writeMimeGroupLPr(TypedXmlSerializer serializer,
            Map<String, Set<String>> mimeGroups) throws IOException {
        if (mimeGroups == null) {
            return;
        }

        for (String mimeGroup: mimeGroups.keySet()) {
            serializer.startTag(null, TAG_MIME_GROUP);
            serializer.attribute(null, ATTR_NAME, mimeGroup);

            for (String mimeType: mimeGroups.get(mimeGroup)) {
                serializer.startTag(null, TAG_MIME_TYPE);
                serializer.attribute(null, ATTR_VALUE, mimeType);
                serializer.endTag(null, TAG_MIME_TYPE);
            }

            serializer.endTag(null, TAG_MIME_GROUP);
        }
    }

    private void readDisabledComponentsLPw(PackageSetting packageSetting, TypedXmlPullParser parser,
            int userId) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (name != null) {
                    packageSetting.addDisabledComponent(name.intern(), userId);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <disabled-components> has"
                                    + " no name at " + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <disabled-components>: " + parser.getName());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readEnabledComponentsLPw(PackageSetting packageSetting, TypedXmlPullParser parser,
            int userId) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (name != null) {
                    packageSetting.addEnabledComponent(name.intern(), userId);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <enabled-components> has"
                                    + " no name at " + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <enabled-components>: " + parser.getName());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readSharedUserLPw(TypedXmlPullParser parser, List<UserInfo> users)
            throws XmlPullParserException, IOException {
        String name = null;
        int pkgFlags = 0;
        int pkgPrivateFlags = 0;
        SharedUserSetting su = null;
        {
            name = parser.getAttributeValue(null, ATTR_NAME);
            int appId = parseAppId(parser);
            if (parser.getAttributeBoolean(null, "system", false)) {
                pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
            }
            if (name == null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <shared-user> has no name at "
                                + parser.getPositionDescription());
            } else if (appId == 0) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: shared-user " + name
                                + " has bad appId " + appId + " at "
                                + parser.getPositionDescription());
            } else {
                if ((su = addSharedUserLPw(name.intern(), appId, pkgFlags, pkgPrivateFlags))
                        == null) {
                    PackageManagerService
                            .reportSettingsProblem(Log.ERROR, "Occurred while parsing settings at "
                                    + parser.getPositionDescription());
                }
            }
        }

        if (su != null) {
            int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("sigs")) {
                    su.signatures.readXml(parser, mPastSignatures.untrackedStorage());
                } else if (tagName.equals("perms")) {
                    readInstallPermissionsLPr(parser, su.getLegacyPermissionState(), users);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element under <shared-user>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }

    void createNewUserLI(@NonNull PackageManagerService service, @NonNull Installer installer,
            @UserIdInt int userHandle, @Nullable Set<String> userTypeInstallablePackages,
            String[] disallowedPackages) {
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                Trace.TRACE_TAG_PACKAGE_MANAGER);
        t.traceBegin("createNewUser-" + userHandle);
        Installer.Batch batch = new Installer.Batch();
        final boolean skipPackageAllowList = userTypeInstallablePackages == null;
        synchronized (mLock) {
            final int size = mPackages.size();
            for (int i = 0; i < size; i++) {
                final PackageSetting ps = mPackages.valueAt(i);
                if (ps.getPkg() == null) {
                    continue;
                }
                final boolean shouldMaybeInstall = ps.isSystem() &&
                        !ArrayUtils.contains(disallowedPackages, ps.getPackageName()) &&
                        !ps.getPkgState().isHiddenUntilInstalled();
                final boolean shouldReallyInstall = shouldMaybeInstall &&
                        (skipPackageAllowList || userTypeInstallablePackages.contains(
                                ps.getPackageName()));
                // Only system apps are initially installed.
                ps.setInstalled(shouldReallyInstall, userHandle);

                // Non-Apex system apps, that are not included in the allowlist in
                // initialNonStoppedSystemPackages, should be marked as stopped by default.
                boolean shouldBeStopped = service.mShouldStopSystemPackagesByDefault
                        && ps.isSystem()
                        && !ps.isApex()
                        && !service.mInitialNonStoppedSystemPackages.contains(ps.getPackageName());
                if (shouldBeStopped) {
                    final Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
                    launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    launcherIntent.setPackage(ps.getPackageName());
                    final List<ResolveInfo> launcherActivities =
                            service.snapshotComputer().queryIntentActivitiesInternal(launcherIntent,
                                    null,
                                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, 0);
                    if (launcherActivities.isEmpty()) {
                        shouldBeStopped = false;
                    }
                }
                ps.setStopped(shouldBeStopped, userHandle);

                // If userTypeInstallablePackages is the *only* reason why we're not installing,
                // then uninstallReason is USER_TYPE. If there's a different reason, or if we
                // actually are installing, put UNKNOWN.
                final int uninstallReason = (shouldMaybeInstall && !shouldReallyInstall) ?
                        UNINSTALL_REASON_USER_TYPE : UNINSTALL_REASON_UNKNOWN;
                ps.setUninstallReason(uninstallReason, userHandle);
                if (shouldReallyInstall) {
                    if (ps.getAppId() < 0) {
                        // No need to create data directories for packages with invalid app id
                        // such as APEX
                        continue;
                    }
                    // We need to create the DE data directory for all apps installed for this user.
                    // (CE storage is not ready yet; the CE data directories will be created later,
                    // when the user is "unlocked".)  Accumulate all required args, and call the
                    // installer after the mPackages lock has been released.
                    final String seInfo = ps.getSeInfo();
                    final boolean usesSdk = !ps.getPkg().getUsesSdkLibraries().isEmpty();
                    final CreateAppDataArgs args = Installer.buildCreateAppDataArgs(
                            ps.getVolumeUuid(), ps.getPackageName(), userHandle,
                            StorageManager.FLAG_STORAGE_DE, ps.getAppId(), seInfo,
                            ps.getPkg().getTargetSdkVersion(), usesSdk);
                    batch.createAppData(args);
                } else {
                    // Make sure the app is excluded from storage mapping for this user
                    writeKernelMappingLPr(ps);
                }
            }
        }
        t.traceBegin("createAppData");
        try {
            batch.execute(installer);
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to prepare app data", e);
        }
        t.traceEnd(); // createAppData
        synchronized (mLock) {
            applyDefaultPreferredAppsLPw(userHandle);
        }
        t.traceEnd(); // createNewUser
    }

    void removeUserLPw(int userId) {
        Set<Entry<String, PackageSetting>> entries = mPackages.entrySet();
        for (Entry<String, PackageSetting> entry : entries) {
            entry.getValue().removeUser(userId);
        }
        mPreferredActivities.remove(userId);

        synchronized (mPackageRestrictionsLock) {
            getUserPackagesStateFile(userId).delete();
            mPendingAsyncPackageRestrictionsWrites.delete(userId);
        }

        removeCrossProfileIntentFiltersLPw(userId);

        mRuntimePermissionsPersistence.onUserRemoved(userId);
        mDomainVerificationManager.clearUser(userId);

        writePackageListLPr();

        // Inform kernel that the user was removed, so that packages are marked uninstalled
        // for sdcardfs
        writeKernelRemoveUserLPr(userId);
    }

    void removeCrossProfileIntentFiltersLPw(int userId) {
        synchronized (mCrossProfileIntentResolvers) {
            // userId is the source user
            if (mCrossProfileIntentResolvers.get(userId) != null) {
                mCrossProfileIntentResolvers.remove(userId);
                writePackageRestrictionsLPr(userId);
            }
            // userId is the target user
            int count = mCrossProfileIntentResolvers.size();
            for (int i = 0; i < count; i++) {
                int sourceUserId = mCrossProfileIntentResolvers.keyAt(i);
                CrossProfileIntentResolver cpir = mCrossProfileIntentResolvers.get(sourceUserId);
                boolean needsWriting = false;
                ArraySet<CrossProfileIntentFilter> cpifs =
                        new ArraySet<CrossProfileIntentFilter>(cpir.filterSet());
                for (CrossProfileIntentFilter cpif : cpifs) {
                    if (cpif.getTargetUserId() == userId) {
                        needsWriting = true;
                        cpir.removeFilter(cpif);
                    }
                }
                if (needsWriting) {
                    writePackageRestrictionsLPr(sourceUserId);
                }
            }
        }
    }

    public VerifierDeviceIdentity getVerifierDeviceIdentityLPw(@NonNull Computer computer) {
        if (mVerifierDeviceIdentity == null) {
            mVerifierDeviceIdentity = VerifierDeviceIdentity.generate();

            writeLPr(computer, /*sync=*/false);
        }

        return mVerifierDeviceIdentity;
    }

    /**
     * Returns the disabled {@link PackageSetting} for the provided package name if one exists,
     * {@code null} otherwise.
     */
    @Nullable
    public PackageSetting getDisabledSystemPkgLPr(String name) {
        PackageSetting ps = mDisabledSysPackages.get(name);
        return ps;
    }

    /**
     * Returns the disabled {@link PackageSetting} for the provided enabled {@link PackageSetting}
     * if one exists, {@code null} otherwise.
     */
    @Nullable
    public PackageSetting getDisabledSystemPkgLPr(PackageSetting enabledPackageSetting) {
        if (enabledPackageSetting == null) {
            return null;
        }
        return getDisabledSystemPkgLPr(enabledPackageSetting.getPackageName());
    }

    int getApplicationEnabledSettingLPr(String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        final PackageSetting pkg = mPackages.get(packageName);
        if (pkg == null) {
            throw new PackageManager.NameNotFoundException(packageName);
        }
        return pkg.getEnabled(userId);
    }

    int getComponentEnabledSettingLPr(ComponentName componentName, int userId)
            throws PackageManager.NameNotFoundException {
        final String packageName = componentName.getPackageName();
        final PackageSetting pkg = mPackages.get(packageName);
        if (pkg == null) {
            throw new PackageManager.NameNotFoundException(componentName.getPackageName());
        }
        final String classNameStr = componentName.getClassName();
        return pkg.getCurrentEnabledStateLPr(classNameStr, userId);
    }

    SharedUserSetting getSharedUserSettingLPr(String packageName) {
        final PackageSetting ps = mPackages.get(packageName);
        return getSharedUserSettingLPr(ps);
    }

    @Nullable
    SharedUserSetting getSharedUserSettingLPr(PackageSetting ps) {
        if (ps == null || !ps.hasSharedUser()) {
            return null;
        }
        return (SharedUserSetting) getSettingLPr(ps.getSharedUserAppId());
    }

    /**
     * Returns all users on the device, including pre-created and dying users.
     *
     * @param userManager UserManagerService instance
     * @return the list of users
     */
    private static List<UserInfo> getAllUsers(UserManagerService userManager) {
        return getUsers(userManager, /* excludeDying= */ false, /* excludePreCreated= */ false);
    }

    /**
     * Returns the list of users on the device, excluding pre-created ones.
     *
     * @param userManager UserManagerService instance
     * @param excludeDying Indicates whether to exclude any users marked for deletion.
     *
     * @return the list of users
     */
    private static List<UserInfo> getActiveUsers(UserManagerService userManager,
            boolean excludeDying) {
        return getUsers(userManager, excludeDying, /* excludePreCreated= */ true);
    }

    /**
     * Returns the list of users on the device.
     *
     * @param userManager UserManagerService instance
     * @param excludeDying Indicates whether to exclude any users marked for deletion.
     * @param excludePreCreated Indicates whether to exclude any pre-created users.
     *
     * @return the list of users
     */
    private static List<UserInfo> getUsers(UserManagerService userManager, boolean excludeDying,
            boolean excludePreCreated) {
        final long id = Binder.clearCallingIdentity();
        try {
            return userManager.getUsers(/* excludePartial= */ true, excludeDying,
                    excludePreCreated);
        } catch (NullPointerException npe) {
            // packagemanager not yet initialized
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        return null;
    }

    /**
     * Return all {@link PackageSetting} that are actively installed on the
     * given {@link VolumeInfo#fsUuid}.
     */
    List<? extends PackageStateInternal> getVolumePackagesLPr(String volumeUuid) {
        ArrayList<PackageStateInternal> res = new ArrayList<>();
        for (int i = 0; i < mPackages.size(); i++) {
            final PackageSetting setting = mPackages.valueAt(i);
            if (Objects.equals(volumeUuid, setting.getVolumeUuid())) {
                res.add(setting);
            }
        }
        return res;
    }

    static void printFlags(PrintWriter pw, int val, Object[] spec) {
        pw.print("[ ");
        for (int i=0; i<spec.length; i+=2) {
            int mask = (Integer)spec[i];
            if ((val & mask) != 0) {
                pw.print(spec[i+1]);
                pw.print(" ");
            }
        }
        pw.print("]");
    }

    static final Object[] FLAG_DUMP_SPEC = new Object[] {
        ApplicationInfo.FLAG_SYSTEM, "SYSTEM",
        ApplicationInfo.FLAG_DEBUGGABLE, "DEBUGGABLE",
        ApplicationInfo.FLAG_HAS_CODE, "HAS_CODE",
        ApplicationInfo.FLAG_PERSISTENT, "PERSISTENT",
        ApplicationInfo.FLAG_FACTORY_TEST, "FACTORY_TEST",
        ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING, "ALLOW_TASK_REPARENTING",
        ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA, "ALLOW_CLEAR_USER_DATA",
        ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, "UPDATED_SYSTEM_APP",
        ApplicationInfo.FLAG_TEST_ONLY, "TEST_ONLY",
        ApplicationInfo.FLAG_VM_SAFE_MODE, "VM_SAFE_MODE",
        ApplicationInfo.FLAG_ALLOW_BACKUP, "ALLOW_BACKUP",
        ApplicationInfo.FLAG_KILL_AFTER_RESTORE, "KILL_AFTER_RESTORE",
        ApplicationInfo.FLAG_RESTORE_ANY_VERSION, "RESTORE_ANY_VERSION",
        ApplicationInfo.FLAG_EXTERNAL_STORAGE, "EXTERNAL_STORAGE",
        ApplicationInfo.FLAG_LARGE_HEAP, "LARGE_HEAP",
    };

    private static final Object[] PRIVATE_FLAG_DUMP_SPEC = new Object[] {
            ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE",
            ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION",
            ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE",
            ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE, "ALLOW_AUDIO_PLAYBACK_CAPTURE",
            ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE, "PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE",
            ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND, "BACKUP_IN_FOREGROUND",
            ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE, "CANT_SAVE_STATE",
            ApplicationInfo.PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE, "DEFAULT_TO_DEVICE_PROTECTED_STORAGE",
            ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE, "DIRECT_BOOT_AWARE",
            ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS, "HAS_DOMAIN_URLS",
            ApplicationInfo.PRIVATE_FLAG_HIDDEN, "HIDDEN",
            ApplicationInfo.PRIVATE_FLAG_INSTANT, "EPHEMERAL",
            ApplicationInfo.PRIVATE_FLAG_ISOLATED_SPLIT_LOADING, "ISOLATED_SPLIT_LOADING",
            ApplicationInfo.PRIVATE_FLAG_OEM, "OEM",
            ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE, "PARTIALLY_DIRECT_BOOT_AWARE",
            ApplicationInfo.PRIVATE_FLAG_PRIVILEGED, "PRIVILEGED",
            ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER, "REQUIRED_FOR_SYSTEM_USER",
            ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY, "STATIC_SHARED_LIBRARY",
            ApplicationInfo.PRIVATE_FLAG_VENDOR, "VENDOR",
            ApplicationInfo.PRIVATE_FLAG_PRODUCT, "PRODUCT",
            ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT, "SYSTEM_EXT",
            ApplicationInfo.PRIVATE_FLAG_VIRTUAL_PRELOAD, "VIRTUAL_PRELOAD",
            ApplicationInfo.PRIVATE_FLAG_ODM, "ODM",
            ApplicationInfo.PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING, "PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING",
            ApplicationInfo.PRIVATE_FLAG_HAS_FRAGILE_USER_DATA, "PRIVATE_FLAG_HAS_FRAGILE_USER_DATA",
    };

    void dumpVersionLPr(IndentingPrintWriter pw) {
        pw.increaseIndent();
        for (int i= 0; i < mVersion.size(); i++) {
            final String volumeUuid = mVersion.keyAt(i);
            final VersionInfo ver = mVersion.valueAt(i);
            if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, volumeUuid)) {
                pw.println("Internal:");
            } else if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, volumeUuid)) {
                pw.println("External:");
            } else {
                pw.println("UUID " + volumeUuid + ":");
            }
            pw.increaseIndent();
            pw.printPair("sdkVersion", ver.sdkVersion);
            pw.printPair("databaseVersion", ver.databaseVersion);
            pw.println();
            pw.printPair("buildFingerprint", ver.buildFingerprint);
            pw.printPair("fingerprint", ver.fingerprint);
            pw.println();
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    @NeverCompile // Avoid size overhead of debugging code.
    void dumpPackageLPr(PrintWriter pw, String prefix, String checkinTag,
            ArraySet<String> permissionNames, PackageSetting ps,
            LegacyPermissionState permissionsState, SimpleDateFormat sdf, Date date,
            List<UserInfo> users, boolean dumpAll, boolean dumpAllComponents) {
        AndroidPackage pkg = ps.getPkg();
        if (checkinTag != null) {
            pw.print(checkinTag);
            pw.print(",");
            pw.print(ps.getRealName() != null ? ps.getRealName() : ps.getPackageName());
            pw.print(",");
            pw.print(ps.getAppId());
            pw.print(",");
            pw.print(ps.getVersionCode());
            pw.print(",");
            pw.print(ps.getLastUpdateTime());
            pw.print(",");
            pw.print(ps.getInstallSource().mInstallerPackageName != null
                    ? ps.getInstallSource().mInstallerPackageName : "?");
            pw.print(ps.getInstallSource().mInstallerPackageUid);
            pw.print(ps.getInstallSource().mUpdateOwnerPackageName != null
                    ? ps.getInstallSource().mUpdateOwnerPackageName : "?");
            pw.print(ps.getInstallSource().mInstallerAttributionTag != null
                    ? "(" + ps.getInstallSource().mInstallerAttributionTag + ")" : "");
            pw.print(",");
            pw.print(ps.getInstallSource().mPackageSource);
            pw.println();
            if (pkg != null) {
                pw.print(checkinTag); pw.print("-"); pw.print("splt,");
                pw.print("base,");
                pw.println(pkg.getBaseRevisionCode());
                int[] splitRevisionCodes = pkg.getSplitRevisionCodes();
                for (int i = 0; i < pkg.getSplitNames().length; i++) {
                    pw.print(checkinTag); pw.print("-"); pw.print("splt,");
                    pw.print(pkg.getSplitNames()[i]); pw.print(",");
                    pw.println(splitRevisionCodes[i]);
                }
            }
            for (UserInfo user : users) {
                final PackageUserStateInternal userState = ps.getUserStateOrDefault(user.id);
                pw.print(checkinTag);
                pw.print("-");
                pw.print("usr");
                pw.print(",");
                pw.print(user.id);
                pw.print(",");
                pw.print(userState.isInstalled() ? "I" : "i");
                pw.print(userState.isHidden() ? "B" : "b");
                pw.print(userState.isSuspended() ? "SU" : "su");
                pw.print(userState.isStopped() ? "S" : "s");
                pw.print(userState.isNotLaunched() ? "l" : "L");
                pw.print(userState.isInstantApp() ? "IA" : "ia");
                pw.print(userState.isVirtualPreload() ? "VPI" : "vpi");
                pw.print(userState.isQuarantined() ? "Q" : "q");
                String harmfulAppWarning = userState.getHarmfulAppWarning();
                pw.print(harmfulAppWarning != null ? "HA" : "ha");
                pw.print(",");
                pw.print(userState.getEnabledState());
                String lastDisabledAppCaller = userState.getLastDisableAppCaller();
                pw.print(",");
                pw.print(lastDisabledAppCaller != null ? lastDisabledAppCaller : "?");
                pw.print(",");
                pw.print(ps.readUserState(user.id).getFirstInstallTimeMillis());
                pw.print(",");
                pw.println();
            }
            return;
        }

        pw.print(prefix); pw.print("Package [");
            pw.print(ps.getRealName() != null ? ps.getRealName() : ps.getPackageName());
            pw.print("] (");
            pw.print(Integer.toHexString(System.identityHashCode(ps)));
            pw.println("):");

        if (ps.getRealName() != null) {
            pw.print(prefix); pw.print("  compat name=");
            pw.println(ps.getPackageName());
        }

        pw.print(prefix); pw.print("  appId="); pw.println(ps.getAppId());

        SharedUserSetting sharedUserSetting = getSharedUserSettingLPr(ps);
        if (sharedUserSetting != null) {
            pw.print(prefix); pw.print("  sharedUser="); pw.println(sharedUserSetting);
        }
        pw.print(prefix); pw.print("  pkg="); pw.println(pkg);
        pw.print(prefix); pw.print("  codePath="); pw.println(ps.getPathString());
        if (permissionNames == null) {
            pw.print(prefix); pw.print("  resourcePath="); pw.println(ps.getPathString());
            pw.print(prefix); pw.print("  legacyNativeLibraryDir=");
            pw.println(ps.getLegacyNativeLibraryPath());
            pw.print(prefix); pw.print("  extractNativeLibs=");
            pw.println((ps.getFlags() & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0
                    ? "true" : "false");
            pw.print(prefix); pw.print("  primaryCpuAbi="); pw.println(ps.getPrimaryCpuAbiLegacy());
            pw.print(prefix); pw.print("  secondaryCpuAbi="); pw.println(ps.getSecondaryCpuAbiLegacy());
            pw.print(prefix); pw.print("  cpuAbiOverride="); pw.println(ps.getCpuAbiOverride());
        }
        pw.print(prefix); pw.print("  versionCode="); pw.print(ps.getVersionCode());
        if (pkg != null) {
            pw.print(" minSdk=");
            pw.print(pkg.getMinSdkVersion());
        }
        pw.print(" targetSdk="); pw.println(ps.getTargetSdkVersion());
        if (pkg != null) {
            SparseIntArray minExtensionVersions = pkg.getMinExtensionVersions();

            pw.print(prefix); pw.print("  minExtensionVersions=[");
            if (minExtensionVersions != null) {
                List<String> minExtVerStrings = new ArrayList<>();
                int size = minExtensionVersions.size();
                for (int index = 0; index < size; index++) {
                    int key = minExtensionVersions.keyAt(index);
                    int value = minExtensionVersions.valueAt(index);
                    minExtVerStrings.add(key + "=" + value);
                }

                pw.print(TextUtils.join(", ", minExtVerStrings));
            }
            pw.print("]");
        }
        pw.println();
        if (pkg != null) {
            pw.print(prefix); pw.print("  versionName="); pw.println(pkg.getVersionName());
            pw.print(prefix); pw.print("  usesNonSdkApi="); pw.println(pkg.isNonSdkApiRequested());
            pw.print(prefix); pw.print("  splits="); dumpSplitNames(pw, pkg); pw.println();
            final int apkSigningVersion = pkg.getSigningDetails().getSignatureSchemeVersion();
            pw.print(prefix); pw.print("  apkSigningVersion="); pw.println(apkSigningVersion);
            pw.print(prefix); pw.print("  flags=");
            printFlags(pw, PackageInfoUtils.appInfoFlags(pkg, ps), FLAG_DUMP_SPEC); pw.println();
            int privateFlags = PackageInfoUtils.appInfoPrivateFlags(pkg, ps);
            if (privateFlags != 0) {
                pw.print(prefix); pw.print("  privateFlags="); printFlags(pw,
                        privateFlags, PRIVATE_FLAG_DUMP_SPEC); pw.println();
            }
            if (pkg.hasPreserveLegacyExternalStorage()) {
                pw.print(prefix); pw.print("  hasPreserveLegacyExternalStorage=true");
                pw.println();
            }
            pw.print(prefix); pw.print("  forceQueryable=");
            pw.print(ps.getPkg().isForceQueryable());
            if (ps.isForceQueryableOverride()) {
                pw.print(" (override=true)");
            }
            pw.println();
            if (!ps.getPkg().getQueriesPackages().isEmpty()) {
                pw.append(prefix).append("  queriesPackages=")
                        .println(ps.getPkg().getQueriesPackages());
            }
            if (!ps.getPkg().getQueriesIntents().isEmpty()) {
                pw.append(prefix).append("  queriesIntents=")
                        .println(ps.getPkg().getQueriesIntents());
            }
            pw.print(prefix); pw.print("  supportsScreens=[");
            boolean first = true;
            if (pkg.isSmallScreensSupported()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("small");
            }
            if (pkg.isNormalScreensSupported()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("medium");
            }
            if (pkg.isLargeScreensSupported()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("large");
            }
            if (pkg.isExtraLargeScreensSupported()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("xlarge");
            }
            if (pkg.isResizeable()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("resizeable");
            }
            if (pkg.isAnyDensity()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("anyDensity");
            }
            pw.println("]");
            final List<String> libraryNames = pkg.getLibraryNames();
            if (libraryNames != null && libraryNames.size() > 0) {
                pw.print(prefix); pw.println("  dynamic libraries:");
                for (int i = 0; i< libraryNames.size(); i++) {
                    pw.print(prefix); pw.print("    ");
                            pw.println(libraryNames.get(i));
                }
            }
            if (pkg.getStaticSharedLibraryName() != null) {
                pw.print(prefix); pw.println("  static library:");
                pw.print(prefix); pw.print("    ");
                pw.print("name:"); pw.print(pkg.getStaticSharedLibraryName());
                pw.print(" version:"); pw.println(pkg.getStaticSharedLibraryVersion());
            }

            if (pkg.getSdkLibraryName() != null) {
                pw.print(prefix); pw.println("  SDK library:");
                pw.print(prefix); pw.print("    ");
                pw.print("name:"); pw.print(pkg.getSdkLibraryName());
                pw.print(" versionMajor:"); pw.println(pkg.getSdkLibVersionMajor());
            }

            List<String> usesLibraries = pkg.getUsesLibraries();
            if (usesLibraries.size() > 0) {
                pw.print(prefix); pw.println("  usesLibraries:");
                for (int i=0; i< usesLibraries.size(); i++) {
                    pw.print(prefix); pw.print("    "); pw.println(usesLibraries.get(i));
                }
            }

            List<String> usesStaticLibraries = pkg.getUsesStaticLibraries();
            long[] usesStaticLibrariesVersions = pkg.getUsesStaticLibrariesVersions();
            if (usesStaticLibraries.size() > 0) {
                pw.print(prefix); pw.println("  usesStaticLibraries:");
                for (int i=0; i< usesStaticLibraries.size(); i++) {
                    pw.print(prefix); pw.print("    ");
                    pw.print(usesStaticLibraries.get(i)); pw.print(" version:");
                            pw.println(usesStaticLibrariesVersions[i]);
                }
            }

            List<String> usesSdkLibraries = pkg.getUsesSdkLibraries();
            long[] usesSdkLibrariesVersionsMajor = pkg.getUsesSdkLibrariesVersionsMajor();
            if (usesSdkLibraries.size() > 0) {
                pw.print(prefix); pw.println("  usesSdkLibraries:");
                for (int i = 0, size = usesSdkLibraries.size(); i < size; ++i) {
                    pw.print(prefix); pw.print("    ");
                    pw.print(usesSdkLibraries.get(i)); pw.print(" version:");
                    pw.println(usesSdkLibrariesVersionsMajor[i]);
                }
            }

            List<String> usesOptionalLibraries = pkg.getUsesOptionalLibraries();
            if (usesOptionalLibraries.size() > 0) {
                pw.print(prefix); pw.println("  usesOptionalLibraries:");
                for (int i=0; i< usesOptionalLibraries.size(); i++) {
                    pw.print(prefix); pw.print("    ");
                    pw.println(usesOptionalLibraries.get(i));
                }
            }

            List<String> usesNativeLibraries = pkg.getUsesNativeLibraries();
            if (usesNativeLibraries.size() > 0) {
                pw.print(prefix); pw.println("  usesNativeLibraries:");
                for (int i=0; i< usesNativeLibraries.size(); i++) {
                    pw.print(prefix); pw.print("    "); pw.println(usesNativeLibraries.get(i));
                }
            }

            List<String> usesOptionalNativeLibraries = pkg.getUsesOptionalNativeLibraries();
            if (usesOptionalNativeLibraries.size() > 0) {
                pw.print(prefix); pw.println("  usesOptionalNativeLibraries:");
                for (int i=0; i< usesOptionalNativeLibraries.size(); i++) {
                    pw.print(prefix); pw.print("    ");
                    pw.println(usesOptionalNativeLibraries.get(i));
                }
            }

            List<String> usesLibraryFiles = ps.getPkgState().getUsesLibraryFiles();
            if (usesLibraryFiles.size() > 0) {
                pw.print(prefix); pw.println("  usesLibraryFiles:");
                for (int i=0; i< usesLibraryFiles.size(); i++) {
                    pw.print(prefix); pw.print("    "); pw.println(usesLibraryFiles.get(i));
                }
            }
            final Map<String, ParsedProcess> procs = pkg.getProcesses();
            if (!procs.isEmpty()) {
                pw.print(prefix); pw.println("  processes:");
                for (ParsedProcess proc : procs.values()) {
                    pw.print(prefix); pw.print("    "); pw.println(proc.getName());
                    if (proc.getDeniedPermissions() != null) {
                        for (String deniedPermission : proc.getDeniedPermissions()) {
                            pw.print(prefix); pw.print("      deny: ");
                            pw.println(deniedPermission);
                        }
                    }
                }
            }
        }
        pw.print(prefix); pw.print("  timeStamp=");
        date.setTime(ps.getLastModifiedTime());
        pw.println(sdf.format(date));
        pw.print(prefix); pw.print("  lastUpdateTime=");
        date.setTime(ps.getLastUpdateTime());
        pw.println(sdf.format(date));
        pw.print(prefix); pw.print("  installerPackageName=");
        pw.println(ps.getInstallSource().mInstallerPackageName);
        pw.print(prefix); pw.print("  installerPackageUid=");
        pw.println(ps.getInstallSource().mInstallerPackageUid);
        pw.print(prefix); pw.print("  initiatingPackageName=");
        pw.println(ps.getInstallSource().mInitiatingPackageName);
        pw.print(prefix); pw.print("  originatingPackageName=");
        pw.println(ps.getInstallSource().mOriginatingPackageName);

        if (ps.getInstallSource().mUpdateOwnerPackageName != null) {
            pw.print(prefix); pw.print("  updateOwnerPackageName=");
            pw.println(ps.getInstallSource().mUpdateOwnerPackageName);
        }
        if (ps.getInstallSource().mInstallerAttributionTag != null) {
            pw.print(prefix); pw.print("  installerAttributionTag=");
            pw.println(ps.getInstallSource().mInstallerAttributionTag);
        }
        pw.print(prefix); pw.print("  packageSource=");
        pw.println(ps.getInstallSource().mPackageSource);
        if (ps.isIncremental()) {
            pw.print(prefix); pw.println("  loadingProgress=" +
                    (int) (ps.getLoadingProgress() * 100) + "%");
            date.setTime(ps.getLoadingCompletedTime());
            pw.print(prefix); pw.println("  loadingCompletedTime=" + sdf.format(date));
        }
        pw.print(prefix); pw.print("  appMetadataFilePath=");
        pw.println(ps.getAppMetadataFilePath());
        if (ps.getVolumeUuid() != null) {
            pw.print(prefix); pw.print("  volumeUuid=");
                    pw.println(ps.getVolumeUuid());
        }
        pw.print(prefix); pw.print("  signatures="); pw.println(ps.getSignatures());
        pw.print(prefix); pw.print("  installPermissionsFixed=");
                pw.print(ps.isInstallPermissionsFixed());
                pw.println();
        pw.print(prefix); pw.print("  pkgFlags="); printFlags(pw, ps.getFlags(), FLAG_DUMP_SPEC);
                pw.println();
        pw.print(prefix); pw.print("  privatePkgFlags="); printFlags(pw, ps.getPrivateFlags(),
                PRIVATE_FLAG_DUMP_SPEC);
        pw.println();
        pw.print(prefix); pw.print("  apexModuleName="); pw.println(ps.getApexModuleName());

        if (pkg != null && pkg.getOverlayTarget() != null) {
            pw.print(prefix); pw.print("  overlayTarget="); pw.println(pkg.getOverlayTarget());
            pw.print(prefix); pw.print("  overlayCategory="); pw.println(pkg.getOverlayCategory());
        }

        if (pkg != null && !pkg.getPermissions().isEmpty()) {
            final List<ParsedPermission> perms = pkg.getPermissions();
            pw.print(prefix); pw.println("  declared permissions:");
            for (int i=0; i<perms.size(); i++) {
                ParsedPermission perm = perms.get(i);
                if (permissionNames != null
                        && !permissionNames.contains(perm.getName())) {
                    continue;
                }
                pw.print(prefix); pw.print("    "); pw.print(perm.getName());
                pw.print(": prot=");
                pw.print(PermissionInfo.protectionToString(perm.getProtectionLevel()));
                if ((perm.getFlags() &PermissionInfo.FLAG_COSTS_MONEY) != 0) {
                    pw.print(", COSTS_MONEY");
                }
                if ((perm.getFlags() &PermissionInfo.FLAG_REMOVED) != 0) {
                    pw.print(", HIDDEN");
                }
                if ((perm.getFlags() &PermissionInfo.FLAG_INSTALLED) != 0) {
                    pw.print(", INSTALLED");
                }
                pw.println();
            }
        }

        if ((permissionNames != null || dumpAll) && pkg != null
                && pkg.getRequestedPermissions() != null
                && pkg.getRequestedPermissions().size() > 0) {
            final Set<String> perms = pkg.getRequestedPermissions();
            pw.print(prefix); pw.println("  requested permissions:");
            for (String perm : perms) {
                if (permissionNames != null
                        && !permissionNames.contains(perm)) {
                    continue;
                }
                pw.print(prefix); pw.print("    "); pw.println(perm);
            }
        }

        if (!ps.hasSharedUser() || permissionNames != null || dumpAll) {
            dumpInstallPermissionsLPr(pw, prefix + "  ", permissionNames, permissionsState, users);
        }

        if (dumpAllComponents) {
            dumpComponents(pw, prefix + "  ", ps);
        }

        for (UserInfo user : users) {
            final PackageUserStateInternal userState = ps.getUserStateOrDefault(user.id);
            pw.print(prefix); pw.print("  User "); pw.print(user.id); pw.print(": ");
            pw.print("ceDataInode=");
            pw.print(userState.getCeDataInode());
            pw.print(" deDataInode=");
            pw.print(userState.getDeDataInode());
            pw.print(" installed=");
            pw.print(userState.isInstalled());
            pw.print(" hidden=");
            pw.print(userState.isHidden());
            pw.print(" suspended=");
            pw.print(userState.isSuspended());
            pw.print(" distractionFlags=");
            pw.print(userState.getDistractionFlags());
            pw.print(" stopped=");
            pw.print(userState.isStopped());
            pw.print(" notLaunched=");
            pw.print(userState.isNotLaunched());
            pw.print(" enabled=");
            pw.print(userState.getEnabledState());
            pw.print(" instant=");
            pw.print(userState.isInstantApp());
            pw.print(" virtual=");
            pw.print(userState.isVirtualPreload());
            pw.print(" quarantined=");
            pw.print(userState.isQuarantined());

            // Dump install state with additional indentation on their own lines.
            pw.println();
            pw.print("      installReason=");
            pw.println(userState.getInstallReason());

            File dataDir = PackageInfoUtils.getDataDir(ps, user.id);
            pw.print("      dataDir=");
            pw.println(dataDir == null ? "null" : dataDir.getAbsolutePath());

            final PackageUserStateInternal pus = ps.readUserState(user.id);
            pw.print("      firstInstallTime=");
            date.setTime(pus.getFirstInstallTimeMillis());
            pw.println(sdf.format(date));

            pw.print("      uninstallReason=");
            pw.println(userState.getUninstallReason());

            if (userState.isSuspended()) {
                pw.print(prefix);
                pw.println("  Suspend params:");
                for (int i = 0; i < userState.getSuspendParams().size(); i++) {
                    pw.print(prefix);
                    pw.print("    suspendingPackage=");
                    pw.print(userState.getSuspendParams().keyAt(i));
                    final SuspendParams params = userState.getSuspendParams().valueAt(i);
                    if (params != null) {
                        pw.print(" dialogInfo=");
                        pw.print(params.getDialogInfo());
                        pw.print(" quarantined=");
                        pw.println(params.isQuarantined());
                    }
                    pw.println();
                }
            }

            final OverlayPaths overlayPaths = userState.getOverlayPaths();
            if (overlayPaths != null) {
                if (!overlayPaths.getOverlayPaths().isEmpty()) {
                    pw.print(prefix);
                    pw.println("    overlay paths:");
                    for (String path : overlayPaths.getOverlayPaths()) {
                        pw.print(prefix);
                        pw.print("      ");
                        pw.println(path);
                    }
                }
                if (!overlayPaths.getResourceDirs().isEmpty()) {
                    pw.print(prefix);
                    pw.println("    legacy overlay paths:");
                    for (String path : overlayPaths.getResourceDirs()) {
                        pw.print(prefix);
                        pw.print("      ");
                        pw.println(path);
                    }
                }
            }

            final Map<String, OverlayPaths> sharedLibraryOverlayPaths =
                    userState.getSharedLibraryOverlayPaths();
            if (sharedLibraryOverlayPaths != null) {
                for (Map.Entry<String, OverlayPaths> libOverlayPaths :
                        sharedLibraryOverlayPaths.entrySet()) {
                    final OverlayPaths paths = libOverlayPaths.getValue();
                    if (paths == null) {
                        continue;
                    }
                    if (!paths.getOverlayPaths().isEmpty()) {
                        pw.print(prefix);
                        pw.println("    ");
                        pw.print(libOverlayPaths.getKey());
                        pw.println(" overlay paths:");
                        for (String path : paths.getOverlayPaths()) {
                            pw.print(prefix);
                            pw.print("        ");
                            pw.println(path);
                        }
                    }
                    if (!paths.getResourceDirs().isEmpty()) {
                        pw.print(prefix);
                        pw.println("      ");
                        pw.print(libOverlayPaths.getKey());
                        pw.println(" legacy overlay paths:");
                        for (String path : paths.getResourceDirs()) {
                            pw.print(prefix);
                            pw.print("      ");
                            pw.println(path);
                        }
                    }
                }
            }

            String lastDisabledAppCaller = userState.getLastDisableAppCaller();
            if (lastDisabledAppCaller != null) {
                pw.print(prefix); pw.print("    lastDisabledCaller: ");
                        pw.println(lastDisabledAppCaller);
            }

            if (!ps.hasSharedUser()) {
                dumpGidsLPr(pw, prefix + "    ", mPermissionDataProvider.getGidsForUid(
                        UserHandle.getUid(user.id, ps.getAppId())));
                dumpRuntimePermissionsLPr(pw, prefix + "    ", permissionNames, permissionsState
                        .getPermissionStates(user.id), dumpAll);
            }

            String harmfulAppWarning = userState.getHarmfulAppWarning();
            if (harmfulAppWarning != null) {
                pw.print(prefix); pw.print("      harmfulAppWarning: ");
                pw.println(harmfulAppWarning);
            }

            if (permissionNames == null) {
                WatchedArraySet<String> cmp = userState.getDisabledComponentsNoCopy();
                if (cmp != null && cmp.size() > 0) {
                    pw.print(prefix); pw.println("    disabledComponents:");
                    for (int i = 0; i < cmp.size(); i++) {
                        pw.print(prefix); pw.print("      "); pw.println(cmp.valueAt(i));
                    }
                }
                cmp = userState.getEnabledComponentsNoCopy();
                if (cmp != null && cmp.size() > 0) {
                    pw.print(prefix); pw.println("    enabledComponents:");
                    for (int i = 0; i < cmp.size(); i++) {
                        pw.print(prefix); pw.print("      "); pw.println(cmp.valueAt(i));
                    }
                }
            }
            ArchiveState archiveState = userState.getArchiveState();
            if (archiveState != null) {
                pw.print(archiveState.toString());
            }
        }
    }

    void dumpPackagesLPr(PrintWriter pw, String packageName, ArraySet<String> permissionNames,
            DumpState dumpState, boolean checkin) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        final Date date = new Date();
        boolean printedSomething = false;
        final boolean dumpAllComponents =
                dumpState.isOptionEnabled(DumpState.OPTION_DUMP_ALL_COMPONENTS);
        List<UserInfo> users = getAllUsers(UserManagerService.getInstance());
        for (final PackageSetting ps : mPackages.values()) {
            if (packageName != null && !packageName.equals(ps.getRealName())
                    && !packageName.equals(ps.getPackageName())) {
                continue;
            }
            if (ps.getPkg() != null && ps.getPkg().isApex()
                    && !dumpState.isOptionEnabled(DumpState.OPTION_INCLUDE_APEX)) {
                // Filter APEX packages which will be dumped in the APEX section
                continue;
            }
            final LegacyPermissionState permissionsState =
                    mPermissionDataProvider.getLegacyPermissionState(ps.getAppId());
            if (permissionNames != null
                    && !permissionsState.hasPermissionState(permissionNames)) {
                continue;
            }

            if (!checkin && packageName != null) {
                dumpState.setSharedUser(getSharedUserSettingLPr(ps));
            }

            if (!checkin && !printedSomething) {
                if (dumpState.onTitlePrinted())
                    pw.println();
                pw.println("Packages:");
                printedSomething = true;
            }
            dumpPackageLPr(pw, "  ", checkin ? "pkg" : null, permissionNames, ps, permissionsState,
                    sdf, date, users, packageName != null, dumpAllComponents);
        }

        printedSomething = false;
        if (mRenamedPackages.size() > 0 && permissionNames == null) {
            for (final Map.Entry<String, String> e : mRenamedPackages.entrySet()) {
                if (packageName != null && !packageName.equals(e.getKey())
                        && !packageName.equals(e.getValue())) {
                    continue;
                }
                if (!checkin) {
                    if (!printedSomething) {
                        if (dumpState.onTitlePrinted())
                            pw.println();
                        pw.println("Renamed packages:");
                        printedSomething = true;
                    }
                    pw.print("  ");
                } else {
                    pw.print("ren,");
                }
                pw.print(e.getKey());
                pw.print(checkin ? " -> " : ",");
                pw.println(e.getValue());
            }
        }

        printedSomething = false;
        if (mDisabledSysPackages.size() > 0 && permissionNames == null) {
            for (final PackageSetting ps : mDisabledSysPackages.values()) {
                if (packageName != null && !packageName.equals(ps.getRealName())
                        && !packageName.equals(ps.getPackageName())) {
                    continue;
                }
                if (ps.getPkg() != null && ps.getPkg().isApex()
                        && !dumpState.isOptionEnabled(DumpState.OPTION_INCLUDE_APEX)) {
                    // Filter APEX packages which will be dumped in the APEX section
                    continue;
                }
                if (!checkin && !printedSomething) {
                    if (dumpState.onTitlePrinted())
                        pw.println();
                    pw.println("Hidden system packages:");
                    printedSomething = true;
                }
                final LegacyPermissionState permissionsState =
                        mPermissionDataProvider.getLegacyPermissionState(ps.getAppId());
                dumpPackageLPr(pw, "  ", checkin ? "dis" : null, permissionNames, ps,
                        permissionsState, sdf, date, users, packageName != null, dumpAllComponents);
            }
        }
    }

    void dumpPackagesProto(ProtoOutputStream proto) {
        List<UserInfo> users = getAllUsers(UserManagerService.getInstance());

        final int count = mPackages.size();
        for (int i = 0; i < count; i++) {
            final PackageSetting ps = mPackages.valueAt(i);
            ps.dumpDebug(proto, PackageServiceDumpProto.PACKAGES, users, mPermissionDataProvider);
        }
    }

    void dumpPermissions(PrintWriter pw, String packageName, ArraySet<String> permissionNames,
            DumpState dumpState) {
        LegacyPermissionSettings.dumpPermissions(pw, packageName, permissionNames,
                mPermissionDataProvider.getLegacyPermissions(),
                mPermissionDataProvider.getAllAppOpPermissionPackages(), true, dumpState);
    }

    void dumpSharedUsersLPr(PrintWriter pw, String packageName, ArraySet<String> permissionNames,
            DumpState dumpState, boolean checkin) {
        boolean printedSomething = false;
        for (SharedUserSetting su : mSharedUsers.values()) {
            if (packageName != null && su != dumpState.getSharedUser()) {
                continue;
            }
            final LegacyPermissionState permissionsState =
                    mPermissionDataProvider.getLegacyPermissionState(su.mAppId);
            if (permissionNames != null
                    && !permissionsState.hasPermissionState(permissionNames)) {
                continue;
            }
            if (!checkin) {
                if (!printedSomething) {
                    if (dumpState.onTitlePrinted())
                        pw.println();
                    pw.println("Shared users:");
                    printedSomething = true;
                }

                pw.print("  SharedUser [");
                pw.print(su.name);
                pw.print("] (");
                pw.print(Integer.toHexString(System.identityHashCode(su)));
                pw.println("):");

                String prefix = "    ";
                pw.print(prefix); pw.print("appId="); pw.println(su.mAppId);

                pw.print(prefix); pw.println("Packages");
                final ArraySet<PackageStateInternal> susPackageStates =
                        (ArraySet<PackageStateInternal>) su.getPackageStates();
                final int numPackages = susPackageStates.size();
                for (int i = 0; i < numPackages; i++) {
                    final PackageStateInternal ps = susPackageStates.valueAt(i);
                    if (ps != null) {
                        pw.print(prefix + "  "); pw.println(ps);
                    } else {
                        pw.print(prefix + "  "); pw.println("NULL?!");
                    }
                }

                if (dumpState.isOptionEnabled(DumpState.OPTION_SKIP_PERMISSIONS)) {
                    continue;
                }

                List<UserInfo> users = getAllUsers(UserManagerService.getInstance());

                dumpInstallPermissionsLPr(pw, prefix, permissionNames, permissionsState, users);

                for (UserInfo user : users) {
                    final int userId = user.id;
                    final int[] gids = mPermissionDataProvider.getGidsForUid(UserHandle.getUid(
                            userId, su.mAppId));
                    final Collection<PermissionState> permissions =
                            permissionsState.getPermissionStates(userId);
                    if (!ArrayUtils.isEmpty(gids) || !permissions.isEmpty()) {
                        pw.print(prefix); pw.print("User "); pw.print(userId); pw.println(": ");
                        dumpGidsLPr(pw, prefix + "  ", gids);
                        dumpRuntimePermissionsLPr(pw, prefix + "  ", permissionNames,
                                permissions, packageName != null);
                    }
                }
            } else {
                pw.print("suid,"); pw.print(su.mAppId); pw.print(","); pw.println(su.name);
            }
        }
    }

    void dumpSharedUsersProto(ProtoOutputStream proto) {
        final int count = mSharedUsers.size();
        for (int i = 0; i < count; i++) {
            mSharedUsers.valueAt(i).dumpDebug(proto, PackageServiceDumpProto.SHARED_USERS);
        }
    }

    void dumpReadMessages(PrintWriter pw, DumpState dumpState) {
        pw.println("Settings parse messages:");
        pw.print(mReadMessages.toString());
    }

    private static void dumpSplitNames(PrintWriter pw, AndroidPackage pkg) {
        if (pkg == null) {
            pw.print("unknown");
        } else {
            // [base:10, config.mdpi, config.xhdpi:12]
            pw.print("[");
            pw.print("base");
            if (pkg.getBaseRevisionCode() != 0) {
                pw.print(":"); pw.print(pkg.getBaseRevisionCode());
            }
            String[] splitNames = pkg.getSplitNames();
            int[] splitRevisionCodes = pkg.getSplitRevisionCodes();
            for (int i = 0; i < splitNames.length; i++) {
                pw.print(", ");
                pw.print(splitNames[i]);
                if (splitRevisionCodes[i] != 0) {
                    pw.print(":"); pw.print(splitRevisionCodes[i]);
                }
            }
            pw.print("]");
        }
    }

    void dumpGidsLPr(PrintWriter pw, String prefix, int[] gids) {
        if (!ArrayUtils.isEmpty(gids)) {
            pw.print(prefix);
            pw.print("gids="); pw.println(
                    PackageManagerServiceUtils.arrayToString(gids));
        }
    }

    void dumpRuntimePermissionsLPr(PrintWriter pw, String prefix, ArraySet<String> permissionNames,
            Collection<PermissionState> permissionStates, boolean dumpAll) {
        boolean hasRuntimePermissions = false;
        for (PermissionState permissionState : permissionStates) {
            if (permissionState.isRuntime()) {
                hasRuntimePermissions = true;
                break;
            }
        }
        if (hasRuntimePermissions || dumpAll) {
            pw.print(prefix); pw.println("runtime permissions:");
            for (PermissionState permissionState : permissionStates) {
                if (!permissionState.isRuntime()) {
                    continue;
                }
                if (permissionNames != null
                        && !permissionNames.contains(permissionState.getName())) {
                    continue;
                }
                pw.print(prefix); pw.print("  "); pw.print(permissionState.getName());
                pw.print(": granted="); pw.print(permissionState.isGranted());
                    pw.println(permissionFlagsToString(", flags=",
                            permissionState.getFlags()));
            }
        }
    }

    private static String permissionFlagsToString(String prefix, int flags) {
        StringBuilder flagsString = null;
        while (flags != 0) {
            if (flagsString == null) {
                flagsString = new StringBuilder();
                flagsString.append(prefix);
                flagsString.append("[ ");
            }
            final int flag = 1 << Integer.numberOfTrailingZeros(flags);
            flags &= ~flag;
            flagsString.append(PackageManager.permissionFlagToString(flag));
            if (flags != 0) {
                flagsString.append('|');
            }

        }
        if (flagsString != null) {
            flagsString.append(']');
            return flagsString.toString();
        } else {
            return "";
        }
    }

    void dumpInstallPermissionsLPr(PrintWriter pw, String prefix,
            ArraySet<String> filterPermissionNames, LegacyPermissionState permissionsState,
            List<UserInfo> users) {
        ArraySet<String> dumpPermissionNames = new ArraySet<>();
        for (UserInfo user : users) {
            int userId = user.id;
            Collection<PermissionState> permissionStates = permissionsState.getPermissionStates(
                    userId);
            for (PermissionState permissionState : permissionStates) {
                if (permissionState.isRuntime()) {
                    continue;
                }
                String permissionName = permissionState.getName();
                if (filterPermissionNames != null
                        && !filterPermissionNames.contains(permissionName)) {
                    continue;
                }
                dumpPermissionNames.add(permissionName);
            }
        }
        boolean printedSomething = false;
        for (String permissionName : dumpPermissionNames) {
            PermissionState systemPermissionState = permissionsState.getPermissionState(
                    permissionName, UserHandle.USER_SYSTEM);
            for (UserInfo user : users) {
                int userId = user.id;
                PermissionState permissionState;
                if (userId == UserHandle.USER_SYSTEM) {
                    permissionState = systemPermissionState;
                } else {
                    permissionState = permissionsState.getPermissionState(permissionName, userId);
                    if (Objects.equals(permissionState, systemPermissionState)) {
                        continue;
                    }
                }
                if (!printedSomething) {
                    pw.print(prefix); pw.println("install permissions:");
                    printedSomething = true;
                }
                pw.print(prefix); pw.print("  "); pw.print(permissionName);
                pw.print(": granted="); pw.print(
                        permissionState != null && permissionState.isGranted());
                pw.print(permissionFlagsToString(", flags=",
                        permissionState != null ? permissionState.getFlags() : 0));
                if (userId == UserHandle.USER_SYSTEM) {
                    pw.println();
                } else {
                    pw.print(", userId="); pw.println(userId);
                }
            }
        }
    }

    void dumpComponents(PrintWriter pw, String prefix, PackageSetting ps) {
        dumpComponents(pw, prefix, "activities:", ps.getPkg().getActivities());
        dumpComponents(pw, prefix, "services:", ps.getPkg().getServices());
        dumpComponents(pw, prefix, "receivers:", ps.getPkg().getReceivers());
        dumpComponents(pw, prefix, "providers:", ps.getPkg().getProviders());
        dumpComponents(pw, prefix, "instrumentations:", ps.getPkg().getInstrumentations());
    }

    void dumpComponents(PrintWriter pw, String prefix, String label,
            List<? extends ParsedComponent> list) {
        final int size = CollectionUtils.size(list);
        if (size == 0) {
            return;
        }
        pw.print(prefix);pw.println(label);
        for (int i = 0; i < size; i++) {
            final ParsedComponent component = list.get(i);
            pw.print(prefix);pw.print("  ");
            pw.println(component.getComponentName().flattenToShortString());
        }
    }

    public void writePermissionStateForUserLPr(int userId, boolean sync) {
        if (sync) {
            mRuntimePermissionsPersistence.writeStateForUser(userId, mPermissionDataProvider,
                    mPackages, mSharedUsers, /*handler=*/null, mLock, /*sync=*/true);
        } else {
            mRuntimePermissionsPersistence.writeStateForUserAsync(userId);
        }
    }

    private static class KeySetToValueMap<K, V> implements Map<K, V> {
        @NonNull
        private final Set<K> mKeySet;
        private final V mValue;

        KeySetToValueMap(@NonNull Set<K> keySet, V value) {
            mKeySet = keySet;
            mValue = value;
        }

        @Override
        public int size() {
            return mKeySet.size();
        }

        @Override
        public boolean isEmpty() {
            return mKeySet.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return mKeySet.contains(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return mValue == value;
        }

        @Override
        public V get(Object key) {
            return mValue;
        }

        @Override
        public V put(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V remove(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<K> keySet() {
            return mKeySet;
        }

        @Override
        public Collection<V> values() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RuntimePermissionPersistence {
        // 700-1300ms delay to avoid monopolizing PMS lock when written for multiple users.
        private static final long WRITE_PERMISSIONS_DELAY_MILLIS = 1000;
        private static final double WRITE_PERMISSIONS_DELAY_JITTER = 0.3;

        private static final long MAX_WRITE_PERMISSIONS_DELAY_MILLIS = 2000;

        private static final int UPGRADE_VERSION = -1;
        private static final int INITIAL_VERSION = 0;

        private static final Random sRandom = new Random();

        private String mExtendedFingerprint;

        @GuardedBy("mPersistenceLock")
        private final RuntimePermissionsPersistence mPersistence;
        private final Object mPersistenceLock = new Object();

        // Low-priority handlers running on SystemBg thread.
        private final Handler mAsyncHandler = new MyHandler();
        private final Handler mPersistenceHandler = new PersistenceHandler();

        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private final SparseBooleanArray mWriteScheduled = new SparseBooleanArray();

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseLongArray mLastNotWrittenMutationTimesMillis = new SparseLongArray();

        @GuardedBy("mLock")
        // Tracking the mutations that haven't yet been written to legacy state.
        // This avoids unnecessary work when writing settings for multiple users.
        private final AtomicBoolean mIsLegacyPermissionStateStale = new AtomicBoolean(false);

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseIntArray mVersions = new SparseIntArray();

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseArray<String> mFingerprints = new SparseArray<>();

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseBooleanArray mPermissionUpgradeNeeded = new SparseBooleanArray();

        @GuardedBy("mLock")
        // Staging area for states prepared to be written.
        private final SparseArray<RuntimePermissionsState> mPendingStatesToWrite =
                new SparseArray<>();

        // This is a hack to allow this class to invoke a write using Settings's data structures,
        // to facilitate moving to a finer scoped lock without a significant refactor.
        private final Consumer<Integer> mInvokeWriteUserStateAsyncCallback;

        public RuntimePermissionPersistence(RuntimePermissionsPersistence persistence,
                Consumer<Integer> invokeWriteUserStateAsyncCallback) {
            mPersistence = persistence;
            mInvokeWriteUserStateAsyncCallback = invokeWriteUserStateAsyncCallback;
        }

        int getVersion(int userId) {
            synchronized (mLock) {
                return mVersions.get(userId, INITIAL_VERSION);
            }
        }

        void setVersion(int version, int userId) {
            synchronized (mLock) {
                mVersions.put(userId, version);
                writeStateForUserAsync(userId);
            }
        }

        public boolean isPermissionUpgradeNeeded(int userId) {
            synchronized (mLock) {
                return mPermissionUpgradeNeeded.get(userId, true);
            }
        }

        public void updateRuntimePermissionsFingerprint(@UserIdInt int userId) {
            synchronized (mLock) {
                if (mExtendedFingerprint == null) {
                    throw new RuntimeException(
                            "The version of the permission controller hasn't been "
                                    + "set before trying to update the fingerprint.");
                }
                mFingerprints.put(userId, mExtendedFingerprint);
                mPermissionUpgradeNeeded.put(userId, false);
                writeStateForUserAsync(userId);
            }
        }

        public void setPermissionControllerVersion(long version) {
            synchronized (mLock) {
                int numUser = mFingerprints.size();
                mExtendedFingerprint = getExtendedFingerprint(version);

                for (int i = 0; i < numUser; i++) {
                    int userId = mFingerprints.keyAt(i);
                    String fingerprint = mFingerprints.valueAt(i);
                    mPermissionUpgradeNeeded.put(userId,
                            !TextUtils.equals(mExtendedFingerprint, fingerprint));
                }
            }
        }

        private String getExtendedFingerprint(long version) {
            return PackagePartitions.FINGERPRINT + "?pc_version=" + version;
        }

        private static long uniformRandom(double low, double high) {
            double mag = high - low;
            return (long) (sRandom.nextDouble() * mag + low);
        }

        private static long nextWritePermissionDelayMillis() {
            final long delay = WRITE_PERMISSIONS_DELAY_MILLIS;
            final double jitter = WRITE_PERMISSIONS_DELAY_JITTER;
            return delay + uniformRandom(-jitter * delay, jitter * delay);
        }

        public void writeStateForUserAsync(int userId) {
            mIsLegacyPermissionStateStale.set(true);
            synchronized (mLock) {
                final long currentTimeMillis = SystemClock.uptimeMillis();
                final long writePermissionDelayMillis = nextWritePermissionDelayMillis();

                if (mWriteScheduled.get(userId)) {
                    mAsyncHandler.removeMessages(userId);

                    // If enough time passed, write without holding off anymore.
                    final long lastNotWrittenMutationTimeMillis = mLastNotWrittenMutationTimesMillis
                            .get(userId);
                    final long timeSinceLastNotWrittenMutationMillis = currentTimeMillis
                            - lastNotWrittenMutationTimeMillis;
                    if (timeSinceLastNotWrittenMutationMillis
                            >= MAX_WRITE_PERMISSIONS_DELAY_MILLIS) {
                        mAsyncHandler.obtainMessage(userId).sendToTarget();
                        return;
                    }

                    // Hold off a bit more as settings are frequently changing.
                    final long maxDelayMillis = Math.max(lastNotWrittenMutationTimeMillis
                            + MAX_WRITE_PERMISSIONS_DELAY_MILLIS - currentTimeMillis, 0);
                    final long writeDelayMillis = Math.min(writePermissionDelayMillis,
                            maxDelayMillis);

                    Message message = mAsyncHandler.obtainMessage(userId);
                    mAsyncHandler.sendMessageDelayed(message, writeDelayMillis);
                } else {
                    mLastNotWrittenMutationTimesMillis.put(userId, currentTimeMillis);
                    Message message = mAsyncHandler.obtainMessage(userId);
                    mAsyncHandler.sendMessageDelayed(message, writePermissionDelayMillis);
                    mWriteScheduled.put(userId, true);
                }
            }
        }

        public void writeStateForUser(int userId, @NonNull LegacyPermissionDataProvider
                legacyPermissionDataProvider,
                @NonNull WatchedArrayMap<String, ? extends PackageStateInternal> packageStates,
                @NonNull WatchedArrayMap<String, SharedUserSetting> sharedUsers,
                @Nullable Handler pmHandler, @NonNull PackageManagerTracedLock pmLock,
                boolean sync) {
            synchronized (mLock) {
                mAsyncHandler.removeMessages(userId);
                mWriteScheduled.delete(userId);
            }

            Runnable writer = () -> {
                boolean isLegacyPermissionStateStale = mIsLegacyPermissionStateStale.getAndSet(
                        false);
                Map<String, List<RuntimePermissionsState.PermissionState>> packagePermissions;
                Map<String, List<RuntimePermissionsState.PermissionState>> sharedUserPermissions;

                synchronized (pmLock) {
                    if (sync || isLegacyPermissionStateStale) {
                        legacyPermissionDataProvider.writeLegacyPermissionStateTEMP();
                    }

                    packagePermissions = getPackagePermissions(userId, packageStates);
                    sharedUserPermissions = getShareUsersPermissions(userId, sharedUsers);
                }
                synchronized (mLock) {
                    int version = mVersions.get(userId, INITIAL_VERSION);
                    String fingerprint = mFingerprints.get(userId);

                    RuntimePermissionsState runtimePermissions = new RuntimePermissionsState(
                            version, fingerprint, packagePermissions, sharedUserPermissions);
                    mPendingStatesToWrite.put(userId, runtimePermissions);
                }
                if (pmHandler != null) {
                    // Async version.
                    mPersistenceHandler.obtainMessage(userId).sendToTarget();
                } else {
                    // Sync version.
                    writePendingStates();
                }
            };

            if (pmHandler != null) {
                // Async version, use pmHandler.
                pmHandler.post(writer);
            } else {
                // Sync version, use caller's thread.
                writer.run();
            }
        }

        @NonNull
        RuntimePermissionsState getLegacyPermissionsState(int userId,
                @NonNull WatchedArrayMap<String, ? extends PackageStateInternal> packageStates,
                @NonNull WatchedArrayMap<String, SharedUserSetting> sharedUsers) {
            int version;
            String fingerprint;
            synchronized (mLock) {
                version = mVersions.get(userId, INITIAL_VERSION);
                fingerprint = mFingerprints.get(userId);
            }

            return new RuntimePermissionsState(
                    version, fingerprint, getPackagePermissions(userId, packageStates),
                    getShareUsersPermissions(userId, sharedUsers));
        }

        @NonNull
        private Map<String, List<RuntimePermissionsState.PermissionState>> getPackagePermissions(
                int userId,
                @NonNull WatchedArrayMap<String, ? extends PackageStateInternal> packageStates) {
            final Map<String, List<RuntimePermissionsState.PermissionState>>
                    packagePermissions = new ArrayMap<>();

            final int packagesSize = packageStates.size();
            for (int i = 0; i < packagesSize; i++) {
                String packageName = packageStates.keyAt(i);
                PackageStateInternal packageState = packageStates.valueAt(i);
                if (!packageState.hasSharedUser()) {
                    List<RuntimePermissionsState.PermissionState> permissions =
                            getPermissionsFromPermissionsState(
                                    packageState.getLegacyPermissionState(), userId);
                    if (permissions.isEmpty()
                            && !packageState.isInstallPermissionsFixed()) {
                        // Storing an empty state means the package is known to the
                        // system and its install permissions have been granted and fixed.
                        // If this is not the case, we should not store anything.
                        continue;
                    }
                    packagePermissions.put(packageName, permissions);
                }
            }
            return packagePermissions;
        }

        @NonNull
        private Map<String, List<RuntimePermissionsState.PermissionState>> getShareUsersPermissions(
                int userId, @NonNull WatchedArrayMap<String, SharedUserSetting> sharedUsers) {
            final Map<String, List<RuntimePermissionsState.PermissionState>>
                    sharedUserPermissions = new ArrayMap<>();

            final int sharedUsersSize = sharedUsers.size();
            for (int i = 0; i < sharedUsersSize; i++) {
                String sharedUserName = sharedUsers.keyAt(i);
                SharedUserSetting sharedUserSetting = sharedUsers.valueAt(i);
                List<RuntimePermissionsState.PermissionState> permissions =
                        getPermissionsFromPermissionsState(
                                sharedUserSetting.getLegacyPermissionState(), userId);
                sharedUserPermissions.put(sharedUserName, permissions);
            }
            return sharedUserPermissions;
        }

        private void writePendingStates() {
            while (true) {
                final RuntimePermissionsState runtimePermissions;
                final int userId;
                synchronized (mLock) {
                    if (mPendingStatesToWrite.size() == 0) {
                        break;
                    }
                    userId = mPendingStatesToWrite.keyAt(0);
                    runtimePermissions = mPendingStatesToWrite.valueAt(0);
                    mPendingStatesToWrite.removeAt(0);
                }
                synchronized (mPersistenceLock) {
                    mPersistence.writeForUser(runtimePermissions, UserHandle.of(userId));
                }
            }
        }

        @NonNull
        private List<RuntimePermissionsState.PermissionState> getPermissionsFromPermissionsState(
                @NonNull LegacyPermissionState permissionsState, @UserIdInt int userId) {
            Collection<PermissionState> permissionStates =
                    permissionsState.getPermissionStates(userId);
            List<RuntimePermissionsState.PermissionState> permissions = new ArrayList<>();
            for (PermissionState permissionState : permissionStates) {
                RuntimePermissionsState.PermissionState permission =
                        new RuntimePermissionsState.PermissionState(permissionState.getName(),
                                permissionState.isGranted(), permissionState.getFlags());
                permissions.add(permission);
            }
            return permissions;
        }

        private void onUserRemoved(int userId) {
            synchronized (mLock) {
                // Make sure we do not
                mAsyncHandler.removeMessages(userId);

                mPermissionUpgradeNeeded.delete(userId);
                mVersions.delete(userId);
                mFingerprints.remove(userId);
            }
        }

        public void deleteUserRuntimePermissionsFile(int userId) {
            synchronized (mPersistenceLock) {
                mPersistence.deleteForUser(UserHandle.of(userId));
            }
        }

        public void readStateForUserSync(int userId, @NonNull VersionInfo internalVersion,
                @NonNull WatchedArrayMap<String, PackageSetting> packageSettings,
                @NonNull WatchedArrayMap<String, SharedUserSetting> sharedUsers,
                @NonNull File userRuntimePermissionsFile) {
            final RuntimePermissionsState runtimePermissions;
            synchronized (mPersistenceLock) {
                runtimePermissions = mPersistence.readForUser(UserHandle.of(userId));
            }
            if (runtimePermissions == null) {
                readLegacyStateForUserSync(userId, userRuntimePermissionsFile, packageSettings,
                        sharedUsers);
                writeStateForUserAsync(userId);
                return;
            }
            synchronized (mLock) {
                // If the runtime permissions file exists but the version is not set this is
                // an upgrade from P->Q. Hence mark it with the special UPGRADE_VERSION.
                int version = runtimePermissions.getVersion();
                if (version == RuntimePermissionsState.NO_VERSION) {
                    version = UPGRADE_VERSION;
                }
                mVersions.put(userId, version);

                String fingerprint = runtimePermissions.getFingerprint();
                mFingerprints.put(userId, fingerprint);

                boolean isUpgradeToR = internalVersion.sdkVersion < Build.VERSION_CODES.R;

                Map<String, List<RuntimePermissionsState.PermissionState>> packagePermissions =
                        runtimePermissions.getPackagePermissions();
                int packagesSize = packageSettings.size();
                for (int i = 0; i < packagesSize; i++) {
                    String packageName = packageSettings.keyAt(i);
                    PackageSetting packageSetting = packageSettings.valueAt(i);

                    List<RuntimePermissionsState.PermissionState> permissions =
                            packagePermissions.get(packageName);
                    if (permissions != null) {
                        readPermissionsState(permissions,
                                packageSetting.getLegacyPermissionState(),
                                userId);
                        packageSetting.setInstallPermissionsFixed(true);
                    } else if (!packageSetting.hasSharedUser() && !isUpgradeToR) {
                        Slogf.w(TAG, "Missing permission state for package %s on user %d",
                                packageName, userId);
                        packageSetting.getLegacyPermissionState().setMissing(true, userId);
                    }
                }

                Map<String, List<RuntimePermissionsState.PermissionState>> sharedUserPermissions =
                        runtimePermissions.getSharedUserPermissions();
                int sharedUsersSize = sharedUsers.size();
                for (int i = 0; i < sharedUsersSize; i++) {
                    String sharedUserName = sharedUsers.keyAt(i);
                    SharedUserSetting sharedUserSetting = sharedUsers.valueAt(i);

                    List<RuntimePermissionsState.PermissionState> permissions =
                            sharedUserPermissions.get(sharedUserName);
                    if (permissions != null) {
                        readPermissionsState(permissions,
                                sharedUserSetting.getLegacyPermissionState(), userId);
                    } else if (!isUpgradeToR) {
                        Slog.w(TAG, "Missing permission state for shared user: " + sharedUserName);
                        sharedUserSetting.getLegacyPermissionState().setMissing(true, userId);
                    }
                }
            }
        }

        private void readPermissionsState(
                @NonNull List<RuntimePermissionsState.PermissionState> permissions,
                @NonNull LegacyPermissionState permissionsState, @UserIdInt int userId) {
            int permissionsSize = permissions.size();
            for (int i = 0; i < permissionsSize; i++) {
                RuntimePermissionsState.PermissionState permission = permissions.get(i);
                String name = permission.getName();
                boolean granted = permission.isGranted();
                int flags = permission.getFlags();
                permissionsState.putPermissionState(new PermissionState(name, true, granted,
                        flags), userId);
            }
        }

        private void readLegacyStateForUserSync(int userId, @NonNull File permissionsFile,
                @NonNull WatchedArrayMap<String, ? extends PackageStateInternal> packageStates,
                @NonNull WatchedArrayMap<String, SharedUserSetting> sharedUsers) {
            synchronized (mLock) {
                if (!permissionsFile.exists()) {
                    return;
                }

                FileInputStream in;
                try {
                    in = new AtomicFile(permissionsFile).openRead();
                } catch (FileNotFoundException fnfe) {
                    Slog.i(PackageManagerService.TAG, "No permissions state");
                    return;
                }

                try {
                    final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                    parseLegacyRuntimePermissions(parser, userId, packageStates, sharedUsers);

                } catch (XmlPullParserException | IOException e) {
                    throw new IllegalStateException("Failed parsing permissions file: "
                            + permissionsFile, e);
                } finally {
                    IoUtils.closeQuietly(in);
                }
            }
        }

        private void parseLegacyRuntimePermissions(TypedXmlPullParser parser, int userId,
                @NonNull WatchedArrayMap<String, ? extends PackageStateInternal> packageStates,
                @NonNull WatchedArrayMap<String, SharedUserSetting> sharedUsers)
                throws IOException, XmlPullParserException {
            synchronized (mLock) {
                final int outerDepth = parser.getDepth();
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    switch (parser.getName()) {
                        case TAG_RUNTIME_PERMISSIONS: {
                            // If the permisions settings file exists but the version is not set this is
                            // an upgrade from P->Q. Hence mark it with the special UPGRADE_VERSION
                            int version = parser.getAttributeInt(null, ATTR_VERSION,
                                    UPGRADE_VERSION);
                            mVersions.put(userId, version);
                            String fingerprint = parser.getAttributeValue(null, ATTR_FINGERPRINT);
                            mFingerprints.put(userId, fingerprint);
                        }
                        break;

                        case TAG_PACKAGE: {
                            String name = parser.getAttributeValue(null, ATTR_NAME);
                            PackageStateInternal ps = packageStates.get(name);
                            if (ps == null) {
                                Slog.w(PackageManagerService.TAG, "Unknown package:" + name);
                                XmlUtils.skipCurrentTag(parser);
                                continue;
                            }
                            parseLegacyPermissionsLPr(parser, ps.getLegacyPermissionState(),
                                    userId);
                        }
                        break;

                        case TAG_SHARED_USER: {
                            String name = parser.getAttributeValue(null, ATTR_NAME);
                            SharedUserSetting sus = sharedUsers.get(name);
                            if (sus == null) {
                                Slog.w(PackageManagerService.TAG, "Unknown shared user:" + name);
                                XmlUtils.skipCurrentTag(parser);
                                continue;
                            }
                            parseLegacyPermissionsLPr(parser, sus.getLegacyPermissionState(),
                                    userId);
                        }
                        break;
                    }
                }
            }
        }

        private void parseLegacyPermissionsLPr(TypedXmlPullParser parser,
                LegacyPermissionState permissionsState, int userId)
                throws IOException, XmlPullParserException {
            synchronized (mLock) {
                final int outerDepth = parser.getDepth();
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    switch (parser.getName()) {
                        case TAG_ITEM: {
                            String name = parser.getAttributeValue(null, ATTR_NAME);
                            final boolean granted =
                                    parser.getAttributeBoolean(null, ATTR_GRANTED, true);
                            final int flags =
                                    parser.getAttributeIntHex(null, ATTR_FLAGS, 0);
                            permissionsState.putPermissionState(new PermissionState(name, true,
                                    granted, flags), userId);
                        }
                        break;
                    }
                }
            }
        }

        private final class MyHandler extends Handler {
            public MyHandler() {
                super(BackgroundThread.getHandler().getLooper());
            }

            @Override
            public void handleMessage(Message message) {
                final int userId = message.what;
                Runnable callback = (Runnable) message.obj;
                mInvokeWriteUserStateAsyncCallback.accept(userId);
                if (callback != null) {
                    callback.run();
                }
            }
        }

        private final class PersistenceHandler extends Handler {
            PersistenceHandler() {
                super(BackgroundThread.getHandler().getLooper());
            }

            @Override
            public void handleMessage(Message message) {
                writePendingStates();
            }
        }
    }

    /**
     * Accessor for preferred activities
     */
    PersistentPreferredIntentResolver getPersistentPreferredActivities(int userId) {
        return mPersistentPreferredActivities.get(userId);
    }

    PreferredIntentResolver getPreferredActivities(int userId) {
        return mPreferredActivities.get(userId);
    }

    @Nullable
    CrossProfileIntentResolver getCrossProfileIntentResolver(int userId) {
        return mCrossProfileIntentResolvers.get(userId);
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    void clearPackagePreferredActivities(String packageName,
            @NonNull SparseBooleanArray outUserChanged, int userId) {
        boolean changed = false;
        ArrayList<PreferredActivity> removed = null;
        for (int i = 0; i < mPreferredActivities.size(); i++) {
            final int thisUserId = mPreferredActivities.keyAt(i);
            PreferredIntentResolver pir = mPreferredActivities.valueAt(i);
            if (userId != UserHandle.USER_ALL && userId != thisUserId) {
                continue;
            }
            Iterator<PreferredActivity> it = pir.filterIterator();
            while (it.hasNext()) {
                PreferredActivity pa = it.next();
                // Mark entry for removal only if it matches the package name
                // and the entry is of type "always".
                if (packageName == null
                        || (pa.mPref.mComponent.getPackageName().equals(packageName)
                                && pa.mPref.mAlways)) {
                    if (removed == null) {
                        removed = new ArrayList<>();
                    }
                    removed.add(pa);
                }
            }
            if (removed != null) {
                for (int j = 0; j < removed.size(); j++) {
                    PreferredActivity pa = removed.get(j);
                    pir.removeFilter(pa);
                }
                outUserChanged.put(thisUserId, true);
                changed = true;
            }
        }
        if (changed) {
            onChanged();
        }
    }

    boolean clearPackagePersistentPreferredActivities(String packageName, int userId) {
        ArrayList<PersistentPreferredActivity> removed = null;
        boolean changed = false;
        for (int i = 0; i < mPersistentPreferredActivities.size(); i++) {
            final int thisUserId = mPersistentPreferredActivities.keyAt(i);
            PersistentPreferredIntentResolver ppir = mPersistentPreferredActivities.valueAt(i);
            if (userId != thisUserId) {
                continue;
            }
            Iterator<PersistentPreferredActivity> it = ppir.filterIterator();
            while (it.hasNext()) {
                PersistentPreferredActivity ppa = it.next();
                // Mark entry for removal only if it matches the package name.
                if (ppa.mComponent.getPackageName().equals(packageName)) {
                    if (removed == null) {
                        removed = new ArrayList<>();
                    }
                    removed.add(ppa);
                }
            }
            if (removed != null) {
                for (int j = 0; j < removed.size(); j++) {
                    PersistentPreferredActivity ppa = removed.get(j);
                    ppir.removeFilter(ppa);
                }
                changed = true;
            }
        }
        if (changed) {
            onChanged();
        }
        return changed;
    }

    boolean clearPersistentPreferredActivity(IntentFilter filter, int userId) {
        ArrayList<PersistentPreferredActivity> removed = null;
        PersistentPreferredIntentResolver ppir = mPersistentPreferredActivities.get(userId);
        Iterator<PersistentPreferredActivity> it = ppir.filterIterator();
        boolean changed = false;
        while (it.hasNext()) {
            PersistentPreferredActivity ppa = it.next();
            if (IntentFilter.filterEquals(ppa.getIntentFilter(), filter)) {
                if (removed == null) {
                    removed = new ArrayList<>();
                }
                removed.add(ppa);
            }
        }
        if (removed != null) {
            for (int i = 0; i < removed.size(); i++) {
                PersistentPreferredActivity ppa = removed.get(i);
                ppir.removeFilter(ppa);
            }
            changed = true;
        }
        if (changed) {
            onChanged();
        }
        return changed;
    }

    ArrayList<Integer> systemReady(ComponentResolver resolver) {
        // Verify that all of the preferred activity components actually
        // exist.  It is possible for applications to be updated and at
        // that point remove a previously declared activity component that
        // had been set as a preferred activity.  We try to clean this up
        // the next time we encounter that preferred activity, but it is
        // possible for the user flow to never be able to return to that
        // situation so here we do a validity check to make sure we haven't
        // left any junk around.
        ArrayList<Integer> changed = new ArrayList<>();
        ArrayList<PreferredActivity> removed = new ArrayList<>();
        for (int i = 0; i < mPreferredActivities.size(); i++) {
            PreferredIntentResolver pir = mPreferredActivities.valueAt(i);
            removed.clear();
            for (PreferredActivity pa : pir.filterSet()) {
                if (!resolver.isActivityDefined(pa.mPref.mComponent)) {
                    removed.add(pa);
                }
            }
            if (removed.size() > 0) {
                for (int r = 0; r < removed.size(); r++) {
                    PreferredActivity pa = removed.get(r);
                    Slog.w(TAG, "Removing dangling preferred activity: "
                            + pa.mPref.mComponent);
                    pir.removeFilter(pa);
                }
                changed.add(mPreferredActivities.keyAt(i));
            }
        }
        onChanged();
        return changed;
    }

    void dumpPreferred(PrintWriter pw, DumpState dumpState, String packageName) {
        for (int i = 0; i < mPreferredActivities.size(); i++) {
            PreferredIntentResolver pir = mPreferredActivities.valueAt(i);
            int user = mPreferredActivities.keyAt(i);
            if (pir.dump(pw,
                         dumpState.getTitlePrinted()
                         ? "\nPreferred Activities User " + user + ":"
                         : "Preferred Activities User " + user + ":", "  ",
                         packageName, true, false)) {
                dumpState.setTitlePrinted(true);
            }
        }
    }

    boolean isInstallerPackage(@NonNull String packageName) {
        return mInstallerPackages.contains(packageName);
    }
}
