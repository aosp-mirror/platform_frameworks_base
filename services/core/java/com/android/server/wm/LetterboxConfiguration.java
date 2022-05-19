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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Color;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Reads letterbox configs from resources and controls their overrides at runtime. */
final class LetterboxConfiguration {

    /**
     * Override of aspect ratio for fixed orientation letterboxing that is set via ADB with
     * set-fixed-orientation-letterbox-aspect-ratio or via {@link
     * com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio} will be ignored
     * if it is <= this value.
     */
    static final float MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO = 1.0f;

    /** Enum for Letterbox background type. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({LETTERBOX_BACKGROUND_SOLID_COLOR, LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND,
            LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING, LETTERBOX_BACKGROUND_WALLPAPER})
    @interface LetterboxBackgroundType {};
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

    // Aspect ratio of letterbox for fixed orientation, values <=
    // MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO will be ignored.
    private float mFixedOrientationLetterboxAspectRatio;

    // Corners radius for activities presented in the letterbox mode, values < 0 will be ignored.
    private int mLetterboxActivityCornersRadius;

    // Color for {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} letterbox background type.
    @Nullable private Color mLetterboxBackgroundColorOverride;

    // Color resource id for {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} letterbox background type.
    @Nullable private Integer mLetterboxBackgroundColorResourceIdOverride;

    @LetterboxBackgroundType
    private int mLetterboxBackgroundType;

    // Blur radius for LETTERBOX_BACKGROUND_WALLPAPER option in mLetterboxBackgroundType.
    // Values <= 0 are ignored and 0 is used instead.
    private int mLetterboxBackgroundWallpaperBlurRadius;

    // Alpha of a black scrim shown over wallpaper letterbox background when
    // LETTERBOX_BACKGROUND_WALLPAPER option is selected for mLetterboxBackgroundType.
    // Values < 0 or >= 1 are ignored and 0.0 (transparent) is used instead.
    private float mLetterboxBackgroundWallpaperDarkScrimAlpha;

    // Horizontal position of a center of the letterboxed app window. 0 corresponds to the left
    // side of the screen and 1.0 to the right side.
    private float mLetterboxHorizontalPositionMultiplier;

    // Vertical position of a center of the letterboxed app window. 0 corresponds to the top
    // side of the screen and 1.0 to the bottom side.
    private float mLetterboxVerticalPositionMultiplier;

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


    // Horizontal position of a center of the letterboxed app window which is global to prevent
    // "jumps" when switching between letterboxed apps. It's updated to reposition the app window
    // in response to a double tap gesture (see LetterboxUiController#handleDoubleTap). Used in
    // LetterboxUiController#getHorizontalPositionMultiplier which is called from
    // ActivityRecord#updateResolvedBoundsPosition.
    // TODO(b/199426138): Global reachability setting causes a jump when resuming an app from
    // Overview after changing position in another app.
    @LetterboxHorizontalReachabilityPosition
    private volatile int mLetterboxPositionForHorizontalReachability;

    // Vertical position of a center of the letterboxed app window which is global to prevent
    // "jumps" when switching between letterboxed apps. It's updated to reposition the app window
    // in response to a double tap gesture (see LetterboxUiController#handleDoubleTap). Used in
    // LetterboxUiController#getVerticalPositionMultiplier which is called from
    // ActivityRecord#updateResolvedBoundsPosition.
    // TODO(b/199426138): Global reachability setting causes a jump when resuming an app from
    // Overview after changing position in another app.
    @LetterboxVerticalReachabilityPosition
    private volatile int mLetterboxPositionForVerticalReachability;

    // Whether education is allowed for letterboxed fullscreen apps.
    private boolean mIsEducationEnabled;

    LetterboxConfiguration(Context systemUiContext) {
        mContext = systemUiContext;
        mFixedOrientationLetterboxAspectRatio = mContext.getResources().getFloat(
                R.dimen.config_fixedOrientationLetterboxAspectRatio);
        mLetterboxActivityCornersRadius = mContext.getResources().getInteger(
                R.integer.config_letterboxActivityCornersRadius);
        mLetterboxBackgroundType = readLetterboxBackgroundTypeFromConfig(mContext);
        mLetterboxBackgroundWallpaperBlurRadius = mContext.getResources().getDimensionPixelSize(
                R.dimen.config_letterboxBackgroundWallpaperBlurRadius);
        mLetterboxBackgroundWallpaperDarkScrimAlpha = mContext.getResources().getFloat(
                R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha);
        mLetterboxHorizontalPositionMultiplier = mContext.getResources().getFloat(
                R.dimen.config_letterboxHorizontalPositionMultiplier);
        mLetterboxVerticalPositionMultiplier = mContext.getResources().getFloat(
                R.dimen.config_letterboxVerticalPositionMultiplier);
        mIsHorizontalReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsHorizontalReachabilityEnabled);
        mIsVerticalReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsVerticalReachabilityEnabled);
        mDefaultPositionForHorizontalReachability =
                readLetterboxHorizontalReachabilityPositionFromConfig(mContext);
        mDefaultPositionForVerticalReachability =
                readLetterboxVerticalReachabilityPositionFromConfig(mContext);
        mLetterboxPositionForHorizontalReachability = mDefaultPositionForHorizontalReachability;
        mLetterboxPositionForVerticalReachability = mDefaultPositionForVerticalReachability;
        mIsEducationEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsEducationEnabled);
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
     * Overrides corners raidus for activities presented in the letterbox mode. If given value < 0,
     * both it and a value of {@link
     * com.android.internal.R.integer.config_letterboxActivityCornersRadius} will be ignored and
     * corners of the activity won't be rounded.
     */
    void setLetterboxActivityCornersRadius(int cornersRadius) {
        mLetterboxActivityCornersRadius = cornersRadius;
    }

    /**
     * Resets corners raidus for activities presented in the letterbox mode to {@link
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
     * Gets corners raidus for activities presented in the letterbox mode.
     */
    int getLetterboxActivityCornersRadius() {
        return mLetterboxActivityCornersRadius;
    }

    /**
     * Gets color of letterbox background which is used when {@link
     * #getLetterboxBackgroundType()} is {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} or as
     * fallback for other backfround types.
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
     * fallback for other backfround types.
     */
    void setLetterboxBackgroundColor(Color color) {
        mLetterboxBackgroundColorOverride = color;
    }

    /**
     * Sets color ID of letterbox background which is used when {@link
     * #getLetterboxBackgroundType()} is {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} or as
     * fallback for other backfround types.
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
     * Gets {@link LetterboxBackgroundType} specified in {@link
     * com.android.internal.R.integer.config_letterboxBackgroundType} or over via ADB command.
     */
    @LetterboxBackgroundType
    int getLetterboxBackgroundType() {
        return mLetterboxBackgroundType;
    }

    /** Sets letterbox background type. */
    void setLetterboxBackgroundType(@LetterboxBackgroundType int backgroundType) {
        mLetterboxBackgroundType = backgroundType;
    }

    /**
     * Resets cletterbox background type to {@link
     * com.android.internal.R.integer.config_letterboxBackgroundType}.
     */
    void resetLetterboxBackgroundType() {
        mLetterboxBackgroundType = readLetterboxBackgroundTypeFromConfig(mContext);
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
     * #LETTERBOX_BACKGROUND_WALLPAPER} option in {@link mLetterboxBackgroundType}.
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
     * Overrides blur radius for {@link #LETTERBOX_BACKGROUND_WALLPAPER} option in
     * {@link mLetterboxBackgroundType}.
     *
     * <p> If given value <= 0, both it and a value of {@link
     * com.android.internal.R.dimen.config_letterboxBackgroundWallpaperBlurRadius} are ignored
     * and 0 is used instead.
     */
    void setLetterboxBackgroundWallpaperBlurRadius(int radius) {
        mLetterboxBackgroundWallpaperBlurRadius = radius;
    }

    /**
     * Resets blur raidus for {@link #LETTERBOX_BACKGROUND_WALLPAPER} option in {@link
     * mLetterboxBackgroundType} to {@link
     * com.android.internal.R.dimen.config_letterboxBackgroundWallpaperBlurRadius}.
     */
    void resetLetterboxBackgroundWallpaperBlurRadius() {
        mLetterboxBackgroundWallpaperBlurRadius = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.config_letterboxBackgroundWallpaperBlurRadius);
    }

    /**
     * Gets blur raidus for {@link #LETTERBOX_BACKGROUND_WALLPAPER} option in {@link
     * mLetterboxBackgroundType}.
     */
    int getLetterboxBackgroundWallpaperBlurRadius() {
        return mLetterboxBackgroundWallpaperBlurRadius;
    }

    /*
     * Gets horizontal position of a center of the letterboxed app window specified
     * in {@link com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier}
     * or via an ADB command. 0 corresponds to the left side of the screen and 1 to the
     * right side.
     */
    float getLetterboxHorizontalPositionMultiplier() {
        return (mLetterboxHorizontalPositionMultiplier < 0.0f
                || mLetterboxHorizontalPositionMultiplier > 1.0f)
                        // Default to central position if invalid value is provided.
                        ? 0.5f : mLetterboxHorizontalPositionMultiplier;
    }

    /*
     * Gets vertical position of a center of the letterboxed app window specified
     * in {@link com.android.internal.R.dimen.config_letterboxVerticalPositionMultiplier}
     * or via an ADB command. 0 corresponds to the top side of the screen and 1 to the
     * bottom side.
     */
    float getLetterboxVerticalPositionMultiplier() {
        return (mLetterboxVerticalPositionMultiplier < 0.0f
                || mLetterboxVerticalPositionMultiplier > 1.0f)
                        // Default to central position if invalid value is provided.
                        ? 0.5f : mLetterboxVerticalPositionMultiplier;
    }

    /**
     * Overrides horizontal position of a center of the letterboxed app window. If given value < 0
     * or > 1, then it and a value of {@link
     * com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier} are ignored and
     * central position (0.5) is used.
     */
    void setLetterboxHorizontalPositionMultiplier(float multiplier) {
        mLetterboxHorizontalPositionMultiplier = multiplier;
    }

    /**
     * Overrides vertical position of a center of the letterboxed app window. If given value < 0
     * or > 1, then it and a value of {@link
     * com.android.internal.R.dimen.config_letterboxVerticalPositionMultiplier} are ignored and
     * central position (0.5) is used.
     */
    void setLetterboxVerticalPositionMultiplier(float multiplier) {
        mLetterboxVerticalPositionMultiplier = multiplier;
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

    /*
     * Whether horizontal reachability repositioning is allowed for letterboxed fullscreen apps in
     * landscape device orientation.
     */
    boolean getIsHorizontalReachabilityEnabled() {
        return mIsHorizontalReachabilityEnabled;
    }

    /*
     * Whether vertical reachability repositioning is allowed for letterboxed fullscreen apps in
     * portrait device orientation.
     */
    boolean getIsVerticalReachabilityEnabled() {
        return mIsVerticalReachabilityEnabled;
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

    /*
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

    /*
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
                readLetterboxHorizontalReachabilityPositionFromConfig(mContext);
    }

    /**
     * Resets default vertical position of the letterboxed app window when vertical reachability
     * is enabled to {@link R.integer.config_letterboxDefaultPositionForVerticalReachability}.
     */
    void resetDefaultPositionForVerticalReachability() {
        mDefaultPositionForVerticalReachability =
                readLetterboxVerticalReachabilityPositionFromConfig(mContext);
    }

    @LetterboxHorizontalReachabilityPosition
    private static int readLetterboxHorizontalReachabilityPositionFromConfig(Context context) {
        int position = context.getResources().getInteger(
                R.integer.config_letterboxDefaultPositionForHorizontalReachability);
        return position == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT
                    || position == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                    || position == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT
                    ? position : LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
    }

    @LetterboxVerticalReachabilityPosition
    private static int readLetterboxVerticalReachabilityPositionFromConfig(Context context) {
        int position = context.getResources().getInteger(
                R.integer.config_letterboxDefaultPositionForVerticalReachability);
        return position == LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP
                || position == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                || position == LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM
                ? position : LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
    }

    /*
     * Gets horizontal position of a center of the letterboxed app window when reachability
     * is enabled specified. 0 corresponds to the left side of the screen and 1 to the right side.
     *
     * <p>The position multiplier is changed after each double tap in the letterbox area.
     */
    float getHorizontalMultiplierForReachability() {
        switch (mLetterboxPositionForHorizontalReachability) {
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT:
                return 0.0f;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER:
                return 0.5f;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT:
                return 1.0f;
            default:
                throw new AssertionError(
                    "Unexpected letterbox position type: "
                            + mLetterboxPositionForHorizontalReachability);
        }
    }
    /*
     * Gets vertical position of a center of the letterboxed app window when reachability
     * is enabled specified. 0 corresponds to the top side of the screen and 1 to the bottom side.
     *
     * <p>The position multiplier is changed after each double tap in the letterbox area.
     */
    float getVerticalMultiplierForReachability() {
        switch (mLetterboxPositionForVerticalReachability) {
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP:
                return 0.0f;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER:
                return 0.5f;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM:
                return 1.0f;
            default:
                throw new AssertionError(
                        "Unexpected letterbox position type: "
                                + mLetterboxPositionForVerticalReachability);
        }
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
    void movePositionForHorizontalReachabilityToNextRightStop() {
        mLetterboxPositionForHorizontalReachability = Math.min(
                mLetterboxPositionForHorizontalReachability + 1,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT);
    }

    /**
     * Changes letterbox position for horizontal reachability to the next available one on the left
     * side.
     */
    void movePositionForHorizontalReachabilityToNextLeftStop() {
        mLetterboxPositionForHorizontalReachability =
                Math.max(mLetterboxPositionForHorizontalReachability - 1, 0);
    }

    /**
     * Changes letterbox position for vertical reachability to the next available one on the bottom
     * side.
     */
    void movePositionForVerticalReachabilityToNextBottomStop() {
        mLetterboxPositionForVerticalReachability = Math.min(
                mLetterboxPositionForVerticalReachability + 1,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM);
    }

    /**
     * Changes letterbox position for vertical reachability to the next available one on the top
     * side.
     */
    void movePositionForVerticalReachabilityToNextTopStop() {
        mLetterboxPositionForVerticalReachability =
                Math.max(mLetterboxPositionForVerticalReachability - 1, 0);
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

}
