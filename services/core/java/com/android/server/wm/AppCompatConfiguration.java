/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Color;
import android.provider.DeviceConfig;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.utils.DimenPxIntSupplier;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Function;

/** Reads app compatibility configs from resources and controls their overrides at runtime. */
final class AppCompatConfiguration {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppCompatConfiguration" : TAG_ATM;

    // Whether camera compatibility treatment is enabled.
    // See DisplayRotationCompatPolicy for context.
    private static final String KEY_ENABLE_CAMERA_COMPAT_TREATMENT =
            "enable_compat_camera_treatment";

    private static final boolean DEFAULT_VALUE_ENABLE_CAMERA_COMPAT_TREATMENT = true;

    // Whether enabling rotation compat policy for immersive apps that prevents auto
    // rotation into non-optimal screen orientation while in fullscreen. This is needed
    // because immersive apps, such as games, are often not optimized for all
    // orientations and can have a poor UX when rotated. Additionally, some games rely
    // on sensors for the gameplay so  users can trigger such rotations accidentally
    // when auto rotation is on.
    private static final String KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY =
            "enable_display_rotation_immersive_app_compat_policy";

    private static final boolean DEFAULT_VALUE_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY =
            true;

    // Whether ignore orientation request is allowed
    private static final String KEY_ALLOW_IGNORE_ORIENTATION_REQUEST =
            "allow_ignore_orientation_request";

    private static final boolean DEFAULT_VALUE_ALLOW_IGNORE_ORIENTATION_REQUEST = true;

    // Whether sending compat fake focus is enabled for unfocused apps in splitscreen.
    // Some game engines wait to get focus before drawing the content of the app so
    // this needs  to be used otherwise the apps get blacked out when they are resumed
    // and do not have focus yet.
    private static final String KEY_ENABLE_COMPAT_FAKE_FOCUS = "enable_compat_fake_focus";

    private static final boolean DEFAULT_VALUE_ENABLE_COMPAT_FAKE_FOCUS = true;

    // Whether translucent activities policy is enabled
    private static final String KEY_ENABLE_LETTERBOX_TRANSLUCENT_ACTIVITY =
            "enable_letterbox_translucent_activity";

    private static final boolean DEFAULT_VALUE_ENABLE_LETTERBOX_TRANSLUCENT_ACTIVITY = true;

    // Whether per-app user aspect ratio override settings is enabled
    private static final String KEY_ENABLE_USER_ASPECT_RATIO_SETTINGS =
            "enable_app_compat_aspect_ratio_user_settings";

    // TODO(b/288142656): Enable user aspect ratio settings by default.
    private static final boolean DEFAULT_VALUE_ENABLE_USER_ASPECT_RATIO_SETTINGS = true;

    // Whether per-app fullscreen user aspect ratio override option is enabled
    private static final String KEY_ENABLE_USER_ASPECT_RATIO_FULLSCREEN =
            "enable_app_compat_user_aspect_ratio_fullscreen";
    private static final boolean DEFAULT_VALUE_ENABLE_USER_ASPECT_RATIO_FULLSCREEN = true;

    // Whether the letterbox wallpaper style is enabled by default
    private static final String KEY_ENABLE_LETTERBOX_BACKGROUND_WALLPAPER =
            "enable_letterbox_background_wallpaper";

    // TODO(b/290048978): Enable wallpaper as default letterbox background.
    private static final boolean DEFAULT_VALUE_ENABLE_LETTERBOX_BACKGROUND_WALLPAPER = false;

    /**
     * Override of aspect ratio for fixed orientation letterboxing that is set via ADB with
     * set-fixed-orientation-letterbox-aspect-ratio or via {@link
     * com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio} will be ignored
     * if it is <= this value.
     */
    static final float MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO = 1.0f;

    /** The default aspect ratio for a letterboxed app in multi-window mode. */
    static final float DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW = 1.01f;

    /** Letterboxed app window position multiplier indicating center position. */
    static final float LETTERBOX_POSITION_MULTIPLIER_CENTER = 0.5f;

    /** Enum for Letterbox background type. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({LETTERBOX_BACKGROUND_OVERRIDE_UNSET,
            LETTERBOX_BACKGROUND_SOLID_COLOR,
            LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND,
            LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING,
            LETTERBOX_BACKGROUND_WALLPAPER})
    @interface LetterboxBackgroundType {};

    /** No letterbox background style set. Using the one defined by DeviceConfig. */
    static final int LETTERBOX_BACKGROUND_OVERRIDE_UNSET = -1;

    /** Solid background using color specified in R.color.config_letterboxBackgroundColor. */
    static final int LETTERBOX_BACKGROUND_SOLID_COLOR = 0;

    /** Color specified in R.attr.colorBackground for the letterboxed application. */
    static final int LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND = 1;

    /** Color specified in R.attr.colorBackgroundFloating for the letterboxed application. */
    static final int LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING = 2;

    /** Using wallpaper as a background which can be blurred or dimmed with dark scrim. */
    static final int LETTERBOX_BACKGROUND_WALLPAPER = 3;

    /**
     * Enum for Letterbox horizontal reachability position types.
     *
     * <p>Order from left to right is important since it's used in {@link
     * #movePositionForReachabilityToNextRightStop} and {@link
     * #movePositionForReachabilityToNextLeftStop}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
            LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
            LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT})
    @interface LetterboxHorizontalReachabilityPosition {};

    /** Letterboxed app window is aligned to the left side. */
    static final int LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT = 0;

    /** Letterboxed app window is positioned in the horizontal center. */
    static final int LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER = 1;

    /** Letterboxed app window is aligned to the right side. */
    static final int LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT = 2;

    /**
     * Enum for Letterbox vertical reachability position types.
     *
     * <p>Order from top to bottom is important since it's used in {@link
     * #movePositionForReachabilityToNextBottomStop} and {@link
     * #movePositionForReachabilityToNextTopStop}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
            LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
            LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM})
    @interface LetterboxVerticalReachabilityPosition {};

    /** Letterboxed app window is aligned to the left side. */
    static final int LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP = 0;

    /** Letterboxed app window is positioned in the vertical center. */
    static final int LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER = 1;

    /** Letterboxed app window is aligned to the right side. */
    static final int LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM = 2;

    final Context mContext;

    // Responsible for the persistence of letterbox[Horizontal|Vertical]PositionMultiplier
    @NonNull
    private final AppCompatConfigurationPersister mAppCompatConfigurationPersister;

    // Aspect ratio of letterbox for fixed orientation, values <=
    // MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO will be ignored.
    private float mFixedOrientationLetterboxAspectRatio;

    // Default min aspect ratio for unresizable apps that are eligible for the size compat mode.
    private float mDefaultMinAspectRatioForUnresizableApps;

    // Corners radius for activities presented in the letterbox mode, values < 0 will be ignored.
    private int mLetterboxActivityCornersRadius;

    // Color for {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} letterbox background type.
    @Nullable private Color mLetterboxBackgroundColorOverride;

    // Color resource id for {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} letterbox background type.
    @Nullable private Integer mLetterboxBackgroundColorResourceIdOverride;

    @LetterboxBackgroundType
    private final int mLetterboxBackgroundType;

    // Blur radius for LETTERBOX_BACKGROUND_WALLPAPER option from getLetterboxBackgroundType().
    // Values <= 0 are ignored and 0 is used instead.
    private int mLetterboxBackgroundWallpaperBlurRadiusPx;

    // Alpha of a black scrim shown over wallpaper letterbox background when
    // LETTERBOX_BACKGROUND_WALLPAPER option is returned from getLetterboxBackgroundType().
    // Values < 0 or >= 1 are ignored and 0.0 (transparent) is used instead.
    private float mLetterboxBackgroundWallpaperDarkScrimAlpha;

    // Horizontal position of a center of the letterboxed app window. 0 corresponds to the left
    // side of the screen and 1.0 to the right side.
    private float mLetterboxHorizontalPositionMultiplier;

    // Vertical position of a center of the letterboxed app window. 0 corresponds to the top
    // side of the screen and 1.0 to the bottom side.
    private float mLetterboxVerticalPositionMultiplier;

    // Horizontal position of a center of the letterboxed app window when the device is half-folded.
    // 0 corresponds to the left side of the screen and 1.0 to the right side.
    private float mLetterboxBookModePositionMultiplier;

    // Vertical position of a center of the letterboxed app window when the device is half-folded.
    // 0 corresponds to the top side of the screen and 1.0 to the bottom side.
    private float mLetterboxTabletopModePositionMultiplier;

    // Default horizontal position the letterboxed app window when horizontal reachability is
    // enabled and an app is fullscreen in landscape device orientation.
    // It is used as a starting point for mLetterboxPositionForHorizontalReachability.
    @LetterboxHorizontalReachabilityPosition
    private int mDefaultPositionForHorizontalReachability;

    // Default vertical position the letterboxed app window when vertical reachability is enabled
    // and an app is fullscreen in portrait device orientation.
    // It is used as a starting point for mLetterboxPositionForVerticalReachability.
    @LetterboxVerticalReachabilityPosition
    private int mDefaultPositionForVerticalReachability;

    // Whether horizontal reachability repositioning is allowed for letterboxed fullscreen apps in
    // landscape device orientation.
    private boolean mIsHorizontalReachabilityEnabled;

    // Whether vertical reachability repositioning is allowed for letterboxed fullscreen apps in
    // portrait device orientation.
    private boolean mIsVerticalReachabilityEnabled;

    // Whether book mode automatic horizontal reachability positioning is allowed for letterboxed
    // fullscreen apps in landscape device orientation.
    private boolean mIsAutomaticReachabilityInBookModeEnabled;

    // Whether education is allowed for letterboxed fullscreen apps.
    private boolean mIsEducationEnabled;

    // Whether using split screen aspect ratio as a default aspect ratio for unresizable apps.
    private boolean mIsSplitScreenAspectRatioForUnresizableAppsEnabled;

    // Whether using display aspect ratio as a default aspect ratio for all letterboxed apps.
    // mIsSplitScreenAspectRatioForUnresizableAppsEnabled and
    // config_letterboxDefaultMinAspectRatioForUnresizableApps take priority over this for
    // unresizable apps
    private boolean mIsDisplayAspectRatioEnabledForFixedOrientationLetterbox;

    // Supplier for the value in pixel to consider when detecting vertical thin letterboxing
    private final DimenPxIntSupplier mThinLetterboxWidthPxSupplier;

    // Supplier for the value in pixel to consider when detecting horizontal thin letterboxing
    private final DimenPxIntSupplier mThinLetterboxHeightPxSupplier;

    // Allows to enable letterboxing strategy for translucent activities ignoring flags.
    private boolean mTranslucentLetterboxingOverrideEnabled;

    // Allows to enable user aspect ratio settings ignoring flags.
    private boolean mUserAppAspectRatioSettingsOverrideEnabled;

    // Allows to enable fullscreen option in user aspect ratio settings ignoring flags.
    private boolean mUserAppAspectRatioFullscreenOverrideEnabled;

    // The override for letterbox background type in case it's different from
    // LETTERBOX_BACKGROUND_OVERRIDE_UNSET
    @LetterboxBackgroundType
    private int mLetterboxBackgroundTypeOverride = LETTERBOX_BACKGROUND_OVERRIDE_UNSET;

    // Whether we should use split screen aspect ratio for the activity when camera compat treatment
    // is enabled and activity is connected to the camera in fullscreen.
    private final boolean mIsCameraCompatSplitScreenAspectRatioEnabled;

    // Whether activity "refresh" in camera compatibility treatment is enabled.
    // See RefreshCallbackItem for context.
    private boolean mIsCameraCompatTreatmentRefreshEnabled = true;

    // Whether activity "refresh" in camera compatibility treatment should happen using the
    // "stopped -> resumed" cycle rather than "paused -> resumed" cycle. Using "stop -> resumed"
    // cycle by default due to higher success rate confirmed with app compatibility testing.
    // See RefreshCallbackItem for context.
    private boolean mIsCameraCompatRefreshCycleThroughStopEnabled = true;

    // Whether should ignore app requested orientation in response to an app
    // calling Activity#setRequestedOrientation. See
    // LetterboxUiController#shouldIgnoreRequestedOrientation for details.
    private final boolean mIsPolicyForIgnoringRequestedOrientationEnabled;

    // Flags dynamically updated with {@link android.provider.DeviceConfig}.
    @NonNull private final SynchedDeviceConfig mDeviceConfig;

    AppCompatConfiguration(@NonNull final Context systemUiContext) {
        this(systemUiContext, new AppCompatConfigurationPersister(
                () -> readLetterboxHorizontalReachabilityPositionFromConfig(
                        systemUiContext, /* forBookMode */ false),
                () -> readLetterboxVerticalReachabilityPositionFromConfig(
                        systemUiContext, /* forTabletopMode */ false),
                () -> readLetterboxHorizontalReachabilityPositionFromConfig(
                        systemUiContext, /* forBookMode */ true),
                () -> readLetterboxVerticalReachabilityPositionFromConfig(
                        systemUiContext, /* forTabletopMode */ true)));
    }

    @VisibleForTesting
    AppCompatConfiguration(@NonNull final Context systemUiContext,
            @NonNull final AppCompatConfigurationPersister appCompatConfigurationPersister) {
        mContext = systemUiContext;

        mFixedOrientationLetterboxAspectRatio = mContext.getResources().getFloat(
                R.dimen.config_fixedOrientationLetterboxAspectRatio);
        mLetterboxBackgroundType = readLetterboxBackgroundTypeFromConfig(mContext);
        mLetterboxActivityCornersRadius = mContext.getResources().getInteger(
                R.integer.config_letterboxActivityCornersRadius);
        mLetterboxBackgroundWallpaperBlurRadiusPx = mContext.getResources().getDimensionPixelSize(
                R.dimen.config_letterboxBackgroundWallpaperBlurRadius);
        mLetterboxBackgroundWallpaperDarkScrimAlpha = mContext.getResources().getFloat(
                R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha);
        setLetterboxHorizontalPositionMultiplier(mContext.getResources().getFloat(
                R.dimen.config_letterboxHorizontalPositionMultiplier));
        setLetterboxVerticalPositionMultiplier(mContext.getResources().getFloat(
                R.dimen.config_letterboxVerticalPositionMultiplier));
        setLetterboxBookModePositionMultiplier(mContext.getResources().getFloat(
                R.dimen.config_letterboxBookModePositionMultiplier));
        setLetterboxTabletopModePositionMultiplier(mContext.getResources()
                .getFloat(R.dimen.config_letterboxTabletopModePositionMultiplier));
        mIsHorizontalReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsHorizontalReachabilityEnabled);
        mIsVerticalReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsVerticalReachabilityEnabled);
        mIsAutomaticReachabilityInBookModeEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsAutomaticReachabilityInBookModeEnabled);
        mDefaultPositionForHorizontalReachability =
                readLetterboxHorizontalReachabilityPositionFromConfig(mContext, false);
        mDefaultPositionForVerticalReachability =
                readLetterboxVerticalReachabilityPositionFromConfig(mContext, false);
        mIsEducationEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsEducationEnabled);
        setDefaultMinAspectRatioForUnresizableApps(mContext.getResources().getFloat(
                R.dimen.config_letterboxDefaultMinAspectRatioForUnresizableApps));
        mIsSplitScreenAspectRatioForUnresizableAppsEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsSplitScreenAspectRatioForUnresizableAppsEnabled);
        mIsDisplayAspectRatioEnabledForFixedOrientationLetterbox = mContext.getResources()
                .getBoolean(R.bool
                        .config_letterboxIsDisplayAspectRatioForFixedOrientationLetterboxEnabled);
        mIsCameraCompatSplitScreenAspectRatioEnabled = mContext.getResources().getBoolean(
                R.bool.config_isWindowManagerCameraCompatSplitScreenAspectRatioEnabled);
        mIsPolicyForIgnoringRequestedOrientationEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsPolicyForIgnoringRequestedOrientationEnabled);

        mThinLetterboxWidthPxSupplier = new DimenPxIntSupplier(mContext,
                R.dimen.config_letterboxThinLetterboxWidthDp);
        mThinLetterboxHeightPxSupplier = new DimenPxIntSupplier(mContext,
                R.dimen.config_letterboxThinLetterboxHeightDp);

        mAppCompatConfigurationPersister = appCompatConfigurationPersister;
        mAppCompatConfigurationPersister.start();

        mDeviceConfig = SynchedDeviceConfig.builder(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                        systemUiContext.getMainExecutor())
                .addDeviceConfigEntry(KEY_ENABLE_CAMERA_COMPAT_TREATMENT,
                        DEFAULT_VALUE_ENABLE_CAMERA_COMPAT_TREATMENT,
                        mContext.getResources().getBoolean(
                                R.bool.config_isWindowManagerCameraCompatTreatmentEnabled))
                .addDeviceConfigEntry(KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY,
                        DEFAULT_VALUE_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY,
                        mContext.getResources().getBoolean(R.bool
                                .config_letterboxIsDisplayRotationImmersiveAppCompatPolicyEnabled))
                .addDeviceConfigEntry(KEY_ALLOW_IGNORE_ORIENTATION_REQUEST,
                        DEFAULT_VALUE_ALLOW_IGNORE_ORIENTATION_REQUEST, /* enabled */ true)
                .addDeviceConfigEntry(KEY_ENABLE_COMPAT_FAKE_FOCUS,
                        DEFAULT_VALUE_ENABLE_COMPAT_FAKE_FOCUS,
                        mContext.getResources().getBoolean(R.bool.config_isCompatFakeFocusEnabled))
                .addDeviceConfigEntry(KEY_ENABLE_LETTERBOX_TRANSLUCENT_ACTIVITY,
                        DEFAULT_VALUE_ENABLE_LETTERBOX_TRANSLUCENT_ACTIVITY,
                        mContext.getResources().getBoolean(
                                R.bool.config_letterboxIsEnabledForTranslucentActivities))
                .addDeviceConfigEntry(KEY_ENABLE_USER_ASPECT_RATIO_SETTINGS,
                        DEFAULT_VALUE_ENABLE_USER_ASPECT_RATIO_SETTINGS,
                        mContext.getResources().getBoolean(
                                R.bool.config_appCompatUserAppAspectRatioSettingsIsEnabled))
                .addDeviceConfigEntry(KEY_ENABLE_LETTERBOX_BACKGROUND_WALLPAPER,
                        DEFAULT_VALUE_ENABLE_LETTERBOX_BACKGROUND_WALLPAPER, /* enabled */ true)
                .addDeviceConfigEntry(KEY_ENABLE_USER_ASPECT_RATIO_FULLSCREEN,
                        DEFAULT_VALUE_ENABLE_USER_ASPECT_RATIO_FULLSCREEN,
                        mContext.getResources().getBoolean(
                                R.bool.config_appCompatUserAppAspectRatioFullscreenIsEnabled))
                .build();
    }

    /**
     * Whether enabling ignoreOrientationRequest is allowed on the device. This value is controlled
     * via {@link android.provider.DeviceConfig}.
     */
    boolean isIgnoreOrientationRequestAllowed() {
        return mDeviceConfig.getFlagValue(KEY_ALLOW_IGNORE_ORIENTATION_REQUEST);
    }

    /**
     * Overrides the aspect ratio of letterbox for fixed orientation. If given value is <= {@link
     * #MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO}, both it and a value of {@link
     * com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio} will be ignored and
     * the framework implementation will be used to determine the aspect ratio.
     */
    void setFixedOrientationLetterboxAspectRatio(float aspectRatio) {
        mFixedOrientationLetterboxAspectRatio = aspectRatio;
    }

    /**
     * Resets the aspect ratio of letterbox for fixed orientation to {@link
     * com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio}.
     */
    void resetFixedOrientationLetterboxAspectRatio() {
        mFixedOrientationLetterboxAspectRatio = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio);
    }

    /**
     * Gets the aspect ratio of letterbox for fixed orientation.
     */
    float getFixedOrientationLetterboxAspectRatio() {
        return mFixedOrientationLetterboxAspectRatio;
    }

    /**
     * Resets the min aspect ratio for unresizable apps that are eligible for size compat mode.
     */
    void resetDefaultMinAspectRatioForUnresizableApps() {
        setDefaultMinAspectRatioForUnresizableApps(mContext.getResources().getFloat(
                R.dimen.config_letterboxDefaultMinAspectRatioForUnresizableApps));
    }

    /**
     * Gets the min aspect ratio for unresizable apps that are eligible for size compat mode.
     */
    float getDefaultMinAspectRatioForUnresizableApps() {
        return mDefaultMinAspectRatioForUnresizableApps;
    }

    /**
     * Overrides the min aspect ratio for unresizable apps that are eligible for size compat mode.
     */
    void setDefaultMinAspectRatioForUnresizableApps(float aspectRatio) {
        mDefaultMinAspectRatioForUnresizableApps = aspectRatio;
    }

    /**
     * Overrides corners radius for activities presented in the letterbox mode. If given value < 0,
     * both it and a value of {@link
     * com.android.internal.R.integer.config_letterboxActivityCornersRadius} will be ignored and
     * corners of the activity won't be rounded.
     */
    void setLetterboxActivityCornersRadius(int cornersRadius) {
        mLetterboxActivityCornersRadius = cornersRadius;
    }

    /**
     * Resets corners radius for activities presented in the letterbox mode to {@link
     * com.android.internal.R.integer.config_letterboxActivityCornersRadius}.
     */
    void resetLetterboxActivityCornersRadius() {
        mLetterboxActivityCornersRadius = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_letterboxActivityCornersRadius);
    }

    /**
     * Whether corners of letterboxed activities are rounded.
     */
    boolean isLetterboxActivityCornersRounded() {
        return getLetterboxActivityCornersRadius() != 0;
    }

    /**
     * Gets corners radius for activities presented in the letterbox mode.
     */
    int getLetterboxActivityCornersRadius() {
        return mLetterboxActivityCornersRadius;
    }

    /**
     * Gets color of letterbox background which is used when {@link
     * #getLetterboxBackgroundType()} is {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} or as
     * fallback for other background types.
     */
    Color getLetterboxBackgroundColor() {
        if (mLetterboxBackgroundColorOverride != null) {
            return mLetterboxBackgroundColorOverride;
        }
        int colorId = mLetterboxBackgroundColorResourceIdOverride != null
                ? mLetterboxBackgroundColorResourceIdOverride
                : R.color.config_letterboxBackgroundColor;
        // Query color dynamically because material colors extracted from wallpaper are updated
        // when wallpaper is changed.
        return Color.valueOf(mContext.getResources().getColor(colorId));
    }


    /**
     * Sets color of letterbox background which is used when {@link
     * #getLetterboxBackgroundType()} is {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} or as
     * fallback for other background types.
     */
    void setLetterboxBackgroundColor(Color color) {
        mLetterboxBackgroundColorOverride = color;
    }

    /**
     * Sets color ID of letterbox background which is used when {@link
     * #getLetterboxBackgroundType()} is {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} or as
     * fallback for other background types.
     */
    void setLetterboxBackgroundColorResourceId(int colorId) {
        mLetterboxBackgroundColorResourceIdOverride = colorId;
    }

    /**
     * Resets color of letterbox background to {@link
     * com.android.internal.R.color.config_letterboxBackgroundColor}.
     */
    void resetLetterboxBackgroundColor() {
        mLetterboxBackgroundColorOverride = null;
        mLetterboxBackgroundColorResourceIdOverride = null;
    }

    /**
     * Gets {@link LetterboxBackgroundType} specified via ADB command or the default one.
     */
    @LetterboxBackgroundType
    int getLetterboxBackgroundType() {
        return mLetterboxBackgroundTypeOverride != LETTERBOX_BACKGROUND_OVERRIDE_UNSET
                ? mLetterboxBackgroundTypeOverride
                : getDefaultLetterboxBackgroundType();
    }

    /** Overrides the letterbox background type. */
    void setLetterboxBackgroundTypeOverride(@LetterboxBackgroundType int backgroundType) {
        mLetterboxBackgroundTypeOverride = backgroundType;
    }

    /**
     * Resets letterbox background type value depending on the
     * {@link #KEY_ENABLE_LETTERBOX_BACKGROUND_WALLPAPER} built time and runtime flags.
     *
     * <p>If enabled, the letterbox background type value is set toZ
     * {@link #LETTERBOX_BACKGROUND_WALLPAPER}. When disabled the letterbox background type value
     * comes from {@link R.integer.config_letterboxBackgroundType}.
     */
    void resetLetterboxBackgroundType() {
        mLetterboxBackgroundTypeOverride = LETTERBOX_BACKGROUND_OVERRIDE_UNSET;
    }

    // Returns KEY_ENABLE_LETTERBOX_BACKGROUND_WALLPAPER if the DeviceConfig flag is enabled
    // or the value in com.android.internal.R.integer.config_letterboxBackgroundType if the flag
    // is disabled.
    @LetterboxBackgroundType
    private int getDefaultLetterboxBackgroundType() {
        return mDeviceConfig.getFlagValue(KEY_ENABLE_LETTERBOX_BACKGROUND_WALLPAPER)
                ? LETTERBOX_BACKGROUND_WALLPAPER : mLetterboxBackgroundType;
    }

    /** Returns a string representing the given {@link LetterboxBackgroundType}. */
    static String letterboxBackgroundTypeToString(
            @LetterboxBackgroundType int backgroundType) {
        switch (backgroundType) {
            case LETTERBOX_BACKGROUND_SOLID_COLOR:
                return "LETTERBOX_BACKGROUND_SOLID_COLOR";
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND:
                return "LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND";
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING:
                return "LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING";
            case LETTERBOX_BACKGROUND_WALLPAPER:
                return "LETTERBOX_BACKGROUND_WALLPAPER";
            default:
                return "unknown=" + backgroundType;
        }
    }

    @LetterboxBackgroundType
    private static int readLetterboxBackgroundTypeFromConfig(Context context) {
        int backgroundType = context.getResources().getInteger(
                com.android.internal.R.integer.config_letterboxBackgroundType);
        return backgroundType == LETTERBOX_BACKGROUND_SOLID_COLOR
                    || backgroundType == LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND
                    || backgroundType == LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING
                    || backgroundType == LETTERBOX_BACKGROUND_WALLPAPER
                    ? backgroundType : LETTERBOX_BACKGROUND_SOLID_COLOR;
    }

    /**
     * Overrides alpha of a black scrim shown over wallpaper for {@link
     * #LETTERBOX_BACKGROUND_WALLPAPER} option returned from {@link #getLetterboxBackgroundType()}.
     *
     * <p>If given value is < 0 or >= 1, both it and a value of {@link
     * com.android.internal.R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha} are ignored
     * and 0.0 (transparent) is instead.
     */
    void setLetterboxBackgroundWallpaperDarkScrimAlpha(float alpha) {
        mLetterboxBackgroundWallpaperDarkScrimAlpha = alpha;
    }

    /**
     * Resets alpha of a black scrim shown over wallpaper letterbox background to {@link
     * com.android.internal.R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha}.
     */
    void resetLetterboxBackgroundWallpaperDarkScrimAlpha() {
        mLetterboxBackgroundWallpaperDarkScrimAlpha = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha);
    }

    /**
     * Gets alpha of a black scrim shown over wallpaper letterbox background.
     */
    float getLetterboxBackgroundWallpaperDarkScrimAlpha() {
        return mLetterboxBackgroundWallpaperDarkScrimAlpha;
    }

    /**
     * Overrides blur radius for {@link #LETTERBOX_BACKGROUND_WALLPAPER} option from {@link
     * #getLetterboxBackgroundType()}.
     *
     * <p> If given value <= 0, both it and a value of {@link
     * com.android.internal.R.dimen.config_letterboxBackgroundWallpaperBlurRadius} are ignored
     * and 0 is used instead.
     */
    void setLetterboxBackgroundWallpaperBlurRadiusPx(int radius) {
        mLetterboxBackgroundWallpaperBlurRadiusPx = radius;
    }

    /**
     * Resets blur raidus for {@link #LETTERBOX_BACKGROUND_WALLPAPER} option returned by {@link
     * #getLetterboxBackgroundType()} to {@link
     * com.android.internal.R.dimen.config_letterboxBackgroundWallpaperBlurRadius}.
     */
    void resetLetterboxBackgroundWallpaperBlurRadiusPx() {
        mLetterboxBackgroundWallpaperBlurRadiusPx = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.config_letterboxBackgroundWallpaperBlurRadius);
    }

    /**
     * Gets blur radius for {@link #LETTERBOX_BACKGROUND_WALLPAPER} option returned by {@link
     * #getLetterboxBackgroundType()}.
     */
    int getLetterboxBackgroundWallpaperBlurRadiusPx() {
        return mLetterboxBackgroundWallpaperBlurRadiusPx;
    }

    /**
     * Gets horizontal position of a center of the letterboxed app window specified
     * in {@link com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier}
     * or via an ADB command. 0 corresponds to the left side of the screen and 1 to the
     * right side.
     */
    float getLetterboxHorizontalPositionMultiplier(boolean isInBookMode) {
        return isInBookMode ? mLetterboxBookModePositionMultiplier
                : mLetterboxHorizontalPositionMultiplier;
    }

    /**
     * Gets vertical position of a center of the letterboxed app window specified
     * in {@link com.android.internal.R.dimen.config_letterboxVerticalPositionMultiplier}
     * or via an ADB command. 0 corresponds to the top side of the screen and 1 to the
     * bottom side.
     */
    float getLetterboxVerticalPositionMultiplier(boolean isInTabletopMode) {
        return isInTabletopMode ? mLetterboxTabletopModePositionMultiplier
                : mLetterboxVerticalPositionMultiplier;
    }

    /**
     * Overrides horizontal position of a center of the letterboxed app window.
     *
     * @throws IllegalArgumentException If given value < 0 or > 1.
     */
    void setLetterboxHorizontalPositionMultiplier(float multiplier) {
        mLetterboxHorizontalPositionMultiplier = assertValidMultiplier(multiplier,
                "mLetterboxHorizontalPositionMultiplier");
    }

    /**
     * Overrides vertical position of a center of the letterboxed app window.
     *
     * @throws IllegalArgumentException If given value < 0 or > 1.
     */
    void setLetterboxVerticalPositionMultiplier(float multiplier) {
        mLetterboxVerticalPositionMultiplier = assertValidMultiplier(multiplier,
                "mLetterboxVerticalPositionMultiplier");
    }

    /**
     * Resets horizontal position of a center of the letterboxed app window to {@link
     * com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier}.
     */
    void resetLetterboxHorizontalPositionMultiplier() {
        mLetterboxHorizontalPositionMultiplier = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier);
    }

    /**
     * Resets vertical position of a center of the letterboxed app window to {@link
     * com.android.internal.R.dimen.config_letterboxVerticalPositionMultiplier}.
     */
    void resetLetterboxVerticalPositionMultiplier() {
        mLetterboxVerticalPositionMultiplier = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_letterboxVerticalPositionMultiplier);
    }

    /**
     * Sets tabletop mode position multiplier.
     *
     * @throws IllegalArgumentException If given value < 0 or > 1.
     */
    @VisibleForTesting
    void setLetterboxTabletopModePositionMultiplier(float multiplier) {
        mLetterboxTabletopModePositionMultiplier = assertValidMultiplier(multiplier,
                "mLetterboxTabletopModePositionMultiplier");
    }

    /**
     * Sets tabletop mode position multiplier.
     *
     * @throws IllegalArgumentException If given value < 0 or > 1.
     */
    @VisibleForTesting
    void setLetterboxBookModePositionMultiplier(float multiplier) {
        mLetterboxBookModePositionMultiplier = assertValidMultiplier(multiplier,
                "mLetterboxBookModePositionMultiplier");
    }

    /**
     * Whether horizontal reachability repositioning is allowed for letterboxed fullscreen apps in
     * landscape device orientation.
     */
    boolean getIsHorizontalReachabilityEnabled() {
        return mIsHorizontalReachabilityEnabled;
    }

    /**
     * Whether vertical reachability repositioning is allowed for letterboxed fullscreen apps in
     * portrait device orientation.
     */
    boolean getIsVerticalReachabilityEnabled() {
        return mIsVerticalReachabilityEnabled;
    }

    /**
     * Whether automatic horizontal reachability repositioning in book mode is allowed for
     * letterboxed fullscreen apps in landscape device orientation.
     */
    boolean getIsAutomaticReachabilityInBookModeEnabled() {
        return mIsAutomaticReachabilityInBookModeEnabled;
    }

    /**
     * Overrides whether horizontal reachability repositioning is allowed for letterboxed fullscreen
     * apps in landscape device orientation.
     */
    void setIsHorizontalReachabilityEnabled(boolean enabled) {
        mIsHorizontalReachabilityEnabled = enabled;
    }

    /**
     * Overrides whether vertical reachability repositioning is allowed for letterboxed fullscreen
     * apps in portrait device orientation.
     */
    void setIsVerticalReachabilityEnabled(boolean enabled) {
        mIsVerticalReachabilityEnabled = enabled;
    }

    /**
     * Overrides whether automatic horizontal reachability repositioning in book mode is allowed for
     * letterboxed fullscreen apps in landscape device orientation.
     */
    void setIsAutomaticReachabilityInBookModeEnabled(boolean enabled) {
        mIsAutomaticReachabilityInBookModeEnabled = enabled;
    }

    /**
     * Resets whether horizontal reachability repositioning is allowed for letterboxed fullscreen
     * apps in landscape device orientation to
     * {@link R.bool.config_letterboxIsHorizontalReachabilityEnabled}.
     */
    void resetIsHorizontalReachabilityEnabled() {
        mIsHorizontalReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsHorizontalReachabilityEnabled);
    }

    /**
     * Resets whether vertical reachability repositioning is allowed for letterboxed fullscreen apps
     * in portrait device orientation to
     * {@link R.bool.config_letterboxIsVerticalReachabilityEnabled}.
     */
    void resetIsVerticalReachabilityEnabled() {
        mIsVerticalReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsVerticalReachabilityEnabled);
    }

    /**
     * Resets whether automatic horizontal reachability repositioning in book mode is
     * allowed for letterboxed fullscreen apps in landscape device orientation to
     * {@link R.bool.config_letterboxIsAutomaticReachabilityInBookModeEnabled}.
     */
    void resetEnabledAutomaticReachabilityInBookMode() {
        mIsAutomaticReachabilityInBookModeEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsAutomaticReachabilityInBookModeEnabled);
    }

    /**
     * Gets default horizontal position of the letterboxed app window when horizontal reachability
     * is enabled.
     *
     * <p> Specified in {@link R.integer.config_letterboxDefaultPositionForHorizontalReachability}
     *  or via an ADB command.
     */
    @LetterboxHorizontalReachabilityPosition
    int getDefaultPositionForHorizontalReachability() {
        return mDefaultPositionForHorizontalReachability;
    }

    /**
     * Gets default vertical position of the letterboxed app window when vertical reachability is
     * enabled.
     *
     * <p> Specified in {@link R.integer.config_letterboxDefaultPositionForVerticalReachability} or
     *  via an ADB command.
     */
    @LetterboxVerticalReachabilityPosition
    int getDefaultPositionForVerticalReachability() {
        return mDefaultPositionForVerticalReachability;
    }

    /**
     * Overrides default horizontal position of the letterboxed app window when horizontal
     * reachability is enabled.
     */
    void setDefaultPositionForHorizontalReachability(
            @LetterboxHorizontalReachabilityPosition int position) {
        mDefaultPositionForHorizontalReachability = position;
    }

    /**
     * Overrides default vertical position of the letterboxed app window when vertical
     * reachability is enabled.
     */
    void setDefaultPositionForVerticalReachability(
            @LetterboxVerticalReachabilityPosition int position) {
        mDefaultPositionForVerticalReachability = position;
    }

    /**
     * Resets default horizontal position of the letterboxed app window when horizontal reachability
     * is enabled to {@link R.integer.config_letterboxDefaultPositionForHorizontalReachability}.
     */
    void resetDefaultPositionForHorizontalReachability() {
        mDefaultPositionForHorizontalReachability =
                readLetterboxHorizontalReachabilityPositionFromConfig(mContext,
                        false /* forBookMode */);
    }

    /**
     * Resets default vertical position of the letterboxed app window when vertical reachability
     * is enabled to {@link R.integer.config_letterboxDefaultPositionForVerticalReachability}.
     */
    void resetDefaultPositionForVerticalReachability() {
        mDefaultPositionForVerticalReachability =
                readLetterboxVerticalReachabilityPositionFromConfig(mContext,
                        false /* forTabletopMode */);
    }

    /**
     * Overrides persistent horizontal position of the letterboxed app window when horizontal
     * reachability is enabled.
     */
    void setPersistentLetterboxPositionForHorizontalReachability(boolean forBookMode,
            @LetterboxHorizontalReachabilityPosition int position) {
        mAppCompatConfigurationPersister.setLetterboxPositionForHorizontalReachability(
                forBookMode, position);
    }

    /**
     * Overrides persistent vertical position of the letterboxed app window when vertical
     * reachability is enabled.
     */
    void setPersistentLetterboxPositionForVerticalReachability(boolean forTabletopMode,
            @LetterboxVerticalReachabilityPosition int position) {
        mAppCompatConfigurationPersister.setLetterboxPositionForVerticalReachability(
                forTabletopMode, position);
    }

    /**
     * Resets persistent horizontal position of the letterboxed app window when horizontal
     * reachability
     * is enabled to default position.
     */
    void resetPersistentLetterboxPositionForHorizontalReachability() {
        mAppCompatConfigurationPersister.setLetterboxPositionForHorizontalReachability(
                false /* forBookMode */,
                readLetterboxHorizontalReachabilityPositionFromConfig(mContext,
                        false /* forBookMode */));
        mAppCompatConfigurationPersister.setLetterboxPositionForHorizontalReachability(
                true /* forBookMode */,
                readLetterboxHorizontalReachabilityPositionFromConfig(mContext,
                        true /* forBookMode */));
    }

    /**
     * Resets persistent vertical position of the letterboxed app window when vertical reachability
     * is
     * enabled to default position.
     */
    void resetPersistentLetterboxPositionForVerticalReachability() {
        mAppCompatConfigurationPersister.setLetterboxPositionForVerticalReachability(
                false /* forTabletopMode */,
                readLetterboxVerticalReachabilityPositionFromConfig(mContext,
                        false /* forTabletopMode */));
        mAppCompatConfigurationPersister.setLetterboxPositionForVerticalReachability(
                true /* forTabletopMode */,
                readLetterboxVerticalReachabilityPositionFromConfig(mContext,
                        true /* forTabletopMode */));
    }

    @LetterboxHorizontalReachabilityPosition
    private static int readLetterboxHorizontalReachabilityPositionFromConfig(Context context,
            boolean forBookMode) {
        int position = context.getResources().getInteger(
                forBookMode
                    ? R.integer.config_letterboxDefaultPositionForBookModeReachability
                    : R.integer.config_letterboxDefaultPositionForHorizontalReachability);
        return position == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT
                || position == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                || position == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT
                    ? position : LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
    }

    @LetterboxVerticalReachabilityPosition
    private static int readLetterboxVerticalReachabilityPositionFromConfig(Context context,
            boolean forTabletopMode) {
        int position = context.getResources().getInteger(
                forTabletopMode
                    ? R.integer.config_letterboxDefaultPositionForTabletopModeReachability
                    : R.integer.config_letterboxDefaultPositionForVerticalReachability);
        return position == LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP
                || position == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                || position == LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM
                    ? position : LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
    }

    /**
     * Gets horizontal position of a center of the letterboxed app window when reachability
     * is enabled specified. 0 corresponds to the left side of the screen and 1 to the right side.
     *
     * <p>The position multiplier is changed after each double tap in the letterbox area.
     */
    float getHorizontalMultiplierForReachability(boolean isDeviceInBookMode) {
        final int letterboxPositionForHorizontalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        isDeviceInBookMode);
        switch (letterboxPositionForHorizontalReachability) {
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT:
                return 0.0f;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER:
                return 0.5f;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT:
                return 1.0f;
            default:
                throw new AssertionError(
                        "Unexpected letterbox position type: "
                                + letterboxPositionForHorizontalReachability);
        }
    }

    /**
     * Gets vertical position of a center of the letterboxed app window when reachability
     * is enabled specified. 0 corresponds to the top side of the screen and 1 to the bottom side.
     *
     * <p>The position multiplier is changed after each double tap in the letterbox area.
     */
    float getVerticalMultiplierForReachability(boolean isDeviceInTabletopMode) {
        final int letterboxPositionForVerticalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForVerticalReachability(
                        isDeviceInTabletopMode);
        switch (letterboxPositionForVerticalReachability) {
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP:
                return 0.0f;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER:
                return 0.5f;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM:
                return 1.0f;
            default:
                throw new AssertionError(
                        "Unexpected letterbox position type: "
                                + letterboxPositionForVerticalReachability);
        }
    }

    /**
     * Gets the horizontal position of the letterboxed app window when horizontal reachability is
     * enabled.
     */
    @LetterboxHorizontalReachabilityPosition
    int getLetterboxPositionForHorizontalReachability(boolean isInFullScreenBookMode) {
        return mAppCompatConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                isInFullScreenBookMode);
    }

    /**
     * Gets the vertical position of the letterboxed app window when vertical reachability is
     * enabled.
     */
    @LetterboxVerticalReachabilityPosition
    int getLetterboxPositionForVerticalReachability(boolean isInFullScreenTabletopMode) {
        return mAppCompatConfigurationPersister.getLetterboxPositionForVerticalReachability(
                isInFullScreenTabletopMode);
    }

    /** Returns a string representing the given {@link LetterboxHorizontalReachabilityPosition}. */
    static String letterboxHorizontalReachabilityPositionToString(
            @LetterboxHorizontalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT:
                return "LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT";
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER:
                return "LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER";
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT:
                return "LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT";
            default:
                throw new AssertionError(
                    "Unexpected letterbox position type: " + position);
        }
    }

    /** Returns a string representing the given {@link LetterboxVerticalReachabilityPosition}. */
    static String letterboxVerticalReachabilityPositionToString(
            @LetterboxVerticalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP:
                return "LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP";
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER:
                return "LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER";
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM:
                return "LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM";
            default:
                throw new AssertionError(
                        "Unexpected letterbox position type: " + position);
        }
    }

    /**
     * Changes letterbox position for horizontal reachability to the next available one on the
     * right side.
     */
    void movePositionForHorizontalReachabilityToNextRightStop(boolean isDeviceInBookMode) {
        updatePositionForHorizontalReachability(isDeviceInBookMode, prev -> Math.min(
                prev + (isDeviceInBookMode ? 2 : 1), // Move 2 stops in book mode to avoid center.
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT));
    }

    /**
     * Changes letterbox position for horizontal reachability to the next available one on the left
     * side.
     */
    void movePositionForHorizontalReachabilityToNextLeftStop(boolean isDeviceInBookMode) {
        updatePositionForHorizontalReachability(isDeviceInBookMode, prev -> Math.max(
                prev - (isDeviceInBookMode ? 2 : 1), 0)); // Move 2 stops in book mode to avoid
                                                          // center.
    }

    /**
     * Changes letterbox position for vertical reachability to the next available one on the bottom
     * side.
     */
    void movePositionForVerticalReachabilityToNextBottomStop(boolean isDeviceInTabletopMode) {
        updatePositionForVerticalReachability(isDeviceInTabletopMode, prev -> Math.min(
                prev + (isDeviceInTabletopMode ? 2 : 1), // Move 2 stops in tabletop mode to avoid
                                                         // center.
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM));
    }

    /**
     * Changes letterbox position for vertical reachability to the next available one on the top
     * side.
     */
    void movePositionForVerticalReachabilityToNextTopStop(boolean isDeviceInTabletopMode) {
        updatePositionForVerticalReachability(isDeviceInTabletopMode, prev -> Math.max(
                prev - (isDeviceInTabletopMode ? 2 : 1), 0)); // Move 2 stops in tabletop mode to
                                                              // avoid center.
    }

    /**
     * Whether education is allowed for letterboxed fullscreen apps.
     */
    boolean getIsEducationEnabled() {
        return mIsEducationEnabled;
    }

    /**
     * Overrides whether education is allowed for letterboxed fullscreen apps.
     */
    void setIsEducationEnabled(boolean enabled) {
        mIsEducationEnabled = enabled;
    }

    /**
     * Resets whether education is allowed for letterboxed fullscreen apps to
     * {@link R.bool.config_letterboxIsEducationEnabled}.
     */
    void resetIsEducationEnabled() {
        mIsEducationEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsEducationEnabled);
    }

    /**
     * Whether using split screen aspect ratio as a default aspect ratio for unresizable apps.
     */
    boolean getIsSplitScreenAspectRatioForUnresizableAppsEnabled() {
        return mIsSplitScreenAspectRatioForUnresizableAppsEnabled;
    }

    /**
     * Whether using display aspect ratio as a default aspect ratio for all letterboxed apps.
     */
    boolean getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox() {
        return mIsDisplayAspectRatioEnabledForFixedOrientationLetterbox;
    }

    /**
     * @return Width in pixel about the padding to use to understand if the letterbox for an
     *         activity is thin. If the available space has width W and the app has width w, this
     *         is the maximum value for (W - w) / 2 to be considered for a thin letterboxed app.
     */
    int getThinLetterboxWidthPx() {
        return mThinLetterboxWidthPxSupplier.getAsInt();
    }

    /**
     * @return Height in pixel about the padding to use to understand if a letterbox is thin.
     *         If the available space has height H and the app has height h, this is the maximum
     *         value for (H - h) / 2 to be considered for a thin letterboxed app.
     */
    int getThinLetterboxHeightPx() {
        return mThinLetterboxHeightPxSupplier.getAsInt();
    }

    /**
     * Overrides whether using split screen aspect ratio as a default aspect ratio for unresizable
     * apps.
     */
    void setIsSplitScreenAspectRatioForUnresizableAppsEnabled(boolean enabled) {
        mIsSplitScreenAspectRatioForUnresizableAppsEnabled = enabled;
    }

    /**
     * Overrides whether using display aspect ratio as a default aspect ratio for all letterboxed
     * apps.
     */
    void setIsDisplayAspectRatioEnabledForFixedOrientationLetterbox(boolean enabled) {
        mIsDisplayAspectRatioEnabledForFixedOrientationLetterbox = enabled;
    }

    /**
     * Resets whether using split screen aspect ratio as a default aspect ratio for unresizable
     * apps {@link R.bool.config_letterboxIsSplitScreenAspectRatioForUnresizableAppsEnabled}.
     */
    void resetIsSplitScreenAspectRatioForUnresizableAppsEnabled() {
        mIsSplitScreenAspectRatioForUnresizableAppsEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsSplitScreenAspectRatioForUnresizableAppsEnabled);
    }

    /**
     * Resets whether using display aspect ratio as a default aspect ratio for all letterboxed
     * apps {@link R.bool.config_letterboxIsDisplayAspectRatioForFixedOrientationLetterboxEnabled}.
     */
    void resetIsDisplayAspectRatioEnabledForFixedOrientationLetterbox() {
        mIsDisplayAspectRatioEnabledForFixedOrientationLetterbox = mContext.getResources()
                .getBoolean(R.bool
                        .config_letterboxIsDisplayAspectRatioForFixedOrientationLetterboxEnabled);
    }

    boolean isTranslucentLetterboxingEnabled() {
        return mTranslucentLetterboxingOverrideEnabled
                || mDeviceConfig.getFlagValue(KEY_ENABLE_LETTERBOX_TRANSLUCENT_ACTIVITY);
    }

    /**
     * Sets whether we use the constraints override strategy for letterboxing when dealing
     * with translucent activities.
     */
    void setTranslucentLetterboxingOverrideEnabled(
            boolean translucentLetterboxingOverrideEnabled) {
        mTranslucentLetterboxingOverrideEnabled = translucentLetterboxingOverrideEnabled;
    }

    /**
     * Resets whether we use the constraints override strategy for letterboxing when dealing
     * with translucent activities, which means mDeviceConfig.getFlagValue(
     * KEY_ENABLE_LETTERBOX_TRANSLUCENT_ACTIVITY) will be used.
     */
    void resetTranslucentLetterboxingEnabled() {
        setTranslucentLetterboxingOverrideEnabled(false);
    }

    /** Calculates a new letterboxPositionForHorizontalReachability value and updates the store */
    private void updatePositionForHorizontalReachability(boolean isDeviceInBookMode,
            Function<Integer, Integer> newHorizonalPositionFun) {
        final int letterboxPositionForHorizontalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        isDeviceInBookMode);
        final int nextHorizontalPosition = newHorizonalPositionFun.apply(
                letterboxPositionForHorizontalReachability);
        mAppCompatConfigurationPersister.setLetterboxPositionForHorizontalReachability(
                isDeviceInBookMode, nextHorizontalPosition);
    }

    /** Calculates a new letterboxPositionForVerticalReachability value and updates the store */
    private void updatePositionForVerticalReachability(boolean isDeviceInTabletopMode,
            Function<Integer, Integer> newVerticalPositionFun) {
        final int letterboxPositionForVerticalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForVerticalReachability(
                        isDeviceInTabletopMode);
        final int nextVerticalPosition = newVerticalPositionFun.apply(
                letterboxPositionForVerticalReachability);
        mAppCompatConfigurationPersister.setLetterboxPositionForVerticalReachability(
                isDeviceInTabletopMode, nextVerticalPosition);
    }

    /** Whether fake sending focus is enabled for unfocused apps in splitscreen */
    boolean isCompatFakeFocusEnabled() {
        return mDeviceConfig.getFlagValue(KEY_ENABLE_COMPAT_FAKE_FOCUS);
    }

    /**
     * Whether should ignore app requested orientation in response to an app calling
     * {@link android.app.Activity#setRequestedOrientation}. See {@link
     * LetterboxUiController#shouldIgnoreRequestedOrientation} for details.
     */
    boolean isPolicyForIgnoringRequestedOrientationEnabled() {
        return mIsPolicyForIgnoringRequestedOrientationEnabled;
    }

    /**
     * Whether we should use split screen aspect ratio for the activity when camera compat treatment
     * is enabled and activity is connected to the camera in fullscreen.
     */
    boolean isCameraCompatSplitScreenAspectRatioEnabled() {
        return mIsCameraCompatSplitScreenAspectRatioEnabled;
    }

    /**
     * @return Whether camera compatibility treatment is currently enabled.
     */
    boolean isCameraCompatTreatmentEnabled() {
        return mDeviceConfig.getFlagValue(KEY_ENABLE_CAMERA_COMPAT_TREATMENT);
    }

    /**
     * @return Whether camera compatibility treatment is enabled at build time. This is used when
     * we need to safely initialize a component before the {@link DeviceConfig} flag value is
     * available.
     */
    boolean isCameraCompatTreatmentEnabledAtBuildTime() {
        return mDeviceConfig.isBuildTimeFlagEnabled(KEY_ENABLE_CAMERA_COMPAT_TREATMENT);
    }

    /** Whether camera compatibility refresh is enabled. */
    boolean isCameraCompatRefreshEnabled() {
        return mIsCameraCompatTreatmentRefreshEnabled;
    }

    /** Overrides whether camera compatibility treatment is enabled. */
    void setCameraCompatRefreshEnabled(boolean enabled) {
        mIsCameraCompatTreatmentRefreshEnabled = enabled;
    }

    /**
     * Resets whether camera compatibility treatment is enabled to {@code true}.
     */
    void resetCameraCompatRefreshEnabled() {
        mIsCameraCompatTreatmentRefreshEnabled = true;
    }

    /**
     * Whether activity "refresh" in camera compatibility treatment should happen using the
     * "stopped -> resumed" cycle rather than "paused -> resumed" cycle.
     */
    boolean isCameraCompatRefreshCycleThroughStopEnabled() {
        return mIsCameraCompatRefreshCycleThroughStopEnabled;
    }

    /**
     * Overrides whether activity "refresh" in camera compatibility treatment should happen using
     * "stopped -> resumed" cycle rather than "paused -> resumed" cycle.
     */
    void setCameraCompatRefreshCycleThroughStopEnabled(boolean enabled) {
        mIsCameraCompatRefreshCycleThroughStopEnabled = enabled;
    }

    /**
     * Resets  whether activity "refresh" in camera compatibility treatment should happen using
     * "stopped -> resumed" cycle rather than "paused -> resumed" cycle to {@code true}.
     */
    void resetCameraCompatRefreshCycleThroughStopEnabled() {
        mIsCameraCompatRefreshCycleThroughStopEnabled = true;
    }

    /**
     * Checks whether rotation compat policy for immersive apps that prevents auto rotation
     * into non-optimal screen orientation while in fullscreen is enabled at build time. This is
     * used when we need to safely initialize a component before the {@link DeviceConfig} flag
     * value is available.
     *
     * <p>This is needed because immersive apps, such as games, are often not optimized for all
     * orientations and can have a poor UX when rotated. Additionally, some games rely on sensors
     * for the gameplay so users can trigger such rotations accidentally when auto rotation is on.
     */
    boolean isDisplayRotationImmersiveAppCompatPolicyEnabledAtBuildTime() {
        return mDeviceConfig.isBuildTimeFlagEnabled(
                KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY);
    }

    /**
     * Checks whether rotation compat policy for immersive apps that prevents auto rotation
     * into non-optimal screen orientation while in fullscreen is currently enabled.
     *
     * <p>This is needed because immersive apps, such as games, are often not optimized for all
     * orientations and can have a poor UX when rotated. Additionally, some games rely on sensors
     * for the gameplay so users can trigger such rotations accidentally when auto rotation is on.
     */
    boolean isDisplayRotationImmersiveAppCompatPolicyEnabled() {
        return mDeviceConfig.getFlagValue(KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY);
    }

    /**
     * Whether per-app user aspect ratio override settings is enabled
     */
    boolean isUserAppAspectRatioSettingsEnabled() {
        return mUserAppAspectRatioSettingsOverrideEnabled
                || mDeviceConfig.getFlagValue(KEY_ENABLE_USER_ASPECT_RATIO_SETTINGS);
    }

    void setUserAppAspectRatioSettingsOverrideEnabled(boolean enabled) {
        mUserAppAspectRatioSettingsOverrideEnabled = enabled;
    }

    /**
     * Resets whether per-app user aspect ratio override settings is enabled
     * {@code mDeviceConfig.getFlagValue(KEY_ENABLE_USER_ASPECT_RATIO_SETTINGS)}.
     */
    void resetUserAppAspectRatioSettingsEnabled() {
        setUserAppAspectRatioSettingsOverrideEnabled(false);
    }

    /**
     * Whether fullscreen option in per-app user aspect ratio settings is enabled
     */
    boolean isUserAppAspectRatioFullscreenEnabled() {
        return isUserAppAspectRatioSettingsEnabled()
                && (mUserAppAspectRatioFullscreenOverrideEnabled
                    || mDeviceConfig.getFlagValue(KEY_ENABLE_USER_ASPECT_RATIO_FULLSCREEN));
    }

    void setUserAppAspectRatioFullscreenOverrideEnabled(boolean enabled) {
        mUserAppAspectRatioFullscreenOverrideEnabled = enabled;
    }

    void resetUserAppAspectRatioFullscreenEnabled() {
        setUserAppAspectRatioFullscreenOverrideEnabled(false);
    }

    void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        // TODO(b/359438445): Add more useful information to dump().
        pw.println(prefix + "  letterboxPositionForHorizontalReachability="
                + letterboxHorizontalReachabilityPositionToString(
                    getLetterboxPositionForHorizontalReachability(
                            /* isInFullScreenBookMode */ false)));
        pw.println(prefix + "  letterboxPositionForVerticalReachability="
                + letterboxVerticalReachabilityPositionToString(
                    getLetterboxPositionForVerticalReachability(
                            /* isInFullScreenTabletopMode */ false)));
        pw.println(prefix + "  fixedOrientationLetterboxAspectRatio="
                + getFixedOrientationLetterboxAspectRatio());
        pw.println(prefix + "  defaultMinAspectRatioForUnresizableApps="
                + getDefaultMinAspectRatioForUnresizableApps());
        pw.println(prefix + "  isSplitScreenAspectRatioForUnresizableAppsEnabled="
                + getIsSplitScreenAspectRatioForUnresizableAppsEnabled());
        pw.println(prefix + "  isDisplayAspectRatioEnabledForFixedOrientationLetterbox="
                + getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox());
    }

    /**
     * Checks whether the multiplier is between [0,1].
     *
     * @param multiplierName sent in the exception if multiplier is invalid, for easier debugging.
     *
     * @return multiplier, if valid
     * @throws IllegalArgumentException if outside bounds.
     */
    private float assertValidMultiplier(float multiplier, String multiplierName)
            throws IllegalArgumentException {
        if (multiplier < 0.0f || multiplier > 1.0f) {
            throw new IllegalArgumentException("Trying to set " + multiplierName
                    + " out of bounds: " + multiplier);
        }
        return multiplier;
    }
}
