/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.theme;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;
import static com.android.systemui.theme.ThemeOverlayApplier.COLOR_SOURCE_HOME;
import static com.android.systemui.theme.ThemeOverlayApplier.COLOR_SOURCE_LOCK;
import static com.android.systemui.theme.ThemeOverlayApplier.COLOR_SOURCE_PRESET;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ACCENT_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SYSTEM_PALETTE;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_COLOR_BOTH;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_COLOR_INDEX;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_COLOR_SOURCE;
import static com.android.systemui.theme.ThemeOverlayApplier.TIMESTAMP_FIELD;

import android.annotation.Nullable;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.app.WallpaperManager.OnColorsChangedListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.SystemUI;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.monet.ColorScheme;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.util.settings.SecureSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Controls the application of theme overlays across the system for all users.
 * This service is responsible for:
 * - Observing changes to Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES and applying the
 * corresponding overlays across the system
 * - Observing user switches, applying the overlays for the current user to user 0 (for systemui)
 * - Observing work profile changes and applying overlays from the primary user to their
 * associated work profiles
 */
@SysUISingleton
public class ThemeOverlayController extends SystemUI implements Dumpable {
    protected static final String TAG = "ThemeOverlayController";
    private static final boolean DEBUG = true;

    protected static final int NEUTRAL = 0;
    protected static final int ACCENT = 1;

    private final ThemeOverlayApplier mThemeManager;
    private final UserManager mUserManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mBgExecutor;
    private SecureSettings mSecureSettings;
    private final Executor mMainExecutor;
    private final Handler mBgHandler;
    private final boolean mIsMonetEnabled;
    private UserTracker mUserTracker;
    private DeviceProvisionedController mDeviceProvisionedController;
    private WallpaperColors mCurrentColors;
    private WallpaperManager mWallpaperManager;
    private ColorScheme mColorScheme;
    // If fabricated overlays were already created for the current theme.
    private boolean mNeedsOverlayCreation;
    // Dominant color extracted from wallpaper, NOT the color used on the overlay
    protected int mMainWallpaperColor = Color.TRANSPARENT;
    // Accent color extracted from wallpaper, NOT the color used on the overlay
    protected int mWallpaperAccentColor = Color.TRANSPARENT;
    // Accent colors overlay
    private FabricatedOverlay mSecondaryOverlay;
    // Neutral system colors overlay
    private FabricatedOverlay mNeutralOverlay;
    // If wallpaper color event will be accepted and change the UI colors.
    private boolean mAcceptColorEvents = true;
    // If non-null, colors that were sent to the framework, and processing was deferred until
    // the next time the screen is off.
    private WallpaperColors mDeferredWallpaperColors;
    private int mDeferredWallpaperColorsFlags;
    private WakefulnessLifecycle mWakefulnessLifecycle;

    // Defers changing themes until Setup Wizard is done.
    private boolean mDeferredThemeEvaluation;
    // Determines if we should ignore THEME_CUSTOMIZATION_OVERLAY_PACKAGES setting changes.
    private boolean mSkipSettingChange;

    private final DeviceProvisionedListener mDeviceProvisionedListener =
            new DeviceProvisionedListener() {
                @Override
                public void onUserSetupChanged() {
                    if (!mDeviceProvisionedController.isCurrentUserSetup()) {
                        return;
                    }
                    if (!mDeferredThemeEvaluation) {
                        return;
                    }
                    Log.i(TAG, "Applying deferred theme");
                    mDeferredThemeEvaluation = false;
                    reevaluateSystemTheme(true /* forceReload */);
                }
            };

    private final OnColorsChangedListener mOnColorsChangedListener = (wallpaperColors, which) -> {
        if (!mAcceptColorEvents && mWakefulnessLifecycle.getWakefulness() != WAKEFULNESS_ASLEEP) {
            mDeferredWallpaperColors = wallpaperColors;
            mDeferredWallpaperColorsFlags = which;
            Log.i(TAG, "colors received; processing deferred until screen off: " + wallpaperColors);
            return;
        }

        if (wallpaperColors != null) {
            mAcceptColorEvents = false;
            // Any cache of colors deferred for process is now stale.
            mDeferredWallpaperColors = null;
            mDeferredWallpaperColorsFlags = 0;
        }

        handleWallpaperColors(wallpaperColors, which);
    };

    private int getLatestWallpaperType() {
        return mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                > mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
    }

    private void handleWallpaperColors(WallpaperColors wallpaperColors, int flags) {
        final boolean hadWallpaperColors = mCurrentColors != null;
        int latestWallpaperType = getLatestWallpaperType();
        if ((flags & latestWallpaperType) != 0) {
            mCurrentColors = wallpaperColors;
            if (DEBUG) Log.d(TAG, "got new colors: " + wallpaperColors + " where: " + flags);
        }

        if (mDeviceProvisionedController != null
                && !mDeviceProvisionedController.isCurrentUserSetup()) {
            if (hadWallpaperColors) {
                Log.i(TAG, "Wallpaper color event deferred until setup is finished: "
                        + wallpaperColors);
                mDeferredThemeEvaluation = true;
                return;
            } else if (mDeferredThemeEvaluation) {
                Log.i(TAG, "Wallpaper color event received, but we already were deferring eval: "
                        + wallpaperColors);
                return;
            } else {
                if (DEBUG) {
                    Log.i(TAG, "During user setup, but allowing first color event: had? "
                            + hadWallpaperColors + " has? " + (mCurrentColors != null));
                }
            }
        }
        // Check if we need to reset to default colors (if a color override was set that is sourced
        // from the wallpaper)
        int currentUser = mUserTracker.getUserId();
        String overlayPackageJson = mSecureSettings.getStringForUser(
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                currentUser);
        boolean isDestinationBoth = (flags == (WallpaperManager.FLAG_SYSTEM
                | WallpaperManager.FLAG_LOCK));
        try {
            JSONObject jsonObject = (overlayPackageJson == null) ? new JSONObject()
                    : new JSONObject(overlayPackageJson);
            if (!COLOR_SOURCE_PRESET.equals(jsonObject.optString(OVERLAY_COLOR_SOURCE))
                    && ((flags & latestWallpaperType) != 0)) {
                mSkipSettingChange = true;
                if (jsonObject.has(OVERLAY_CATEGORY_ACCENT_COLOR) || jsonObject.has(
                        OVERLAY_CATEGORY_SYSTEM_PALETTE)) {
                    jsonObject.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
                    jsonObject.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
                    jsonObject.remove(OVERLAY_COLOR_INDEX);
                }
                // Keep color_both value because users can change either or both home and
                // lock screen wallpapers.
                jsonObject.put(OVERLAY_COLOR_BOTH, isDestinationBoth ? "1" : "0");

                jsonObject.put(OVERLAY_COLOR_SOURCE,
                        (flags == WallpaperManager.FLAG_LOCK) ? COLOR_SOURCE_LOCK
                                : COLOR_SOURCE_HOME);
                jsonObject.put(TIMESTAMP_FIELD, System.currentTimeMillis());
                if (DEBUG) {
                    Log.d(TAG, "Updating theme setting from "
                            + overlayPackageJson + " to " + jsonObject.toString());
                }
                mSecureSettings.putString(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                        jsonObject.toString());
            }
        } catch (JSONException e) {
            Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
        }
        reevaluateSystemTheme(false /* forceReload */);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean newWorkProfile = Intent.ACTION_MANAGED_PROFILE_ADDED.equals(intent.getAction());
            boolean userStarted = Intent.ACTION_USER_SWITCHED.equals(intent.getAction());
            boolean isManagedProfile = mUserManager.isManagedProfile(
                    intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
            if (userStarted || newWorkProfile) {
                if (!mDeviceProvisionedController.isCurrentUserSetup() && isManagedProfile) {
                    Log.i(TAG, "User setup not finished when " + intent.getAction()
                            + " was received. Deferring... Managed profile? " + isManagedProfile);
                    return;
                }
                if (DEBUG) Log.d(TAG, "Updating overlays for user switch / profile added.");
                reevaluateSystemTheme(true /* forceReload */);
            } else if (Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                mAcceptColorEvents = true;
                Log.i(TAG, "Allowing color events again");
            }
        }
    };

    @Inject
    public ThemeOverlayController(Context context, BroadcastDispatcher broadcastDispatcher,
            @Background Handler bgHandler, @Main Executor mainExecutor,
            @Background Executor bgExecutor, ThemeOverlayApplier themeOverlayApplier,
            SecureSettings secureSettings, WallpaperManager wallpaperManager,
            UserManager userManager, DeviceProvisionedController deviceProvisionedController,
            UserTracker userTracker, DumpManager dumpManager, FeatureFlags featureFlags,
            WakefulnessLifecycle wakefulnessLifecycle) {
        super(context);

        mIsMonetEnabled = featureFlags.isMonetEnabled();
        mDeviceProvisionedController = deviceProvisionedController;
        mBroadcastDispatcher = broadcastDispatcher;
        mUserManager = userManager;
        mBgExecutor = bgExecutor;
        mMainExecutor = mainExecutor;
        mBgHandler = bgHandler;
        mThemeManager = themeOverlayApplier;
        mSecureSettings = secureSettings;
        mWallpaperManager = wallpaperManager;
        mUserTracker = userTracker;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        dumpManager.registerDumpable(TAG, this);
    }

    @Override
    public void start() {
        if (DEBUG) Log.d(TAG, "Start");
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_WALLPAPER_CHANGED);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter, mMainExecutor,
                UserHandle.ALL);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                false,
                new ContentObserver(mBgHandler) {
                    @Override
                    public void onChange(boolean selfChange, Collection<Uri> collection, int flags,
                            int userId) {
                        if (DEBUG) Log.d(TAG, "Overlay changed for user: " + userId);
                        if (mUserTracker.getUserId() != userId) {
                            return;
                        }
                        if (!mDeviceProvisionedController.isUserSetup(userId)) {
                            Log.i(TAG, "Theme application deferred when setting changed.");
                            mDeferredThemeEvaluation = true;
                            return;
                        }
                        if (mSkipSettingChange) {
                            if (DEBUG) Log.d(TAG, "Skipping setting change");
                            mSkipSettingChange = false;
                            return;
                        }
                        reevaluateSystemTheme(true /* forceReload */);
                    }
                },
                UserHandle.USER_ALL);

        if (!mIsMonetEnabled) {
            return;
        }

        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);

        // All wallpaper color and keyguard logic only applies when Monet is enabled.
        if (!mIsMonetEnabled) {
            return;
        }

        // Upon boot, make sure we have the most up to date colors
        Runnable updateColors = () -> {
            WallpaperColors systemColor = mWallpaperManager.getWallpaperColors(
                    getLatestWallpaperType());
            Runnable applyColors = () -> {
                if (DEBUG) Log.d(TAG, "Boot colors: " + systemColor);
                mCurrentColors = systemColor;
                reevaluateSystemTheme(false /* forceReload */);
            };
            if (mDeviceProvisionedController.isCurrentUserSetup()) {
                mMainExecutor.execute(applyColors);
            } else {
                applyColors.run();
            }
        };

        // Whenever we're going directly to setup wizard, we need to process colors synchronously,
        // otherwise we'll see some jank when the activity is recreated.
        if (!mDeviceProvisionedController.isCurrentUserSetup()) {
            updateColors.run();
        } else {
            mBgExecutor.execute(updateColors);
        }
        mWallpaperManager.addOnColorsChangedListener(mOnColorsChangedListener, null,
                UserHandle.USER_ALL);
        mWakefulnessLifecycle.addObserver(new WakefulnessLifecycle.Observer() {
            @Override
            public void onFinishedGoingToSleep() {
                if (mDeferredWallpaperColors != null) {
                    WallpaperColors colors = mDeferredWallpaperColors;
                    int flags = mDeferredWallpaperColorsFlags;

                    mDeferredWallpaperColors = null;
                    mDeferredWallpaperColorsFlags = 0;

                    handleWallpaperColors(colors, flags);
                }
            }
        });
    }

    private void reevaluateSystemTheme(boolean forceReload) {
        final WallpaperColors currentColors = mCurrentColors;
        final int mainColor;
        final int accentCandidate;
        if (currentColors == null) {
            mainColor = Color.TRANSPARENT;
            accentCandidate = Color.TRANSPARENT;
        } else {
            mainColor = getNeutralColor(currentColors);
            accentCandidate = getAccentColor(currentColors);
        }

        if (mMainWallpaperColor == mainColor && mWallpaperAccentColor == accentCandidate
                && !forceReload) {
            return;
        }

        mMainWallpaperColor = mainColor;
        mWallpaperAccentColor = accentCandidate;

        if (mIsMonetEnabled) {
            mSecondaryOverlay = getOverlay(mWallpaperAccentColor, ACCENT);
            mNeutralOverlay = getOverlay(mMainWallpaperColor, NEUTRAL);
            mNeedsOverlayCreation = true;
            if (DEBUG) {
                Log.d(TAG, "fetched overlays. accent: " + mSecondaryOverlay
                        + " neutral: " + mNeutralOverlay);
            }
        }

        updateThemeOverlays();
    }

    /**
     * Return the main theme color from a given {@link WallpaperColors} instance.
     */
    protected int getNeutralColor(@NonNull WallpaperColors wallpaperColors) {
        return ColorScheme.getSeedColor(wallpaperColors);
    }

    protected int getAccentColor(@NonNull WallpaperColors wallpaperColors) {
        return ColorScheme.getSeedColor(wallpaperColors);
    }

    /**
     * Given a color candidate, return an overlay definition.
     */
    protected @Nullable FabricatedOverlay getOverlay(int color, int type) {
        boolean nightMode = (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        mColorScheme = new ColorScheme(color, nightMode);
        List<Integer> colorShades = type == ACCENT
                ? mColorScheme.getAllAccentColors() : mColorScheme.getAllNeutralColors();
        String name = type == ACCENT ? "accent" : "neutral";
        int paletteSize = mColorScheme.getAccent1().size();
        FabricatedOverlay.Builder overlay =
                new FabricatedOverlay.Builder("com.android.systemui", name, "android");
        for (int i = 0; i < colorShades.size(); i++) {
            int luminosity = i % paletteSize;
            int paletteIndex = i / paletteSize + 1;
            String resourceName;
            switch (luminosity) {
                case 0:
                    resourceName = "android:color/system_" + name + paletteIndex + "_10";
                    break;
                case 1:
                    resourceName = "android:color/system_" + name + paletteIndex + "_50";
                    break;
                default:
                    int l = luminosity - 1;
                    resourceName = "android:color/system_" + name + paletteIndex + "_" + l + "00";
            }
            overlay.setResourceValue(resourceName, TypedValue.TYPE_INT_COLOR_ARGB8,
                    ColorUtils.setAlphaComponent(colorShades.get(i), 0xFF));
        }

        return overlay.build();
    }

    private void updateThemeOverlays() {
        final int currentUser = mUserTracker.getUserId();
        final String overlayPackageJson = mSecureSettings.getStringForUser(
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                currentUser);
        if (DEBUG) Log.d(TAG, "updateThemeOverlays. Setting: " + overlayPackageJson);
        final Map<String, OverlayIdentifier> categoryToPackage = new ArrayMap<>();
        if (!TextUtils.isEmpty(overlayPackageJson)) {
            try {
                JSONObject object = new JSONObject(overlayPackageJson);
                for (String category : ThemeOverlayApplier.THEME_CATEGORIES) {
                    if (object.has(category)) {
                        OverlayIdentifier identifier =
                                new OverlayIdentifier(object.getString(category));
                        categoryToPackage.put(category, identifier);
                    }
                }
            } catch (JSONException e) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
            }
        }

        // Let's generate system overlay if the style picker decided to override it.
        OverlayIdentifier systemPalette = categoryToPackage.get(OVERLAY_CATEGORY_SYSTEM_PALETTE);
        if (mIsMonetEnabled && systemPalette != null && systemPalette.getPackageName() != null) {
            try {
                String colorString =  systemPalette.getPackageName().toLowerCase();
                if (!colorString.startsWith("#")) {
                    colorString = "#" + colorString;
                }
                int color = Color.parseColor(colorString);
                mNeutralOverlay = getOverlay(color, NEUTRAL);
                mNeedsOverlayCreation = true;
                categoryToPackage.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
            } catch (Exception e) {
                // Color.parseColor doesn't catch any exceptions from the calls it makes
                Log.w(TAG, "Invalid color definition: " + systemPalette.getPackageName(), e);
            }
        } else if (!mIsMonetEnabled && systemPalette != null) {
            try {
                // It's possible that we flipped the flag off and still have a @ColorInt in the
                // setting. We need to sanitize the input, otherwise the overlay transaction will
                // fail.
                categoryToPackage.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
            } catch (NumberFormatException e) {
                // This is a package name. All good, let's continue
            }
        }

        // Same for accent color.
        OverlayIdentifier accentPalette = categoryToPackage.get(OVERLAY_CATEGORY_ACCENT_COLOR);
        if (mIsMonetEnabled && accentPalette != null && accentPalette.getPackageName() != null) {
            try {
                String colorString =  accentPalette.getPackageName().toLowerCase();
                if (!colorString.startsWith("#")) {
                    colorString = "#" + colorString;
                }
                int color = Color.parseColor(colorString);
                mSecondaryOverlay = getOverlay(color, ACCENT);
                mNeedsOverlayCreation = true;
                categoryToPackage.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
            } catch (Exception e) {
                // Color.parseColor doesn't catch any exceptions from the calls it makes
                Log.w(TAG, "Invalid color definition: " + accentPalette.getPackageName(), e);
            }
        } else if (!mIsMonetEnabled && accentPalette != null) {
            try {
                Integer.parseInt(accentPalette.getPackageName().toLowerCase(), 16);
                categoryToPackage.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
            } catch (NumberFormatException e) {
                // This is a package name. All good, let's continue
            }
        }

        // Compatibility with legacy themes, where full packages were defined, instead of just
        // colors.
        if (!categoryToPackage.containsKey(OVERLAY_CATEGORY_SYSTEM_PALETTE)
                && mNeutralOverlay != null) {
            categoryToPackage.put(OVERLAY_CATEGORY_SYSTEM_PALETTE,
                    mNeutralOverlay.getIdentifier());
        }
        if (!categoryToPackage.containsKey(OVERLAY_CATEGORY_ACCENT_COLOR)
                && mSecondaryOverlay != null) {
            categoryToPackage.put(OVERLAY_CATEGORY_ACCENT_COLOR, mSecondaryOverlay.getIdentifier());
        }

        Set<UserHandle> managedProfiles = new HashSet<>();
        for (UserInfo userInfo : mUserManager.getEnabledProfiles(currentUser)) {
            if (userInfo.isManagedProfile()) {
                managedProfiles.add(userInfo.getUserHandle());
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Applying overlays: " + categoryToPackage.keySet().stream()
                    .map(key -> key + " -> " + categoryToPackage.get(key)).collect(
                            Collectors.joining(", ")));
        }
        Runnable overlaysAppliedRunnable = this::onOverlaysApplied;
        if (mNeedsOverlayCreation) {
            mNeedsOverlayCreation = false;
            mThemeManager.applyCurrentUserOverlays(categoryToPackage, new FabricatedOverlay[] {
                    mSecondaryOverlay, mNeutralOverlay
            }, currentUser, managedProfiles, overlaysAppliedRunnable);
        } else {
            mThemeManager.applyCurrentUserOverlays(categoryToPackage, null, currentUser,
                    managedProfiles, overlaysAppliedRunnable);
        }
    }

    protected void onOverlaysApplied() {
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mSystemColors=" + mCurrentColors);
        pw.println("mMainWallpaperColor=" + Integer.toHexString(mMainWallpaperColor));
        pw.println("mWallpaperAccentColor=" + Integer.toHexString(mWallpaperAccentColor));
        pw.println("mSecondaryOverlay=" + mSecondaryOverlay);
        pw.println("mNeutralOverlay=" + mNeutralOverlay);
        pw.println("mIsMonetEnabled=" + mIsMonetEnabled);
        pw.println("mColorScheme=" + mColorScheme);
        pw.println("mNeedsOverlayCreation=" + mNeedsOverlayCreation);
        pw.println("mAcceptColorEvents=" + mAcceptColorEvents);
        pw.println("mDeferredThemeEvaluation=" + mDeferredThemeEvaluation);
    }
}
