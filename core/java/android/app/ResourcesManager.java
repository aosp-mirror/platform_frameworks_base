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

import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ResourcesKey;
import android.hardware.display.DisplayManagerGlobal;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAdjustments;

import java.lang.ref.WeakReference;
import java.util.Locale;

/** @hide */
public class ResourcesManager {
    static final String TAG = "ResourcesManager";
    private static final boolean DEBUG = false;

    private static ResourcesManager sResourcesManager;
    private final ArrayMap<ResourcesKey, WeakReference<Resources> > mActiveResources =
            new ArrayMap<>();
    private final ArrayMap<Pair<Integer, DisplayAdjustments>, WeakReference<Display>> mDisplays =
            new ArrayMap<>();

    CompatibilityInfo mResCompatibilityInfo;

    Configuration mResConfiguration;

    public static ResourcesManager getInstance() {
        synchronized (ResourcesManager.class) {
            if (sResourcesManager == null) {
                sResourcesManager = new ResourcesManager();
            }
            return sResourcesManager;
        }
    }

    public Configuration getConfiguration() {
        return mResConfiguration;
    }

    DisplayMetrics getDisplayMetricsLocked() {
        return getDisplayMetricsLocked(Display.DEFAULT_DISPLAY);
    }

    DisplayMetrics getDisplayMetricsLocked(int displayId) {
        DisplayMetrics dm = new DisplayMetrics();
        final Display display =
                getAdjustedDisplay(displayId, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        if (display != null) {
            display.getMetrics(dm);
        } else {
            dm.setToDefaults();
        }
        return dm;
    }

    final void applyNonDefaultDisplayMetricsToConfigurationLocked(
            DisplayMetrics dm, Configuration config) {
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.densityDpi = dm.densityDpi;
        config.screenWidthDp = (int)(dm.widthPixels / dm.density);
        config.screenHeightDp = (int)(dm.heightPixels / dm.density);
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
        config.smallestScreenWidthDp = config.screenWidthDp; // assume screen does not rotate
        config.compatScreenWidthDp = config.screenWidthDp;
        config.compatScreenHeightDp = config.screenHeightDp;
        config.compatSmallestScreenWidthDp = config.smallestScreenWidthDp;
    }

    public boolean applyCompatConfiguration(int displayDensity,
            Configuration compatConfiguration) {
        if (mResCompatibilityInfo != null && !mResCompatibilityInfo.supportsScreen()) {
            mResCompatibilityInfo.applyToConfiguration(displayDensity, compatConfiguration);
            return true;
        }
        return false;
    }

    /**
     * Returns an adjusted {@link Display} object based on the inputs or null if display isn't
     * available.
     *
     * @param displayId display Id.
     * @param displayAdjustments display adjustments.
     */
    public Display getAdjustedDisplay(final int displayId, DisplayAdjustments displayAdjustments) {
        final DisplayAdjustments displayAdjustmentsCopy = (displayAdjustments != null)
                ? new DisplayAdjustments(displayAdjustments) : new DisplayAdjustments();
        final Pair<Integer, DisplayAdjustments> key =
                Pair.create(displayId, displayAdjustmentsCopy);
        synchronized (this) {
            WeakReference<Display> wd = mDisplays.get(key);
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
                mDisplays.put(key, new WeakReference<>(display));
            }
            return display;
        }
    }

    /**
     * Creates the top level Resources for applications with the given compatibility info.
     *
     * @param resDir the resource directory.
     * @param splitResDirs split resource directories.
     * @param overlayDirs the resource overlay directories.
     * @param libDirs the shared library resource dirs this app references.
     * @param displayId display Id.
     * @param overrideConfiguration override configurations.
     * @param compatInfo the compatibility info. Must not be null.
     */
    Resources getTopLevelResources(String resDir, String[] splitResDirs,
            String[] overlayDirs, String[] libDirs, int displayId,
            Configuration overrideConfiguration, CompatibilityInfo compatInfo) {
        final float scale = compatInfo.applicationScale;
        Configuration overrideConfigCopy = (overrideConfiguration != null)
                ? new Configuration(overrideConfiguration) : null;
        ResourcesKey key = new ResourcesKey(resDir, displayId, overrideConfigCopy, scale);
        Resources r;
        synchronized (this) {
            // Resources is app scale dependent.
            if (DEBUG) Slog.w(TAG, "getTopLevelResources: " + resDir + " / " + scale);

            WeakReference<Resources> wr = mActiveResources.get(key);
            r = wr != null ? wr.get() : null;
            //if (r != null) Log.i(TAG, "isUpToDate " + resDir + ": " + r.getAssets().isUpToDate());
            if (r != null && r.getAssets().isUpToDate()) {
                if (DEBUG) Slog.w(TAG, "Returning cached resources " + r + " " + resDir
                        + ": appScale=" + r.getCompatibilityInfo().applicationScale
                        + " key=" + key + " overrideConfig=" + overrideConfiguration);
                return r;
            }
        }

        //if (r != null) {
        //    Log.w(TAG, "Throwing away out-of-date resources!!!! "
        //            + r + " " + resDir);
        //}

        AssetManager assets = new AssetManager();
        // resDir can be null if the 'android' package is creating a new Resources object.
        // This is fine, since each AssetManager automatically loads the 'android' package
        // already.
        if (resDir != null) {
            if (assets.addAssetPath(resDir) == 0) {
                return null;
            }
        }

        if (splitResDirs != null) {
            for (String splitResDir : splitResDirs) {
                if (assets.addAssetPath(splitResDir) == 0) {
                    return null;
                }
            }
        }

        if (overlayDirs != null) {
            for (String idmapPath : overlayDirs) {
                assets.addOverlayPath(idmapPath);
            }
        }

        if (libDirs != null) {
            for (String libDir : libDirs) {
                if (libDir.endsWith(".apk")) {
                    // Avoid opening files we know do not have resources,
                    // like code-only .jar files.
                    if (assets.addAssetPath(libDir) == 0) {
                        Log.w(TAG, "Asset path '" + libDir +
                                "' does not exist or contains no resources.");
                    }
                }
            }
        }

        //Log.i(TAG, "Resource: key=" + key + ", display metrics=" + metrics);
        DisplayMetrics dm = getDisplayMetricsLocked(displayId);
        Configuration config;
        final boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
        final boolean hasOverrideConfig = key.hasOverrideConfiguration();
        if (!isDefaultDisplay || hasOverrideConfig) {
            config = new Configuration(getConfiguration());
            if (!isDefaultDisplay) {
                applyNonDefaultDisplayMetricsToConfigurationLocked(dm, config);
            }
            if (hasOverrideConfig) {
                config.updateFrom(key.mOverrideConfiguration);
                if (DEBUG) Slog.v(TAG, "Applied overrideConfig=" + key.mOverrideConfiguration);
            }
        } else {
            config = getConfiguration();
        }
        r = new Resources(assets, dm, config, compatInfo);
        if (DEBUG) Slog.i(TAG, "Created app resources " + resDir + " " + r + ": "
                + r.getConfiguration() + " appScale=" + r.getCompatibilityInfo().applicationScale);

        synchronized (this) {
            WeakReference<Resources> wr = mActiveResources.get(key);
            Resources existing = wr != null ? wr.get() : null;
            if (existing != null && existing.getAssets().isUpToDate()) {
                // Someone else already created the resources while we were
                // unlocked; go ahead and use theirs.
                r.getAssets().close();
                return existing;
            }

            // XXX need to remove entries when weak references go away
            mActiveResources.put(key, new WeakReference<>(r));
            if (DEBUG) Slog.v(TAG, "mActiveResources.size()=" + mActiveResources.size());
            return r;
        }
    }

    final boolean applyConfigurationToResourcesLocked(Configuration config,
            CompatibilityInfo compat) {
        if (mResConfiguration == null) {
            mResConfiguration = new Configuration();
        }
        if (!mResConfiguration.isOtherSeqNewer(config) && compat == null) {
            if (DEBUG || DEBUG_CONFIGURATION) Slog.v(TAG, "Skipping new config: curSeq="
                    + mResConfiguration.seq + ", newSeq=" + config.seq);
            return false;
        }
        int changes = mResConfiguration.updateFrom(config);
        // Things might have changed in display manager, so clear the cached displays.
        mDisplays.clear();
        DisplayMetrics defaultDisplayMetrics = getDisplayMetricsLocked();

        if (compat != null && (mResCompatibilityInfo == null ||
                !mResCompatibilityInfo.equals(compat))) {
            mResCompatibilityInfo = compat;
            changes |= ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_SCREEN_SIZE
                    | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        }

        // set it for java, this also affects newly created Resources
        if (config.locale != null) {
            Locale.setDefault(config.locale);
        }

        Resources.updateSystemConfiguration(config, defaultDisplayMetrics, compat);

        ApplicationPackageManager.configurationChanged();
        //Slog.i(TAG, "Configuration changed in " + currentPackageName());

        Configuration tmpConfig = null;

        for (int i = mActiveResources.size() - 1; i >= 0; i--) {
            ResourcesKey key = mActiveResources.keyAt(i);
            Resources r = mActiveResources.valueAt(i).get();
            if (r != null) {
                if (DEBUG || DEBUG_CONFIGURATION) Slog.v(TAG, "Changing resources "
                        + r + " config to: " + config);
                int displayId = key.mDisplayId;
                boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
                DisplayMetrics dm = defaultDisplayMetrics;
                final boolean hasOverrideConfiguration = key.hasOverrideConfiguration();
                if (!isDefaultDisplay || hasOverrideConfiguration) {
                    if (tmpConfig == null) {
                        tmpConfig = new Configuration();
                    }
                    tmpConfig.setTo(config);
                    if (!isDefaultDisplay) {
                        dm = getDisplayMetricsLocked(displayId);
                        applyNonDefaultDisplayMetricsToConfigurationLocked(dm, tmpConfig);
                    }
                    if (hasOverrideConfiguration) {
                        tmpConfig.updateFrom(key.mOverrideConfiguration);
                    }
                    r.updateConfiguration(tmpConfig, dm, compat);
                } else {
                    r.updateConfiguration(config, dm, compat);
                }
                //Slog.i(TAG, "Updated app resources " + v.getKey()
                //        + " " + r + ": " + r.getConfiguration());
            } else {
                //Slog.i(TAG, "Removing old resources " + v.getKey());
                mActiveResources.removeAt(i);
            }
        }

        return changes != 0;
    }

}
