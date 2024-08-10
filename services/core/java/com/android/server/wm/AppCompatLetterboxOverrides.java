/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.graphics.Color;
import android.util.Slog;
import android.view.WindowManager;

import com.android.server.wm.AppCompatConfiguration.LetterboxBackgroundType;

/**
 * Encapsulates overrides and configuration related to the Letterboxing policy.
 */
class AppCompatLetterboxOverrides {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppCompatLetterboxOverrides" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final AppCompatConfiguration mAppCompatConfiguration;

    private boolean mShowWallpaperForLetterboxBackground;

    AppCompatLetterboxOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration) {
        mActivityRecord = activityRecord;
        mAppCompatConfiguration = appCompatConfiguration;
    }

    boolean shouldLetterboxHaveRoundedCorners() {
        // TODO(b/214030873): remove once background is drawn for transparent activities
        // Letterbox shouldn't have rounded corners if the activity is transparent
        return mAppCompatConfiguration.isLetterboxActivityCornersRounded()
                && mActivityRecord.fillsParent();
    }

    boolean isLetterboxEducationEnabled() {
        return mAppCompatConfiguration.getIsEducationEnabled();
    }

    boolean hasWallpaperBackgroundForLetterbox() {
        return mShowWallpaperForLetterboxBackground;
    }

    boolean checkWallpaperBackgroundForLetterbox(boolean wallpaperShouldBeShown) {
        if (mShowWallpaperForLetterboxBackground != wallpaperShouldBeShown) {
            mShowWallpaperForLetterboxBackground = wallpaperShouldBeShown;
            return true;
        }
        return false;
    }

    @NonNull
    Color getLetterboxBackgroundColor() {
        final WindowState w = mActivityRecord.findMainWindow();
        if (w == null || w.isLetterboxedForDisplayCutout()) {
            return Color.valueOf(Color.BLACK);
        }
        final @LetterboxBackgroundType int letterboxBackgroundType =
                mAppCompatConfiguration.getLetterboxBackgroundType();
        final ActivityManager.TaskDescription taskDescription = mActivityRecord.taskDescription;
        switch (letterboxBackgroundType) {
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING:
                if (taskDescription != null && taskDescription.getBackgroundColorFloating() != 0) {
                    return Color.valueOf(taskDescription.getBackgroundColorFloating());
                }
                break;
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND:
                if (taskDescription != null && taskDescription.getBackgroundColor() != 0) {
                    return Color.valueOf(taskDescription.getBackgroundColor());
                }
                break;
            case LETTERBOX_BACKGROUND_WALLPAPER:
                if (hasWallpaperBackgroundForLetterbox()) {
                    // Color is used for translucent scrim that dims wallpaper.
                    return mAppCompatConfiguration.getLetterboxBackgroundColor();
                }
                Slog.w(TAG, "Wallpaper option is selected for letterbox background but "
                        + "blur is not supported by a device or not supported in the current "
                        + "window configuration or both alpha scrim and blur radius aren't "
                        + "provided so using solid color background");
                break;
            case LETTERBOX_BACKGROUND_SOLID_COLOR:
                return mAppCompatConfiguration.getLetterboxBackgroundColor();
            default:
                throw new AssertionError(
                        "Unexpected letterbox background type: " + letterboxBackgroundType);
        }
        // If picked option configured incorrectly or not supported then default to a solid color
        // background.
        return mAppCompatConfiguration.getLetterboxBackgroundColor();
    }

    int getLetterboxActivityCornersRadius() {
        return mAppCompatConfiguration.getLetterboxActivityCornersRadius();
    }

    boolean isLetterboxActivityCornersRounded() {
        return mAppCompatConfiguration.isLetterboxActivityCornersRounded();
    }

    @LetterboxBackgroundType
    int getLetterboxBackgroundType() {
        return mAppCompatConfiguration.getLetterboxBackgroundType();
    }

    int getLetterboxWallpaperBlurRadiusPx() {
        int blurRadius = mAppCompatConfiguration.getLetterboxBackgroundWallpaperBlurRadiusPx();
        return Math.max(blurRadius, 0);
    }

    float getLetterboxWallpaperDarkScrimAlpha() {
        float alpha = mAppCompatConfiguration.getLetterboxBackgroundWallpaperDarkScrimAlpha();
        // No scrim by default.
        return (alpha < 0 || alpha >= 1) ? 0.0f : alpha;
    }

    boolean isLetterboxWallpaperBlurSupported() {
        return mAppCompatConfiguration.mContext.getSystemService(WindowManager.class)
                .isCrossWindowBlurEnabled();
    }

}
