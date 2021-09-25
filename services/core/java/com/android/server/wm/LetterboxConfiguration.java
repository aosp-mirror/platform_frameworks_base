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

    // Default horizontal position of a center of the letterboxed app window when reachability is
    // enabled and an app is fullscreen in landscape device orientatio. 0 corresponds to the left
    // side of the screen and 1.0 to the right side.
    // It is used as a starting point for mLetterboxHorizontalMultiplierForReachability.
    private float mDefaultPositionMultiplierForReachability;

    // Whether reachability repositioning is allowed for letterboxed fullscreen apps in landscape
    // device orientation.
    private boolean mIsReachabilityEnabled;

    // Horizontal position of a center of the letterboxed app window. 0 corresponds to
    // the left side of the screen and 1 to the right side. Keep it global to prevent
    // "jumps" when switching between letterboxed apps. It's updated to reposition the app
    // window in response to a double tap gesture (see LetterboxUiController#handleDoubleTap).
    // Used in LetterboxUiController#getHorizontalPositionMultiplier which is called from
    // ActivityRecord#updateResolvedBoundsHorizontalPosition.
    // TODO(b/199426138): Global reachability setting causes a jump when resuming an app from
    // Overview after changing position in another app.
    private volatile float mLetterboxHorizontalMultiplierForReachability;

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
        mIsReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsReachabilityEnabled);
        mDefaultPositionMultiplierForReachability = mContext.getResources().getFloat(
                R.dimen.config_letterboxDefaultPositionMultiplierForReachability);
        mLetterboxHorizontalMultiplierForReachability = mDefaultPositionMultiplierForReachability;
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
     * and corners of the activity won't be rounded.
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
     * Resets horizontal position of a center of the letterboxed app window to {@link
     * com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier}.
     */
    void resetLetterboxHorizontalPositionMultiplier() {
        mLetterboxHorizontalPositionMultiplier = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier);
    }

    /*
     * Whether reachability repositioning is allowed for letterboxed fullscreen apps in landscape
     * device orientation.
     */
    boolean getIsReachabilityEnabled() {
        return mIsReachabilityEnabled;
    }

    /**
     * Overrides whether reachability repositioning is allowed for letterboxed fullscreen apps in
     * landscape device orientation.
     */
    void setIsReachabilityEnabled(boolean enabled) {
        mIsReachabilityEnabled = enabled;
    }

    /**
     * Resets whether reachability repositioning is allowed for letterboxed fullscreen apps in
     * landscape device orientation to {@link R.bool.config_letterboxIsReachabilityEnabled}.
     */
    void resetIsReachabilityEnabled() {
        mIsReachabilityEnabled = mContext.getResources().getBoolean(
                R.bool.config_letterboxIsReachabilityEnabled);
    }

    /*
     * Gets default horizontal position of a center of the letterboxed app window when reachability
     * is enabled specified in {@link
     * R.dimen.config_letterboxDefaultPositionMultiplierForReachability} or via an ADB command.
     * 0 corresponds to the left side of the screen and 1 to the right side. The returned value is
     * >= 0.0 and <= 1.0.
     */
    float getDefaultPositionMultiplierForReachability() {
        return (mDefaultPositionMultiplierForReachability < 0.0f
                || mDefaultPositionMultiplierForReachability > 1.0f)
                        // Default to a right position if invalid value is provided.
                        ? 1.0f : mDefaultPositionMultiplierForReachability;
    }

    /**
     * Overrides default horizontal position of a center of the letterboxed app window when
     * reachability is enabled. If given value < 0.0 or > 1.0, then it and a value of {@link
     * R.dimen.config_letterboxDefaultPositionMultiplierForReachability} are ignored and the right
     * position (1.0) is used.
     */
    void setDefaultPositionMultiplierForReachability(float multiplier) {
        mDefaultPositionMultiplierForReachability = multiplier;
    }

    /**
     * Resets default horizontal position of a center of the letterboxed app window when
     * reachability is enabled to {@link
     * R.dimen.config_letterboxDefaultPositionMultiplierForReachability}.
     */
    void resetDefaultPositionMultiplierForReachability() {
        mDefaultPositionMultiplierForReachability = mContext.getResources().getFloat(
                R.dimen.config_letterboxDefaultPositionMultiplierForReachability);
    }

    /*
     * Gets horizontal position of a center of the letterboxed app window when reachability
     * is enabled specified. 0 corresponds to the left side of the screen and 1 to the right side.
     *
     * <p>The position multiplier is changed to a symmetrical value computed as (1 - current
     * multiplier) after each double tap in the letterbox area.
     */
    float getHorizontalMultiplierForReachability() {
        return mLetterboxHorizontalMultiplierForReachability;
    }

    /**
     * Changes horizontal position of a center of the letterboxed app window to the opposite
     * (1 - current multiplier) when reachability is enabled specified. 0 corresponds to the left
     * side of the screen and 1 to the right side.
     */
    void flipHorizontalMultiplierForReachability() {
        mLetterboxHorizontalMultiplierForReachability =
                1.0f - mLetterboxHorizontalMultiplierForReachability;
    }

}
