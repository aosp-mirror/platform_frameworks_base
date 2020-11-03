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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.CompatResources;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ResourcesImpl;
import android.content.res.ResourcesKey;
import android.content.res.loader.ResourcesLoader;
import android.hardware.display.DisplayManagerGlobal;
import android.os.IBinder;
import android.os.Process;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAdjustments;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;

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
import java.util.function.Consumer;

/** @hide */
public class ResourcesManager {
    static final String TAG = "ResourcesManager";
    private static final boolean DEBUG = false;

    private static ResourcesManager sResourcesManager;

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

    private static class ApkKey {
        public final String path;
        public final boolean sharedLib;
        public final boolean overlay;

        ApkKey(String path, boolean sharedLib, boolean overlay) {
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
        public boolean equals(Object obj) {
            if (!(obj instanceof ApkKey)) {
                return false;
            }
            ApkKey other = (ApkKey) obj;
            return this.path.equals(other.path) && this.sharedLib == other.sharedLib
                    && this.overlay == other.overlay;
        }
    }

    /**
     * Loads {@link ApkAssets} and caches them to prevent their garbage collection while the
     * instance is alive and reachable.
     */
    private class ApkAssetsSupplier {
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
     */
    private final ArrayMap<ApkKey, WeakReference<ApkAssets>> mCachedApkAssets = new ArrayMap<>();

    /**
     * Resources and base configuration override associated with an Activity.
     */
    private static class ActivityResources {
        @UnsupportedAppUsage
        private ActivityResources() {
        }
        public final Configuration overrideConfig = new Configuration();
        public final ArrayList<WeakReference<Resources>> activityResources = new ArrayList<>();
        final ReferenceQueue<Resources> activityResourcesQueue = new ReferenceQueue<>();
    }

    /**
     * Each Activity may has a base override configuration that is applied to each Resources object,
     * which in turn may have their own override configuration specified.
     */
    @UnsupportedAppUsage
    private final WeakHashMap<IBinder, ActivityResources> mActivityResourceReferences =
            new WeakHashMap<>();

    /**
     * A cache of DisplayId, DisplayAdjustments to Display.
     */
    private final ArrayMap<Pair<Integer, DisplayAdjustments>, WeakReference<Display>>
            mAdjustedDisplays = new ArrayMap<>();

    /**
     * Callback implementation for handling updates to Resources objects.
     */
    private final UpdateHandler mUpdateCallbacks = new UpdateHandler();

    @UnsupportedAppUsage
    public ResourcesManager() {
    }

    @UnsupportedAppUsage
    public static ResourcesManager getInstance() {
        synchronized (ResourcesManager.class) {
            if (sResourcesManager == null) {
                sResourcesManager = new ResourcesManager();
            }
            return sResourcesManager;
        }
    }

    /**
     * Invalidate and destroy any resources that reference content under the
     * given filesystem path. Typically used when unmounting a storage device to
     * try as hard as possible to release any open FDs.
     */
    public void invalidatePath(String path) {
        synchronized (this) {
            int count = 0;

            for (int i = mResourceImpls.size() - 1; i >= 0; i--) {
                final ResourcesKey key = mResourceImpls.keyAt(i);
                if (key.isPathReferenced(path)) {
                    ResourcesImpl impl = mResourceImpls.removeAt(i).get();
                    if (impl != null) {
                        impl.flushLayoutCache();
                    }
                    count++;
                }
            }

            Log.i(TAG, "Invalidated " + count + " asset managers that referenced " + path);

            for (int i = mCachedApkAssets.size() - 1; i >= 0; i--) {
                final ApkKey key = mCachedApkAssets.keyAt(i);
                if (key.path.equals(path)) {
                    WeakReference<ApkAssets> apkAssetsRef = mCachedApkAssets.removeAt(i);
                    if (apkAssetsRef != null && apkAssetsRef.get() != null) {
                        apkAssetsRef.get().close();
                    }
                }
            }
        }
    }

    public Configuration getConfiguration() {
        synchronized (this) {
            return mResConfiguration;
        }
    }

    DisplayMetrics getDisplayMetrics() {
        return getDisplayMetrics(Display.DEFAULT_DISPLAY,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    /**
     * Protected so that tests can override and returns something a fixed value.
     */
    @VisibleForTesting
    protected @NonNull DisplayMetrics getDisplayMetrics(int displayId, DisplayAdjustments da) {
        DisplayMetrics dm = new DisplayMetrics();
        final Display display = getAdjustedDisplay(displayId, da);
        if (display != null) {
            display.getMetrics(dm);
        } else {
            dm.setToDefaults();
        }
        return dm;
    }

    private static void applyNonDefaultDisplayMetricsToConfiguration(
            @NonNull DisplayMetrics dm, @NonNull Configuration config) {
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.densityDpi = dm.densityDpi;
        config.screenWidthDp = (int) (dm.widthPixels / dm.density);
        config.screenHeightDp = (int) (dm.heightPixels / dm.density);
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

    public boolean applyCompatConfigurationLocked(int displayDensity,
            @NonNull Configuration compatConfiguration) {
        if (mResCompatibilityInfo != null && !mResCompatibilityInfo.supportsScreen()) {
            mResCompatibilityInfo.applyToConfiguration(displayDensity, compatConfiguration);
            return true;
        }
        return false;
    }

    /**
     * Returns an adjusted {@link Display} object based on the inputs or null if display isn't
     * available. This method is only used within {@link ResourcesManager} to calculate display
     * metrics based on a set {@link DisplayAdjustments}. All other usages should instead call
     * {@link ResourcesManager#getAdjustedDisplay(int, Resources)}.
     *
     * @param displayId display Id.
     * @param displayAdjustments display adjustments.
     */
    private Display getAdjustedDisplay(final int displayId,
            @Nullable DisplayAdjustments displayAdjustments) {
        final DisplayAdjustments displayAdjustmentsCopy = (displayAdjustments != null)
                ? new DisplayAdjustments(displayAdjustments) : new DisplayAdjustments();
        final Pair<Integer, DisplayAdjustments> key =
                Pair.create(displayId, displayAdjustmentsCopy);
        synchronized (this) {
            WeakReference<Display> wd = mAdjustedDisplays.get(key);
            if (wd != null) {
                final Display display = wd.get();
                if (display != null) {
                    return display;
                }
            }
            final DisplayManagerGlobal dm = DisplayManagerGlobal.getInstance();
            if (dm == null) {
                // may be null early in system startup
                return null;
            }
            final Display display = dm.getCompatibleDisplay(displayId, key.second);
            if (display != null) {
                mAdjustedDisplays.put(key, new WeakReference<>(display));
            }
            return display;
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
        synchronized (this) {
            final DisplayManagerGlobal dm = DisplayManagerGlobal.getInstance();
            if (dm == null) {
                // may be null early in system startup
                return null;
            }
            return dm.getCompatibleDisplay(displayId, resources);
        }
    }

    private static String overlayPathToIdmapPath(String path) {
        return "/data/resource-cache/" + path.substring(1).replace('/', '@') + "@idmap";
    }

    private @NonNull ApkAssets loadApkAssets(@NonNull final ApkKey key) throws IOException {
        ApkAssets apkAssets;

        // Optimistically check if this ApkAssets exists somewhere else.
        synchronized (this) {
            final WeakReference<ApkAssets> apkAssetsRef = mCachedApkAssets.get(key);
            if (apkAssetsRef != null) {
                apkAssets = apkAssetsRef.get();
                if (apkAssets != null && apkAssets.isUpToDate()) {
                    return apkAssets;
                } else {
                    // Clean up the reference.
                    mCachedApkAssets.remove(key);
                }
            }
        }

        // We must load this from disk.
        if (key.overlay) {
            apkAssets = ApkAssets.loadOverlayFromPath(overlayPathToIdmapPath(key.path),
                    0 /*flags*/);
        } else {
            apkAssets = ApkAssets.loadFromPath(key.path,
                    key.sharedLib ? ApkAssets.PROPERTY_DYNAMIC : 0);
        }

        synchronized (this) {
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

        if (key.mOverlayDirs != null) {
            for (final String idmapPath : key.mOverlayDirs) {
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
    private @Nullable AssetManager createAssetManager(@NonNull final ResourcesKey key,
            @Nullable ApkAssetsSupplier apkSupplier) {
        final AssetManager.Builder builder = new AssetManager.Builder();

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
        synchronized (this) {
            IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
            for (int i = 0; i < prefix.length() / 2; i++) {
                pw.increaseIndent();
            }

            pw.println("ResourcesManager:");
            pw.increaseIndent();
            pw.print("total apks: ");
            pw.println(countLiveReferences(mCachedApkAssets.values()));

            pw.print("resources: ");

            int references = countLiveReferences(mResourceReferences);
            for (ActivityResources activityResources : mActivityResourceReferences.values()) {
                references += countLiveReferences(activityResources.activityResources);
            }
            pw.println(references);

            pw.print("resource impls: ");
            pw.println(countLiveReferences(mResourceImpls.values()));
        }
    }

    private Configuration generateConfig(@NonNull ResourcesKey key, @NonNull DisplayMetrics dm) {
        Configuration config;
        final boolean isDefaultDisplay = (key.mDisplayId == Display.DEFAULT_DISPLAY);
        final boolean hasOverrideConfig = key.hasOverrideConfiguration();
        if (!isDefaultDisplay || hasOverrideConfig) {
            config = new Configuration(getConfiguration());
            if (!isDefaultDisplay) {
                applyNonDefaultDisplayMetricsToConfiguration(dm, config);
            }
            if (hasOverrideConfig) {
                config.updateFrom(key.mOverrideConfiguration);
                if (DEBUG) Slog.v(TAG, "Applied overrideConfig=" + key.mOverrideConfiguration);
            }
        } else {
            config = getConfiguration();
        }
        return config;
    }

    private @Nullable ResourcesImpl createResourcesImpl(@NonNull ResourcesKey key,
            @Nullable ApkAssetsSupplier apkSupplier) {
        final DisplayAdjustments daj = new DisplayAdjustments(key.mOverrideConfiguration);
        daj.setCompatibilityInfo(key.mCompatInfo);

        final AssetManager assets = createAssetManager(key, apkSupplier);
        if (assets == null) {
            return null;
        }

        final DisplayMetrics dm = getDisplayMetrics(key.mDisplayId, daj);
        final Configuration config = generateConfig(key, dm);
        final ResourcesImpl impl = new ResourcesImpl(assets, dm, config, daj);

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
        if (impl == null) {
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
            ResourcesImpl impl = weakImplRef != null ? weakImplRef.get() : null;
            if (resourceImpl == impl) {
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
    boolean isSameResourcesOverrideConfig(@Nullable IBinder activityToken,
            @Nullable Configuration overrideConfig) {
        synchronized (this) {
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
            WeakReference<Resources> ref = activityResources.activityResources.get(index);
            Resources resources = ref.get();
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

    private @NonNull Resources createResourcesForActivityLocked(@NonNull IBinder activityToken,
            @NonNull ClassLoader classLoader, @NonNull ResourcesImpl impl,
            @NonNull CompatibilityInfo compatInfo) {
        final ActivityResources activityResources = getOrCreateActivityResourcesStructLocked(
                activityToken);
        cleanupReferences(activityResources.activityResources,
                activityResources.activityResourcesQueue);

        Resources resources = compatInfo.needsCompatResources() ? new CompatResources(classLoader)
                : new Resources(classLoader);
        resources.setImpl(impl);
        resources.setCallbacks(mUpdateCallbacks);
        activityResources.activityResources.add(
                new WeakReference<>(resources, activityResources.activityResourcesQueue));
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
     * {@link #getResources(IBinder, String, String[], String[], String[], int, Configuration,
     * CompatibilityInfo, ClassLoader, List)} with the same binder token will have their override
     * configurations merged with the one specified here.
     *
     * @param token Represents an {@link Activity} or {@link WindowContext}.
     * @param resDir The base resource path. Can be null (only framework resources will be loaded).
     * @param splitResDirs An array of split resource paths. Can be null.
     * @param overlayDirs An array of overlay paths. Can be null.
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
            @Nullable String[] overlayDirs,
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
                    overlayDirs,
                    libDirs,
                    displayId,
                    overrideConfig != null ? new Configuration(overrideConfig) : null, // Copy
                    compatInfo,
                    loaders == null ? null : loaders.toArray(new ResourcesLoader[0]));
            classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();

            if (DEBUG) {
                Slog.d(TAG, "createBaseActivityResources activity=" + token
                        + " with key=" + key);
            }

            synchronized (this) {
                // Force the creation of an ActivityResourcesStruct.
                getOrCreateActivityResourcesStructLocked(token);
            }

            // Update any existing Activity Resources references.
            updateResourcesForActivity(token, overrideConfig, displayId,
                    false /* movedToDifferentDisplay */);

            rebaseKeyForActivity(token, key);

            synchronized (this) {
                Resources resources = findResourcesForActivityLocked(token, key,
                        classLoader);
                if (resources != null) {
                    return resources;
                }
            }

            // Now request an actual Resources object.
            return createResources(token, key, classLoader, /* apkSupplier */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * Rebases a key's override config on top of the Activity's base override.
     */
    private void rebaseKeyForActivity(IBinder activityToken, ResourcesKey key) {
        synchronized (this) {
            final ActivityResources activityResources =
                    getOrCreateActivityResourcesStructLocked(activityToken);

            // Rebase the key's override config on top of the Activity's base override.
            if (key.hasOverrideConfiguration()
                    && !activityResources.overrideConfig.equals(Configuration.EMPTY)) {
                final Configuration temp = new Configuration(activityResources.overrideConfig);
                temp.updateFrom(key.mOverrideConfiguration);
                key.mOverrideConfiguration.setTo(temp);
            }
        }
    }

    /**
     * Check WeakReferences and remove any dead references so they don't pile up.
     */
    private static <T> void cleanupReferences(ArrayList<WeakReference<T>> references,
            ReferenceQueue<T> referenceQueue) {
        Reference<? extends T> enduedRef = referenceQueue.poll();
        if (enduedRef == null) {
            return;
        }

        final HashSet<Reference<? extends T>> deadReferences = new HashSet<>();
        for (; enduedRef != null; enduedRef = referenceQueue.poll()) {
            deadReferences.add(enduedRef);
        }

        ArrayUtils.unstableRemoveIf(references,
                (ref) -> ref == null || deadReferences.contains(ref));
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
     * @param activityToken The Activity this Resources object should be associated with.
     * @param key The key describing the parameters of the ResourcesImpl object.
     * @param classLoader The classloader to use for the Resources object.
     *                    If null, {@link ClassLoader#getSystemClassLoader()} is used.
     * @param apkSupplier The apk assets supplier to use when creating a new ResourcesImpl object.
     * @return A Resources object that gets updated when
     *         {@link #applyConfigurationToResourcesLocked(Configuration, CompatibilityInfo)}
     *         is called.
     */
    private @Nullable Resources createResources(@Nullable IBinder activityToken,
            @NonNull ResourcesKey key, @NonNull ClassLoader classLoader,
            @Nullable ApkAssetsSupplier apkSupplier) {
        synchronized (this) {
            if (DEBUG) {
                Throwable here = new Throwable();
                here.fillInStackTrace();
                Slog.w(TAG, "!! Get resources for activity=" + activityToken + " key=" + key, here);
            }

            ResourcesImpl resourcesImpl = findOrCreateResourcesImplForKeyLocked(key, apkSupplier);
            if (resourcesImpl == null) {
                return null;
            }

            if (activityToken != null) {
                return createResourcesForActivityLocked(activityToken, classLoader,
                        resourcesImpl, key.mCompatInfo);
            } else {
                return createResourcesLocked(classLoader, resourcesImpl, key.mCompatInfo);
            }
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
     * @param overlayDirs An array of overlay paths. Can be null.
     * @param libDirs An array of resource library paths. Can be null.
     * @param displayId The ID of the display for which to create the resources.
     * @param overrideConfig The configuration to apply on top of the base configuration. Can be
     * null. Mostly used with Activities that are in multi-window which may override width and
     * height properties from the base config.
     * @param compatInfo The compatibility settings to use. Cannot be null. A default to use is
     * {@link CompatibilityInfo#DEFAULT_COMPATIBILITY_INFO}.
     * @param classLoader The class loader to use when inflating Resources. If null, the
     * {@link ClassLoader#getSystemClassLoader()} is used.
     * @return a Resources object from which to access resources.
     */
    public @Nullable Resources getResources(
            @Nullable IBinder activityToken,
            @Nullable String resDir,
            @Nullable String[] splitResDirs,
            @Nullable String[] overlayDirs,
            @Nullable String[] libDirs,
            int displayId,
            @Nullable Configuration overrideConfig,
            @NonNull CompatibilityInfo compatInfo,
            @Nullable ClassLoader classLoader,
            @Nullable List<ResourcesLoader> loaders) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES, "ResourcesManager#getResources");
            final ResourcesKey key = new ResourcesKey(
                    resDir,
                    splitResDirs,
                    overlayDirs,
                    libDirs,
                    displayId,
                    overrideConfig != null ? new Configuration(overrideConfig) : null, // Copy
                    compatInfo,
                    loaders == null ? null : loaders.toArray(new ResourcesLoader[0]));
            classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();

            if (activityToken != null) {
                rebaseKeyForActivity(activityToken, key);
            }

            // Preload the ApkAssets required by the key to prevent performing heavy I/O while the
            // ResourcesManager lock is held.
            final ApkAssetsSupplier assetsSupplier = createApkAssetsSupplierNotLocked(key);
            return createResources(activityToken, key, classLoader, assetsSupplier);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * Updates an Activity's Resources object with overrideConfig. The Resources object
     * that was previously returned by {@link #getResources(IBinder, String, String[], String[],
     * String[], int, Configuration, CompatibilityInfo, ClassLoader, List)} is still valid and will
     * have the updated configuration.
     *
     * @param activityToken The Activity token.
     * @param overrideConfig The configuration override to update.
     * @param displayId Id of the display where activity currently resides.
     * @param movedToDifferentDisplay Indicates if the activity was moved to different display.
     */
    public void updateResourcesForActivity(@NonNull IBinder activityToken,
            @Nullable Configuration overrideConfig, int displayId,
            boolean movedToDifferentDisplay) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES,
                    "ResourcesManager#updateResourcesForActivity");
            synchronized (this) {
                final ActivityResources activityResources =
                        getOrCreateActivityResourcesStructLocked(activityToken);

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
                    final WeakReference<Resources> weakResRef =
                            activityResources.activityResources.get(i);

                    final Resources resources = weakResRef.get();
                    if (resources == null) {
                        continue;
                    }

                    final ResourcesKey newKey = rebaseActivityOverrideConfig(resources, oldConfig,
                            overrideConfig, displayId);
                    if (newKey != null) {
                        final ResourcesImpl resourcesImpl =
                                findOrCreateResourcesImplForKeyLocked(newKey);
                        if (resourcesImpl != null && resourcesImpl != resources.getImpl()) {
                            // Set the ResourcesImpl, updating it for all users of this Resources
                            // object.
                            resources.setImpl(resourcesImpl);
                        }
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
    private ResourcesKey rebaseActivityOverrideConfig(@NonNull Resources resources,
            @NonNull Configuration oldOverrideConfig, @Nullable Configuration newOverrideConfig,
            int displayId) {
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

        final boolean hadOverrideConfig = !oldOverrideConfig.equals(Configuration.EMPTY);
        if (hadOverrideConfig && oldKey.hasOverrideConfiguration()) {
            // Generate a delta between the old base Activity override configuration and
            // the actual final override configuration that was used to figure out the
            // real delta this Resources object wanted.
            Configuration overrideOverrideConfig = Configuration.generateDelta(
                    oldOverrideConfig, oldKey.mOverrideConfiguration);
            rebasedOverrideConfig.updateFrom(overrideOverrideConfig);
        }

        // Create the new ResourcesKey with the rebased override config.
        final ResourcesKey newKey = new ResourcesKey(oldKey.mResDir,
                oldKey.mSplitResDirs, oldKey.mOverlayDirs, oldKey.mLibDirs,
                displayId, rebasedOverrideConfig, oldKey.mCompatInfo, oldKey.mLoaders);

        if (DEBUG) {
            Slog.d(TAG, "rebasing ref=" + resources + " from oldKey=" + oldKey
                    + " to newKey=" + newKey + ", displayId=" + displayId);
        }

        return newKey;
    }

    @TestApi
    public final boolean applyConfigurationToResources(@NonNull Configuration config,
            @Nullable CompatibilityInfo compat) {
        synchronized(this) {
            return applyConfigurationToResourcesLocked(config, compat, null /* adjustments */);
        }
    }

    public final boolean applyConfigurationToResourcesLocked(@NonNull Configuration config,
            @Nullable CompatibilityInfo compat) {
        return applyConfigurationToResourcesLocked(config, compat, null /* adjustments */);
    }

    /** Applies the global configuration to the managed resources. */
    public final boolean applyConfigurationToResourcesLocked(@NonNull Configuration config,
            @Nullable CompatibilityInfo compat, @Nullable DisplayAdjustments adjustments) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES,
                    "ResourcesManager#applyConfigurationToResourcesLocked");

            if (!mResConfiguration.isOtherSeqNewer(config) && compat == null) {
                if (DEBUG || DEBUG_CONFIGURATION) Slog.v(TAG, "Skipping new config: curSeq="
                        + mResConfiguration.seq + ", newSeq=" + config.seq);
                return false;
            }
            int changes = mResConfiguration.updateFrom(config);
            // Things might have changed in display manager, so clear the cached displays.
            mAdjustedDisplays.clear();

            DisplayMetrics defaultDisplayMetrics = getDisplayMetrics();

            if (compat != null && (mResCompatibilityInfo == null ||
                    !mResCompatibilityInfo.equals(compat))) {
                mResCompatibilityInfo = compat;
                changes |= ActivityInfo.CONFIG_SCREEN_LAYOUT
                        | ActivityInfo.CONFIG_SCREEN_SIZE
                        | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
            }

            if (adjustments != null) {
                // Currently the only case where the adjustment takes effect is to simulate placing
                // an app in a rotated display.
                adjustments.adjustGlobalAppMetrics(defaultDisplayMetrics);
            }
            Resources.updateSystemConfiguration(config, defaultDisplayMetrics, compat);

            ApplicationPackageManager.configurationChanged();
            //Slog.i(TAG, "Configuration changed in " + currentPackageName());

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

    private void applyConfigurationToResourcesLocked(@NonNull Configuration config,
            @Nullable CompatibilityInfo compat, Configuration tmpConfig,
            ResourcesKey key, ResourcesImpl resourcesImpl) {
        if (DEBUG || DEBUG_CONFIGURATION) {
            Slog.v(TAG, "Changing resources "
                    + resourcesImpl + " config to: " + config);
        }

        tmpConfig.setTo(config);

        // Apply the override configuration before setting the display adjustments to ensure that
        // the process config does not override activity display adjustments.
        final boolean hasOverrideConfiguration = key.hasOverrideConfiguration();
        if (hasOverrideConfiguration) {
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

        final int displayId = key.mDisplayId;
        if (displayId == Display.DEFAULT_DISPLAY) {
            daj.setConfiguration(tmpConfig);
        }
        DisplayMetrics dm = getDisplayMetrics(displayId, daj);
        if (displayId != Display.DEFAULT_DISPLAY) {
            applyNonDefaultDisplayMetricsToConfiguration(dm, tmpConfig);

            // Re-apply the override configuration to ensure that configuration contexts based on
            // a display context (ex: createDisplayContext().createConfigurationContext()) have the
            // correct override.
            if (hasOverrideConfiguration) {
                tmpConfig.updateFrom(key.mOverrideConfiguration);
            }
        }

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
        synchronized (this) {
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
                                key.mOverlayDirs,
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

    // TODO(adamlesinski): Make this accept more than just overlay directories.
    final void applyNewResourceDirsLocked(@NonNull final ApplicationInfo appInfo,
            @Nullable final String[] oldPaths) {
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
            String[] copiedResourceDirs = ArrayUtils.cloneOrNull(appInfo.resourceDirs);

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
                        || ArrayUtils.contains(oldPaths, key.mResDir)) {
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
                final WeakReference<Resources> ref = activityResources.activityResources.get(i);
                final Resources r = ref != null ? ref.get() : null;
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

    /**
     * Overrides the display adjustments of all resources which are associated with the given token.
     *
     * @param token The token that owns the resources.
     * @param override The operation to override the existing display adjustments. If it is null,
     *                 the override adjustments will be cleared.
     * @return {@code true} if the override takes effect.
     */
    public boolean overrideTokenDisplayAdjustments(IBinder token,
            @Nullable Consumer<DisplayAdjustments> override) {
        boolean handled = false;
        synchronized (this) {
            final ActivityResources tokenResources = mActivityResourceReferences.get(token);
            if (tokenResources == null) {
                return false;
            }
            final ArrayList<WeakReference<Resources>> resourcesRefs =
                    tokenResources.activityResources;
            for (int i = resourcesRefs.size() - 1; i >= 0; i--) {
                final Resources res = resourcesRefs.get(i).get();
                if (res != null) {
                    res.overrideDisplayAdjustments(override);
                    handled = true;
                }
            }
        }
        return handled;
    }

    private class UpdateHandler implements Resources.UpdateCallbacks {

        /**
         * Updates the list of {@link ResourcesLoader ResourcesLoader(s)} that the {@code resources}
         * instance uses.
         */
        @Override
        public void onLoadersChanged(@NonNull Resources resources,
                @NonNull List<ResourcesLoader> newLoader) {
            synchronized (ResourcesManager.this) {
                final ResourcesKey oldKey = findKeyForResourceImplLocked(resources.getImpl());
                if (oldKey == null) {
                    throw new IllegalArgumentException("Cannot modify resource loaders of"
                            + " ResourcesImpl not registered with ResourcesManager");
                }

                final ResourcesKey newKey = new ResourcesKey(
                        oldKey.mResDir,
                        oldKey.mSplitResDirs,
                        oldKey.mOverlayDirs,
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
        public void onLoaderUpdated(@NonNull ResourcesLoader loader) {
            synchronized (ResourcesManager.this) {
                final ArrayMap<ResourcesImpl, ResourcesKey> updatedResourceImplKeys =
                        new ArrayMap<>();

                for (int i = mResourceImpls.size() - 1; i >= 0; i--) {
                    final ResourcesKey key = mResourceImpls.keyAt(i);
                    final WeakReference<ResourcesImpl> impl = mResourceImpls.valueAt(i);
                    if (impl == null || impl.get() == null
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
}
