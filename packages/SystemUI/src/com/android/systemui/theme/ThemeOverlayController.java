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

import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ACCENT_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SYSTEM_PALETTE;

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

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.SystemUI;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.util.settings.SecureSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashSet;
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
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    protected static final int NEUTRAL = 0;
    protected static final int ACCENT = 1;

    private final ThemeOverlayApplier mThemeManager;
    private final UserManager mUserManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mBgExecutor;
    private final SecureSettings mSecureSettings;
    private final Executor mMainExecutor;
    private final Handler mBgHandler;
    private final WallpaperManager mWallpaperManager;
    private final boolean mIsMonetEnabled;
    private final UserTracker mUserTracker;
    private DeviceProvisionedController mDeviceProvisionedController;
    private WallpaperColors mSystemColors;
    // If fabricated overlays were already created for the current theme.
    private boolean mNeedsOverlayCreation;
    // Dominant olor extracted from wallpaper, NOT the color used on the overlay
    protected int mMainWallpaperColor = Color.TRANSPARENT;
    // Accent color extracted from wallpaper, NOT the color used on the overlay
    protected int mWallpaperAccentColor = Color.TRANSPARENT;
    // Accent colors overlay
    private FabricatedOverlay mSecondaryOverlay;
    // Neutral system colors overlay
    private FabricatedOverlay mNeutralOverlay;
    // If wallpaper color event will be accepted and change the UI colors.
    private boolean mAcceptColorEvents = true;
    // Defers changing themes until Setup Wizard is done.
    private boolean mDeferredThemeEvaluation;

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
        if (!mAcceptColorEvents) {
            Log.i(TAG, "Wallpaper color event rejected: " + wallpaperColors);
            return;
        }
        if (wallpaperColors != null) {
            mAcceptColorEvents = false;
        }

        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            mSystemColors = wallpaperColors;
            if (DEBUG) {
                Log.d(TAG, "got new lock colors: " + wallpaperColors + " where: " + which);
            }
        }

        if (mDeviceProvisionedController != null
                && !mDeviceProvisionedController.isCurrentUserSetup()) {
            Log.i(TAG, "Wallpaper color event deferred until setup is finished: "
                    + wallpaperColors);
            mDeferredThemeEvaluation = true;
            return;
        }
        reevaluateSystemTheme(false /* forceReload */);
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())
                    || Intent.ACTION_MANAGED_PROFILE_ADDED.equals(intent.getAction())) {
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
            UserTracker userTracker, DumpManager dumpManager, FeatureFlags featureFlags) {
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
                        reevaluateSystemTheme(true /* forceReload */);
                    }
                },
                UserHandle.USER_ALL);
        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);

        // Upon boot, make sure we have the most up to date colors
        mBgExecutor.execute(() -> {
            WallpaperColors systemColor = mWallpaperManager.getWallpaperColors(
                    WallpaperManager.FLAG_SYSTEM);
            mMainExecutor.execute(() -> {
                mSystemColors = systemColor;
                reevaluateSystemTheme(false /* forceReload */);
            });
        });
        mWallpaperManager.addOnColorsChangedListener(mOnColorsChangedListener, null,
                UserHandle.USER_ALL);
    }

    private void reevaluateSystemTheme(boolean forceReload) {
        final WallpaperColors currentColors = mSystemColors;
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
        return wallpaperColors.getPrimaryColor().toArgb();
    }

    protected int getAccentColor(@NonNull WallpaperColors wallpaperColors) {
        Color accentCandidate = wallpaperColors.getSecondaryColor();
        if (accentCandidate == null) {
            accentCandidate = wallpaperColors.getTertiaryColor();
        }
        if (accentCandidate == null) {
            accentCandidate = wallpaperColors.getPrimaryColor();
        }
        return accentCandidate.toArgb();
    }

    /**
     * Given a color candidate, return an overlay definition.
     */
    protected @Nullable FabricatedOverlay getOverlay(int color, int type) {
        return null;
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
                int color = Integer.parseInt(systemPalette.getPackageName().toLowerCase(), 16);
                mNeutralOverlay = getOverlay(color, NEUTRAL);
                mNeedsOverlayCreation = true;
                categoryToPackage.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid color definition: " + systemPalette.getPackageName());
            }
        } else if (!mIsMonetEnabled && systemPalette != null) {
            try {
                // It's possible that we flipped the flag off and still have a @ColorInt in the
                // setting. We need to sanitize the input, otherwise the overlay transaction will
                // fail.
                Integer.parseInt(systemPalette.getPackageName().toLowerCase(), 16);
                categoryToPackage.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
            } catch (NumberFormatException e) {
                // This is a package name. All good, let's continue
            }
        }

        // Same for accent color.
        OverlayIdentifier accentPalette = categoryToPackage.get(OVERLAY_CATEGORY_ACCENT_COLOR);
        if (mIsMonetEnabled && accentPalette != null && accentPalette.getPackageName() != null) {
            try {
                int color = Integer.parseInt(accentPalette.getPackageName().toLowerCase(), 16);
                mSecondaryOverlay = getOverlay(color, ACCENT);
                mNeedsOverlayCreation = true;
                categoryToPackage.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid color definition: " + accentPalette.getPackageName());
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
        if (mNeedsOverlayCreation) {
            mNeedsOverlayCreation = false;
            mThemeManager.applyCurrentUserOverlays(categoryToPackage, new FabricatedOverlay[] {
                    mSecondaryOverlay, mNeutralOverlay
            }, currentUser, managedProfiles);
        } else {
            mThemeManager.applyCurrentUserOverlays(categoryToPackage, null, currentUser,
                    managedProfiles);
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mSystemColors=" + mSystemColors);
        pw.println("mMainWallpaperColor=" + Integer.toHexString(mMainWallpaperColor));
        pw.println("mWallpaperAccentColor=" + Integer.toHexString(mWallpaperAccentColor));
        pw.println("mSecondaryOverlay=" + mSecondaryOverlay);
        pw.println("mNeutralOverlay=" + mNeutralOverlay);
        pw.println("mIsMonetEnabled=" + mIsMonetEnabled);
        pw.println("mNeedsOverlayCreation=" + mNeedsOverlayCreation);
        pw.println("mAcceptColorEvents=" + mAcceptColorEvents);
        pw.println("mDeferredThemeEvaluation=" + mDeferredThemeEvaluation);
    }
}
