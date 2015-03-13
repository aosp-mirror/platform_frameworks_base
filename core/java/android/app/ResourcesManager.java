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
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAdjustments;

import java.lang.ref.WeakReference;
import java.util.Locale;

/** @hide */
public class ResourcesManager {
    static final String TAG = "ResourcesManager";
    static final boolean DEBUG_CACHE = false;
    static final boolean DEBUG_STATS = true;

    private static ResourcesManager sResourcesManager;
    final ArrayMap<ResourcesKey, WeakReference<Resources> > mActiveResources
            = new ArrayMap<ResourcesKey, WeakReference<Resources> >();

    final ArrayMap<DisplayAdjustments, DisplayMetrics> mDefaultDisplayMetrics
            = new ArrayMap<DisplayAdjustments, DisplayMetrics>();

    CompatibilityInfo mResCompatibilityInfo;

    Configuration mResConfiguration;
    final Configuration mTmpConfig = new Configuration();

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

    public void flushDisplayMetricsLocked() {
        mDefaultDisplayMetrics.clear();
    }

    public DisplayMetrics getDisplayMetricsLocked(int displayId) {
        return getDisplayMetricsLocked(displayId, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    public DisplayMetrics getDisplayMetricsLocked(int displayId, DisplayAdjustments daj) {
        boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
        DisplayMetrics dm = isDefaultDisplay ? mDefaultDisplayMetrics.get(daj) : null;
        if (dm != null) {
            return dm;
        }
        dm = new DisplayMetrics();

        DisplayManagerGlobal displayManager = DisplayManagerGlobal.getInstance();
        if (displayManager == null) {
            // may be null early in system startup
            dm.setToDefaults();
            return dm;
        }

        if (isDefaultDisplay) {
            mDefaultDisplayMetrics.put(daj, dm);
        }

        Display d = displayManager.getCompatibleDisplay(displayId, daj);
        if (d != null) {
            d.getMetrics(dm);
        } else {
            // Display no longer exists
            // FIXME: This would not be a problem if we kept the Display object around
            // instead of using the raw display id everywhere.  The Display object caches
            // its information even after the display has been removed.
            dm.setToDefaults();
        }
        //Slog.i("foo", "New metrics: w=" + metrics.widthPixels + " h="
        //        + metrics.heightPixels + " den=" + metrics.density
        //        + " xdpi=" + metrics.xdpi + " ydpi=" + metrics.ydpi);
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
     * Creates the top level Resources for applications with the given compatibility info.
     *
     * @param resDir the resource directory.
     * @param overlayDirs the resource overlay directories.
     * @param libDirs the shared library resource dirs this app references.
     * @param compatInfo the compability info. Must not be null.
     * @param token the application token for determining stack bounds.
     */
    public Resources getTopLevelResources(String resDir, String[] splitResDirs,
            String[] overlayDirs, String[] libDirs, int displayId,
            Configuration overrideConfiguration, CompatibilityInfo compatInfo, IBinder token) {
        final float scale = compatInfo.applicationScale;
        ResourcesKey key = new ResourcesKey(resDir, displayId, overrideConfiguration, scale, token);
        Resources r;
        synchronized (this) {
            // Resources is app scale dependent.
            if (false) {
                Slog.w(TAG, "getTopLevelResources: " + resDir + " / " + scale);
            }
            WeakReference<Resources> wr = mActiveResources.get(key);
            r = wr != null ? wr.get() : null;
            //if (r != null) Slog.i(TAG, "isUpToDate " + resDir + ": " + r.getAssets().isUpToDate());
            if (r != null && r.getAssets().isUpToDate()) {
                if (false) {
                    Slog.w(TAG, "Returning cached resources " + r + " " + resDir
                            + ": appScale=" + r.getCompatibilityInfo().applicationScale);
                }
                return r;
            }
        }

        //if (r != null) {
        //    Slog.w(TAG, "Throwing away out-of-date resources!!!! "
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
                if (assets.addAssetPath(libDir) == 0) {
                    Slog.w(TAG, "Asset path '" + libDir +
                            "' does not exist or contains no resources.");
                }
            }
        }

        //Slog.i(TAG, "Resource: key=" + key + ", display metrics=" + metrics);
        DisplayMetrics dm = null;
        synchronized(this) {
            dm = getDisplayMetricsLocked(displayId);
        }
        Configuration config;
        boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
        final boolean hasOverrideConfig = key.hasOverrideConfiguration();
        if (!isDefaultDisplay || hasOverrideConfig) {
            config = new Configuration(getConfiguration());
            if (!isDefaultDisplay) {
                applyNonDefaultDisplayMetricsToConfigurationLocked(dm, config);
            }
            if (hasOverrideConfig) {
                config.updateFrom(key.mOverrideConfiguration);
            }
        } else {
            config = getConfiguration();
        }
        r = new Resources(assets, dm, config, compatInfo, token);
        if (false) {
            Slog.i(TAG, "Created app resources " + resDir + " " + r + ": "
                    + r.getConfiguration() + " appScale="
                    + r.getCompatibilityInfo().applicationScale);
        }

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
            mActiveResources.put(key, new WeakReference<Resources>(r));
            return r;
        }
    }

    public final boolean applyConfigurationToResourcesLocked(Configuration config,
            CompatibilityInfo compat) {
        if (mResConfiguration == null) {
            mResConfiguration = new Configuration();
        }
        if (!mResConfiguration.isOtherSeqNewer(config) && compat == null) {
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Skipping new config: curSeq="
                    + mResConfiguration.seq + ", newSeq=" + config.seq);
            return false;
        }
        int changes = mResConfiguration.updateFrom(config);
        flushDisplayMetricsLocked();
        DisplayMetrics defaultDisplayMetrics = getDisplayMetricsLocked(Display.DEFAULT_DISPLAY);

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

        for (int i=mActiveResources.size()-1; i>=0; i--) {
            ResourcesKey key = mActiveResources.keyAt(i);
            Resources r = mActiveResources.valueAt(i).get();
            if (r != null) {
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Changing resources "
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
