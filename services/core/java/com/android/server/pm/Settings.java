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
import static android.content.pm.PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.content.pm.PackageManager.UNINSTALL_REASON_USER_TYPE;
import static android.os.Process.PACKAGE_INFO_GID;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.compat.ChangeIdStateCache;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageUserState;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.parsing.PackageInfoWithoutStateUtils;
import android.content.pm.parsing.component.ParsedComponent;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedProcess;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Message;
import android.os.PatternMatcher;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.SELinux;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.IncrementalManager;
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
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;
import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.permission.persistence.RuntimePermissionsState;
import com.android.server.LocalServices;
import com.android.server.backup.PreferredActivityBackupHelper;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.permission.LegacyPermissionSettings;
import com.android.server.pm.permission.LegacyPermissionState;
import com.android.server.pm.permission.LegacyPermissionState.PermissionState;
import com.android.server.pm.verify.domain.DomainVerificationLegacySettings;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationPersistence;
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
import java.util.Set;
import java.util.UUID;

/**
 * Holds information about dynamic settings.
 */
public final class Settings implements Watchable, Snappable {
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

    private static final boolean DEBUG_STOPPED = false;
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
     * @deprecated Moved to {@link android.content.pm.PackageUserState.SuspendParams}
     */
    @Deprecated
    private static final String TAG_SUSPENDED_DIALOG_INFO = "suspended-dialog-info";
    /**
     * @deprecated Moved to {@link android.content.pm.PackageUserState.SuspendParams}
     */
    @Deprecated
    private static final String TAG_SUSPENDED_APP_EXTRAS = "suspended-app-extras";
    /**
     * @deprecated Moved to {@link android.content.pm.PackageUserState.SuspendParams}
     */
    @Deprecated
    private static final String TAG_SUSPENDED_LAUNCHER_EXTRAS = "suspended-launcher-extras";
    private static final String TAG_SUSPEND_PARAMS = "suspend-params";
    private static final String TAG_MIME_GROUP = "mime-group";
    private static final String TAG_MIME_TYPE = "mime-type";

    public static final String ATTR_NAME = "name";
    public static final String ATTR_PACKAGE = "package";
    private static final String ATTR_GRANTED = "granted";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_VERSION = "version";

    private static final String ATTR_CE_DATA_INODE = "ceDataInode";
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
    private static final String ATTR_DOMAIN_VERIFICATON_STATE = "domainVerificationStatus";
    private static final String ATTR_APP_LINK_GENERATION = "app-link-generation";
    private static final String ATTR_INSTALL_REASON = "install-reason";
    private static final String ATTR_UNINSTALL_REASON = "uninstall-reason";
    private static final String ATTR_INSTANT_APP = "instant-app";
    private static final String ATTR_VIRTUAL_PRELOAD = "virtual-preload";
    private static final String ATTR_HARMFUL_APP_WARNING = "harmful-app-warning";
    private static final String ATTR_SPLASH_SCREEN_THEME = "splash-screen-theme";

    private static final String ATTR_PACKAGE_NAME = "packageName";
    private static final String ATTR_FINGERPRINT = "fingerprint";
    private static final String ATTR_VOLUME_UUID = "volumeUuid";
    private static final String ATTR_SDK_VERSION = "sdkVersion";
    private static final String ATTR_DATABASE_VERSION = "databaseVersion";
    private static final String ATTR_VALUE = "value";

    private final PackageManagerTracedLock mLock;

    @Watched(manual = true)
    private final RuntimePermissionPersistence mRuntimePermissionsPersistence;

    private final File mSettingsFilename;
    private final File mBackupSettingsFilename;
    private final File mPackageListFilename;
    private final File mStoppedPackagesFilename;
    private final File mBackupStoppedPackagesFilename;
    /** The top level directory in configfs for sdcardfs to push the package->uid,userId mappings */
    private final File mKernelMappingFilename;

    /** Map from package name to settings */
    @Watched
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    final WatchedArrayMap<String, PackageSetting> mPackages;
    private final SnapshotCache<WatchedArrayMap<String, PackageSetting>> mPackagesSnapshot;

    /**
     * List of packages that were involved in installing other packages, i.e. are listed
     * in at least one app's InstallSource.
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

    private static int mFirstAvailableUid = 0;

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
         * Last known value of {@link Build#FINGERPRINT}. Used to determine when
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
            fingerprint = Build.FINGERPRINT;
        }
    }

    /** Device identity for the purpose of package verification. */
    @Watched(manual = true)
    private VerifierDeviceIdentity mVerifierDeviceIdentity;

    // The user's preferred activities associated with particular intent
    // filters.
    @Watched
    private final WatchedSparseArray<PreferredIntentResolver>
            mPreferredActivities = new WatchedSparseArray<>();

    // The persistent preferred activities of the user's profile/device owner
    // associated with particular intent filters.
    @Watched
    private final WatchedSparseArray<PersistentPreferredIntentResolver>
            mPersistentPreferredActivities = new WatchedSparseArray<>();

    // For every user, it is used to find to which other users the intent can be forwarded.
    @Watched
    private final WatchedSparseArray<CrossProfileIntentResolver>
            mCrossProfileIntentResolvers = new WatchedSparseArray<>();

    @Watched
    final WatchedArrayMap<String, SharedUserSetting> mSharedUsers = new WatchedArrayMap<>();
    @Watched
    private final WatchedArrayList<SettingBase> mAppIds;
    @Watched
    private final WatchedSparseArray<SettingBase> mOtherAppIds;

    // For reading/writing settings file.
    @Watched
    private final WatchedArrayList<Signature> mPastSignatures =
            new WatchedArrayList<Signature>();
    @Watched
    private final WatchedArrayMap<Long, Integer> mKeySetRefs =
            new WatchedArrayMap<Long, Integer>();

    // Packages that have been renamed since they were first installed.
    // Keys are the new names of the packages, values are the original
    // names.  The packages appear everywhere else under their original
    // names.
    @Watched
    private final WatchedArrayMap<String, String> mRenamedPackages =
            new WatchedArrayMap<String, String>();

    // For every user, it is used to find the package name of the default Browser App.
    @Watched
    final WatchedSparseArray<String> mDefaultBrowserApp = new WatchedSparseArray<String>();

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
    private final WatchedArrayList<PackageSetting> mPendingPackages = new WatchedArrayList<>();

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
        mOtherAppIds.registerObserver(mObserver);
        mRenamedPackages.registerObserver(mObserver);
        mNextAppLinkGeneration.registerObserver(mObserver);
        mDefaultBrowserApp.registerObserver(mObserver);
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
        mKeySetManagerService = new KeySetManagerService(mPackages);

        mLock = new PackageManagerTracedLock();
        mPackages.putAll(pkgSettings);
        mAppIds = new WatchedArrayList<>();
        mOtherAppIds = new WatchedSparseArray<>();
        mSystemDir = null;
        mPermissions = null;
        mRuntimePermissionsPersistence = null;
        mPermissionDataProvider = null;
        mSettingsFilename = null;
        mBackupSettingsFilename = null;
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
        mKeySetManagerService = new KeySetManagerService(mPackages);

        mLock = lock;
        mAppIds = new WatchedArrayList<>();
        mOtherAppIds = new WatchedSparseArray<>();
        mPermissions = new LegacyPermissionSettings(lock);
        mRuntimePermissionsPersistence = new RuntimePermissionPersistence(
                runtimePermissionsPersistence);
        mPermissionDataProvider = permissionDataProvider;

        mSystemDir = new File(dataDir, "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG
                |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                -1, -1);
        mSettingsFilename = new File(mSystemDir, "packages.xml");
        mBackupSettingsFilename = new File(mSystemDir, "packages-backup.xml");
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
        mKeySetManagerService = new KeySetManagerService(mPackages);

        // The following assignments satisfy Java requirements but are not
        // needed by the read-only methods.  Note especially that the lock
        // is not required because this clone is meant to support lock-free
        // read-only methods.
        mLock = null;
        mRuntimePermissionsPersistence = r.mRuntimePermissionsPersistence;
        mSettingsFilename = null;
        mBackupSettingsFilename = null;
        mPackageListFilename = null;
        mStoppedPackagesFilename = null;
        mBackupStoppedPackagesFilename = null;
        mKernelMappingFilename = null;

        mDomainVerificationManager = r.mDomainVerificationManager;

        mDisabledSysPackages.snapshot(r.mDisabledSysPackages);
        mBlockUninstallPackages.snapshot(r.mBlockUninstallPackages);
        mVersion.putAll(r.mVersion);
        mVerifierDeviceIdentity = r.mVerifierDeviceIdentity;
        WatchedSparseArray.snapshot(
                mPreferredActivities, r.mPreferredActivities);
        WatchedSparseArray.snapshot(
                mPersistentPreferredActivities, r.mPersistentPreferredActivities);
        WatchedSparseArray.snapshot(
                mCrossProfileIntentResolvers, r.mCrossProfileIntentResolvers);
        mSharedUsers.snapshot(r.mSharedUsers);
        mAppIds = r.mAppIds.snapshot();
        mOtherAppIds = r.mOtherAppIds.snapshot();
        WatchedArrayList.snapshot(
                mPastSignatures, r.mPastSignatures);
        WatchedArrayMap.snapshot(
                mKeySetRefs, r.mKeySetRefs);
        mRenamedPackages.snapshot(r.mRenamedPackages);
        mNextAppLinkGeneration.snapshot(r.mNextAppLinkGeneration);
        mDefaultBrowserApp.snapshot(r.mDefaultBrowserApp);
        // mReadMessages
        WatchedArrayList.snapshot(
                mPendingPackages, r.mPendingPackages);
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
            s.userId = acquireAndRegisterNewAppIdLPw(s);
            if (s.userId < 0) {
                // < 0 means we couldn't assign a userid; throw exception
                throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                        "Creating shared user " + name + " failed");
            }
            Log.i(PackageManagerService.TAG, "New shared user " + name + ": id=" + s.userId);
            mSharedUsers.put(name, s);
        }
        return s;
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
        if (dp == null && p.pkg != null && p.pkg.isSystem()
                && !p.getPkgState().isUpdatedSystemApp()) {
            p.getPkgState().setUpdatedSystemApp(true);
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
            mDisabledSysPackages.put(name, disabled);

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
        p.getPkgState().setUpdatedSystemApp(false);
        PackageSetting ret = addPackageLPw(name, p.realName, p.getPath(),
                p.legacyNativeLibraryPathString, p.primaryCpuAbiString,
                p.secondaryCpuAbiString, p.cpuAbiOverrideString,
                p.appId, p.versionCode, p.pkgFlags, p.pkgPrivateFlags,
                p.usesStaticLibraries, p.usesStaticLibrariesVersions, p.mimeGroups,
                mDomainVerificationManager.generateNewId());
        if (ret != null) {
            ret.getPkgState().setUpdatedSystemApp(false);
        }
        mDisabledSysPackages.remove(name);
        return ret;
    }

    boolean isDisabledSystemPackageLPr(String name) {
        return mDisabledSysPackages.containsKey(name);
    }

    void removeDisabledSystemPackageLPw(String name) {
        mDisabledSysPackages.remove(name);
    }

    PackageSetting addPackageLPw(String name, String realName, File codePath,
            String legacyNativeLibraryPathString, String primaryCpuAbiString,
            String secondaryCpuAbiString, String cpuAbiOverrideString, int uid, long vc, int
            pkgFlags, int pkgPrivateFlags, String[] usesStaticLibraries,
            long[] usesStaticLibraryNames, Map<String, ArraySet<String>> mimeGroups,
            @NonNull UUID domainSetId) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (p.appId == uid) {
                return p;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate package, keeping first: " + name);
            return null;
        }
        p = new PackageSetting(name, realName, codePath, legacyNativeLibraryPathString,
                primaryCpuAbiString, secondaryCpuAbiString, cpuAbiOverrideString, vc, pkgFlags,
                pkgPrivateFlags, 0 /*userId*/, usesStaticLibraries, usesStaticLibraryNames,
                mimeGroups, domainSetId);
        p.appId = uid;
        if (registerExistingAppIdLPw(uid, p, name)) {
            mPackages.put(name, p);
            return p;
        }
        return null;
    }

    SharedUserSetting addSharedUserLPw(String name, int uid, int pkgFlags, int pkgPrivateFlags) {
        SharedUserSetting s = mSharedUsers.get(name);
        if (s != null) {
            if (s.userId == uid) {
                return s;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate shared user, keeping first: " + name);
            return null;
        }
        s = new SharedUserSetting(name, pkgFlags, pkgPrivateFlags);
        s.userId = uid;
        if (registerExistingAppIdLPw(uid, s, name)) {
            mSharedUsers.put(name, s);
            return s;
        }
        return null;
    }

    void pruneSharedUsersLPw() {
        ArrayList<String> removeStage = new ArrayList<String>();
        for (Map.Entry<String,SharedUserSetting> entry : mSharedUsers.entrySet()) {
            final SharedUserSetting sus = entry.getValue();
            if (sus == null) {
                removeStage.add(entry.getKey());
                continue;
            }
            // remove packages that are no longer installed
            for (Iterator<PackageSetting> iter = sus.packages.iterator(); iter.hasNext();) {
                PackageSetting ps = iter.next();
                if (mPackages.get(ps.name) == null) {
                    iter.remove();
                }
            }
            if (sus.packages.size() == 0) {
                removeStage.add(entry.getKey());
            }
        }
        for (int i = 0; i < removeStage.size(); i++) {
            mSharedUsers.remove(removeStage.get(i));
        }
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
            boolean virtualPreload, UserManagerService userManager,
            String[] usesStaticLibraries, long[] usesStaticLibrariesVersions,
            Set<String> mimeGroupNames, @NonNull UUID domainSetId) {
        final PackageSetting pkgSetting;
        if (originalPkg != null) {
            if (PackageManagerService.DEBUG_UPGRADE) Log.v(PackageManagerService.TAG, "Package "
                    + pkgName + " is adopting original package " + originalPkg.name);
            pkgSetting = new PackageSetting(originalPkg, pkgName /*realPkgName*/);
            pkgSetting.setPath(codePath);
            pkgSetting.legacyNativeLibraryPathString = legacyNativeLibraryPath;
            pkgSetting.pkgFlags = pkgFlags;
            pkgSetting.pkgPrivateFlags = pkgPrivateFlags;
            pkgSetting.primaryCpuAbiString = primaryCpuAbi;
            pkgSetting.secondaryCpuAbiString = secondaryCpuAbi;
            // NOTE: Create a deeper copy of the package signatures so we don't
            // overwrite the signatures in the original package setting.
            pkgSetting.signatures = new PackageSignatures();
            pkgSetting.versionCode = versionCode;
            pkgSetting.usesStaticLibraries = usesStaticLibraries;
            pkgSetting.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
            // Update new package state.
            pkgSetting.setTimeStamp(codePath.lastModified());
            pkgSetting.setDomainSetId(domainSetId);
        } else {
            pkgSetting = new PackageSetting(pkgName, realPkgName, codePath,
                    legacyNativeLibraryPath, primaryCpuAbi, secondaryCpuAbi,
                    null /*cpuAbiOverrideString*/, versionCode, pkgFlags, pkgPrivateFlags,
                    0 /*sharedUserId*/, usesStaticLibraries,
                    usesStaticLibrariesVersions, createMimeGroups(mimeGroupNames), domainSetId);
            pkgSetting.setTimeStamp(codePath.lastModified());
            pkgSetting.sharedUser = sharedUser;
            // If this is not a system app, it starts out stopped.
            if ((pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                if (DEBUG_STOPPED) {
                    RuntimeException e = new RuntimeException("here");
                    e.fillInStackTrace();
                    Slog.i(PackageManagerService.TAG, "Stopping package " + pkgName, e);
                }
                List<UserInfo> users = getAllUsers(userManager);
                final int installUserId = installUser != null ? installUser.getIdentifier() : 0;
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
                                    && !isAdbInstallDisallowed(userManager, user.id))
                                || installUserId == user.id;
                        pkgSetting.setUserState(user.id, 0, COMPONENT_ENABLED_STATE_DEFAULT,
                                installed,
                                true /*stopped*/,
                                true /*notLaunched*/,
                                false /*hidden*/,
                                0 /*distractionFlags*/,
                                false /*suspended*/,
                                null /*suspendParams*/,
                                instantApp,
                                virtualPreload,
                                null /*lastDisableAppCaller*/,
                                null /*enabledComponents*/,
                                null /*disabledComponents*/,
                                PackageManager.INSTALL_REASON_UNKNOWN,
                                PackageManager.UNINSTALL_REASON_UNKNOWN,
                                null, /*harmfulAppWarning*/
                                null /*splashscreenTheme*/
                        );
                    }
                }
            }
            if (sharedUser != null) {
                pkgSetting.appId = sharedUser.userId;
            } else {
                // Clone the setting here for disabled system packages
                if (disabledPkg != null) {
                    // For disabled packages a new setting is created
                    // from the existing user id. This still has to be
                    // added to list of user id's
                    // Copy signatures from previous setting
                    pkgSetting.signatures = new PackageSignatures(disabledPkg.signatures);
                    pkgSetting.appId = disabledPkg.appId;
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

    private static Map<String, ArraySet<String>> createMimeGroups(Set<String> mimeGroupNames) {
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
            @Nullable PackageSetting disabledPkg, @Nullable SharedUserSetting sharedUser,
            @NonNull File codePath, @Nullable String legacyNativeLibraryPath,
            @Nullable String primaryCpuAbi, @Nullable String secondaryCpuAbi, int pkgFlags,
            int pkgPrivateFlags, @NonNull UserManagerService userManager,
            @Nullable String[] usesStaticLibraries, @Nullable long[] usesStaticLibrariesVersions,
            @Nullable Set<String> mimeGroupNames, @NonNull UUID domainSetId)
                    throws PackageManagerException {
        final String pkgName = pkgSetting.name;
        if (pkgSetting.sharedUser != sharedUser) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Package " + pkgName + " shared user changed from "
                    + (pkgSetting.sharedUser != null ? pkgSetting.sharedUser.name : "<nothing>")
                    + " to " + (sharedUser != null ? sharedUser.name : "<nothing>"));
            throw new PackageManagerException(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                    "Updating application package " + pkgName + " failed");
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
                pkgSetting.legacyNativeLibraryPathString = legacyNativeLibraryPath;
            }
            pkgSetting.setPath(codePath);
            if (IncrementalManager.isIncrementalPath(codePath.getAbsolutePath())) {
                pkgSetting.incrementalStates = new IncrementalStates();
            }
        }
        // If what we are scanning is a system (and possibly privileged) package,
        // then make it so, regardless of whether it was previously installed only
        // in the data partition. Reset first.
        pkgSetting.pkgFlags &= ~ApplicationInfo.FLAG_SYSTEM;
        pkgSetting.pkgPrivateFlags &= ~(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED
                | ApplicationInfo.PRIVATE_FLAG_OEM
                | ApplicationInfo.PRIVATE_FLAG_VENDOR
                | ApplicationInfo.PRIVATE_FLAG_PRODUCT
                | ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT
                | ApplicationInfo.PRIVATE_FLAG_ODM);
        pkgSetting.pkgFlags |= pkgFlags & ApplicationInfo.FLAG_SYSTEM;
        pkgSetting.pkgPrivateFlags |=
                pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        pkgSetting.pkgPrivateFlags |=
                pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_OEM;
        pkgSetting.pkgPrivateFlags |=
                pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_VENDOR;
        pkgSetting.pkgPrivateFlags |=
                pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRODUCT;
        pkgSetting.pkgPrivateFlags |=
                pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT;
        pkgSetting.pkgPrivateFlags |=
                pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_ODM;
        pkgSetting.primaryCpuAbiString = primaryCpuAbi;
        pkgSetting.secondaryCpuAbiString = secondaryCpuAbi;
        // Update static shared library dependencies if needed
        if (usesStaticLibraries != null && usesStaticLibrariesVersions != null
                && usesStaticLibraries.length == usesStaticLibrariesVersions.length) {
            pkgSetting.usesStaticLibraries = usesStaticLibraries;
            pkgSetting.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        } else {
            pkgSetting.usesStaticLibraries = null;
            pkgSetting.usesStaticLibrariesVersions = null;
        }
        pkgSetting.updateMimeGroups(mimeGroupNames);
        pkgSetting.setDomainSetId(domainSetId);
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
        if (p.appId == 0 || forceNew) {
            // Assign new user ID
            p.appId = acquireAndRegisterNewAppIdLPw(p);
            createdNew = true;
        } else {
            // Add new setting to list of user IDs
            createdNew = registerExistingAppIdLPw(p.appId, p, p.name);
        }
        if (p.appId < 0) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Package " + p.name + " could not be assigned a valid UID");
            throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                    "Package " + p.name + " could not be assigned a valid UID");
        }
        return createdNew;
    }

    /**
     * Writes per-user package restrictions if the user state has changed. If the user
     * state has not changed, this does nothing.
     */
    void writeUserRestrictionsLPw(PackageSetting newPackage, PackageSetting oldPackage) {
        // package doesn't exist; do nothing
        if (getPackageLPr(newPackage.name) == null) {
            return;
        }
        // no users defined; do nothing
        final List<UserInfo> allUsers = getAllUsers(UserManagerService.getInstance());
        if (allUsers == null) {
            return;
        }
        for (UserInfo user : allUsers) {
            final PackageUserState oldUserState = oldPackage == null
                    ? PackageSettingBase.DEFAULT_USER_STATE
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
        if (p.signatures.mSigningDetails.getSignatures() == null) {
            p.signatures.mSigningDetails = pkg.getSigningDetails();
        }
        // If this app defines a shared user id initialize
        // the shared user signatures as well.
        if (p.sharedUser != null
                && p.sharedUser.signatures.mSigningDetails.getSignatures() == null) {
            p.sharedUser.signatures.mSigningDetails = pkg.getSigningDetails();
        }
        addPackageSettingLPw(p, p.sharedUser);
    }

    // Utility method that adds a PackageSetting to mPackages and
    // completes updating the shared user attributes and any restored
    // app link verification state
    private void addPackageSettingLPw(PackageSetting p, SharedUserSetting sharedUser) {
        mPackages.put(p.name, p);
        if (sharedUser != null) {
            if (p.sharedUser != null && p.sharedUser != sharedUser) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Package " + p.name + " was user "
                        + p.sharedUser + " but is now " + sharedUser
                        + "; I am not changing its files so it will probably fail!");
                p.sharedUser.removePackage(p);
            } else if (p.appId != sharedUser.userId) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Package " + p.name + " was user id " + p.appId
                    + " but is now user " + sharedUser
                    + " with id " + sharedUser.userId
                    + "; I am not changing its files so it will probably fail!");
            }

            sharedUser.addPackage(p);
            p.sharedUser = sharedUser;
            p.appId = sharedUser.userId;
        }

        // If the we know about this user id, we have to update it as it
        // has to point to the same PackageSetting instance as the package.
        Object userIdPs = getSettingLPr(p.appId);
        if (sharedUser == null) {
            if (userIdPs != null && userIdPs != p) {
                replaceAppIdLPw(p.appId, p);
            }
        } else {
            if (userIdPs != null && userIdPs != sharedUser) {
                replaceAppIdLPw(p.appId, sharedUser);
            }
        }
    }

    int removePackageLPw(String name) {
        final PackageSetting p = mPackages.get(name);
        if (p != null) {
            mPackages.remove(name);
            removeInstallerPackageStatus(name);
            if (p.sharedUser != null) {
                p.sharedUser.removePackage(p);
                if (p.sharedUser.packages.size() == 0) {
                    mSharedUsers.remove(p.sharedUser.name);
                    removeAppIdLPw(p.sharedUser.userId);
                    return p.sharedUser.userId;
                }
            } else {
                removeAppIdLPw(p.appId);
                return p.appId;
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

    /** Returns true if the requested AppID was valid and not already registered. */
    private boolean registerExistingAppIdLPw(int appId, SettingBase obj, Object name) {
        if (appId > Process.LAST_APPLICATION_UID) {
            return false;
        }

        if (appId >= Process.FIRST_APPLICATION_UID) {
            int size = mAppIds.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            // fill the array until our index becomes valid
            while (index >= size) {
                mAppIds.add(null);
                size++;
            }
            if (mAppIds.get(index) != null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Adding duplicate app id: " + appId
                        + " name=" + name);
                return false;
            }
            mAppIds.set(index, obj);
        } else {
            if (mOtherAppIds.get(appId) != null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Adding duplicate shared id: " + appId
                                + " name=" + name);
                return false;
            }
            mOtherAppIds.put(appId, obj);
        }
        return true;
    }

    /** Gets the setting associated with the provided App ID */
    public SettingBase getSettingLPr(int appId) {
        if (appId >= Process.FIRST_APPLICATION_UID) {
            final int size = mAppIds.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            return index < size ? mAppIds.get(index) : null;
        } else {
            return mOtherAppIds.get(appId);
        }
    }

    /** Unregisters the provided app ID. */
    void removeAppIdLPw(int appId) {
        if (appId >= Process.FIRST_APPLICATION_UID) {
            final int size = mAppIds.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            if (index < size) mAppIds.set(index, null);
        } else {
            mOtherAppIds.remove(appId);
        }
        setFirstAvailableUid(appId + 1);
    }

    private void replaceAppIdLPw(int appId, SettingBase obj) {
        if (appId >= Process.FIRST_APPLICATION_UID) {
            final int size = mAppIds.size();
            final int index = appId - Process.FIRST_APPLICATION_UID;
            if (index < size) mAppIds.set(index, obj);
        } else {
            mOtherAppIds.put(appId, obj);
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

    String removeDefaultBrowserPackageNameLPw(int userId) {
        return (userId == UserHandle.USER_ALL) ? null : mDefaultBrowserApp.removeReturnOld(userId);
    }

    private File getUserPackagesStateFile(int userId) {
        // TODO: Implement a cleaner solution when adding tests.
        // This instead of Environment.getUserSystemDirectory(userId) to support testing.
        File userDir = new File(new File(mSystemDir, "users"), Integer.toString(userId));
        return new File(userDir, "package-restrictions.xml");
    }

    private File getUserRuntimePermissionsFile(int userId) {
        // TODO: Implement a cleaner solution when adding tests.
        // This instead of Environment.getUserSystemDirectory(userId) to support testing.
        File userDir = new File(new File(mSystemDir, "users"), Integer.toString(userId));
        return new File(userDir, RUNTIME_PERMISSIONS_FILE_NAME);
    }

    private File getUserPackagesStateBackupFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId),
                "package-restrictions-backup.xml");
    }

    void writeAllUsersPackageRestrictionsLPr() {
        List<UserInfo> users = getAllUsers(UserManagerService.getInstance());
        if (users == null) return;

        for (UserInfo user : users) {
            writePackageRestrictionsLPr(user.id);
        }
    }

    void writeAllRuntimePermissionsLPr() {
        for (int userId : UserManagerService.getInstance().getUserIds()) {
            mRuntimePermissionsPersistence.writeStateForUserAsyncLPr(userId);
        }
    }

    boolean isPermissionUpgradeNeededLPr(int userId) {
        return mRuntimePermissionsPersistence.isPermissionUpgradeNeeded(userId);
    }

    void updateRuntimePermissionsFingerprintLPr(@UserIdInt int userId) {
        mRuntimePermissionsPersistence.updateRuntimePermissionsFingerprintLPr(userId);
    }

    int getDefaultRuntimePermissionsVersionLPr(int userId) {
        return mRuntimePermissionsPersistence.getVersionLPr(userId);
    }

    void setDefaultRuntimePermissionsVersionLPr(int version, int userId) {
        mRuntimePermissionsPersistence.setVersionLPr(version, userId);
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
                        resolver.addFilter(pa);
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
                editPersistentPreferredActivitiesLPw(userId).addFilter(ppa);
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
                editCrossProfileIntentResolverLPw(userId).addFilter(cpif);
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
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals(TAG_DEFAULT_BROWSER)) {
                String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                mDefaultBrowserApp.put(userId, packageName);
            } else if (tagName.equals(TAG_DEFAULT_DIALER)) {
                // Ignored.
            } else {
                String msg = "Unknown element under " +  TAG_DEFAULT_APPS + ": " +
                        parser.getName();
                PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                XmlUtils.skipCurrentTag(parser);
            }
        }
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

    void readPackageRestrictionsLPr(int userId) {
        if (DEBUG_MU) {
            Log.i(TAG, "Reading package restrictions for user=" + userId);
        }
        FileInputStream str = null;
        File userPackagesStateFile = getUserPackagesStateFile(userId);
        File backupFile = getUserPackagesStateBackupFile(userId);
        if (backupFile.exists()) {
            try {
                str = new FileInputStream(backupFile);
                mReadMessages.append("Reading from backup stopped packages file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO,
                        "Need to read from backup stopped packages file");
                if (userPackagesStateFile.exists()) {
                    // If both the backup and normal file exist, we
                    // ignore the normal one since it might have been
                    // corrupted.
                    Slog.w(PackageManagerService.TAG, "Cleaning up stopped packages file "
                            + userPackagesStateFile);
                    userPackagesStateFile.delete();
                }
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        try {
            if (str == null) {
                if (!userPackagesStateFile.exists()) {
                    mReadMessages.append("No stopped packages file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No stopped packages file; "
                            + "assuming all started");
                    // At first boot, make sure no packages are stopped.
                    // We usually want to have third party apps initialize
                    // in the stopped state, but not at first boot.  Also
                    // consider all applications to be installed.
                    for (PackageSetting pkg : mPackages.values()) {
                        pkg.setUserState(userId, 0, COMPONENT_ENABLED_STATE_DEFAULT,
                                true  /*installed*/,
                                false /*stopped*/,
                                false /*notLaunched*/,
                                false /*hidden*/,
                                0 /*distractionFlags*/,
                                false /*suspended*/,
                                null /*suspendParams*/,
                                false /*instantApp*/,
                                false /*virtualPreload*/,
                                null /*lastDisableAppCaller*/,
                                null /*enabledComponents*/,
                                null /*disabledComponents*/,
                                PackageManager.INSTALL_REASON_UNKNOWN,
                                PackageManager.UNINSTALL_REASON_UNKNOWN,
                                null /*harmfulAppWarning*/,
                                null /* splashScreenTheme*/);
                    }
                    return;
                }
                str = new FileInputStream(userPackagesStateFile);
                if (DEBUG_MU) Log.i(TAG, "Reading " + userPackagesStateFile);
            }
            final TypedXmlPullParser parser = Xml.resolvePullParser(str);

            int type;
            while ((type=parser.next()) != XmlPullParser.START_TAG
                       && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                mReadMessages.append("No start tag found in package restrictions file\n");
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "No start tag found in package manager stopped packages");
                return;
            }

            int outerDepth = parser.getDepth();
            PackageSetting ps = null;
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
                    ps = mPackages.get(name);
                    if (ps == null) {
                        Slog.w(PackageManagerService.TAG, "No package known for stopped package "
                                + name);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }

                    final long ceDataInode =
                            parser.getAttributeLong(null, ATTR_CE_DATA_INODE, 0);
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

                    final int distractionFlags = parser.getAttributeInt(null, ATTR_DISTRACTION_FLAGS, 0);
                    final boolean suspended = parser.getAttributeBoolean(null, ATTR_SUSPENDED, false);
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
                            ATTR_DOMAIN_VERIFICATON_STATE,
                            PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED);
                    final int installReason = parser.getAttributeInt(null, ATTR_INSTALL_REASON,
                            PackageManager.INSTALL_REASON_UNKNOWN);
                    final int uninstallReason = parser.getAttributeInt(null, ATTR_UNINSTALL_REASON,
                            PackageManager.UNINSTALL_REASON_UNKNOWN);
                    final String splashScreenTheme = parser.getAttributeValue(null,
                            ATTR_SPLASH_SCREEN_THEME);

                    ArraySet<String> enabledComponents = null;
                    ArraySet<String> disabledComponents = null;
                    PersistableBundle suspendedAppExtras = null;
                    PersistableBundle suspendedLauncherExtras = null;
                    SuspendDialogInfo oldSuspendDialogInfo = null;

                    int packageDepth = parser.getDepth();
                    ArrayMap<String, PackageUserState.SuspendParams> suspendParamsMap = null;
                    while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
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
                            case TAG_SUSPENDED_APP_EXTRAS:
                                suspendedAppExtras = PersistableBundle.restoreFromXml(parser);
                                break;
                            case TAG_SUSPENDED_LAUNCHER_EXTRAS:
                                suspendedLauncherExtras = PersistableBundle.restoreFromXml(parser);
                                break;
                            case TAG_SUSPENDED_DIALOG_INFO:
                                oldSuspendDialogInfo = SuspendDialogInfo.restoreFromXml(parser);
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
                                        PackageUserState.SuspendParams.restoreFromXml(parser));
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
                        final PackageUserState.SuspendParams suspendParams =
                                PackageUserState.SuspendParams.getInstanceOrNull(
                                        oldSuspendDialogInfo,
                                        suspendedAppExtras,
                                        suspendedLauncherExtras);
                        suspendParamsMap = new ArrayMap<>();
                        suspendParamsMap.put(oldSuspendingPackage, suspendParams);
                    }

                    if (blockUninstall) {
                        setBlockUninstallLPw(userId, name, true);
                    }
                    ps.setUserState(userId, ceDataInode, enabled, installed, stopped, notLaunched,
                            hidden, distractionFlags, suspended, suspendParamsMap,
                            instantApp, virtualPreload, enabledCaller, enabledComponents,
                            disabledComponents, installReason, uninstallReason, harmfulAppWarning,
                            splashScreenTheme);

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
        serializer.startTag(null, TAG_DEFAULT_APPS);
        String defaultBrowser = mDefaultBrowserApp.get(userId);
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

    void writePackageRestrictionsLPr(int userId) {
        invalidatePackageCache();

        if (DEBUG_MU) {
            Log.i(TAG, "Writing package restrictions for user=" + userId);
        }
        final long startTime = SystemClock.uptimeMillis();

        // Keep the old stopped packages around until we know the new ones have
        // been successfully written.
        File userPackagesStateFile = getUserPackagesStateFile(userId);
        File backupFile = getUserPackagesStateBackupFile(userId);
        new File(userPackagesStateFile.getParent()).mkdirs();
        if (userPackagesStateFile.exists()) {
            // Presence of backup settings file indicates that we failed
            // to persist packages earlier. So preserve the older
            // backup for future reference since the current packages
            // might have been corrupted.
            if (!backupFile.exists()) {
                if (!userPackagesStateFile.renameTo(backupFile)) {
                    Slog.wtf(PackageManagerService.TAG,
                            "Unable to backup user packages state file, "
                            + "current changes will be lost at reboot");
                    return;
                }
            } else {
                userPackagesStateFile.delete();
                Slog.w(PackageManagerService.TAG, "Preserving older stopped packages backup");
            }
        }

        try {
            final FileOutputStream fstr = new FileOutputStream(userPackagesStateFile);
            final TypedXmlSerializer serializer = Xml.resolveSerializer(fstr);
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_PACKAGE_RESTRICTIONS);

            if (DEBUG_MU) Log.i(TAG, "Writing " + userPackagesStateFile);
            for (final PackageSetting pkg : mPackages.values()) {
                final PackageUserState ustate = pkg.readUserState(userId);
                if (DEBUG_MU) {
                    Log.i(TAG, "  pkg=" + pkg.name + ", installed=" + ustate.installed
                            + ", state=" + ustate.enabled);
                }

                serializer.startTag(null, TAG_PACKAGE);
                serializer.attribute(null, ATTR_NAME, pkg.name);
                if (ustate.ceDataInode != 0) {
                    serializer.attributeLong(null, ATTR_CE_DATA_INODE, ustate.ceDataInode);
                }
                if (!ustate.installed) {
                    serializer.attributeBoolean(null, ATTR_INSTALLED, false);
                }
                if (ustate.stopped) {
                    serializer.attributeBoolean(null, ATTR_STOPPED, true);
                }
                if (ustate.notLaunched) {
                    serializer.attributeBoolean(null, ATTR_NOT_LAUNCHED, true);
                }
                if (ustate.hidden) {
                    serializer.attributeBoolean(null, ATTR_HIDDEN, true);
                }
                if (ustate.distractionFlags != 0) {
                    serializer.attributeInt(null, ATTR_DISTRACTION_FLAGS, ustate.distractionFlags);
                }
                if (ustate.suspended) {
                    serializer.attributeBoolean(null, ATTR_SUSPENDED, true);
                }
                if (ustate.instantApp) {
                    serializer.attributeBoolean(null, ATTR_INSTANT_APP, true);
                }
                if (ustate.virtualPreload) {
                    serializer.attributeBoolean(null, ATTR_VIRTUAL_PRELOAD, true);
                }
                if (ustate.enabled != COMPONENT_ENABLED_STATE_DEFAULT) {
                    serializer.attributeInt(null, ATTR_ENABLED, ustate.enabled);
                    if (ustate.lastDisableAppCaller != null) {
                        serializer.attribute(null, ATTR_ENABLED_CALLER,
                                ustate.lastDisableAppCaller);
                    }
                }
                if (ustate.installReason != PackageManager.INSTALL_REASON_UNKNOWN) {
                    serializer.attributeInt(null, ATTR_INSTALL_REASON, ustate.installReason);
                }
                if (ustate.uninstallReason != PackageManager.UNINSTALL_REASON_UNKNOWN) {
                    serializer.attributeInt(null, ATTR_UNINSTALL_REASON, ustate.uninstallReason);
                }
                if (ustate.harmfulAppWarning != null) {
                    serializer.attribute(null, ATTR_HARMFUL_APP_WARNING,
                            ustate.harmfulAppWarning);
                }
                if (ustate.splashScreenTheme != null) {
                    serializer.attribute(null, ATTR_SPLASH_SCREEN_THEME,
                            ustate.splashScreenTheme);
                }
                if (ustate.suspended) {
                    for (int i = 0; i < ustate.suspendParams.size(); i++) {
                        final String suspendingPackage = ustate.suspendParams.keyAt(i);
                        serializer.startTag(null, TAG_SUSPEND_PARAMS);
                        serializer.attribute(null, ATTR_SUSPENDING_PACKAGE, suspendingPackage);
                        final PackageUserState.SuspendParams params =
                                ustate.suspendParams.valueAt(i);
                        if (params != null) {
                            params.saveToXml(serializer);
                        }
                        serializer.endTag(null, TAG_SUSPEND_PARAMS);
                    }
                }
                if (!ArrayUtils.isEmpty(ustate.enabledComponents)) {
                    serializer.startTag(null, TAG_ENABLED_COMPONENTS);
                    for (final String name : ustate.enabledComponents) {
                        serializer.startTag(null, TAG_ITEM);
                        serializer.attribute(null, ATTR_NAME, name);
                        serializer.endTag(null, TAG_ITEM);
                    }
                    serializer.endTag(null, TAG_ENABLED_COMPONENTS);
                }
                if (!ArrayUtils.isEmpty(ustate.disabledComponents)) {
                    serializer.startTag(null, TAG_DISABLED_COMPONENTS);
                    for (final String name : ustate.disabledComponents) {
                        serializer.startTag(null, TAG_ITEM);
                        serializer.attribute(null, ATTR_NAME, name);
                        serializer.endTag(null, TAG_ITEM);
                    }
                    serializer.endTag(null, TAG_DISABLED_COMPONENTS);
                }

                serializer.endTag(null, TAG_PACKAGE);
            }

            writePreferredActivitiesLPr(serializer, userId, true);
            writePersistentPreferredActivitiesLPr(serializer, userId);
            writeCrossProfileIntentFiltersLPr(serializer, userId);
            writeDefaultAppsLPr(serializer, userId);
            writeBlockUninstallPackagesLPr(serializer, userId);

            serializer.endTag(null, TAG_PACKAGE_RESTRICTIONS);

            serializer.endDocument();

            fstr.flush();
            FileUtils.sync(fstr);
            fstr.close();

            // New settings successfully written, old ones are no longer
            // needed.
            backupFile.delete();
            FileUtils.setPermissions(userPackagesStateFile.toString(),
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP,
                    -1, -1);

            com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                    "package-user-" + userId, SystemClock.uptimeMillis() - startTime);

            // Done, all is good!
            return;
        } catch(java.io.IOException e) {
            Slog.wtf(PackageManagerService.TAG,
                    "Unable to write package manager user packages state, "
                    + " current changes will be lost at reboot", e);
        }

        // Clean up partially written files
        if (userPackagesStateFile.exists()) {
            if (!userPackagesStateFile.delete()) {
                Log.i(PackageManagerService.TAG, "Failed to clean up mangled file: "
                        + mStoppedPackagesFilename);
            }
        }
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

    void readUsesStaticLibLPw(TypedXmlPullParser parser, PackageSetting outPs)
            throws IOException, XmlPullParserException {
        String libName = parser.getAttributeValue(null, ATTR_NAME);
        long libVersion = parser.getAttributeLong(null, ATTR_VERSION, -1);

        if (libName != null && libVersion >= 0) {
            outPs.usesStaticLibraries = ArrayUtils.appendElement(String.class,
                    outPs.usesStaticLibraries, libName);
            outPs.usesStaticLibrariesVersions = ArrayUtils.appendLong(
                    outPs.usesStaticLibrariesVersions, libVersion);
        }

        XmlUtils.skipCurrentTag(parser);
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
                if (mSettingsFilename.exists()) {
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

    void writeLPr() {
        //Debug.startMethodTracing("/data/system/packageprof", 8 * 1024 * 1024);

        final long startTime = SystemClock.uptimeMillis();

        // Whenever package manager changes something on the system, it writes out whatever it
        // changed in the form of a settings object change, and it does so under its internal
        // lock --- so if we invalidate the package cache here, we end up invalidating at the
        // right time.
        invalidatePackageCache();

        // Keep the old settings around until we know the new ones have
        // been successfully written.
        if (mSettingsFilename.exists()) {
            // Presence of backup settings file indicates that we failed
            // to persist settings earlier. So preserve the older
            // backup for future reference since the current settings
            // might have been corrupted.
            if (!mBackupSettingsFilename.exists()) {
                if (!mSettingsFilename.renameTo(mBackupSettingsFilename)) {
                    Slog.wtf(PackageManagerService.TAG,
                            "Unable to backup package manager settings, "
                            + " current changes will be lost at reboot");
                    return;
                }
            } else {
                mSettingsFilename.delete();
                Slog.w(PackageManagerService.TAG, "Preserving older settings backup");
            }
        }

        mPastSignatures.clear();

        try {
            final FileOutputStream fstr = new FileOutputStream(mSettingsFilename);
            final TypedXmlSerializer serializer = Xml.resolveSerializer(fstr);
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, "packages");

            for (int i = 0; i < mVersion.size(); i++) {
                final String volumeUuid = mVersion.keyAt(i);
                final VersionInfo ver = mVersion.valueAt(i);

                serializer.startTag(null, TAG_VERSION);
                XmlUtils.writeStringAttribute(serializer, ATTR_VOLUME_UUID, volumeUuid);
                serializer.attributeInt(null, ATTR_SDK_VERSION, ver.sdkVersion);
                serializer.attributeInt(null, ATTR_DATABASE_VERSION, ver.databaseVersion);
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
                writePackageLPr(serializer, pkg);
            }

            for (final PackageSetting pkg : mDisabledSysPackages.values()) {
                writeDisabledSysPackageLPr(serializer, pkg);
            }

            for (final SharedUserSetting usr : mSharedUsers.values()) {
                serializer.startTag(null, "shared-user");
                serializer.attribute(null, ATTR_NAME, usr.name);
                serializer.attributeInt(null, "userId", usr.userId);
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

            mDomainVerificationManager.writeSettings(serializer, false /* includeSignatures */,
                    UserHandle.USER_ALL);

            mKeySetManagerService.writeKeySetManagerServiceLPr(serializer);

            serializer.endTag(null, "packages");

            serializer.endDocument();

            fstr.flush();
            FileUtils.sync(fstr);
            fstr.close();

            // New settings successfully written, old ones are no longer
            // needed.
            mBackupSettingsFilename.delete();
            FileUtils.setPermissions(mSettingsFilename.toString(),
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP,
                    -1, -1);

            writeKernelMappingLPr();
            writePackageListLPr();
            writeAllUsersPackageRestrictionsLPr();
            writeAllRuntimePermissionsLPr();
            com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                    "package", SystemClock.uptimeMillis() - startTime);
            return;

        } catch(java.io.IOException e) {
            Slog.wtf(PackageManagerService.TAG, "Unable to write package manager settings, "
                    + "current changes will be lost at reboot", e);
        }
        // Clean up partially written files
        if (mSettingsFilename.exists()) {
            if (!mSettingsFilename.delete()) {
                Slog.wtf(PackageManagerService.TAG, "Failed to clean up mangled file: "
                        + mSettingsFilename);
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
            knownSet.remove(ps.name);
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
        if (mKernelMappingFilename == null || ps == null || ps.name == null) return;

        writeKernelMappingLPr(ps.name, ps.appId, ps.getNotInstalledUserIds());
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
            for (final PackageSetting pkg : mPackages.values()) {
                // TODO(b/135203078): This doesn't handle multiple users
                final String dataPath = pkg.pkg == null ? null :
                        PackageInfoWithoutStateUtils.getDataDir(pkg.pkg,
                                UserHandle.USER_SYSTEM).getAbsolutePath();

                if (pkg.pkg == null || dataPath == null) {
                    if (!"android".equals(pkg.name)) {
                        Slog.w(TAG, "Skipping " + pkg + " due to missing metadata");
                    }
                    continue;
                }

                final boolean isDebug = pkg.pkg.isDebuggable();
                final IntArray gids = new IntArray();
                for (final int userId : userIds) {
                    gids.addAll(mPermissionDataProvider.getGidsForUid(UserHandle.getUid(userId,
                            pkg.appId)));
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
                sb.append(pkg.pkg.getPackageName());
                sb.append(" ");
                sb.append(pkg.pkg.getUid());
                sb.append(isDebug ? " 1 " : " 0 ");
                sb.append(dataPath);
                sb.append(" ");
                sb.append(AndroidPackageUtils.getSeInfo(pkg.pkg, pkg));
                sb.append(" ");
                final int gidsSize = gids.size();
                if (gids != null && gids.size() > 0) {
                    sb.append(gids.get(0));
                    for (int i = 1; i < gidsSize; i++) {
                        sb.append(",");
                        sb.append(gids.get(i));
                    }
                } else {
                    sb.append("none");
                }
                sb.append(" ");
                sb.append(pkg.pkg.isProfileableByShell() ? "1" : "0");
                sb.append(" ");
                sb.append(pkg.pkg.getLongVersionCode());
                sb.append(" ");
                sb.append(pkg.pkg.isProfileable() ? "1" : "0");
                sb.append(" ");
                if (pkg.isSystem()) {
                    sb.append("@system");
                } else if (pkg.isProduct()) {
                    sb.append("@product");
                } else if (pkg.installSource.installerPackageName != null
                           && !pkg.installSource.installerPackageName.isEmpty()) {
                    sb.append(pkg.installSource.installerPackageName);
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
        serializer.attribute(null, ATTR_NAME, pkg.name);
        if (pkg.realName != null) {
            serializer.attribute(null, "realName", pkg.realName);
        }
        serializer.attribute(null, "codePath", pkg.getPathString());
        serializer.attributeLongHex(null, "ft", pkg.timeStamp);
        serializer.attributeLongHex(null, "it", pkg.firstInstallTime);
        serializer.attributeLongHex(null, "ut", pkg.lastUpdateTime);
        serializer.attributeLong(null, "version", pkg.versionCode);
        if (pkg.legacyNativeLibraryPathString != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.legacyNativeLibraryPathString);
        }
        if (pkg.primaryCpuAbiString != null) {
           serializer.attribute(null, "primaryCpuAbi", pkg.primaryCpuAbiString);
        }
        if (pkg.secondaryCpuAbiString != null) {
            serializer.attribute(null, "secondaryCpuAbi", pkg.secondaryCpuAbiString);
        }
        if (pkg.cpuAbiOverrideString != null) {
            serializer.attribute(null, "cpuAbiOverride", pkg.cpuAbiOverrideString);
        }

        if (pkg.sharedUser == null) {
            serializer.attributeInt(null, "userId", pkg.appId);
        } else {
            serializer.attributeInt(null, "sharedUserId", pkg.appId);
        }
        serializer.attributeFloat(null, "loadingProgress",
                pkg.getIncrementalStates().getProgress());

        writeUsesStaticLibLPw(serializer, pkg.usesStaticLibraries, pkg.usesStaticLibrariesVersions);

        serializer.endTag(null, "updated-package");
    }

    void writePackageLPr(TypedXmlSerializer serializer, final PackageSetting pkg)
            throws java.io.IOException {
        serializer.startTag(null, "package");
        serializer.attribute(null, ATTR_NAME, pkg.name);
        if (pkg.realName != null) {
            serializer.attribute(null, "realName", pkg.realName);
        }
        serializer.attribute(null, "codePath", pkg.getPathString());

        if (pkg.legacyNativeLibraryPathString != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.legacyNativeLibraryPathString);
        }
        if (pkg.primaryCpuAbiString != null) {
            serializer.attribute(null, "primaryCpuAbi", pkg.primaryCpuAbiString);
        }
        if (pkg.secondaryCpuAbiString != null) {
            serializer.attribute(null, "secondaryCpuAbi", pkg.secondaryCpuAbiString);
        }
        if (pkg.cpuAbiOverrideString != null) {
            serializer.attribute(null, "cpuAbiOverride", pkg.cpuAbiOverrideString);
        }

        serializer.attributeInt(null, "publicFlags", pkg.pkgFlags);
        serializer.attributeInt(null, "privateFlags", pkg.pkgPrivateFlags);
        serializer.attributeLongHex(null, "ft", pkg.timeStamp);
        serializer.attributeLongHex(null, "it", pkg.firstInstallTime);
        serializer.attributeLongHex(null, "ut", pkg.lastUpdateTime);
        serializer.attributeLong(null, "version", pkg.versionCode);
        if (pkg.sharedUser == null) {
            serializer.attributeInt(null, "userId", pkg.appId);
        } else {
            serializer.attributeInt(null, "sharedUserId", pkg.appId);
        }
        if (pkg.uidError) {
            serializer.attributeBoolean(null, "uidError", true);
        }
        InstallSource installSource = pkg.installSource;
        if (installSource.installerPackageName != null) {
            serializer.attribute(null, "installer", installSource.installerPackageName);
        }
        if (installSource.installerAttributionTag != null) {
            serializer.attribute(null, "installerAttributionTag",
                    installSource.installerAttributionTag);
        }
        if (installSource.isOrphaned) {
            serializer.attributeBoolean(null, "isOrphaned", true);
        }
        if (installSource.initiatingPackageName != null) {
            serializer.attribute(null, "installInitiator", installSource.initiatingPackageName);
        }
        if (installSource.isInitiatingPackageUninstalled) {
            serializer.attributeBoolean(null, "installInitiatorUninstalled", true);
        }
        if (installSource.originatingPackageName != null) {
            serializer.attribute(null, "installOriginator", installSource.originatingPackageName);
        }
        if (pkg.volumeUuid != null) {
            serializer.attribute(null, "volumeUuid", pkg.volumeUuid);
        }
        if (pkg.categoryHint != ApplicationInfo.CATEGORY_UNDEFINED) {
            serializer.attributeInt(null, "categoryHint", pkg.categoryHint);
        }
        if (pkg.updateAvailable) {
            serializer.attributeBoolean(null, "updateAvailable", true);
        }
        if (pkg.forceQueryableOverride) {
            serializer.attributeBoolean(null, "forceQueryable", true);
        }
        if (pkg.isPackageLoading()) {
            serializer.attributeBoolean(null, "isLoading", true);
        }
        serializer.attributeFloat(null, "loadingProgress",
                pkg.getIncrementalStates().getProgress());

        serializer.attribute(null, "domainSetId", pkg.getDomainSetId().toString());

        writeUsesStaticLibLPw(serializer, pkg.usesStaticLibraries, pkg.usesStaticLibrariesVersions);

        pkg.signatures.writeXml(serializer, "sigs", mPastSignatures.untrackedStorage());

        if (installSource.initiatingPackageSignatures != null) {
            installSource.initiatingPackageSignatures.writeXml(
                    serializer, "install-initiator-sigs", mPastSignatures.untrackedStorage());
        }

        writeSigningKeySetLPr(serializer, pkg.keySetData);
        writeUpgradeKeySetsLPr(serializer, pkg.keySetData);
        writeKeySetAliasesLPr(serializer, pkg.keySetData);
        writeMimeGroupLPr(serializer, pkg.mimeGroups);

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

    boolean readLPw(@NonNull List<UserInfo> users) {
        FileInputStream str = null;
        if (mBackupSettingsFilename.exists()) {
            try {
                str = new FileInputStream(mBackupSettingsFilename);
                mReadMessages.append("Reading from backup settings file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO,
                        "Need to read from backup settings file");
                if (mSettingsFilename.exists()) {
                    // If both the backup and settings file exist, we
                    // ignore the settings since it might have been
                    // corrupted.
                    Slog.w(PackageManagerService.TAG, "Cleaning up settings file "
                            + mSettingsFilename);
                    mSettingsFilename.delete();
                }
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        mPendingPackages.clear();
        mPastSignatures.clear();
        mKeySetRefs.clear();
        mInstallerPackages.clear();

        try {
            if (str == null) {
                if (!mSettingsFilename.exists()) {
                    mReadMessages.append("No settings file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No settings file; creating initial state");
                    // It's enough to just touch version details to create them
                    // with default values
                    findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL).forceCurrent();
                    findOrCreateVersion(StorageManager.UUID_PRIMARY_PHYSICAL).forceCurrent();
                    return false;
                }
                str = new FileInputStream(mSettingsFilename);
            }
            final TypedXmlPullParser parser = Xml.resolvePullParser(str);

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
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
                    readPackageLPw(parser, users);
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
                    mKeySetManagerService.readKeySetsLPw(parser, mKeySetRefs.untrackedStorage());
                } else if (TAG_VERSION.equals(tagName)) {
                    final String volumeUuid = XmlUtils.readStringAttribute(parser,
                            ATTR_VOLUME_UUID);
                    final VersionInfo ver = findOrCreateVersion(volumeUuid);
                    ver.sdkVersion = parser.getAttributeInt(null, ATTR_SDK_VERSION);
                    ver.databaseVersion = parser.getAttributeInt(null, ATTR_DATABASE_VERSION);
                    ver.fingerprint = XmlUtils.readStringAttribute(parser, ATTR_FINGERPRINT);
                } else if (tagName.equals(DomainVerificationPersistence.TAG_DOMAIN_VERIFICATIONS)) {
                    mDomainVerificationManager.readSettings(parser);
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
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading settings: " + e);
            Slog.wtf(PackageManagerService.TAG, "Error reading package manager settings", e);
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

        // If the build is setup to drop runtime permissions
        // on update drop the files before loading them.
        if (PackageManagerService.CLEAR_RUNTIME_PERMISSIONS_ON_UPGRADE) {
            final VersionInfo internal = getInternalVersion();
            if (!Build.FINGERPRINT.equals(internal.fingerprint)) {
                for (UserInfo user : users) {
                    mRuntimePermissionsPersistence.deleteUserRuntimePermissionsFile(user.id);
                }
            }
        }

        final int N = mPendingPackages.size();

        for (int i = 0; i < N; i++) {
            final PackageSetting p = mPendingPackages.get(i);
            final int sharedUserId = p.getSharedUserId();
            final Object idObj = getSettingLPr(sharedUserId);
            if (idObj instanceof SharedUserSetting) {
                final SharedUserSetting sharedUser = (SharedUserSetting) idObj;
                p.sharedUser = sharedUser;
                p.appId = sharedUser.userId;
                addPackageSettingLPw(p, sharedUser);
            } else if (idObj != null) {
                String msg = "Bad package setting: package " + p.name + " has shared uid "
                        + sharedUserId + " that is not a shared uid\n";
                mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(Log.ERROR, msg);
            } else {
                String msg = "Bad package setting: package " + p.name + " has shared uid "
                        + sharedUserId + " that is not defined\n";
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
            writePackageRestrictionsLPr(UserHandle.USER_SYSTEM);
        } else {
            for (UserInfo user : users) {
                readPackageRestrictionsLPr(user.id);
            }
        }

        for (UserInfo user : users) {
            mRuntimePermissionsPersistence.readStateForUserSyncLPr(user.id);
        }

        /*
         * Make sure all the updated system packages have their shared users
         * associated with them.
         */
        final Iterator<PackageSetting> disabledIt = mDisabledSysPackages.values().iterator();
        while (disabledIt.hasNext()) {
            final PackageSetting disabledPs = disabledIt.next();
            final Object id = getSettingLPr(disabledPs.appId);
            if (id != null && id instanceof SharedUserSetting) {
                disabledPs.sharedUser = (SharedUserSetting) id;
            }
        }

        mReadMessages.append("Read completed successfully: " + mPackages.size() + " packages, "
                + mSharedUsers.size() + " shared uids\n");

        writeKernelMappingLPr();

        return true;
    }

    void readPermissionStateForUserSyncLPr(@UserIdInt int userId) {
        mRuntimePermissionsPersistence.readStateForUserSyncLPr(userId);
    }

    void applyDefaultPreferredAppsLPw(int userId) {
        // First pull data from any pre-installed apps.
        final PackageManagerInternal pmInternal =
                LocalServices.getService(PackageManagerInternal.class);
        for (PackageSetting ps : mPackages.values()) {
            if ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0 && ps.pkg != null
                    && !ps.pkg.getPreferredActivityFilters().isEmpty()) {
                List<Pair<String, ParsedIntentInfo>> intents
                        = ps.pkg.getPreferredActivityFilters();
                for (int i=0; i<intents.size(); i++) {
                    Pair<String, ParsedIntentInfo> pair = intents.get(i);
                    applyDefaultPreferredActivityLPw(pmInternal,
                            new WatchedIntentFilter(pair.second),
                            new ComponentName(ps.name, pair.first), userId);
                }
            }
        }

        // Read preferred apps from .../etc/preferred-apps directories.
        int size = PackageManagerService.SYSTEM_PARTITIONS.size();
        for (int index = 0; index < size; index++) {
            PackageManagerService.ScanPartition partition =
                    PackageManagerService.SYSTEM_PARTITIONS.get(index);

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
            WatchedIntentFilter tmpPa, ComponentName cn, int userId) {
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
                        intent, intent.getType(), flags, Binder.getCallingUid(), 0);
        if (PackageManagerService.DEBUG_PREFERRED) {
            Log.d(TAG, "Queried " + intent + " results: " + ri);
        }
        int systemMatch = 0;
        int thirdPartyMatch = 0;
        final int numMatches = (ri == null ? 0 : ri.size());
        if (numMatches <= 1) {
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
            pir.addFilter(pa);
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
                            pmInternal, tmpPa, tmpPa.mPref.mComponent, userId);
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
        PackageSetting ps = new PackageSetting(name, realName, new File(codePathStr),
                legacyNativeLibraryPathStr, primaryCpuAbiStr, secondaryCpuAbiStr, cpuAbiOverrideStr,
                versionCode, pkgFlags, pkgPrivateFlags, 0 /*sharedUserId*/, null, null, null,
                domainSetId);
        long timeStamp = parser.getAttributeLongHex(null, "ft", 0);
        if (timeStamp == 0) {
            timeStamp = parser.getAttributeLong(null, "ts", 0);
        }
        ps.setTimeStamp(timeStamp);
        ps.firstInstallTime = parser.getAttributeLongHex(null, "it", 0);
        ps.lastUpdateTime = parser.getAttributeLongHex(null, "ut", 0);
        ps.appId = parser.getAttributeInt(null, "userId", 0);
        if (ps.appId <= 0) {
            ps.appId = parser.getAttributeInt(null, "sharedUserId", 0);
        }
        final float loadingProgress =
                parser.getAttributeFloat(null, "loadingProgress", 0);
        ps.setLoadingProgress(loadingProgress);

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals(TAG_PERMISSIONS)) {
                readInstallPermissionsLPr(parser, ps.getLegacyPermissionState(), users);
            } else if (parser.getName().equals(TAG_USES_STATIC_LIB)) {
                readUsesStaticLibLPw(parser, ps);
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <updated-package>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }

        mDisabledSysPackages.put(name, ps);
    }

    private static int PRE_M_APP_INFO_FLAG_HIDDEN = 1<<27;
    private static int PRE_M_APP_INFO_FLAG_CANT_SAVE_STATE = 1<<28;
    private static int PRE_M_APP_INFO_FLAG_PRIVILEGED = 1<<30;

    private void readPackageLPw(TypedXmlPullParser parser, List<UserInfo> users)
            throws XmlPullParserException, IOException {
        String name = null;
        String realName = null;
        int userId = 0;
        int sharedUserId = 0;
        String codePathStr = null;
        String legacyCpuAbiString = null;
        String legacyNativeLibraryPathStr = null;
        String primaryCpuAbiString = null;
        String secondaryCpuAbiString = null;
        String cpuAbiOverrideString = null;
        String systemStr = null;
        String installerPackageName = null;
        String installerAttributionTag = null;
        boolean isOrphaned = false;
        String installOriginatingPackageName = null;
        String installInitiatingPackageName = null;
        boolean installInitiatorUninstalled = false;
        String volumeUuid = null;
        boolean updateAvailable = false;
        int categoryHint = ApplicationInfo.CATEGORY_UNDEFINED;
        boolean uidError = false;
        int pkgFlags = 0;
        int pkgPrivateFlags = 0;
        long timeStamp = 0;
        long firstInstallTime = 0;
        long lastUpdateTime = 0;
        PackageSetting packageSetting = null;
        long versionCode = 0;
        boolean installedForceQueryable = false;
        boolean isLoading = false;
        float loadingProgress = 0;
        UUID domainSetId;
        try {
            name = parser.getAttributeValue(null, ATTR_NAME);
            realName = parser.getAttributeValue(null, "realName");
            userId = parser.getAttributeInt(null, "userId", 0);
            uidError = parser.getAttributeBoolean(null, "uidError", false);
            sharedUserId = parser.getAttributeInt(null, "sharedUserId", 0);
            codePathStr = parser.getAttributeValue(null, "codePath");

            legacyCpuAbiString = parser.getAttributeValue(null, "requiredCpuAbi");

            legacyNativeLibraryPathStr = parser.getAttributeValue(null, "nativeLibraryPath");
            primaryCpuAbiString = parser.getAttributeValue(null, "primaryCpuAbi");
            secondaryCpuAbiString = parser.getAttributeValue(null, "secondaryCpuAbi");
            cpuAbiOverrideString = parser.getAttributeValue(null, "cpuAbiOverride");
            updateAvailable = parser.getAttributeBoolean(null, "updateAvailable", false);
            installedForceQueryable = parser.getAttributeBoolean(null, "forceQueryable", false);
            isLoading = parser.getAttributeBoolean(null, "isLoading", false);
            loadingProgress = parser.getAttributeFloat(null, "loadingProgress", 0);

            if (primaryCpuAbiString == null && legacyCpuAbiString != null) {
                primaryCpuAbiString = legacyCpuAbiString;
            }

            versionCode = parser.getAttributeLong(null, "version", 0);
            installerPackageName = parser.getAttributeValue(null, "installer");
            installerAttributionTag = parser.getAttributeValue(null, "installerAttributionTag");
            isOrphaned = parser.getAttributeBoolean(null, "isOrphaned", false);
            installInitiatingPackageName = parser.getAttributeValue(null, "installInitiator");
            installOriginatingPackageName = parser.getAttributeValue(null, "installOriginator");
            installInitiatorUninstalled = parser.getAttributeBoolean(null,
                    "installInitiatorUninstalled", false);
            volumeUuid = parser.getAttributeValue(null, "volumeUuid");
            categoryHint = parser.getAttributeInt(null, "categoryHint",
                    ApplicationInfo.CATEGORY_UNDEFINED);

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
                Log.v(PackageManagerService.TAG, "Reading package: " + name + " userId=" + userId
                        + " sharedUserId=" + sharedUserId);
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
            } else if (userId > 0) {
                packageSetting = addPackageLPw(name.intern(), realName, new File(codePathStr),
                        legacyNativeLibraryPathStr, primaryCpuAbiString, secondaryCpuAbiString,
                        cpuAbiOverrideString, userId, versionCode, pkgFlags, pkgPrivateFlags,
                        null /*usesStaticLibraries*/, null /*usesStaticLibraryVersions*/,
                        null /*mimeGroups*/, domainSetId);
                if (PackageManagerService.DEBUG_SETTINGS)
                    Log.i(PackageManagerService.TAG, "Reading package " + name + ": userId="
                            + userId + " pkg=" + packageSetting);
                if (packageSetting == null) {
                    PackageManagerService.reportSettingsProblem(Log.ERROR, "Failure adding uid "
                            + userId + " while parsing settings at "
                            + parser.getPositionDescription());
                } else {
                    packageSetting.setTimeStamp(timeStamp);
                    packageSetting.firstInstallTime = firstInstallTime;
                    packageSetting.lastUpdateTime = lastUpdateTime;
                }
            } else if (sharedUserId != 0) {
                if (sharedUserId > 0) {
                    packageSetting = new PackageSetting(name.intern(), realName,
                            new File(codePathStr), legacyNativeLibraryPathStr,
                            primaryCpuAbiString, secondaryCpuAbiString, cpuAbiOverrideString,
                            versionCode, pkgFlags, pkgPrivateFlags, sharedUserId,
                            null /*usesStaticLibraries*/,
                            null /*usesStaticLibraryVersions*/,
                            null /*mimeGroups*/, domainSetId);
                    packageSetting.setTimeStamp(timeStamp);
                    packageSetting.firstInstallTime = firstInstallTime;
                    packageSetting.lastUpdateTime = lastUpdateTime;
                    mPendingPackages.add(packageSetting);
                    if (PackageManagerService.DEBUG_SETTINGS)
                        Log.i(PackageManagerService.TAG, "Reading package " + name
                                + ": sharedUserId=" + sharedUserId + " pkg=" + packageSetting);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: package " + name
                                    + " has bad sharedId " + sharedUserId + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: package " + name + " has bad userId "
                                + userId + " at " + parser.getPositionDescription());
            }
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: package " + name + " has bad userId "
                            + userId + " at " + parser.getPositionDescription());
        }
        if (packageSetting != null) {
            packageSetting.uidError = uidError;
            InstallSource installSource = InstallSource.create(
                    installInitiatingPackageName, installOriginatingPackageName,
                    installerPackageName, installerAttributionTag, isOrphaned,
                    installInitiatorUninstalled);
            packageSetting.installSource = installSource;
            packageSetting.volumeUuid = volumeUuid;
            packageSetting.categoryHint = categoryHint;
            packageSetting.legacyNativeLibraryPathString = legacyNativeLibraryPathStr;
            packageSetting.primaryCpuAbiString = primaryCpuAbiString;
            packageSetting.secondaryCpuAbiString = secondaryCpuAbiString;
            packageSetting.updateAvailable = updateAvailable;
            packageSetting.forceQueryableOverride = installedForceQueryable;
            packageSetting.incrementalStates = new IncrementalStates(isLoading, loadingProgress);
            // Handle legacy string here for single-user mode
            final String enabledStr = parser.getAttributeValue(null, ATTR_ENABLED);
            if (enabledStr != null) {
                try {
                    packageSetting.setEnabled(Integer.parseInt(enabledStr), 0 /* userId */, null);
                } catch (NumberFormatException e) {
                    if (enabledStr.equalsIgnoreCase("true")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 0, null);
                    } else if (enabledStr.equalsIgnoreCase("false")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0, null);
                    } else if (enabledStr.equalsIgnoreCase("default")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0, null);
                    } else {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: package " + name
                                        + " has bad enabled value: " + enabledStr + " at "
                                        + parser.getPositionDescription());
                    }
                }
            } else {
                packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0, null);
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
                    packageSetting.signatures.readXml(parser, mPastSignatures.untrackedStorage());
                } else if (tagName.equals(TAG_PERMISSIONS)) {
                    readInstallPermissionsLPr(parser,
                            packageSetting.getLegacyPermissionState(), users);
                    packageSetting.installPermissionsFixed = true;
                } else if (tagName.equals("proper-signing-keyset")) {
                    long id = parser.getAttributeLong(null, "identifier");
                    Integer refCt = mKeySetRefs.get(id);
                    if (refCt != null) {
                        mKeySetRefs.put(id, refCt + 1);
                    } else {
                        mKeySetRefs.put(id, 1);
                    }
                    packageSetting.keySetData.setProperSigningKeySet(id);
                } else if (tagName.equals("signing-keyset")) {
                    // from v1 of keysetmanagerservice - no longer used
                } else if (tagName.equals("upgrade-keyset")) {
                    long id = parser.getAttributeLong(null, "identifier");
                    packageSetting.keySetData.addUpgradeKeySetById(id);
                } else if (tagName.equals("defined-keyset")) {
                    long id = parser.getAttributeLong(null, "identifier");
                    String alias = parser.getAttributeValue(null, "alias");
                    Integer refCt = mKeySetRefs.get(id);
                    if (refCt != null) {
                        mKeySetRefs.put(id, refCt + 1);
                    } else {
                        mKeySetRefs.put(id, 1);
                    }
                    packageSetting.keySetData.addDefinedKeySet(id, alias);
                } else if (tagName.equals("install-initiator-sigs")) {
                    final PackageSignatures signatures = new PackageSignatures();
                    signatures.readXml(parser, mPastSignatures.untrackedStorage());
                    packageSetting.installSource =
                            packageSetting.installSource.setInitiatingPackageSignatures(signatures);
                } else if (tagName.equals(TAG_DOMAIN_VERIFICATION)) {
                    IntentFilterVerificationInfo ivi = new IntentFilterVerificationInfo(parser);
                    mDomainVerificationManager.addLegacySetting(packageSetting.name, ivi);
                    if (DEBUG_PARSER) {
                        Log.d(TAG, "Read domain verification for package: " + ivi.getPackageName());
                    }
                } else if (tagName.equals(TAG_MIME_GROUP)) {
                    packageSetting.mimeGroups = readMimeGroupLPw(parser, packageSetting.mimeGroups);
                } else if (tagName.equals(TAG_USES_STATIC_LIB)) {
                    readUsesStaticLibLPw(parser, packageSetting);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element under <package>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }

    void addInstallerPackageNames(InstallSource installSource) {
        if (installSource.installerPackageName != null) {
            mInstallerPackages.add(installSource.installerPackageName);
        }
        if (installSource.initiatingPackageName != null) {
            mInstallerPackages.add(installSource.initiatingPackageName);
        }
        if (installSource.originatingPackageName != null) {
            mInstallerPackages.add(installSource.originatingPackageName);
        }
    }

    private Map<String, ArraySet<String>> readMimeGroupLPw(TypedXmlPullParser parser,
            Map<String, ArraySet<String>> mimeGroups) throws XmlPullParserException, IOException {
        String groupName = parser.getAttributeValue(null, ATTR_NAME);
        if (groupName == null) {
            XmlUtils.skipCurrentTag(parser);
            return mimeGroups;
        }

        if (mimeGroups == null) {
            mimeGroups = new ArrayMap<>();
        }

        ArraySet<String> mimeTypes = mimeGroups.get(groupName);
        if (mimeTypes == null) {
            mimeTypes = new ArraySet<>();
            mimeGroups.put(groupName, mimeTypes);
        }
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

        return mimeGroups;
    }

    private void writeMimeGroupLPr(TypedXmlSerializer serializer,
            Map<String, ArraySet<String>> mimeGroups) throws IOException {
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

    private void readDisabledComponentsLPw(PackageSettingBase packageSetting,
            TypedXmlPullParser parser, int userId) throws IOException, XmlPullParserException {
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

    private void readEnabledComponentsLPw(PackageSettingBase packageSetting,
            TypedXmlPullParser parser, int userId) throws IOException, XmlPullParserException {
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
            int userId = parser.getAttributeInt(null, "userId", 0);
            if (parser.getAttributeBoolean(null, "system", false)) {
                pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
            }
            if (name == null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <shared-user> has no name at "
                                + parser.getPositionDescription());
            } else if (userId == 0) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: shared-user " + name
                                + " has bad userId " + userId + " at "
                                + parser.getPositionDescription());
            } else {
                if ((su = addSharedUserLPw(name.intern(), userId, pkgFlags, pkgPrivateFlags))
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
        final boolean skipPackageWhitelist = userTypeInstallablePackages == null;
        synchronized (mLock) {
            final int size = mPackages.size();
            for (int i = 0; i < size; i++) {
                final PackageSetting ps = mPackages.valueAt(i);
                if (ps.pkg == null) {
                    continue;
                }
                final boolean shouldMaybeInstall = ps.isSystem() &&
                        !ArrayUtils.contains(disallowedPackages, ps.name) &&
                        !ps.getPkgState().isHiddenUntilInstalled();
                final boolean shouldReallyInstall = shouldMaybeInstall &&
                        (skipPackageWhitelist || userTypeInstallablePackages.contains(ps.name));
                // Only system apps are initially installed.
                ps.setInstalled(shouldReallyInstall, userHandle);
                // If userTypeInstallablePackages is the *only* reason why we're not installing,
                // then uninstallReason is USER_TYPE. If there's a different reason, or if we
                // actually are installing, put UNKNOWN.
                final int uninstallReason = (shouldMaybeInstall && !shouldReallyInstall) ?
                        UNINSTALL_REASON_USER_TYPE : UNINSTALL_REASON_UNKNOWN;
                ps.setUninstallReason(uninstallReason, userHandle);
                if (shouldReallyInstall) {
                    // Need to create a data directory for all apps installed for this user.
                    // Accumulate all required args and call the installer after mPackages lock
                    // has been released
                    final String seInfo = AndroidPackageUtils.getSeInfo(ps.pkg, ps);
                    batch.createAppData(ps.volumeUuid, ps.name, userHandle,
                            StorageManager.FLAG_STORAGE_CE | StorageManager.FLAG_STORAGE_DE,
                            ps.appId, seInfo, ps.pkg.getTargetSdkVersion());
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
        File file = getUserPackagesStateFile(userId);
        file.delete();
        file = getUserPackagesStateBackupFile(userId);
        file.delete();
        removeCrossProfileIntentFiltersLPw(userId);

        mRuntimePermissionsPersistence.onUserRemovedLPw(userId);
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

    // This should be called (at least) whenever an application is removed
    private void setFirstAvailableUid(int uid) {
        if (uid > mFirstAvailableUid) {
            mFirstAvailableUid = uid;
        }
    }

    /** Returns a new AppID or -1 if we could not find an available AppID to assign */
    private int acquireAndRegisterNewAppIdLPw(SettingBase obj) {
        // Let's be stupidly inefficient for now...
        final int size = mAppIds.size();
        for (int i = mFirstAvailableUid; i < size; i++) {
            if (mAppIds.get(i) == null) {
                mAppIds.set(i, obj);
                return Process.FIRST_APPLICATION_UID + i;
            }
        }

        // None left?
        if (size > (Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID)) {
            return -1;
        }

        mAppIds.add(obj);
        return Process.FIRST_APPLICATION_UID + size;
    }

    public VerifierDeviceIdentity getVerifierDeviceIdentityLPw() {
        if (mVerifierDeviceIdentity == null) {
            mVerifierDeviceIdentity = VerifierDeviceIdentity.generate();

            writeLPr();
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
        return getDisabledSystemPkgLPr(enabledPackageSetting.name);
    }

    boolean isEnabledAndMatchLPr(ComponentInfo componentInfo, int flags, int userId) {
        final PackageSetting ps = mPackages.get(componentInfo.packageName);
        if (ps == null) return false;

        final PackageUserState userState = ps.readUserState(userId);
        return userState.isMatch(componentInfo, flags);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isEnabledAndMatchLPr(AndroidPackage pkg, ParsedMainComponent component,
            int flags, int userId) {
        final PackageSetting ps = mPackages.get(component.getPackageName());
        if (ps == null) return false;

        final PackageUserState userState = ps.readUserState(userId);
        return userState.isMatch(pkg.isSystem(), pkg.isEnabled(), component, flags);
    }

    boolean isOrphaned(String packageName) {
        final PackageSetting pkg = mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkg.installSource.isOrphaned;
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

    boolean wasPackageEverLaunchedLPr(String packageName, int userId) {
        final PackageSetting pkgSetting = mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return !pkgSetting.getNotLaunched(userId);
    }

    boolean setPackageStoppedStateLPw(PackageManagerService pm, String packageName,
            boolean stopped, int userId) {
        final PackageSetting pkgSetting = mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        if (DEBUG_STOPPED) {
            if (stopped) {
                RuntimeException e = new RuntimeException("here");
                e.fillInStackTrace();
                Slog.i(TAG, "Stopping package " + packageName, e);
            }
        }
        if (pkgSetting.getStopped(userId) != stopped) {
            pkgSetting.setStopped(stopped, userId);
            // pkgSetting.pkg.mSetStopped = stopped;
            if (pkgSetting.getNotLaunched(userId)) {
                if (pkgSetting.installSource.installerPackageName != null) {
                    pm.notifyFirstLaunch(pkgSetting.name,
                            pkgSetting.installSource.installerPackageName, userId);
                }
                pkgSetting.setNotLaunched(false, userId);
            }
            return true;
        }
        return false;
    }

    void setHarmfulAppWarningLPw(String packageName, CharSequence warning, int userId) {
        final PackageSetting pkgSetting = mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        pkgSetting.setHarmfulAppWarning(userId, warning == null ? null : warning.toString());
    }

    String getHarmfulAppWarningLPr(String packageName, int userId) {
        final PackageSetting pkgSetting = mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkgSetting.getHarmfulAppWarning(userId);
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
    List<PackageSetting> getVolumePackagesLPr(String volumeUuid) {
        ArrayList<PackageSetting> res = new ArrayList<>();
        for (int i = 0; i < mPackages.size(); i++) {
            final PackageSetting setting = mPackages.valueAt(i);
            if (Objects.equals(volumeUuid, setting.volumeUuid)) {
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
            pw.printPair("fingerprint", ver.fingerprint);
            pw.println();
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    void dumpPackageLPr(PrintWriter pw, String prefix, String checkinTag,
            ArraySet<String> permissionNames, PackageSetting ps,
            LegacyPermissionState permissionsState, SimpleDateFormat sdf, Date date,
            List<UserInfo> users, boolean dumpAll, boolean dumpAllComponents) {
        AndroidPackage pkg = ps.pkg;
        if (checkinTag != null) {
            pw.print(checkinTag);
            pw.print(",");
            pw.print(ps.realName != null ? ps.realName : ps.name);
            pw.print(",");
            pw.print(ps.appId);
            pw.print(",");
            pw.print(ps.versionCode);
            pw.print(",");
            pw.print(ps.firstInstallTime);
            pw.print(",");
            pw.print(ps.lastUpdateTime);
            pw.print(",");
            pw.print(ps.installSource.installerPackageName != null
                    ? ps.installSource.installerPackageName : "?");
            pw.print(ps.installSource.installerAttributionTag != null
                    ? "(" + ps.installSource.installerAttributionTag + ")" : "");
            pw.println();
            if (pkg != null) {
                pw.print(checkinTag); pw.print("-"); pw.print("splt,");
                pw.print("base,");
                pw.println(pkg.getBaseRevisionCode());
                if (pkg.getSplitNames() != null) {
                    int[] splitRevisionCodes = pkg.getSplitRevisionCodes();
                    for (int i = 0; i < pkg.getSplitNames().length; i++) {
                        pw.print(checkinTag); pw.print("-"); pw.print("splt,");
                        pw.print(pkg.getSplitNames()[i]); pw.print(",");
                        pw.println(splitRevisionCodes[i]);
                    }
                }
            }
            for (UserInfo user : users) {
                pw.print(checkinTag);
                pw.print("-");
                pw.print("usr");
                pw.print(",");
                pw.print(user.id);
                pw.print(",");
                pw.print(ps.getInstalled(user.id) ? "I" : "i");
                pw.print(ps.getHidden(user.id) ? "B" : "b");
                pw.print(ps.getSuspended(user.id) ? "SU" : "su");
                pw.print(ps.getStopped(user.id) ? "S" : "s");
                pw.print(ps.getNotLaunched(user.id) ? "l" : "L");
                pw.print(ps.getInstantApp(user.id) ? "IA" : "ia");
                pw.print(ps.getVirtulalPreload(user.id) ? "VPI" : "vpi");
                String harmfulAppWarning = ps.getHarmfulAppWarning(user.id);
                pw.print(harmfulAppWarning != null ? "HA" : "ha");
                pw.print(",");
                pw.print(ps.getEnabled(user.id));
                String lastDisabledAppCaller = ps.getLastDisabledAppCaller(user.id);
                pw.print(",");
                pw.print(lastDisabledAppCaller != null ? lastDisabledAppCaller : "?");
                pw.print(",");
                pw.println();
            }
            return;
        }

        pw.print(prefix); pw.print("Package [");
            pw.print(ps.realName != null ? ps.realName : ps.name);
            pw.print("] (");
            pw.print(Integer.toHexString(System.identityHashCode(ps)));
            pw.println("):");

        if (ps.realName != null) {
            pw.print(prefix); pw.print("  compat name=");
            pw.println(ps.name);
        }

        pw.print(prefix); pw.print("  userId="); pw.println(ps.appId);

        if (ps.sharedUser != null) {
            pw.print(prefix); pw.print("  sharedUser="); pw.println(ps.sharedUser);
        }
        pw.print(prefix); pw.print("  pkg="); pw.println(pkg);
        pw.print(prefix); pw.print("  codePath="); pw.println(ps.getPathString());
        if (permissionNames == null) {
            pw.print(prefix); pw.print("  resourcePath="); pw.println(ps.getPathString());
            pw.print(prefix); pw.print("  legacyNativeLibraryDir=");
            pw.println(ps.legacyNativeLibraryPathString);
            pw.print(prefix); pw.print("  extractNativeLibs=");
            pw.println((ps.pkgFlags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0
                    ? "true" : "false");
            pw.print(prefix); pw.print("  primaryCpuAbi="); pw.println(ps.primaryCpuAbiString);
            pw.print(prefix); pw.print("  secondaryCpuAbi="); pw.println(ps.secondaryCpuAbiString);
            pw.print(prefix); pw.print("  cpuAbiOverride="); pw.println(ps.cpuAbiOverrideString);
        }
        pw.print(prefix); pw.print("  versionCode="); pw.print(ps.versionCode);
        if (pkg != null) {
            pw.print(" minSdk="); pw.print(pkg.getMinSdkVersion());
            pw.print(" targetSdk="); pw.println(pkg.getTargetSdkVersion());

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
            pw.print(prefix); pw.print("  usesNonSdkApi="); pw.println(pkg.isUsesNonSdkApi());
            pw.print(prefix); pw.print("  splits="); dumpSplitNames(pw, pkg); pw.println();
            final int apkSigningVersion = pkg.getSigningDetails().getSignatureSchemeVersion();
            pw.print(prefix); pw.print("  apkSigningVersion="); pw.println(apkSigningVersion);
            pw.print(prefix); pw.print("  applicationInfo=");
            pw.println(pkg.toAppInfoToString());
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
            pw.print(prefix); pw.print("  forceQueryable="); pw.print(ps.pkg.isForceQueryable());
            if (ps.forceQueryableOverride) {
                pw.print(" (override=true)");
            }
            pw.println();
            if (ps.pkg.getQueriesPackages().isEmpty()) {
                pw.append(prefix).append("  queriesPackages=").println(ps.pkg.getQueriesPackages());
            }
            if (!ps.pkg.getQueriesIntents().isEmpty()) {
                pw.append(prefix).append("  queriesIntents=").println(ps.pkg.getQueriesIntents());
            }
            File dataDir = PackageInfoWithoutStateUtils.getDataDir(pkg, UserHandle.myUserId());
            pw.print(prefix); pw.print("  dataDir="); pw.println(dataDir.getAbsolutePath());
            pw.print(prefix); pw.print("  supportsScreens=[");
            boolean first = true;
            if (pkg.isSupportsSmallScreens()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("small");
            }
            if (pkg.isSupportsNormalScreens()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("medium");
            }
            if (pkg.isSupportsLargeScreens()) {
                if (!first)
                    pw.print(", ");
                first = false;
                pw.print("large");
            }
            if (pkg.isSupportsExtraLargeScreens()) {
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
            if (pkg.getStaticSharedLibName() != null) {
                pw.print(prefix); pw.println("  static library:");
                pw.print(prefix); pw.print("    ");
                pw.print("name:"); pw.print(pkg.getStaticSharedLibName());
                pw.print(" version:"); pw.println(pkg.getStaticSharedLibVersion());
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
            date.setTime(ps.timeStamp);
            pw.println(sdf.format(date));
        pw.print(prefix); pw.print("  firstInstallTime=");
            date.setTime(ps.firstInstallTime);
            pw.println(sdf.format(date));
        pw.print(prefix); pw.print("  lastUpdateTime=");
            date.setTime(ps.lastUpdateTime);
            pw.println(sdf.format(date));
        if (ps.installSource.installerPackageName != null) {
            pw.print(prefix); pw.print("  installerPackageName=");
            pw.println(ps.installSource.installerPackageName);
        }
        if (ps.installSource.installerAttributionTag != null) {
            pw.print(prefix); pw.print("  installerAttributionTag=");
            pw.println(ps.installSource.installerAttributionTag);
        }
        if (ps.isPackageLoading()) {
            pw.print(prefix); pw.println("  loadingProgress="
                    + (int) (ps.getIncrementalStates().getProgress() * 100) + "%");
        }
        if (ps.volumeUuid != null) {
            pw.print(prefix); pw.print("  volumeUuid=");
                    pw.println(ps.volumeUuid);
        }
        pw.print(prefix); pw.print("  signatures="); pw.println(ps.signatures);
        pw.print(prefix); pw.print("  installPermissionsFixed=");
                pw.print(ps.installPermissionsFixed);
                pw.println();
        pw.print(prefix); pw.print("  pkgFlags="); printFlags(pw, ps.pkgFlags, FLAG_DUMP_SPEC);
                pw.println();

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
            final List<String> perms = pkg.getRequestedPermissions();
            pw.print(prefix); pw.println("  requested permissions:");
            for (int i=0; i<perms.size(); i++) {
                String perm = perms.get(i);
                if (permissionNames != null
                        && !permissionNames.contains(perm)) {
                    continue;
                }
                pw.print(prefix); pw.print("    "); pw.println(perm);
            }
        }

        if (ps.sharedUser == null || permissionNames != null || dumpAll) {
            dumpInstallPermissionsLPr(pw, prefix + "  ", permissionNames, permissionsState, users);
        }

        if (dumpAllComponents) {
            dumpComponents(pw, prefix + "  ", ps);
        }

        for (UserInfo user : users) {
            pw.print(prefix); pw.print("  User "); pw.print(user.id); pw.print(": ");
            pw.print("ceDataInode=");
            pw.print(ps.getCeDataInode(user.id));
            pw.print(" installed=");
            pw.print(ps.getInstalled(user.id));
            pw.print(" hidden=");
            pw.print(ps.getHidden(user.id));
            pw.print(" suspended=");
            pw.print(ps.getSuspended(user.id));
            pw.print(" distractionFlags=");
            pw.print(ps.getDistractionFlags(user.id));
            pw.print(" stopped=");
            pw.print(ps.getStopped(user.id));
            pw.print(" notLaunched=");
            pw.print(ps.getNotLaunched(user.id));
            pw.print(" enabled=");
            pw.print(ps.getEnabled(user.id));
            pw.print(" instant=");
            pw.print(ps.getInstantApp(user.id));
            pw.print(" virtual=");
            pw.println(ps.getVirtulalPreload(user.id));

            if (ps.getSuspended(user.id)) {
                pw.print(prefix);
                pw.println("  Suspend params:");
                final PackageUserState pus = ps.readUserState(user.id);
                for (int i = 0; i < pus.suspendParams.size(); i++) {
                    pw.print(prefix);
                    pw.print("    suspendingPackage=");
                    pw.print(pus.suspendParams.keyAt(i));
                    final PackageUserState.SuspendParams params = pus.suspendParams.valueAt(i);
                    if (params != null) {
                        pw.print(" dialogInfo=");
                        pw.print(params.dialogInfo);
                    }
                    pw.println();
                }
            }

            final OverlayPaths overlayPaths = ps.getOverlayPaths(user.id);
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
                    ps.getOverlayPathsForLibrary(user.id);
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

            String lastDisabledAppCaller = ps.getLastDisabledAppCaller(user.id);
            if (lastDisabledAppCaller != null) {
                pw.print(prefix); pw.print("    lastDisabledCaller: ");
                        pw.println(lastDisabledAppCaller);
            }

            if (ps.sharedUser == null) {
                dumpGidsLPr(pw, prefix + "    ", mPermissionDataProvider.getGidsForUid(
                        UserHandle.getUid(user.id, ps.appId)));
                dumpRuntimePermissionsLPr(pw, prefix + "    ", permissionNames, permissionsState
                        .getPermissionStates(user.id), dumpAll);
            }

            String harmfulAppWarning = ps.getHarmfulAppWarning(user.id);
            if (harmfulAppWarning != null) {
                pw.print(prefix); pw.print("      harmfulAppWarning: ");
                pw.println(harmfulAppWarning);
            }

            if (permissionNames == null) {
                ArraySet<String> cmp = ps.getDisabledComponents(user.id);
                if (cmp != null && cmp.size() > 0) {
                    pw.print(prefix); pw.println("    disabledComponents:");
                    for (String s : cmp) {
                        pw.print(prefix); pw.print("      "); pw.println(s);
                    }
                }
                cmp = ps.getEnabledComponents(user.id);
                if (cmp != null && cmp.size() > 0) {
                    pw.print(prefix); pw.println("    enabledComponents:");
                    for (String s : cmp) {
                        pw.print(prefix); pw.print("      "); pw.println(s);
                    }
                }
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
            if (packageName != null && !packageName.equals(ps.realName)
                    && !packageName.equals(ps.name)) {
                continue;
            }
            final LegacyPermissionState permissionsState =
                    mPermissionDataProvider.getLegacyPermissionState(ps.appId);
            if (permissionNames != null
                    && !permissionsState.hasPermissionState(permissionNames)) {
                continue;
            }

            if (!checkin && packageName != null) {
                dumpState.setSharedUser(ps.sharedUser);
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
                if (packageName != null && !packageName.equals(ps.realName)
                        && !packageName.equals(ps.name)) {
                    continue;
                }
                if (!checkin && !printedSomething) {
                    if (dumpState.onTitlePrinted())
                        pw.println();
                    pw.println("Hidden system packages:");
                    printedSomething = true;
                }
                final LegacyPermissionState permissionsState =
                        mPermissionDataProvider.getLegacyPermissionState(ps.appId);
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
                    mPermissionDataProvider.getLegacyPermissionState(su.userId);
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
                pw.print(prefix); pw.print("userId="); pw.println(su.userId);

                pw.print(prefix); pw.println("Packages");
                final int numPackages = su.packages.size();
                for (int i = 0; i < numPackages; i++) {
                    final PackageSetting ps = su.packages.valueAt(i);
                    if (ps != null) {
                        pw.print(prefix + "  "); pw.println(ps.toString());
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
                            userId, su.userId));
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
                pw.print("suid,"); pw.print(su.userId); pw.print(","); pw.println(su.name);
            }
        }
    }

    void dumpSharedUsersProto(ProtoOutputStream proto) {
        final int count = mSharedUsers.size();
        for (int i = 0; i < count; i++) {
            mSharedUsers.valueAt(i).dumpDebug(proto, PackageServiceDumpProto.SHARED_USERS);
        }
    }

    void dumpReadMessagesLPr(PrintWriter pw, DumpState dumpState) {
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
            if (splitNames != null) {
                for (int i = 0; i < splitNames.length; i++) {
                    pw.print(", ");
                    pw.print(splitNames[i]);
                    if (splitRevisionCodes[i] != 0) {
                        pw.print(":"); pw.print(splitRevisionCodes[i]);
                    }
                }
            }
            pw.print("]");
        }
    }

    void dumpGidsLPr(PrintWriter pw, String prefix, int[] gids) {
        if (!ArrayUtils.isEmpty(gids)) {
            pw.print(prefix);
            pw.print("gids="); pw.println(
                    PackageManagerService.arrayToString(gids));
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
        dumpComponents(pw, prefix, "activities:", ps.pkg.getActivities());
        dumpComponents(pw, prefix, "services:", ps.pkg.getServices());
        dumpComponents(pw, prefix, "receivers:", ps.pkg.getReceivers());
        dumpComponents(pw, prefix, "providers:", ps.pkg.getProviders());
        dumpComponents(pw, prefix, "instrumentations:", ps.pkg.getInstrumentations());
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
            mRuntimePermissionsPersistence.writeStateForUserSyncLPr(userId);
        } else {
            mRuntimePermissionsPersistence.writeStateForUserAsyncLPr(userId);
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

    private final class RuntimePermissionPersistence {
        private static final long WRITE_PERMISSIONS_DELAY_MILLIS = 200;
        private static final long MAX_WRITE_PERMISSIONS_DELAY_MILLIS = 2000;

        private static final int UPGRADE_VERSION = -1;
        private static final int INITIAL_VERSION = 0;

        private String mExtendedFingerprint;

        private final RuntimePermissionsPersistence mPersistence;

        private final Handler mHandler = new MyHandler();

        @GuardedBy("mLock")
        private final SparseBooleanArray mWriteScheduled = new SparseBooleanArray();

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseLongArray mLastNotWrittenMutationTimesMillis = new SparseLongArray();

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseIntArray mVersions = new SparseIntArray();

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseArray<String> mFingerprints = new SparseArray<>();

        @GuardedBy("mLock")
        // The mapping keys are user ids.
        private final SparseBooleanArray mPermissionUpgradeNeeded = new SparseBooleanArray();

        public RuntimePermissionPersistence(RuntimePermissionsPersistence persistence) {
            mPersistence = persistence;
        }

        @GuardedBy("Settings.this.mLock")
        int getVersionLPr(int userId) {
            return mVersions.get(userId, INITIAL_VERSION);
        }

        @GuardedBy("Settings.this.mLock")
        void setVersionLPr(int version, int userId) {
            mVersions.put(userId, version);
            writeStateForUserAsyncLPr(userId);
        }

        @GuardedBy("Settings.this.mLock")
        public boolean isPermissionUpgradeNeeded(int userId) {
            return mPermissionUpgradeNeeded.get(userId, true);
        }

        @GuardedBy("Settings.this.mLock")
        public void updateRuntimePermissionsFingerprintLPr(@UserIdInt int userId) {
            if (mExtendedFingerprint == null) {
                throw new RuntimeException("The version of the permission controller hasn't been "
                        + "set before trying to update the fingerprint.");
            }
            mFingerprints.put(userId, mExtendedFingerprint);
            writeStateForUserAsyncLPr(userId);
        }

        public void setPermissionControllerVersion(long version) {
            int numUser = mFingerprints.size();
            mExtendedFingerprint = getExtendedFingerprint(version);

            for (int i = 0;  i < numUser; i++) {
                int userId = mFingerprints.keyAt(i);
                String fingerprint = mFingerprints.valueAt(i);
                mPermissionUpgradeNeeded.put(userId,
                        !TextUtils.equals(mExtendedFingerprint, fingerprint));
            }
        }

        private String getExtendedFingerprint(long version) {
            return Build.FINGERPRINT + "?pc_version=" + version;
        }

        public void writeStateForUserAsyncLPr(int userId) {
            final long currentTimeMillis = SystemClock.uptimeMillis();

            if (mWriteScheduled.get(userId)) {
                mHandler.removeMessages(userId);

                // If enough time passed, write without holding off anymore.
                final long lastNotWrittenMutationTimeMillis = mLastNotWrittenMutationTimesMillis
                        .get(userId);
                final long timeSinceLastNotWrittenMutationMillis = currentTimeMillis
                        - lastNotWrittenMutationTimeMillis;
                if (timeSinceLastNotWrittenMutationMillis >= MAX_WRITE_PERMISSIONS_DELAY_MILLIS) {
                    mHandler.obtainMessage(userId).sendToTarget();
                    return;
                }

                // Hold off a bit more as settings are frequently changing.
                final long maxDelayMillis = Math.max(lastNotWrittenMutationTimeMillis
                        + MAX_WRITE_PERMISSIONS_DELAY_MILLIS - currentTimeMillis, 0);
                final long writeDelayMillis = Math.min(WRITE_PERMISSIONS_DELAY_MILLIS,
                        maxDelayMillis);

                Message message = mHandler.obtainMessage(userId);
                mHandler.sendMessageDelayed(message, writeDelayMillis);
            } else {
                mLastNotWrittenMutationTimesMillis.put(userId, currentTimeMillis);
                Message message = mHandler.obtainMessage(userId);
                mHandler.sendMessageDelayed(message, WRITE_PERMISSIONS_DELAY_MILLIS);
                mWriteScheduled.put(userId, true);
            }
        }

        public void writeStateForUserSyncLPr(int userId) {
            mHandler.removeMessages(userId);
            mWriteScheduled.delete(userId);

            mPermissionDataProvider.writeLegacyPermissionStateTEMP();

            int version = mVersions.get(userId, INITIAL_VERSION);

            String fingerprint = mFingerprints.get(userId);

            Map<String, List<RuntimePermissionsState.PermissionState>> packagePermissions =
                    new ArrayMap<>();
            int packagesSize = mPackages.size();
            for (int i = 0; i < packagesSize; i++) {
                String packageName = mPackages.keyAt(i);
                PackageSetting packageSetting = mPackages.valueAt(i);
                if (packageSetting.sharedUser == null) {
                    List<RuntimePermissionsState.PermissionState> permissions =
                            getPermissionsFromPermissionsState(
                                    packageSetting.getLegacyPermissionState(), userId);
                    if (permissions.isEmpty() && !packageSetting.areInstallPermissionsFixed()) {
                        // Storing an empty state means the package is known to the system and its
                        // install permissions have been granted and fixed. If this is not the case,
                        // we should not store anything.
                        continue;
                    }
                    packagePermissions.put(packageName, permissions);
                }
            }

            Map<String, List<RuntimePermissionsState.PermissionState>> sharedUserPermissions =
                    new ArrayMap<>();
            final int sharedUsersSize = mSharedUsers.size();
            for (int i = 0; i < sharedUsersSize; i++) {
                String sharedUserName = mSharedUsers.keyAt(i);
                SharedUserSetting sharedUserSetting = mSharedUsers.valueAt(i);
                List<RuntimePermissionsState.PermissionState> permissions =
                        getPermissionsFromPermissionsState(
                                sharedUserSetting.getLegacyPermissionState(), userId);
                sharedUserPermissions.put(sharedUserName, permissions);
            }

            RuntimePermissionsState runtimePermissions = new RuntimePermissionsState(version,
                    fingerprint, packagePermissions, sharedUserPermissions);

            mPersistence.writeForUser(runtimePermissions, UserHandle.of(userId));
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

        @GuardedBy("Settings.this.mLock")
        private void onUserRemovedLPw(int userId) {
            // Make sure we do not
            mHandler.removeMessages(userId);

            mPermissionUpgradeNeeded.delete(userId);
            mVersions.delete(userId);
            mFingerprints.remove(userId);
        }

        public void deleteUserRuntimePermissionsFile(int userId) {
            mPersistence.deleteForUser(UserHandle.of(userId));
        }

        @GuardedBy("Settings.this.mLock")
        public void readStateForUserSyncLPr(int userId) {
            RuntimePermissionsState runtimePermissions = mPersistence.readForUser(UserHandle.of(
                    userId));
            if (runtimePermissions == null) {
                readLegacyStateForUserSyncLPr(userId);
                writeStateForUserAsyncLPr(userId);
                return;
            }

            // If the runtime permissions file exists but the version is not set this is
            // an upgrade from P->Q. Hence mark it with the special UPGRADE_VERSION.
            int version = runtimePermissions.getVersion();
            if (version == RuntimePermissionsState.NO_VERSION) {
                version = UPGRADE_VERSION;
            }
            mVersions.put(userId, version);

            String fingerprint = runtimePermissions.getFingerprint();
            mFingerprints.put(userId, fingerprint);

            boolean isUpgradeToR = getInternalVersion().sdkVersion < Build.VERSION_CODES.R;

            Map<String, List<RuntimePermissionsState.PermissionState>> packagePermissions =
                    runtimePermissions.getPackagePermissions();
            int packagesSize = mPackages.size();
            for (int i = 0; i < packagesSize; i++) {
                String packageName = mPackages.keyAt(i);
                PackageSetting packageSetting = mPackages.valueAt(i);

                List<RuntimePermissionsState.PermissionState> permissions =
                        packagePermissions.get(packageName);
                if (permissions != null) {
                    readPermissionsStateLpr(permissions, packageSetting.getLegacyPermissionState(),
                            userId);
                    packageSetting.installPermissionsFixed = true;
                } else if (packageSetting.sharedUser == null && !isUpgradeToR) {
                    Slog.w(TAG, "Missing permission state for package: " + packageName);
                    packageSetting.getLegacyPermissionState().setMissing(true, userId);
                }
            }

            Map<String, List<RuntimePermissionsState.PermissionState>> sharedUserPermissions =
                    runtimePermissions.getSharedUserPermissions();
            int sharedUsersSize = mSharedUsers.size();
            for (int i = 0; i < sharedUsersSize; i++) {
                String sharedUserName = mSharedUsers.keyAt(i);
                SharedUserSetting sharedUserSetting = mSharedUsers.valueAt(i);

                List<RuntimePermissionsState.PermissionState> permissions =
                        sharedUserPermissions.get(sharedUserName);
                if (permissions != null) {
                    readPermissionsStateLpr(permissions,
                            sharedUserSetting.getLegacyPermissionState(), userId);
                } else if (!isUpgradeToR) {
                    Slog.w(TAG, "Missing permission state for shared user: " + sharedUserName);
                    sharedUserSetting.getLegacyPermissionState().setMissing(true, userId);
                }
            }
        }

        private void readPermissionsStateLpr(
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

        @GuardedBy("Settings.this.mLock")
        private void readLegacyStateForUserSyncLPr(int userId) {
            File permissionsFile = getUserRuntimePermissionsFile(userId);
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
                parseLegacyRuntimePermissionsLPr(parser, userId);

            } catch (XmlPullParserException | IOException e) {
                throw new IllegalStateException("Failed parsing permissions file: "
                        + permissionsFile, e);
            } finally {
                IoUtils.closeQuietly(in);
            }
        }

        // Private internals

        @GuardedBy("Settings.this.mLock")
        private void parseLegacyRuntimePermissionsLPr(TypedXmlPullParser parser, int userId)
                throws IOException, XmlPullParserException {
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
                        int version = parser.getAttributeInt(null, ATTR_VERSION, UPGRADE_VERSION);
                        mVersions.put(userId, version);
                        String fingerprint = parser.getAttributeValue(null, ATTR_FINGERPRINT);
                        mFingerprints.put(userId, fingerprint);
                    } break;

                    case TAG_PACKAGE: {
                        String name = parser.getAttributeValue(null, ATTR_NAME);
                        PackageSetting ps = mPackages.get(name);
                        if (ps == null) {
                            Slog.w(PackageManagerService.TAG, "Unknown package:" + name);
                            XmlUtils.skipCurrentTag(parser);
                            continue;
                        }
                        parseLegacyPermissionsLPr(parser, ps.getLegacyPermissionState(), userId);
                    } break;

                    case TAG_SHARED_USER: {
                        String name = parser.getAttributeValue(null, ATTR_NAME);
                        SharedUserSetting sus = mSharedUsers.get(name);
                        if (sus == null) {
                            Slog.w(PackageManagerService.TAG, "Unknown shared user:" + name);
                            XmlUtils.skipCurrentTag(parser);
                            continue;
                        }
                        parseLegacyPermissionsLPr(parser, sus.getLegacyPermissionState(), userId);
                    } break;
                }
            }
        }

        private void parseLegacyPermissionsLPr(TypedXmlPullParser parser,
                LegacyPermissionState permissionsState, int userId)
                throws IOException, XmlPullParserException {
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

        private final class MyHandler extends Handler {
            public MyHandler() {
                super(BackgroundThread.getHandler().getLooper());
            }

            @Override
            public void handleMessage(Message message) {
                final int userId = message.what;
                Runnable callback = (Runnable) message.obj;
                synchronized (mLock) {
                    writeStateForUserSyncLPr(userId);
                }
                if (callback != null) {
                    callback.run();
                }
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
}
