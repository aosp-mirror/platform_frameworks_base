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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;

import java.util.function.Predicate;

/**
 * Utility to unify rounded corners in app compat.
 */
class AppCompatRoundedCorners {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final Predicate<WindowState> mIsLetterboxedNotForDisplayCutout;

    AppCompatRoundedCorners(@NonNull ActivityRecord  activityRecord,
            @NonNull Predicate<WindowState> isLetterboxedNotForDisplayCutout) {
        mActivityRecord = activityRecord;
        mIsLetterboxedNotForDisplayCutout = isLetterboxedNotForDisplayCutout;
    }

    void updateRoundedCornersIfNeeded(@NonNull final WindowState mainWindow) {
        final SurfaceControl windowSurface = mainWindow.getSurfaceControl();
        if (windowSurface == null || !windowSurface.isValid()) {
            return;
        }

        // cropBounds must be non-null for the cornerRadius to be ever applied.
        mActivityRecord.getSyncTransaction()
                .setCrop(windowSurface, getCropBoundsIfNeeded(mainWindow))
                .setCornerRadius(windowSurface, getRoundedCornersRadius(mainWindow));
    }

    @VisibleForTesting
    @Nullable
    Rect getCropBoundsIfNeeded(@NonNull final WindowState mainWindow) {
        if (!requiresRoundedCorners(mainWindow) || mActivityRecord.isInLetterboxAnimation()) {
            // We don't want corner radius on the window.
            // In the case the ActivityRecord requires a letterboxed animation we never want
            // rounded corners on the window because rounded corners are applied at the
            // animation-bounds surface level and rounded corners on the window would interfere
            // with that leading to unexpected rounded corner positioning during the animation.
            return null;
        }

        final Rect cropBounds = new Rect(mActivityRecord.getBounds());

        // In case of translucent activities we check if the requested size is different from
        // the size provided using inherited bounds. In that case we decide to not apply rounded
        // corners because we assume the specific layout would. This is the case when the layout
        // of the translucent activity uses only a part of all the bounds because of the use of
        // LayoutParams.WRAP_CONTENT.
        final TransparentPolicy transparentPolicy = mActivityRecord.mAppCompatController
                .getTransparentPolicy();
        if (transparentPolicy.isRunning() && (cropBounds.width() != mainWindow.mRequestedWidth
                || cropBounds.height() != mainWindow.mRequestedHeight)) {
            return null;
        }

        // It is important to call {@link #adjustBoundsIfNeeded} before {@link cropBounds.offsetTo}
        // because taskbar bounds used in {@link #adjustBoundsIfNeeded}
        // are in screen coordinates
        AppCompatUtils.adjustBoundsForTaskbar(mainWindow, cropBounds);

        final float scale = mainWindow.mInvGlobalScale;
        if (scale != 1f && scale > 0f) {
            cropBounds.scale(scale);
        }

        // ActivityRecord bounds are in screen coordinates while (0,0) for activity's surface
        // control is in the top left corner of an app window so offsetting bounds
        // accordingly.
        cropBounds.offsetTo(0, 0);
        return cropBounds;
    }

    /**
     * Returns rounded corners radius the letterboxed activity should have based on override in
     * R.integer.config_letterboxActivityCornersRadius or min device bottom corner radii.
     * Device corners can be different on the right and left sides, but we use the same radius
     * for all corners for consistency and pick a minimal bottom one for consistency with a
     * taskbar rounded corners.
     *
     * @param mainWindow    The {@link WindowState} to consider for rounded corners calculation.
     */
    int getRoundedCornersRadius(@NonNull final WindowState mainWindow) {
        if (!requiresRoundedCorners(mainWindow)) {
            return 0;
        }
        final AppCompatLetterboxOverrides letterboxOverrides = mActivityRecord
                .mAppCompatController.getAppCompatLetterboxOverrides();
        final int radius;
        if (letterboxOverrides.getLetterboxActivityCornersRadius() >= 0) {
            radius = letterboxOverrides.getLetterboxActivityCornersRadius();
        } else {
            final InsetsState insetsState = mainWindow.getInsetsState();
            radius = Math.min(
                    getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_LEFT),
                    getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_RIGHT));
        }

        final float scale = mainWindow.mInvGlobalScale;
        return (scale != 1f && scale > 0f) ? (int) (scale * radius) : radius;
    }

    private static int getInsetsStateCornerRadius(@NonNull InsetsState insetsState,
            @RoundedCorner.Position int position) {
        final RoundedCorner corner = insetsState.getRoundedCorners().getRoundedCorner(position);
        return corner == null ? 0 : corner.getRadius();
    }

    private boolean requiresRoundedCorners(@NonNull final WindowState mainWindow) {
        final AppCompatLetterboxOverrides letterboxOverrides = mActivityRecord
                .mAppCompatController.getAppCompatLetterboxOverrides();
        return mIsLetterboxedNotForDisplayCutout.test(mainWindow)
                && letterboxOverrides.isLetterboxActivityCornersRounded();
    }

}
