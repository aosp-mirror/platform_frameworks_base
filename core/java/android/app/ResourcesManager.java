/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.app;

import static android.app.ActivityThread.DEBUG_CONFIGURATION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.CompatResources;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Flags;
import android.content.res.Resources;
import android.content.res.ResourcesImpl;
import android.content.res.ResourcesKey;
import android.content.res.loader.ResourcesLoader;
import android.hardware.display.DisplayManagerGlobal;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Process;
import android.os.Trace;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodReplace;
import android.ravenwood.annotation.RavenwoodThrow;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.window.WindowContext;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Function;

/** @hide */
@RavenwoodKeepWholeClass
public class ResourcesManager {
    static final String TAG = "ResourcesManager";
    private static final boolean DEBUG = false;

    private static volatile ResourcesManager sResourcesManager;

    /**
     * Internal lock object
     */
    private final Object mLock = new Object();

    /**
     * The global compatibility settings.
     */
    private CompatibilityInfo mResCompatibilityInfo;

    /**
     * The global configuration upon which all Resources are based. Multi-window Resources
     * apply their overrides to this configuration.
     */
    @UnsupportedAppUsage
    private final Configuration mResConfiguration = new Configuration();

    /**
     * The display upon which all Resources are based. Activity, window token, and display context
     * resources apply their overrides to this display id.
     */
    private int mResDisplayId = DEFAULT_DISPLAY;

    /**
     * ApplicationInfo changes that need to be applied to Resources when the next configuration
     * change occurs.
     */
    private ArrayList<Pair<String[], ApplicationInfo>> mPendingAppInfoUpdates;

    /**
     * A mapping of ResourceImpls and their configurations. These are heavy weight objects
     * which should be reused as much as possible.
     */
    @UnsupportedAppUsage
    private final ArrayMap<ResourcesKey, WeakReference<ResourcesImpl>> mResourceImpls =
            new ArrayMap<>();

    /**
     * A list of Resource references that can be reused.
     */
    @UnsupportedAppUsage
    private final ArrayList<WeakReference<Resources>> mResourceReferences = new ArrayList<>();
    private final ReferenceQueue<Resources> mResourcesReferencesQueue = new ReferenceQueue<>();

    /**
     * A list of Resources references for all Resources instances created through Resources public
     * constructor, only system Resources created by the private constructor are excluded.
     * This addition is necessary due to certain Application Resources created by constructor
     * directly which are not managed by ResourcesManager, hence we require a comprehensive
     * collection of all Resources references to help with asset paths appending tasks when shared
     * libraries are registered.
     */
    private final ArrayList<WeakReference<Resources>> mAllResourceReferences = new ArrayList<>();
    private final ReferenceQueue<Resources> mAllResourceReferencesQueue = new ReferenceQueue<>();

    /**
     * The localeConfig of the app.
     */
    private LocaleConfig mLocaleConfig = new LocaleConfig(LocaleList.getEmptyLocaleList());

    private final ArrayMap<String, SharedLibraryAssets> mSharedLibAssetsMap =
            new ArrayMap<>();

    @VisibleForTesting
    public ArrayMap<String, SharedLibraryAssets> getRegisteredResourcePaths() {
        return mSharedLibAssetsMap;
    }

    /**
     * The internal function to register the resources paths of a package (e.g. a shared library).
     * This will collect the package resources' paths from its ApplicationInfo and add them to all
     * existing and future contexts while the application is running.
     */
    @RavenwoodThrow(reason = "FLAG_REGISTER_RESOURCE_PATHS is unsupported")
    public void registerResourcePaths(@NonNull String uniqueId, @NonNull ApplicationInfo appInfo) {
        if (!Flags.registerResourcePaths()) {
            return;
        }

        final var sharedLibAssets = new SharedLibraryAssets(appInfo);
        synchronized (mLock) {
            if (mSharedLibAssetsMap.containsKey(uniqueId)) {
                Slog.v(TAG, "Package resources' paths for uniqueId: " + uniqueId
                        + " has already been registered, this is a no-op.");
                return;
            }
            mSharedLibAssetsMap.put(uniqueId, sharedLibAssets);
            appendLibAssetsLocked(sharedLibAssets);
            Slog.v(TAG, "The following library key has been added: "
                    + sharedLibAssets.getResourcesKey());
        }
    }

    /**
     * Apply the registered library paths to the passed impl object
     * @return the hash code for the current version of the registered paths
     */
    public int updateResourceImplWithRegisteredLibs(@NonNull ResourcesImpl impl) {
        if (!Flags.registerResourcePaths()) {
            return 0;
        }

        final var collector = new PathCollector(null);
        final int size = mSharedLibAssetsMap.size();
        for (int i = 0; i < size; i++) {
            final var libraryKey = mSharedLibAssetsMap.valueAt(i).getResourcesKey();
            collector.appendKey(libraryKey);
        }
        impl.getAssets().addPresetApkKeys(extractApkKeys(collector.collectedKey()));
        return size;
    }

    public static class ApkKey {
        public final String path;
        public final boolean sharedLib;
        public final boolean overlay;

        public ApkKey(String path, boolean sharedLib, boolean overlay) {
            this.path = path;
            this.sharedLib = sharedLib;
            this.overlay = overlay;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.path.hashCode();
            result = 31 * result + Boolean.hashCode(this.sharedLib);
            result = 31 * result + Boolean.hashCode(this.overlay);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof ApkKey)) {
                return false;
            }
            ApkKey other = (ApkKey) obj;
            return this.path.equals(other.path) && this.sharedLib == other.sharedLib
                    && this.overlay == other.overlay;
        }

        @Override
        public String toString() {
            return "ApkKey[" + (sharedLib ? "lib" : "app") + (overlay ? ", overlay" : "") + ": "
                    + path + "]";
        }
    }

    /**
     * Loads {@link ApkAssets} and caches them to prevent their garbage collection while the
     * instance is alive and reachable.
     */
    @VisibleForTesting
    protected class ApkAssetsSupplier {
        final ArrayMap<ApkKey, ApkAssets> mLocalCache = new ArrayMap<>();

        /**
         * Retrieves the {@link ApkAssets} corresponding to the specified key, caches the ApkAssets
         * within this instance, and inserts the loaded ApkAssets into the {@link #mCachedApkAssets}
         * cache.
         */
        ApkAssets load(final ApkKey apkKey) throws IOException {
            ApkAssets apkAssets = mLocalCache.get(apkKey);
            if (apkAssets == null) {
                apkAssets = loadApkAssets(apkKey);
                mLocalCache.put(apkKey, apkAssets);
            }
            return apkAssets;
        }
    }

    /**
     * The ApkAssets that are being referenced in the wild that we can reuse.
     * Used as a lock for itself as well.
     */
    private final ArrayMap<ApkKey, WeakReference<ApkAssets>> mCachedApkAssets = new ArrayMap<>();

    /**
     * Class containing the base configuration override and set of resources associated with an
     * {@link Activity} or a {@link WindowContext}.
     */
    private static class ActivityResources {
        /**
         * Override config to apply to all resources associated with the token this instance is
         * based on.
         *
         * @see #activityResources
         * @see #getResources(IBinder, String, String[], String[], String[], String[], Integer,
         * Configuration, CompatibilityInfo, ClassLoader, List)
         */
        public final Configuration overrideConfig = new Configuration();

        /**
         * The display to apply to all resources associated with the token this instance is based
         * on.
         */
        public int overrideDisplayId;

        /** List of {@link ActivityResource} associated with the token this instance is based on. */
        public final ArrayList<ActivityResource> activityResources = new ArrayList<>();

        public final ReferenceQueue<Resources> activityResourcesQueue = new ReferenceQueue<>();

        @UnsupportedAppUsage
        private ActivityResources() {}

        /** Returns the number of live resource references within {@code activityResources}. */
        public int countLiveReferences() {
            int count = 0;
            for (int i = 0; i < activityResources.size(); i++) {
                WeakReference<Resources> resources = activityResources.get(i).resources;
                if (resources != null && resources.get() != null) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Contains a resource derived from an {@link Activity} or {@link WindowContext} and information
     * about how this resource expects its configuration to differ from the token's.
     *
     * @see ActivityResources
     */
    // TODO: Ideally this class should be called something token related, like TokenBasedResource.
    private static class ActivityResource {
        /**
         * The override configuration applied on top of the token's override config for this
         * resource.
         */
        public final Configuration overrideConfig = new Configuration();

        /**
         * If non-null this resource expects its configuration to override the display from the
         * token's configuration.
         *
         * @see #applyDisplayMetricsToConfiguration(DisplayMetrics, Configuration)
         */
        @Nullable
        public Integer overrideDisplayId;

        @Nullable
        public WeakReference<Resources> resources;

        private ActivityResource() {}
    }

    /**
     * Each Activity or WindowToken may has a base override configuration that is applied to each
     * Resources object, which in turn may have their own override configuration specified.
     */
    @UnsupportedAppUsage
    private final WeakHashMap<IBinder, ActivityResources> mActivityResourceReferences =
            new WeakHashMap<>();

    /**
     * Callback implementation for handling updates to Resources objects.
     */
    private final UpdateHandler mUpdateCallbacks = new UpdateHandler();

    /**
     * The set of APK paths belonging to this process. This is used to disable incremental
     * installation crash protections on these APKs so the app either behaves as expects or crashes.
     */
    private final ArraySet<String> mApplicationOwnedApks = new ArraySet<>();

    @UnsupportedAppUsage
    public ResourcesManager() {
    }

    /**
     * Inject a customized ResourcesManager instance for testing, return the old ResourcesManager
     * instance.
     */
    @UnsupportedAppUsage
    @VisibleForTesting
    public static ResourcesManager setInstance(ResourcesManager resourcesManager) {
        synchronized (ResourcesManager.class) {
            ResourcesManager oldResourceManager = sResourcesManager;
            sResourcesManager = resourcesManager;
            return oldResourceManager;
        }
    }

    @UnsupportedAppUsage
    public static ResourcesManager getInstance() {
        var rm = sResourcesManager;
        if (rm == null) {
            synchronized (ResourcesManager.class) {
                rm = sResourcesManager;
                if (rm == null) {
                    sResourcesManager = rm = new ResourcesManager();
                }
            }
        }
        return rm;
    }

    /**
     * Invalidate and destroy any resources that reference content under the
     * given filesystem path. Typically used when unmounting a storage device to
     * try as hard as possible to release any open FDs.
     */
    public void invalidatePath(String path) {
        final List<ResourcesImpl> implsToFlush = new ArrayList<>();
        synchronized (mLock) {
            for (int i = mResourceImpls.size() - 1; i >= 0; i--) {
                final ResourcesKey key = mResourceImpls.keyAt(i);
                if (key.isPathReferenced(path)) {
                    ResourcesImpl resImpl = mResourceImpls.removeAt(i).get();
                    if (resImpl != null) {
                        implsToFlush.add(resImpl);
                    }
                }
            }
        }
        for (int i = 0; i < implsToFlush.size(); i++) {
            implsToFlush.get(i).flushLayoutCache();
        }
        final List<ApkAssets> assetsToClose = new ArrayList<>();
        synchronized (mCachedApkAssets) {
            for (int i = mCachedApkAssets.size() - 1; i >= 0; i--) {
                final ApkKey key = mCachedApkAssets.keyAt(i);
                if (key.path.equals(path)) {
                    final WeakReference<ApkAssets> apkAssetsRef = mCachedApkAssets.removeAt(i);
                    final ApkAssets apkAssets = apkAssetsRef != null ? apkAssetsRef.get() : null;
                    if (apkAssets != null) {
                        assetsToClose.add(apkAssets);
                    }
                }
            }
        }
        for (int i = 0; i < assetsToClose.size(); i++) {
            assetsToClose.get(i).close();
        }
        Log.i(TAG,
                "Invalidated " + implsToFlush.size() + " asset managers that referenced " + path);
    }

    public Configuration getConfiguration() {
        return mResConfiguration;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public DisplayMetrics getDisplayMetrics() {
        return getDisplayMetrics(mResDisplayId, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    /**
     * public so that tests can access and override
     */
    @VisibleForTesting
    public @NonNull DisplayMetrics getDisplayMetrics(int displayId, DisplayAdjustments da) {
        final DisplayManagerGlobal displayManagerGlobal = DisplayManagerGlobal.getInstance();
        final DisplayMetrics dm = new DisplayMetrics();
        final DisplayInfo displayInfo = displayManagerGlobal != null
                ? displayManagerGlobal.getDisplayInfo(displayId) : null;
        if (displayInfo != null) {
            final Configuration dajConfig = da.getConfiguration();
            displayInfo.getAppMetrics(dm, da.getCompatibilityInfo(),
                    (mResDisplayId == displayId && Configuration.EMPTY.equals(dajConfig))
                            ? mResConfiguration : dajConfig);
        } else {
            dm.setToDefaults();
        }
        return dm;
    }

    /**
     * Like getDisplayMetrics, but will adjust the result based on the display information in
     * config. This is used to make sure that the global configuration matches the activity's
     * apparent display.
     */
    private DisplayMetrics getDisplayMetrics(Configuration config) {
        final DisplayManagerGlobal displayManagerGlobal = DisplayManagerGlobal.getInstance();
        final DisplayMetrics dm = new DisplayMetrics();
        final DisplayInfo displayInfo = displayManagerGlobal != null
                ? displayManagerGlobal.getDisplayInfo(mResDisplayId) : null;
        if (displayInfo != null) {
            displayInfo.getAppMetrics(dm,
                    DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS.getCompatibilityInfo(), config);
        } else {
            dm.setToDefaults();
        }
        return dm;
    }

    private static void applyDisplayMetricsToConfiguration(@NonNull DisplayMetrics dm,
            @NonNull Configuration config) {
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.densityDpi = dm.densityDpi;
        config.screenWidthDp = (int) (dm.widthPixels / dm.density + 0.5f);
        config.screenHeightDp = (int) (dm.heightPixels / dm.density + 0.5f);
        int sl = Configuration.resetScreenLayout(config.screenLayout);
        if (dm.widthPixels > dm.heightPixels) {
            config.orientation = Configuration.ORIENTATION_LANDSCAPE;
            config.screenLayout = Configuration.reduceScreenLayout(sl,
                    config.screenWidthDp, config.screenHeightDp);
        } else {
            config.orientation = Configuration.ORIENTATION_PORTRAIT;
            config.screenLayout = Configuration.reduceScreenLayout(sl,
                    config.screenHeightDp, config.screenWidthDp);
        }
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        config.compatScreenWidthDp = config.screenWidthDp;
        config.compatScreenHeightDp = config.screenHeightDp;
        config.compatSmallestScreenWidthDp = config.smallestScreenWidthDp;
    }

    public boolean applyCompatConfiguration(int displayDensity,
            @NonNull Configuration compatConfiguration) {
        synchronized (mLock) {
            if (mResCompatibilityInfo != null && !mResCompatibilityInfo.supportsScreen()) {
                mResCompatibilityInfo.applyToConfiguration(displayDensity, compatConfiguration);
                return true;
            }
            return false;
        }
    }

    /**
     * Returns an adjusted {@link Display} object based on the inputs or null if display isn't
     * available.
     *
     * @param displayId display Id.
     * @param resources The {@link Resources} backing the display adjustments.
     */
    public Display getAdjustedDisplay(final int displayId, Resources resources) {
        final DisplayManagerGlobal dm = DisplayManagerGlobal.getInstance();
        if (dm == null) {
            // may be null early in system startup
            return null;
        }
        return dm.getCompatibleDisplay(displayId, resources);
    }

    /**
     * Initializes the set of APKs owned by the application running in this process.
     */
    public void initializeApplicationPaths(@NonNull String sourceDir,
            @Nullable String[] splitDirs) {
        synchronized (mLock) {
            if (mApplicationOwnedApks.isEmpty()) {
                addApplicationPathsLocked(sourceDir, splitDirs);
            }
        }
    }

    /**
     * Updates the set of APKs owned by the application running in this process.
     *
     * This method only appends to the set of APKs owned by this process because the previous APKs
     * paths still belong to the application running in this process.
     */
    private void addApplicationPathsLocked(@NonNull String sourceDir,
            @Nullable String[] splitDirs) {
        mApplicationOwnedApks.add(sourceDir);
        if (splitDirs != null) {
            mApplicationOwnedApks.addAll(Arrays.asList(splitDirs));
        }
    }

    private static String overlayPathToIdmapPath(String path) {
        return "/data/resource-cache/" + path.substring(1).replace('/', '@') + "@idmap";
    }

    /**
     * Loads the ApkAssets object for the passed key, or picks the one from the cache if available.
     */
    public @NonNull ApkAssets loadApkAssets(@NonNull final ApkKey key) throws IOException {
        ApkAssets apkAssets;

        // Optimistically check if this ApkAssets exists somewhere else.
        final WeakReference<ApkAssets> apkAssetsRef;
        synchronized (mCachedApkAssets) {
            apkAssetsRef = mCachedApkAssets.get(key);
        }
        if (apkAssetsRef != null) {
            apkAssets = apkAssetsRef.get();
            if (apkAssets != null && apkAssets.isUpToDate()) {
                return apkAssets;
            }
        }

        int flags = 0;
        if (key.sharedLib) {
            flags |= ApkAssets.PROPERTY_DYNAMIC;
        }
        if (mApplicationOwnedApks.contains(key.path)) {
            flags |= ApkAssets.PROPERTY_DISABLE_INCREMENTAL_HARDENING;
        }
        if (key.overlay) {
            apkAssets = ApkAssets.loadOverlayFromPath(overlayPathToIdmapPath(key.path), flags);
        } else {
            apkAssets = ApkAssets.loadFromPath(key.path, flags);
        }

        synchronized (mCachedApkAssets) {
            mCachedApkAssets.put(key, new WeakReference<>(apkAssets));
        }

        return apkAssets;
    }

    /**
     * Retrieves a list of apk keys representing the ApkAssets that should be loaded for
     * AssetManagers mapped to the {@param key}.
     */
    private static @NonNull ArrayList<ApkKey> extractApkKeys(@NonNull final ResourcesKey key) {
        final ArrayList<ApkKey> apkKeys = new ArrayList<>();

        // resDir can be null if the 'android' package is creating a new Resources object.
        // This is fine, since each AssetManager automatically loads the 'android' package
        // already.
        if (key.mResDir != null) {
            apkKeys.add(new ApkKey(key.mResDir, false /*sharedLib*/, false /*overlay*/));
        }

        if (key.mSplitResDirs != null) {
            for (final String splitResDir : key.mSplitResDirs) {
                apkKeys.add(new ApkKey(splitResDir, false /*sharedLib*/, false /*overlay*/));
            }
        }

        if (key.mLibDirs != null) {
            for (final String libDir : key.mLibDirs) {
                // Avoid opening files we know do not have resources, like code-only .jar files.
                if (libDir.endsWith(".apk")) {
                    apkKeys.add(new ApkKey(libDir, true /*sharedLib*/, false /*overlay*/));
                }
            }
        }

        if (key.mOverlayPaths != null) {
            for (final String idmapPath : key.mOverlayPaths) {
                apkKeys.add(new ApkKey(idmapPath, false /*sharedLib*/, true /*overlay*/));
            }
        }

        return apkKeys;
    }

    /**
     * Creates an AssetManager from the paths within the ResourcesKey.
     *
     * This can be overridden in tests so as to avoid creating a real AssetManager with
     * real APK paths.
     * @param key The key containing the resource paths to add to the AssetManager.
     * @return a new AssetManager.
    */
    @VisibleForTesting
    @UnsupportedAppUsage
    protected @Nullable AssetManager createAssetManager(@NonNull final ResourcesKey key) {
        return createAssetManager(key, /* apkSupplier */ null);
    }

    /**
     * Variant of {@link #createAssetManager(ResourcesKey)} that attempts to load ApkAssets
     * from an {@link ApkAssetsSupplier} if non-null; otherwise ApkAssets are loaded using
     * {@link #loadApkAssets(ApkKey)}.
     */

    @VisibleForTesting
    @UnsupportedAppUsage
    protected @Nullable AssetManager createAssetManager(@NonNull final ResourcesKey key,
            @Nullable ApkAssetsSupplier apkSupplier) {
        final AssetManager.Builder builder = new AssetManager.Builder().setNoInit();

        final ArrayList<ApkKey> apkKeys = extractApkKeys(key);
        for (int i = 0, n = apkKeys.size(); i < n; i++) {
            final ApkKey apkKey = apkKeys.get(i);
            try {
                builder.addApkAssets(
                        (apkSupplier != null) ? apkSupplier.load(apkKey) : loadApkAssets(apkKey));
            } catch (IOException e) {
                if (apkKey.overlay) {
                    Log.w(TAG, String.format("failed to add overlay path '%s'", apkKey.path), e);
                } else if (apkKey.sharedLib) {
                    Log.w(TAG, String.format(
                            "asset path '%s' does not exist or contains no resources",
                            apkKey.path), e);
                } else {
                    Log.e(TAG, String.format("failed to add asset path '%s'", apkKey.path), e);
                    return null;
                }
            }
        }

        if (key.mLoaders != null) {
            for (final ResourcesLoader loader : key.mLoaders) {
                builder.addLoader(loader);
            }
        }

        return builder.build();
    }

    private static <T> int countLiveReferences(Collection<WeakReference<T>> collection) {
        int count = 0;
        for (WeakReference<T> ref : collection) {
            final T value = ref != null ? ref.get() : null;
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * @hide
     */
    public void dump(String prefix, PrintWriter printWriter) {
        final int references;
        final int resImpls;
        synchronized (mLock) {
            int refs = countLiveReferences(mResourceReferences);
            for (ActivityResources activityResources : mActivityResourceReferences.values()) {
                refs += activityResources.countLiveReferences();
            }
            references = refs;
            resImpls = countLiveReferences(mResourceImpls.values());
        }
        final int liveAssets;
        synchronized (mCachedApkAssets) {
            liveAssets = countLiveReferences(mCachedApkAssets.values());
        }

        final var pw = new IndentingPrintWriter(printWriter, "  ");
        for (int i = 0; i < prefix.length() / 2; i++) {
            pw.increaseIndent();
        }
        pw.println("ResourcesManager:");
        pw.increaseIndent();
        pw.print("total apks: ");
        pw.println(liveAssets);
        pw.print("resources: ");
        pw.println(references);
        pw.print("resource impls: ");
        pw.println(resImpls);
    }

    private Configuration generateConfig(@NonNull ResourcesKey key) {
        Configuration config;
        final boolean hasOverrideConfig = key.hasOverrideConfiguration();
        if (hasOverrideConfig) {
            config = new Configuration(getConfiguration());
            config.updateFrom(key.mOverrideConfiguration);
            if (DEBUG) Slog.v(TAG, "Applied overrideConfig=" + key.mOverrideConfiguration);
        } else {
            config = getConfiguration();
        }
        return config;
    }

    private int generateDisplayId(@NonNull ResourcesKey key) {
        return key.mDisplayId != INVALID_DISPLAY ? key.mDisplayId : mResDisplayId;
    }

    private @Nullable ResourcesImpl createResourcesImpl(@NonNull ResourcesKey key,
            @Nullable ApkAssetsSupplier apkSupplier) {
        final AssetManager assets = createAssetManager(key, apkSupplier);
        if (assets == null) {
            return null;
        }

        final DisplayAdjustments daj = new DisplayAdjustments(key.mOverrideConfiguration);
        daj.setCompatibilityInfo(key.mCompatInfo);

        final Configuration config = generateConfig(key);
        final DisplayMetrics displayMetrics = getDisplayMetrics(generateDisplayId(key), daj);
        final ResourcesImpl impl = new ResourcesImpl(assets, displayMetrics, config, daj);

        if (DEBUG) {
            Slog.d(TAG, "- creating impl=" + impl + " with key: " + key);
        }
        return impl;
    }

    /**
     * Finds a cached ResourcesImpl object that matches the given ResourcesKey.
     *
     * @param key The key to match.
     * @return a ResourcesImpl if the key matches a cache entry, null otherwise.
     */
    private @Nullable ResourcesImpl findResourcesImplForKeyLocked(@NonNull ResourcesKey key) {
        WeakReference<ResourcesImpl> weakImplRef = mResourceImpls.get(key);
        ResourcesImpl impl = weakImplRef != null ? weakImplRef.get() : null;
        if (impl != null && impl.getAssets().isUpToDate()) {
            return impl;
        }
        return null;
    }

    /**
     * Finds a cached ResourcesImpl object that matches the given ResourcesKey, or
     * creates a new one and caches it for future use.
     * @param key The key to match.
     * @return a ResourcesImpl object matching the key.
     */
    private @Nullable ResourcesImpl findOrCreateResourcesImplForKeyLocked(
            @NonNull ResourcesKey key) {
        return findOrCreateResourcesImplForKeyLocked(key, /* apkSupplier */ null);
    }

    /**
     * Variant of {@link #findOrCreateResourcesImplForKeyLocked(ResourcesKey)} that attempts to
     * load ApkAssets from a {@link ApkAssetsSupplier} when creating a new ResourcesImpl.
     */
    private @Nullable ResourcesImpl findOrCreateResourcesImplForKeyLocked(
            @NonNull ResourcesKey key, @Nullable ApkAssetsSupplier apkSupplier) {
        ResourcesImpl impl = findResourcesImplForKeyLocked(key);
        // ResourcesImpl also need to be recreated if its shared library hash is not up-to-date.
        if (impl == null || impl.getAppliedSharedLibsHash() != mSharedLibAssetsMap.size()) {
            impl = createResourcesImpl(key, apkSupplier);
            if (impl != null) {
                mResourceImpls.put(key, new WeakReference<>(impl));
            }
        }
        return impl;
    }

    /**
     * Find the ResourcesKey that this ResourcesImpl object is associated with.
     * @return the ResourcesKey or null if none was found.
     */
    private @Nullable ResourcesKey findKeyForResourceImplLocked(
            @NonNull ResourcesImpl resourceImpl) {
        int refCount = mResourceImpls.size();
        for (int i = 0; i < refCount; i++) {
            WeakReference<ResourcesImpl> weakImplRef = mResourceImpls.valueAt(i);
            if (weakImplRef != null && weakImplRef.refersTo(resourceImpl)) {
                return mResourceImpls.keyAt(i);
            }
        }
        return null;
    }

    /**
     * Check if activity resources have same override config as the provided on.
     * @param activityToken The Activity that resources should be associated with.
     * @param overrideConfig The override configuration to be checked for equality with.
     * @return true if activity resources override config matches the provided one or they are both
     *         null, false otherwise.
     */
    public boolean isSameResourcesOverrideConfig(@Nullable IBinder activityToken,
            @Nullable Configuration overrideConfig) {
        synchronized (mLock) {
            final ActivityResources activityResources
                    = activityToken != null ? mActivityResourceReferences.get(activityToken) : null;
            if (activityResources == null) {
                return overrideConfig == null;
            } else {
                // The two configurations must either be equal or publicly equivalent to be
                // considered the same.
                return Objects.equals(activityResources.overrideConfig, overrideConfig)
                        || (overrideConfig != null && activityResources.overrideConfig != null
                                && 0 == overrideConfig.diffPublicOnly(
                                        activityResources.overrideConfig));
            }
        }
    }

    private ActivityResources getOrCreateActivityResourcesStructLocked(
            @NonNull IBinder activityToken) {
        ActivityResources activityResources = mActivityResourceReferences.get(activityToken);
        if (activityResources == null) {
            activityResources = new ActivityResources();
            mActivityResourceReferences.put(activityToken, activityResources);
        }
        return activityResources;
    }

    @Nullable
    private Resources findResourcesForActivityLocked(@NonNull IBinder targetActivityToken,
            @NonNull ResourcesKey targetKey, @NonNull ClassLoader targetClassLoader) {
        ActivityResources activityResources = getOrCreateActivityResourcesStructLocked(
                targetActivityToken);

        final int size = activityResources.activityResources.size();
        for (int index = 0; index < size; index++) {
            ActivityResource activityResource = activityResources.activityResources.get(index);
            Resources resources = activityResource.resources.get();
            ResourcesKey key = resources == null ? null : findKeyForResourceImplLocked(
                    resources.getImpl());

            if (key != null
                    && Objects.equals(resources.getClassLoader(), targetClassLoader)
                    && Objects.equals(key, targetKey)) {
                return resources;
            }
        }

        return null;
    }

    @NonNull
    private Resources createResourcesForActivityLocked(@NonNull IBinder activityToken,
            @NonNull Configuration initialOverrideConfig, @Nullable Integer overrideDisplayId,
            @NonNull ClassLoader classLoader, @NonNull ResourcesImpl impl,
            @NonNull CompatibilityInfo compatInfo) {
        final ActivityResources activityResources = getOrCreateActivityResourcesStructLocked(
                activityToken);
        cleanupReferences(activityResources.activityResources,
                activityResources.activityResourcesQueue,
                (r) -> r.resources);

        Resources resources = compatInfo.needsCompatResources() ? new CompatResources(classLoader)
                : new Resources(classLoader);
        resources.setImpl(impl);
        resources.setCallbacks(mUpdateCallbacks);

        ActivityResource activityResource = new ActivityResource();
        activityResource.resources = new WeakReference<>(resources,
                activityResources.activityResourcesQueue);
        activityResource.overrideConfig.setTo(initialOverrideConfig);
        activityResource.overrideDisplayId = overrideDisplayId;
        activityResources.activityResources.add(activityResource);
        if (DEBUG) {
            Slog.d(TAG, "- creating new ref=" + resources);
            Slog.d(TAG, "- setting ref=" + resources + " with impl=" + impl);
        }
        return resources;
    }

    private @NonNull Resources createResourcesLocked(@NonNull ClassLoader classLoader,
            @NonNull ResourcesImpl impl, @NonNull CompatibilityInfo compatInfo) {
        cleanupReferences(mResourceReferences, mResourcesReferencesQueue);

        Resources resources = compatInfo.needsCompatResources() ? new CompatResources(classLoader)
                : new Resources(classLoader);
        resources.setImpl(impl);
        resources.setCallbacks(mUpdateCallbacks);
        mResourceReferences.add(new WeakReference<>(resources, mResourcesReferencesQueue));
        if (DEBUG) {
            Slog.d(TAG, "- creating new ref=" + resources);
            Slog.d(TAG, "- setting ref=" + resources + " with impl=" + impl);
        }
        return resources;
    }

    /**
     * Creates base resources for a binder token. Calls to
     *
     * {@link #getResources(IBinder, String, String[], String[], String[], String[], Integer,
     * Configuration, CompatibilityInfo, ClassLoader, List)} with the same binder token will have
     * their override configurations merged with the one specified here.
     *
     * @param token Represents an {@link Activity} or {@link WindowContext}.
     * @param resDir The base resource path. Can be null (only framework resources will be loaded).
     * @param splitResDirs An array of split resource paths. Can be null.
     * @param legacyOverlayDirs An array of overlay APK paths. Can be null.
     * @param overlayPaths An array of overlay APK and non-APK paths. Can be null.
     * @param libDirs An array of resource library paths. Can be null.
     * @param displayId The ID of the display for which to create the resources.
     * @param overrideConfig The configuration to apply on top of the base configuration. Can be
     *                       {@code null}. This provides the base override for this token.
     * @param compatInfo The compatibility settings to use. Cannot be null. A default to use is
     *                   {@link CompatibilityInfo#DEFAULT_COMPATIBILITY_INFO}.
     * @param classLoader The class loader to use when inflating Resources. If null, the
     *                    {@link ClassLoader#getSystemClassLoader()} is used.
     * @return a Resources object from which to access resources.
     */
    public @Nullable Resources createBaseTokenResources(@NonNull IBinder token,
            @Nullable String resDir,
            @Nullable String[] splitResDirs,
            @Nullable String[] legacyOverlayDirs,
            @Nullable String[] overlayPaths,
            @Nullable String[] libDirs,
            int displayId,
            @Nullable Configuration overrideConfig,
            @NonNull CompatibilityInfo compatInfo,
            @Nullable ClassLoader classLoader,
            @Nullable List<ResourcesLoader> loaders) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES,
                    "ResourcesManager#createBaseActivityResources");
            final ResourcesKey key = new ResourcesKey(
                    resDir,
                    splitResDirs,
                    combinedOverlayPaths(legacyOverlayDirs, overlayPaths),
                    libDirs,
                    displayId,
                    overrideConfig,
                    compatInfo,
                    loaders == null ? null : loaders.toArray(new ResourcesLoader[0]));
            classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();

            if (DEBUG) {
                Slog.d(TAG, "createBaseActivityResources activity=" + token
                        + " with key=" + key);
            }

            synchronized (mLock) {
                // Force the creation of an ActivityResourcesStruct.
                getOrCreateActivityResourcesStructLocked(token);
            }

            // Update any existing Activity Resources references.
            updateResourcesForActivity(token, overrideConfig, displayId);

            synchronized (mLock) {
                Resources resources = findResourcesForActivityLocked(token, key,
                        classLoader);
                if (resources != null) {
                    return resources;
                }
            }

            // Now request an actual Resources object.
            return createResourcesForActivity(token, key,
                    /* initialOverrideConfig */ Configuration.EMPTY, /* overrideDisplayId */ null,
                    classLoader, /* apkSupplier */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * Rebases a key's override config on top of the Activity's base override.
     *
     * @param activityToken the token the supplied {@code key} is derived from.
     * @param key the key to rebase
     * @param overridesActivityDisplay whether this key is overriding the display from the token
     */
    private void rebaseKeyForActivity(IBinder activityToken, ResourcesKey key,
            boolean overridesActivityDisplay) {
        synchronized (mLock) {
            final ActivityResources activityResources =
                    getOrCreateActivityResourcesStructLocked(activityToken);

            if (key.mDisplayId == INVALID_DISPLAY) {
                key.mDisplayId = activityResources.overrideDisplayId;
            }

            Configuration config;
            if (key.hasOverrideConfiguration()) {
                config = new Configuration(activityResources.overrideConfig);
                config.updateFrom(key.mOverrideConfiguration);
            } else {
                config = activityResources.overrideConfig;
            }

            if (overridesActivityDisplay
                    && key.mOverrideConfiguration.windowConfiguration.getAppBounds() == null) {
                if (!key.hasOverrideConfiguration()) {
                    // Make a copy to handle the case where the override config is set to defaults.
                    config = new Configuration(config);
                }

                // If this key is overriding the display from the token and the key's
                // window config app bounds is null we need to explicitly override this to
                // ensure the display adjustments are as expected.
                config.windowConfiguration.setAppBounds(null);
            }

            key.mOverrideConfiguration.setTo(config);
        }
    }

    /**
     * Rebases a key's override config with display metrics of the {@code overrideDisplay} paired
     * with the {code displayAdjustments}.
     *
     * @see #applyDisplayMetricsToConfiguration(DisplayMetrics, Configuration)
     */
    private void rebaseKeyForDisplay(ResourcesKey key, int overrideDisplay) {
        final Configuration temp = new Configuration();

        DisplayAdjustments daj = new DisplayAdjustments(key.mOverrideConfiguration);
        daj.setCompatibilityInfo(key.mCompatInfo);

        final DisplayMetrics dm = getDisplayMetrics(overrideDisplay, daj);
        applyDisplayMetricsToConfiguration(dm, temp);

        if (key.hasOverrideConfiguration()) {
            temp.updateFrom(key.mOverrideConfiguration);
        }
        key.mOverrideConfiguration.setTo(temp);
    }

    /**
     * Check WeakReferences and remove any dead references so they don't pile up.
     */
    private static <T> void cleanupReferences(ArrayList<WeakReference<T>> references,
            ReferenceQueue<T> referenceQueue) {
        cleanupReferences(references, referenceQueue, Function.identity());
    }

    /**
     * Check WeakReferences and remove any dead references so they don't pile up.
     */
    private static <C, T> void cleanupReferences(ArrayList<C> referenceContainers,
            ReferenceQueue<T> referenceQueue, Function<C, WeakReference<T>> unwrappingFunction) {
        Reference<? extends T> enqueuedRef = referenceQueue.poll();
        if (enqueuedRef == null) {
            return;
        }

        final HashSet<Reference<? extends T>> deadReferences = new HashSet<>();
        for (; enqueuedRef != null; enqueuedRef = referenceQueue.poll()) {
            deadReferences.add(enqueuedRef);
        }

        ArrayUtils.unstableRemoveIf(referenceContainers, (refContainer) -> {
            WeakReference<T> ref = unwrappingFunction.apply(refContainer);
            return ref == null || deadReferences.contains(ref);
        });
    }

    /**
     * Creates an {@link ApkAssetsSupplier} and loads all the ApkAssets required by the {@param key}
     * into the supplier. This should be done while the lock is not held to prevent performing I/O
     * while holding the lock.
     */
    private @NonNull ApkAssetsSupplier createApkAssetsSupplierNotLocked(@NonNull ResourcesKey key) {
        Trace.traceBegin(Trace.TRACE_TAG_RESOURCES,
                "ResourcesManager#createApkAssetsSupplierNotLocked");
        try {
            if (DEBUG && Thread.holdsLock(mLock)) {
                Slog.w(TAG, "Calling thread " + Thread.currentThread().getName()
                    + " is holding mLock", new Throwable());
            }

            final ApkAssetsSupplier supplier = new ApkAssetsSupplier();
            final ArrayList<ApkKey> apkKeys = extractApkKeys(key);
            for (int i = 0, n = apkKeys.size(); i < n; i++) {
                final ApkKey apkKey = apkKeys.get(i);
                try {
                    supplier.load(apkKey);
                } catch (IOException e) {
                    Log.w(TAG, String.format("failed to preload asset path '%s'", apkKey.path), e);
                }
            }
            return supplier;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * Creates a Resources object set with a ResourcesImpl object matching the given key.
     *
     * @param key The key describing the parameters of the ResourcesImpl object.
     * @param classLoader The classloader to use for the Resources object.
     *                    If null, {@link ClassLoader#getSystemClassLoader()} is used.
     * @return A Resources object that gets updated when
     *         {@link #applyConfigurationToResources(Configuration, CompatibilityInfo)}
     *         is called.
     */
    @Nullable
    private Resources createResources(@NonNull ResourcesKey key, @NonNull ClassLoader classLoader,
            @Nullable ApkAssetsSupplier apkSupplier) {
        synchronized (mLock) {
            if (DEBUG) {
                Throwable here = new Throwable();
                here.fillInStackTrace();
                Slog.w(TAG, "!! Create resources for key=" + key, here);
            }

            ResourcesImpl resourcesImpl = findOrCreateResourcesImplForKeyLocked(key, apkSupplier);
            if (resourcesImpl == null) {
                return null;
            }

            return createResourcesLocked(classLoader, resourcesImpl, key.mCompatInfo);
        }
    }

    @Nullable
    private Resources createResourcesForActivity(@NonNull IBinder activityToken,
            @NonNull ResourcesKey key, @NonNull Configuration initialOverrideConfig,
            @Nullable Integer overrideDisplayId, @NonNull ClassLoader classLoader,
            @Nullable ApkAssetsSupplier apkSupplier) {
        synchronized (mLock) {
            if (DEBUG) {
                Throwable here = new Throwable();
                here.fillInStackTrace();
                Slog.w(TAG, "!! Get resources for activity=" + activityToken + " key=" + key, here);
            }

            ResourcesImpl resourcesImpl = findOrCreateResourcesImplForKeyLocked(key, apkSupplier);
            if (resourcesImpl == null) {
                return null;
            }

            return createResourcesForActivityLocked(activityToken, initialOverrideConfig,
                    overrideDisplayId, classLoader, resourcesImpl, key.mCompatInfo);
        }
    }

    /**
     * Gets or creates a new Resources object associated with the IBinder token. References returned
     * by this method live as long as the Activity, meaning they can be cached and used by the
     * Activity even after a configuration change. If any other parameter is changed
     * (resDir, splitResDirs, overrideConfig) for a given Activity, the same Resources object
     * is updated and handed back to the caller. However, changing the class loader will result in a
     * new Resources object.
     * <p/>
     * If activityToken is null, a cached Resources object will be returned if it matches the
     * input parameters. Otherwise a new Resources object that satisfies these parameters is
     * returned.
     *
     * @param activityToken Represents an Activity. If null, global resources are assumed.
     * @param resDir The base resource path. Can be null (only framework resources will be loaded).
     * @param splitResDirs An array of split resource paths. Can be null.
     * @param legacyOverlayDirs An array of overlay APK paths. Can be null.
     * @param overlayPaths An array of overlay APK and non-APK paths. Can be null.
     * @param libDirs An array of resource library paths. Can be null.
     * @param overrideDisplayId The ID of the display for which the returned Resources should be
     * based. This will cause display-based configuration properties to override those of the base
     * Resources for the {@code activityToken}, or the global configuration if {@code activityToken}
     * is null.
     * @param overrideConfig The configuration to apply on top of the base configuration. Can be
     * null. Mostly used with Activities that are in multi-window which may override width and
     * height properties from the base config.
     * @param compatInfo The compatibility settings to use. Cannot be null. A default to use is
     * {@link CompatibilityInfo#DEFAULT_COMPATIBILITY_INFO}.
     * @param classLoader The class loader to use when inflating Resources. If null, the
     * {@link ClassLoader#getSystemClassLoader()} is used.
     * @return a Resources object from which to access resources.
     */
    @Nullable
    public Resources getResources(
            @Nullable IBinder activityToken,
            @Nullable String resDir,
            @Nullable String[] splitResDirs,
            @Nullable String[] legacyOverlayDirs,
            @Nullable String[] overlayPaths,
            @Nullable String[] libDirs,
            @Nullable Integer overrideDisplayId,
            @Nullable Configuration overrideConfig,
            @NonNull CompatibilityInfo compatInfo,
            @Nullable ClassLoader classLoader,
            @Nullable List<ResourcesLoader> loaders) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES, "ResourcesManager#getResources");
            final ResourcesKey key = new ResourcesKey(
                    resDir,
                    splitResDirs,
                    combinedOverlayPaths(legacyOverlayDirs, overlayPaths),
                    libDirs,
                    overrideDisplayId != null ? overrideDisplayId : INVALID_DISPLAY,
                    overrideConfig,
                    compatInfo,
                    loaders == null ? null : loaders.toArray(new ResourcesLoader[0]));
            classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();

            // Preload the ApkAssets required by the key to prevent performing heavy I/O while the
            // ResourcesManager lock is held.
            final ApkAssetsSupplier assetsSupplier = createApkAssetsSupplierNotLocked(key);

            if (overrideDisplayId != null) {
                rebaseKeyForDisplay(key, overrideDisplayId);
            }

            Resources resources;
            if (activityToken != null) {
                Configuration initialOverrideConfig = new Configuration(key.mOverrideConfiguration);
                rebaseKeyForActivity(activityToken, key, overrideDisplayId != null);
                resources = createResourcesForActivity(activityToken, key, initialOverrideConfig,
                        overrideDisplayId, classLoader, assetsSupplier);
            } else {
                resources = createResources(key, classLoader, assetsSupplier);
            }
            return resources;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * Updates an Activity's Resources object with overrideConfig. The Resources object
     * that was previously returned by {@link #getResources(IBinder, String, String[], String[],
     * String[], String[], Integer, Configuration, CompatibilityInfo, ClassLoader, List)} is still
     * valid and will have the updated configuration.
     *
     * @param activityToken The Activity token.
     * @param overrideConfig The configuration override to update.
     * @param displayId Id of the display where activity currently resides.
     */
    public void updateResourcesForActivity(@NonNull IBinder activityToken,
            @Nullable Configuration overrideConfig, int displayId) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES,
                    "ResourcesManager#updateResourcesForActivity");
            if (displayId == INVALID_DISPLAY) {
                throw new IllegalArgumentException("displayId can not be INVALID_DISPLAY");
            }
            synchronized (mLock) {
                final ActivityResources activityResources =
                        getOrCreateActivityResourcesStructLocked(activityToken);

                boolean movedToDifferentDisplay = activityResources.overrideDisplayId != displayId;
                if (Objects.equals(activityResources.overrideConfig, overrideConfig)
                        && !movedToDifferentDisplay) {
                    // They are the same and no change of display id, no work to do.
                    return;
                }

                // Grab a copy of the old configuration so we can create the delta's of each
                // Resources object associated with this Activity.
                final Configuration oldConfig = new Configuration(activityResources.overrideConfig);

                // Update the Activity's base override.
                if (overrideConfig != null) {
                    activityResources.overrideConfig.setTo(overrideConfig);
                } else {
                    activityResources.overrideConfig.unset();
                }

                // Update the Activity's override display id.
                activityResources.overrideDisplayId = displayId;

                // If a application info update was scheduled to occur in this process but has not
                // occurred yet, apply it now so the resources objects will have updated paths if
                // the assets sequence changed.
                applyAllPendingAppInfoUpdates();

                if (DEBUG) {
                    Throwable here = new Throwable();
                    here.fillInStackTrace();
                    Slog.d(TAG, "updating resources override for activity=" + activityToken
                            + " from oldConfig="
                            + Configuration.resourceQualifierString(oldConfig)
                            + " to newConfig="
                            + Configuration.resourceQualifierString(
                            activityResources.overrideConfig) + " displayId=" + displayId,
                            here);
                }


                // Rebase each Resources associated with this Activity.
                final int refCount = activityResources.activityResources.size();
                for (int i = 0; i < refCount; i++) {
                    final ActivityResource activityResource =
                            activityResources.activityResources.get(i);

                    final Resources resources = activityResource.resources.get();
                    if (resources == null) {
                        continue;
                    }

                    final ResourcesKey newKey = rebaseActivityOverrideConfig(activityResource,
                            overrideConfig, displayId);
                    if (newKey == null) {
                        continue;
                    }

                    // TODO(b/173090263): Improve the performance of AssetManager & ResourcesImpl
                    // constructions.
                    final ResourcesImpl resourcesImpl =
                            findOrCreateResourcesImplForKeyLocked(newKey);
                    if (resourcesImpl != null && resourcesImpl != resources.getImpl()) {
                        // Set the ResourcesImpl, updating it for all users of this Resources
                        // object.
                        resources.setImpl(resourcesImpl);
                    }
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * Rebases an updated override config over any old override config and returns the new one
     * that an Activity's Resources should be set to.
     */
    @Nullable
    private ResourcesKey rebaseActivityOverrideConfig(@NonNull ActivityResource activityResource,
            @Nullable Configuration newOverrideConfig, int displayId) {
        final Resources resources = activityResource.resources.get();
        if (resources == null) {
            return null;
        }

        // Extract the ResourcesKey that was last used to create the Resources for this
        // activity.
        final ResourcesKey oldKey = findKeyForResourceImplLocked(resources.getImpl());
        if (oldKey == null) {
            Slog.e(TAG, "can't find ResourcesKey for resources impl="
                    + resources.getImpl());
            return null;
        }

        // Build the new override configuration for this ResourcesKey.
        final Configuration rebasedOverrideConfig = new Configuration();
        if (newOverrideConfig != null) {
            rebasedOverrideConfig.setTo(newOverrideConfig);
        }

        final Integer overrideDisplayId = activityResource.overrideDisplayId;
        if (overrideDisplayId != null) {
            DisplayAdjustments displayAdjustments = new DisplayAdjustments(rebasedOverrideConfig);
            displayAdjustments.getConfiguration().setTo(activityResource.overrideConfig);
            displayAdjustments.setCompatibilityInfo(oldKey.mCompatInfo);

            DisplayMetrics dm = getDisplayMetrics(overrideDisplayId, displayAdjustments);
            applyDisplayMetricsToConfiguration(dm, rebasedOverrideConfig);
        }

        final boolean hasOverrideConfig =
                !activityResource.overrideConfig.equals(Configuration.EMPTY);
        if (hasOverrideConfig) {
            rebasedOverrideConfig.updateFrom(activityResource.overrideConfig);
        }

        if (activityResource.overrideDisplayId != null
                && activityResource.overrideConfig.windowConfiguration.getAppBounds() == null) {
            // If this activity resource is overriding the display from the token and the key's
            // window config app bounds is null we need to explicitly override this to
            // ensure the display adjustments are as expected.
            rebasedOverrideConfig.windowConfiguration.setAppBounds(null);
        }

        // Ensure the new key keeps the expected override display instead of the new token display.
        displayId = overrideDisplayId != null ? overrideDisplayId : displayId;

        // Create the new ResourcesKey with the rebased override config.
        final ResourcesKey newKey = new ResourcesKey(oldKey.mResDir,
                oldKey.mSplitResDirs, oldKey.mOverlayPaths, oldKey.mLibDirs,
                displayId, rebasedOverrideConfig, oldKey.mCompatInfo, oldKey.mLoaders);

        if (DEBUG) {
            Slog.d(TAG, "rebasing ref=" + resources + " from oldKey=" + oldKey
                    + " to newKey=" + newKey + ", displayId=" + displayId);
        }

        return newKey;
    }

    @RavenwoodThrow(reason = "AppInfo update not supported")
    public void appendPendingAppInfoUpdate(@NonNull String[] oldSourceDirs,
            @NonNull ApplicationInfo appInfo) {
        synchronized (mLock) {
            if (mPendingAppInfoUpdates == null) {
                mPendingAppInfoUpdates = new ArrayList<>();
            }
            // Clear previous app info changes for a package to prevent multiple ResourcesImpl
            // recreations when the recreation caused by this update completely overrides the
            // previous pending changes.
            for (int i = mPendingAppInfoUpdates.size() - 1; i >= 0; i--) {
                if (ArrayUtils.containsAll(oldSourceDirs, mPendingAppInfoUpdates.get(i).first)) {
                    mPendingAppInfoUpdates.remove(i);
                }
            }
            mPendingAppInfoUpdates.add(new Pair<>(oldSourceDirs, appInfo));
        }
    }

    @RavenwoodReplace(reason = "AppInfo update not supported")
    public final void applyAllPendingAppInfoUpdates() {
        synchronized (mLock) {
            if (mPendingAppInfoUpdates != null) {
                for (int i = 0, n = mPendingAppInfoUpdates.size(); i < n; i++) {
                    final Pair<String[], ApplicationInfo> appInfo = mPendingAppInfoUpdates.get(i);
                    applyNewResourceDirsLocked(appInfo.first, appInfo.second);
                }
                mPendingAppInfoUpdates = null;
            }
        }
    }

    private void applyAllPendingAppInfoUpdates$ravenwood() {
        /* no-op */
    }

    public final boolean applyConfigurationToResources(@NonNull Configuration config,
            @Nullable CompatibilityInfo compat) {
        synchronized (mLock) {
            try {
                Trace.traceBegin(Trace.TRACE_TAG_RESOURCES,
                        "ResourcesManager#applyConfigurationToResources");

                if (!mResConfiguration.isOtherSeqNewer(config) && compat == null) {
                    if (DEBUG || DEBUG_CONFIGURATION) {
                        Slog.v(TAG, "Skipping new config: curSeq="
                                + mResConfiguration.seq + ", newSeq=" + config.seq);
                    }
                    return false;
                }

                int changes = mResConfiguration.updateFrom(config);
                if (compat != null && (mResCompatibilityInfo == null
                        || !mResCompatibilityInfo.equals(compat))) {
                    mResCompatibilityInfo = compat;
                    changes |= ActivityInfo.CONFIG_SCREEN_LAYOUT
                            | ActivityInfo.CONFIG_SCREEN_SIZE
                            | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
                }

                // If a application info update was scheduled to occur in this process but has not
                // occurred yet, apply it now so the resources objects will have updated paths when
                // the assets sequence changes.
                if ((changes & ActivityInfo.CONFIG_ASSETS_PATHS) != 0) {
                    applyAllPendingAppInfoUpdates();
                }

                final DisplayMetrics displayMetrics = getDisplayMetrics(config);
                Resources.updateSystemConfiguration(config, displayMetrics, compat);

                ApplicationPackageManager.configurationChanged();

                Configuration tmpConfig = new Configuration();

                for (int i = mResourceImpls.size() - 1; i >= 0; i--) {
                    ResourcesKey key = mResourceImpls.keyAt(i);
                    WeakReference<ResourcesImpl> weakImplRef = mResourceImpls.valueAt(i);
                    ResourcesImpl r = weakImplRef != null ? weakImplRef.get() : null;
                    if (r != null) {
                        applyConfigurationToResourcesLocked(config, compat, tmpConfig, key, r);
                    } else {
                        mResourceImpls.removeAt(i);
                    }
                }

                return changes != 0;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
            }
        }
    }

    private void applyConfigurationToResourcesLocked(@NonNull Configuration config,
            @Nullable CompatibilityInfo compat, Configuration tmpConfig,
            ResourcesKey key, ResourcesImpl resourcesImpl) {
        if (DEBUG || DEBUG_CONFIGURATION) {
            Slog.v(TAG, "Changing resources "
                    + resourcesImpl + " config to: " + config);
        }

        tmpConfig.setTo(config);
        if (key.hasOverrideConfiguration()) {
            tmpConfig.updateFrom(key.mOverrideConfiguration);
        }

        // Get new DisplayMetrics based on the DisplayAdjustments given to the ResourcesImpl. Update
        // a copy if the CompatibilityInfo changed, because the ResourcesImpl object will handle the
        // update internally.
        DisplayAdjustments daj = resourcesImpl.getDisplayAdjustments();
        if (compat != null) {
            daj = new DisplayAdjustments(daj);
            daj.setCompatibilityInfo(compat);
        }
        daj.setConfiguration(tmpConfig);
        DisplayMetrics dm = getDisplayMetrics(generateDisplayId(key), daj);

        resourcesImpl.updateConfiguration(tmpConfig, dm, compat);
    }

    /**
     * Appends the library asset path to any ResourcesImpl object that contains the main
     * assetPath.
     * @param assetPath The main asset path for which to add the library asset path.
     * @param libAsset The library asset path to add.
     */
    @UnsupportedAppUsage
    public void appendLibAssetForMainAssetPath(String assetPath, String libAsset) {
        appendLibAssetsForMainAssetPath(assetPath, new String[] { libAsset });
    }

    /**
     * Appends the library asset paths to any ResourcesImpl object that contains the main
     * assetPath.
     * @param assetPath The main asset path for which to add the library asset path.
     * @param libAssets The library asset paths to add.
     */
    public void appendLibAssetsForMainAssetPath(String assetPath, String[] libAssets) {
        synchronized (mLock) {
            // Record which ResourcesImpl need updating
            // (and what ResourcesKey they should update to).
            final ArrayMap<ResourcesImpl, ResourcesKey> updatedResourceKeys = new ArrayMap<>();

            final int implCount = mResourceImpls.size();
            for (int i = 0; i < implCount; i++) {
                final ResourcesKey key = mResourceImpls.keyAt(i);
                final WeakReference<ResourcesImpl> weakImplRef = mResourceImpls.valueAt(i);
                final ResourcesImpl impl = weakImplRef != null ? weakImplRef.get() : null;
                if (impl != null && Objects.equals(key.mResDir, assetPath)) {
                    String[] newLibAssets = key.mLibDirs;
                    for (String libAsset : libAssets) {
                        newLibAssets =
                                ArrayUtils.appendElement(String.class, newLibAssets, libAsset);
                    }

                    if (!Arrays.equals(newLibAssets, key.mLibDirs)) {
                        updatedResourceKeys.put(impl, new ResourcesKey(
                                key.mResDir,
                                key.mSplitResDirs,
                                key.mOverlayPaths,
                                newLibAssets,
                                key.mDisplayId,
                                key.mOverrideConfiguration,
                                key.mCompatInfo,
                                key.mLoaders));
                    }
                }
            }

            redirectResourcesToNewImplLocked(updatedResourceKeys);
        }
    }

    /**
     * A utility class to collect resources paths into a ResourcesKey object:
     *  - Separates the libraries and the overlays into different sets as those are loaded in
     *    different ways.
     *  - Allows to start with an existing original key object, and copies all non-path related
     *    properties into the final one.
     *  - Preserves the path order while dropping all duplicates in an efficient manner.
     */
    private static class PathCollector {
        public final ResourcesKey originalKey;
        public final ArrayList<String> orderedLibs = new ArrayList<>();
        public final ArraySet<String> libsSet = new ArraySet<>();
        public final ArrayList<String> orderedOverlays = new ArrayList<>();
        public final ArraySet<String> overlaysSet = new ArraySet<>();

        static void appendNewPath(@NonNull String path,
                @NonNull ArraySet<String> uniquePaths, @NonNull ArrayList<String> orderedPaths) {
            if (uniquePaths.add(path)) {
                orderedPaths.add(path);
            }
        }

        static void appendAllNewPaths(@Nullable String[] paths,
                @NonNull ArraySet<String> uniquePaths, @NonNull ArrayList<String> orderedPaths) {
            if (paths == null) return;
            for (int i = 0, size = paths.length; i < size; i++) {
                appendNewPath(paths[i], uniquePaths, orderedPaths);
            }
        }

        PathCollector(@Nullable ResourcesKey original) {
            originalKey = original;
            if (originalKey != null) {
                appendKey(originalKey);
            }
        }

        public void appendKey(@NonNull ResourcesKey key) {
            appendAllNewPaths(key.mLibDirs, libsSet, orderedLibs);
            appendAllNewPaths(key.mOverlayPaths, overlaysSet, orderedOverlays);
        }

        boolean isSameAsOriginal() {
            if (originalKey == null) {
                return orderedLibs.isEmpty() && orderedOverlays.isEmpty();
            }
            return ((originalKey.mLibDirs == null && orderedLibs.isEmpty())
                        || (originalKey.mLibDirs != null
                            && originalKey.mLibDirs.length == orderedLibs.size()))
                    && ((originalKey.mOverlayPaths == null && orderedOverlays.isEmpty())
                        || (originalKey.mOverlayPaths != null
                                && originalKey.mOverlayPaths.length == orderedOverlays.size()));
        }

        @NonNull ResourcesKey collectedKey() {
            return new ResourcesKey(
                    originalKey == null ? null : originalKey.mResDir,
                    originalKey == null ? null : originalKey.mSplitResDirs,
                    orderedOverlays.toArray(new String[0]), orderedLibs.toArray(new String[0]),
                    originalKey == null ? 0 : originalKey.mDisplayId,
                    originalKey == null ? null : originalKey.mOverrideConfiguration,
                    originalKey == null ? null : originalKey.mCompatInfo,
                    originalKey == null ? null : originalKey.mLoaders);
        }
    }

    /**
     * Takes the original resources key and the one containing a set of library paths and overlays
     * to append, and combines them together. In case when the original key already contains all
     * those paths this function returns null, otherwise it makes a new ResourcesKey object.
     */
    private @Nullable ResourcesKey createNewResourceKeyIfNeeded(
            @NonNull ResourcesKey original, @NonNull ResourcesKey library) {
        final var collector = new PathCollector(original);
        collector.appendKey(library);
        return collector.isSameAsOriginal() ? null : collector.collectedKey();
    }

    /**
     * Append the newly registered shared library asset paths to all existing resources objects.
     */
    private void appendLibAssetsLocked(@NonNull SharedLibraryAssets libAssets) {
        // Record the ResourcesImpl's that need updating, and what ResourcesKey they should
        // update to.
        final ArrayMap<ResourcesImpl, ResourcesKey> updatedResourceKeys = new ArrayMap<>();
        final int implCount = mResourceImpls.size();
        for (int i = 0; i < implCount; i++) {
            final ResourcesKey key = mResourceImpls.keyAt(i);
            final WeakReference<ResourcesImpl> weakImplRef = mResourceImpls.valueAt(i);
            final ResourcesImpl impl = weakImplRef != null ? weakImplRef.get() : null;
            if (impl == null) {
                Slog.w(TAG, "Found a null ResourcesImpl, skipped.");
                continue;
            }

            final var newKey = createNewResourceKeyIfNeeded(key, libAssets.getResourcesKey());
            if (newKey != null) {
                updatedResourceKeys.put(impl, newKey);
            }
        }
        redirectAllResourcesToNewImplLocked(updatedResourceKeys);
    }

    private void applyNewResourceDirsLocked(@Nullable final String[] oldSourceDirs,
            @NonNull final ApplicationInfo appInfo) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES,
                    "ResourcesManager#applyNewResourceDirsLocked");

            String baseCodePath = appInfo.getBaseCodePath();

            final int myUid = Process.myUid();
            String[] newSplitDirs = appInfo.uid == myUid
                    ? appInfo.splitSourceDirs
                    : appInfo.splitPublicSourceDirs;

            // ApplicationInfo is mutable, so clone the arrays to prevent outside modification
            String[] copiedSplitDirs = ArrayUtils.cloneOrNull(newSplitDirs);
            String[] copiedResourceDirs = combinedOverlayPaths(appInfo.resourceDirs,
                    appInfo.overlayPaths);

            if (appInfo.uid == myUid) {
                addApplicationPathsLocked(baseCodePath, copiedSplitDirs);
            }

            final ArrayMap<ResourcesImpl, ResourcesKey> updatedResourceKeys = new ArrayMap<>();
            final int implCount = mResourceImpls.size();
            for (int i = 0; i < implCount; i++) {
                final ResourcesKey key = mResourceImpls.keyAt(i);
                final WeakReference<ResourcesImpl> weakImplRef = mResourceImpls.valueAt(i);
                final ResourcesImpl impl = weakImplRef != null ? weakImplRef.get() : null;

                if (impl == null) {
                    continue;
                }

                if (key.mResDir == null
                        || key.mResDir.equals(baseCodePath)
                        || ArrayUtils.contains(oldSourceDirs, key.mResDir)) {
                    updatedResourceKeys.put(impl, new ResourcesKey(
                            baseCodePath,
                            copiedSplitDirs,
                            copiedResourceDirs,
                            key.mLibDirs,
                            key.mDisplayId,
                            key.mOverrideConfiguration,
                            key.mCompatInfo,
                            key.mLoaders
                    ));
                }
            }

            redirectResourcesToNewImplLocked(updatedResourceKeys);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * Creates an array with the contents of {@param overlayPaths} and the unique elements of
     * {@param resourceDirs}.
     *
     * {@link ApplicationInfo#resourceDirs} only contains paths of overlays APKs.
     * {@link ApplicationInfo#overlayPaths} was created to contain paths of overlay of varying file
     * formats. It also contains the contents of {@code resourceDirs} because the order of loaded
     * overlays matter. In case {@code resourceDirs} contains overlay APK paths that are not present
     * in overlayPaths (perhaps an app inserted an additional overlay path into a
     * {@code resourceDirs}), this method is used to combine the contents of {@code resourceDirs}
     * that do not exist in {@code overlayPaths}} and {@code overlayPaths}}.
     */
    @Nullable
    private static String[] combinedOverlayPaths(@Nullable String[] resourceDirs,
            @Nullable String[] overlayPaths) {
        if (resourceDirs == null) {
            return ArrayUtils.cloneOrNull(overlayPaths);
        } else if(overlayPaths == null) {
            return ArrayUtils.cloneOrNull(resourceDirs);
        } else {
            final var paths = new ArrayList<String>(overlayPaths.length + resourceDirs.length);
            for (final String path : overlayPaths) {
                paths.add(path);
            }
            for (final String path : resourceDirs) {
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            }
            return paths.toArray(new String[0]);
        }
    }

    private void redirectResourcesToNewImplLocked(
            @NonNull final ArrayMap<ResourcesImpl, ResourcesKey> updatedResourceKeys) {
        // Bail early if there is no work to do.
        if (updatedResourceKeys.isEmpty()) {
            return;
        }

        // Update any references to ResourcesImpl that require reloading.
        final int resourcesCount = mResourceReferences.size();
        for (int i = 0; i < resourcesCount; i++) {
            final WeakReference<Resources> ref = mResourceReferences.get(i);
            final Resources r = ref != null ? ref.get() : null;
            if (r != null) {
                final ResourcesKey key = updatedResourceKeys.get(r.getImpl());
                if (key != null) {
                    final ResourcesImpl impl = findOrCreateResourcesImplForKeyLocked(key);
                    if (impl == null) {
                        throw new Resources.NotFoundException("failed to redirect ResourcesImpl");
                    }
                    r.setImpl(impl);
                }
            }
        }

        // Update any references to ResourcesImpl that require reloading for each Activity.
        for (ActivityResources activityResources : mActivityResourceReferences.values()) {
            final int resCount = activityResources.activityResources.size();
            for (int i = 0; i < resCount; i++) {
                final ActivityResource activityResource =
                        activityResources.activityResources.get(i);
                final Resources r = activityResource != null
                        ? activityResource.resources.get() : null;
                if (r != null) {
                    final ResourcesKey key = updatedResourceKeys.get(r.getImpl());
                    if (key != null) {
                        final ResourcesImpl impl = findOrCreateResourcesImplForKeyLocked(key);
                        if (impl == null) {
                            throw new Resources.NotFoundException(
                                    "failed to redirect ResourcesImpl");
                        }
                        r.setImpl(impl);
                    }
                }
            }
        }
    }

    // Another redirect function which will loop through all Resources in the process, even the ones
    // the app created outside of the regular Android Runtime, and reload their ResourcesImpl if it
    // needs a shared library asset paths update.
    private void redirectAllResourcesToNewImplLocked(
            @NonNull final ArrayMap<ResourcesImpl, ResourcesKey> updatedResourceKeys) {
        cleanupReferences(mAllResourceReferences, mAllResourceReferencesQueue);

        // Update any references to ResourcesImpl that require reloading.
        final int resourcesCount = mAllResourceReferences.size();
        for (int i = 0; i < resourcesCount; i++) {
            final WeakReference<Resources> ref = mAllResourceReferences.get(i);
            final Resources r = ref != null ? ref.get() : null;
            if (r != null) {
                final ResourcesKey key = updatedResourceKeys.get(r.getImpl());
                if (key != null) {
                    final ResourcesImpl impl = findOrCreateResourcesImplForKeyLocked(key);
                    if (impl == null) {
                        throw new Resources.NotFoundException("failed to redirect ResourcesImpl");
                    }
                    r.setImpl(impl);
                } else {
                    // ResourcesKey is null which means the ResourcesImpl could belong to a
                    // Resources created by application through Resources constructor and was not
                    // managed by ResourcesManager, so the ResourcesImpl needs to be recreated to
                    // have shared library asset paths appended if there are any.
                    if (r.getImpl() != null) {
                        final ResourcesImpl oldImpl = r.getImpl();
                        final AssetManager oldAssets = oldImpl.getAssets();
                        // ResourcesImpl constructor will help to append shared library asset paths.
                        if (oldAssets != AssetManager.getSystem() && oldAssets.isUpToDate()) {
                            final ResourcesImpl newImpl = new ResourcesImpl(oldAssets,
                                    oldImpl.getMetrics(), oldImpl.getConfiguration(),
                                    oldImpl.getDisplayAdjustments());
                            r.setImpl(newImpl);
                        } else {
                            Slog.w(TAG, "Skip appending shared library asset paths for the "
                                    + "Resource as its assets are not up to date.");
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the LocaleConfig current set
     */
    public LocaleConfig getLocaleConfig() {
        return mLocaleConfig;
    }

    /**
     * Sets the LocaleConfig of the app
     */
    public void setLocaleConfig(LocaleConfig localeConfig) {
        if ((localeConfig != null) && (localeConfig.getSupportedLocales() != null)
                && !localeConfig.getSupportedLocales().isEmpty()) {
            mLocaleConfig = localeConfig;
        }
    }

    private class UpdateHandler implements Resources.UpdateCallbacks {

        /**
         * Updates the list of {@link ResourcesLoader ResourcesLoader(s)} that the {@code resources}
         * instance uses.
         */
        @Override
        @RavenwoodThrow(blockedBy = ResourcesLoader.class)
        public void onLoadersChanged(@NonNull Resources resources,
                @NonNull List<ResourcesLoader> newLoader) {
            synchronized (mLock) {
                final ResourcesKey oldKey = findKeyForResourceImplLocked(resources.getImpl());
                if (oldKey == null) {
                    throw new IllegalArgumentException("Cannot modify resource loaders of"
                            + " ResourcesImpl not registered with ResourcesManager");
                }

                final ResourcesKey newKey = new ResourcesKey(
                        oldKey.mResDir,
                        oldKey.mSplitResDirs,
                        oldKey.mOverlayPaths,
                        oldKey.mLibDirs,
                        oldKey.mDisplayId,
                        oldKey.mOverrideConfiguration,
                        oldKey.mCompatInfo,
                        newLoader.toArray(new ResourcesLoader[0]));

                final ResourcesImpl impl = findOrCreateResourcesImplForKeyLocked(newKey);
                resources.setImpl(impl);
            }
        }

        /**
         * Refreshes the {@link AssetManager} of all {@link ResourcesImpl} that contain the
         * {@code loader} to apply any changes of the set of {@link ApkAssets}.
         **/
        @Override
        @RavenwoodThrow(blockedBy = ResourcesLoader.class)
        public void onLoaderUpdated(@NonNull ResourcesLoader loader) {
            synchronized (mLock) {
                final ArrayMap<ResourcesImpl, ResourcesKey> updatedResourceImplKeys =
                        new ArrayMap<>();

                for (int i = mResourceImpls.size() - 1; i >= 0; i--) {
                    final ResourcesKey key = mResourceImpls.keyAt(i);
                    final WeakReference<ResourcesImpl> impl = mResourceImpls.valueAt(i);
                    if (impl == null || impl.refersTo(null)
                            || !ArrayUtils.contains(key.mLoaders, loader)) {
                        continue;
                    }

                    mResourceImpls.remove(key);
                    updatedResourceImplKeys.put(impl.get(), key);
                }

                redirectResourcesToNewImplLocked(updatedResourceImplKeys);
            }
        }
    }

    @VisibleForTesting
    public static class SharedLibraryAssets {
        private final ResourcesKey mResourcesKey;

        private SharedLibraryAssets(ApplicationInfo appInfo) {
            // We're loading all library's files as shared libs, regardless where they are in
            // its own ApplicationInfo.
            final var collector = new PathCollector(null);
            PathCollector.appendNewPath(appInfo.sourceDir, collector.libsSet,
                    collector.orderedLibs);
            PathCollector.appendAllNewPaths(appInfo.splitSourceDirs, collector.libsSet,
                    collector.orderedLibs);
            PathCollector.appendAllNewPaths(appInfo.sharedLibraryFiles, collector.libsSet,
                    collector.orderedLibs);
            PathCollector.appendAllNewPaths(appInfo.resourceDirs, collector.overlaysSet,
                    collector.orderedOverlays);
            PathCollector.appendAllNewPaths(appInfo.overlayPaths, collector.overlaysSet,
                    collector.orderedOverlays);
            mResourcesKey = collector.collectedKey();
        }

        /**
         * @return the resources key for this library assets.
         */
        public @NonNull ResourcesKey getResourcesKey() {
            return mResourcesKey;
        }
    }

    /**
     * Add all resources references to the list which is designed to help to append shared library
     * asset paths. This is invoked in Resources constructor to include all Resources instances.
     */
    public void registerAllResourcesReference(@NonNull Resources resources) {
        if (android.content.res.Flags.registerResourcePaths()) {
            synchronized (mLock) {
                cleanupReferences(mAllResourceReferences, mAllResourceReferencesQueue);
                mAllResourceReferences.add(
                        new WeakReference<>(resources, mAllResourceReferencesQueue));
            }
        }
    }
}
