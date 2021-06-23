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
import android.content.Context;
import android.graphics.Color;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

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
    private Color mLetterboxBackgroundColor;

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

    LetterboxConfiguration(Context context) {
        mContext = context;
        mFixedOrientationLetterboxAspectRatio = context.getResources().getFloat(
                R.dimen.config_fixedOrientationLetterboxAspectRatio);
        mLetterboxActivityCornersRadius = context.getResources().getInteger(
                R.integer.config_letterboxActivityCornersRadius);
        mLetterboxBackgroundColor = Color.valueOf(context.getResources().getColor(
                R.color.config_letterboxBackgroundColor));
        mLetterboxBackgroundType = readLetterboxBackgroundTypeFromConfig(context);
        mLetterboxBackgroundWallpaperBlurRadius = context.getResources().getDimensionPixelSize(
                R.dimen.config_letterboxBackgroundWallpaperBlurRadius);
        mLetterboxBackgroundWallpaperDarkScrimAlpha = context.getResources().getFloat(
                R.dimen.config_letterboxBackgroundWallaperDarkScrimAlpha);
        mLetterboxHorizontalPositionMultiplier = context.getResources().getFloat(
                R.dimen.config_letterboxHorizontalPositionMultiplier);
    }

    /**
     * Overrides the aspect ratio of letterbox for fixed orientation. If given value is <= {@link
     * #MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO}, both it and a value of {@link
     * com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio} will be ignored and
     * the framework implementation will be used to determine the aspect ratio.
     */
    @VisibleForTesting
    void setFixedOrientationLetterboxAspectRatio(float aspectRatio) {
        mFixedOrientationLetterboxAspectRatio = aspectRatio;
    }

    /**
     * Gets the aspect ratio of letterbox for fixed orientation.
     */
    float getFixedOrientationLetterboxAspectRatio() {
        return mFixedOrientationLetterboxAspectRatio;
    }

    /**
     * Whether corners of letterboxed activities are rounded.
     */
    boolean isLetterboxActivityCornersRounded() {
        return getLetterboxActivityCornersRadius() > 0;
    }

    /**
     * Gets corners raidus for activities presented in the letterbox mode.
     */
    int getLetterboxActivityCornersRadius() {
        return mLetterboxActivityCornersRadius;
    }

    /**
     * Gets color of letterbox background which is  used when {@link
     * #getLetterboxBackgroundType()} is {@link #LETTERBOX_BACKGROUND_SOLID_COLOR} or as
     * fallback for other backfround types.
     */
    Color getLetterboxBackgroundColor() {
        return mLetterboxBackgroundColor;
    }

    /**
     * Gets {@link LetterboxBackgroundType} specified in {@link
     * com.android.internal.R.integer.config_letterboxBackgroundType} or over via ADB command.
     */
    @LetterboxBackgroundType
    int getLetterboxBackgroundType() {
        return mLetterboxBackgroundType;
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
     * Gets alpha of a black scrim shown over wallpaper letterbox background.
     */
    float getLetterboxBackgroundWallpaperDarkScrimAlpha() {
        return mLetterboxBackgroundWallpaperDarkScrimAlpha;
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
     *
     * <p>This value can be outside of [0, 1] range so clients need to check and default to the
     * central position (0.5).
     */
    float getLetterboxHorizontalPositionMultiplier() {
        return mLetterboxHorizontalPositionMultiplier;
    }

    /**
     * Overrides horizontal position of a center of the letterboxed app window. If given value < 0
     * or > 1, then it and a value of {@link
     * com.android.internal.R.dimen.config_letterboxHorizontalPositionMultiplier} are ignored and
     * central position (0.5) is used.
     */
    @VisibleForTesting
    void setLetterboxHorizontalPositionMultiplier(float multiplier) {
        mLetterboxHorizontalPositionMultiplier = multiplier;
    }

}
