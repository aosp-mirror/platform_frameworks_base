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

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.server.wm.AppCompatConfiguration.LetterboxBackgroundType;

/**
 * Encapsulates the logic for the Letterboxing policy.
 */
class AppCompatLetterboxPolicy {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final LetterboxPolicyState mLetterboxPolicyState;

    private boolean mLastShouldShowLetterboxUi;

    AppCompatLetterboxPolicy(@NonNull ActivityRecord  activityRecord) {
        mActivityRecord = activityRecord;
        mLetterboxPolicyState = new LetterboxPolicyState();
    }

    /** Cleans up {@link Letterbox} if it exists.*/
    void destroy() {
        mLetterboxPolicyState.destroy();
    }

    /** @return {@value true} if the letterbox policy is running and the activity letterboxed. */
    boolean isRunning() {
        return mLetterboxPolicyState.isRunning();
    }

    void onMovedToDisplay(int displayId) {
        mLetterboxPolicyState.onMovedToDisplay(displayId);
    }

    /** Gets the letterbox insets. The insets will be empty if there is no letterbox. */
    @NonNull
    Rect getLetterboxInsets() {
        return mLetterboxPolicyState.getLetterboxInsets();
    }

    /** Gets the inner bounds of letterbox. The bounds will be empty if there is no letterbox. */
    void getLetterboxInnerBounds(@NonNull Rect outBounds) {
        mLetterboxPolicyState.getLetterboxInnerBounds(outBounds);
    }

    @Nullable
    LetterboxDetails getLetterboxDetails() {
        return mLetterboxPolicyState.getLetterboxDetails();
    }

    /**
     * @return {@code true} if bar shown within a given rectangle is allowed to be fully transparent
     *     when the current activity is displayed.
     */
    boolean isFullyTransparentBarAllowed(@NonNull Rect rect) {
        return mLetterboxPolicyState.isFullyTransparentBarAllowed(rect);
    }

    void updateLetterboxSurfaceIfNeeded(@NonNull WindowState winHint,
            @NonNull SurfaceControl.Transaction t,
            @NonNull SurfaceControl.Transaction inputT) {
        mLetterboxPolicyState.updateLetterboxSurfaceIfNeeded(winHint, t, inputT);
    }

    void updateLetterboxSurfaceIfNeeded(@NonNull WindowState winHint) {
        mLetterboxPolicyState.updateLetterboxSurfaceIfNeeded(winHint,
                mActivityRecord.getSyncTransaction(), mActivityRecord.getPendingTransaction());
    }

    void start(@NonNull WindowState w) {
        if (shouldNotLayoutLetterbox(w)) {
            return;
        }
        updateRoundedCornersIfNeeded(w);
        updateWallpaperForLetterbox(w);
        if (shouldShowLetterboxUi(w)) {
            mLetterboxPolicyState.layoutLetterboxIfNeeded(w);
        }  else {
            mLetterboxPolicyState.hide();
        }
    }

    @VisibleForTesting
    boolean shouldShowLetterboxUi(@NonNull WindowState mainWindow) {
        if (mActivityRecord.mAppCompatController.getAppCompatOrientationOverrides()
                .getIsRelaunchingAfterRequestedOrientationChanged()) {
            return mLastShouldShowLetterboxUi;
        }

        final boolean shouldShowLetterboxUi =
                (mActivityRecord.isInLetterboxAnimation() || mActivityRecord.isVisible()
                        || mActivityRecord.isVisibleRequested())
                        && mainWindow.areAppWindowBoundsLetterboxed()
                        // Check for FLAG_SHOW_WALLPAPER explicitly instead of using
                        // WindowContainer#showWallpaper because the later will return true when
                        // this activity is using blurred wallpaper for letterbox background.
                        && (mainWindow.getAttrs().flags & FLAG_SHOW_WALLPAPER) == 0;

        mLastShouldShowLetterboxUi = shouldShowLetterboxUi;

        return shouldShowLetterboxUi;
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
        adjustBoundsForTaskbar(mainWindow, cropBounds);

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


    // Returns rounded corners radius the letterboxed activity should have based on override in
    // R.integer.config_letterboxActivityCornersRadius or min device bottom corner radii.
    // Device corners can be different on the right and left sides, but we use the same radius
    // for all corners for consistency and pick a minimal bottom one for consistency with a
    // taskbar rounded corners.
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

    void adjustBoundsForTaskbar(@NonNull final WindowState mainWindow,
            @NonNull final Rect bounds) {
        // Rounded corners should be displayed above the taskbar. When taskbar is hidden,
        // an insets frame is equal to a navigation bar which shouldn't affect position of
        // rounded corners since apps are expected to handle navigation bar inset.
        // This condition checks whether the taskbar is visible.
        // Do not crop the taskbar inset if the window is in immersive mode - the user can
        // swipe to show/hide the taskbar as an overlay.
        // Adjust the bounds only in case there is an expanded taskbar,
        // otherwise the rounded corners will be shown behind the navbar.
        final InsetsSource expandedTaskbarOrNull =
                AppCompatUtils.getExpandedTaskbarOrNull(mainWindow);
        if (expandedTaskbarOrNull != null) {
            // Rounded corners should be displayed above the expanded taskbar.
            bounds.bottom = Math.min(bounds.bottom, expandedTaskbarOrNull.getFrame().top);
        }
    }

    private int getInsetsStateCornerRadius(@NonNull InsetsState insetsState,
            @RoundedCorner.Position int position) {
        final RoundedCorner corner = insetsState.getRoundedCorners().getRoundedCorner(position);
        return corner == null ? 0 : corner.getRadius();
    }

    private void updateWallpaperForLetterbox(@NonNull WindowState mainWindow) {
        final AppCompatLetterboxOverrides letterboxOverrides = mActivityRecord
                .mAppCompatController.getAppCompatLetterboxOverrides();
        final @LetterboxBackgroundType int letterboxBackgroundType =
                letterboxOverrides.getLetterboxBackgroundType();
        boolean wallpaperShouldBeShown =
                letterboxBackgroundType == LETTERBOX_BACKGROUND_WALLPAPER
                        // Don't use wallpaper as a background if letterboxed for display cutout.
                        && isLetterboxedNotForDisplayCutout(mainWindow)
                        // Check that dark scrim alpha or blur radius are provided
                        && (letterboxOverrides.getLetterboxWallpaperBlurRadiusPx() > 0
                        || letterboxOverrides.getLetterboxWallpaperDarkScrimAlpha() > 0)
                        // Check that blur is supported by a device if blur radius is provided.
                        && (letterboxOverrides.getLetterboxWallpaperBlurRadiusPx() <= 0
                        || letterboxOverrides.isLetterboxWallpaperBlurSupported());
        if (letterboxOverrides.checkWallpaperBackgroundForLetterbox(wallpaperShouldBeShown)) {
            mActivityRecord.requestUpdateWallpaperIfNeeded();
        }
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

    private boolean requiresRoundedCorners(@NonNull final WindowState mainWindow) {
        final AppCompatLetterboxOverrides letterboxOverrides = mActivityRecord
                .mAppCompatController.getAppCompatLetterboxOverrides();
        return isLetterboxedNotForDisplayCutout(mainWindow)
                && letterboxOverrides.isLetterboxActivityCornersRounded();
    }

    private boolean isLetterboxedNotForDisplayCutout(@NonNull WindowState mainWindow) {
        return shouldShowLetterboxUi(mainWindow)
                && !mainWindow.isLetterboxedForDisplayCutout();
    }

    private static boolean shouldNotLayoutLetterbox(@Nullable WindowState w) {
        if (w == null) {
            return true;
        }
        final int type = w.mAttrs.type;
        // Allow letterbox to be displayed early for base application or application starting
        // windows even if it is not on the top z order to prevent flickering when the
        // letterboxed window is brought to the top
        return (type != TYPE_BASE_APPLICATION && type != TYPE_APPLICATION_STARTING)
                || w.mAnimatingExit;
    }

    private class LetterboxPolicyState {

        @Nullable
        private Letterbox mLetterbox;

        void layoutLetterboxIfNeeded(@NonNull WindowState w) {
            if (!isRunning()) {
                final AppCompatLetterboxOverrides letterboxOverrides = mActivityRecord
                        .mAppCompatController.getAppCompatLetterboxOverrides();
                final AppCompatReachabilityPolicy reachabilityPolicy = mActivityRecord
                        .mAppCompatController.getAppCompatReachabilityPolicy();
                mLetterbox = new Letterbox(() -> mActivityRecord.makeChildSurface(null),
                        mActivityRecord.mWmService.mTransactionFactory,
                        reachabilityPolicy, letterboxOverrides,
                        this::getLetterboxParentSurface);
                mLetterbox.attachInput(w);
                mActivityRecord.mAppCompatController.getAppCompatReachabilityPolicy()
                        .setLetterboxInnerBoundsSupplier(mLetterbox::getInnerFrame);
            }
            final Point letterboxPosition = new Point();
            if (mActivityRecord.isInLetterboxAnimation()) {
                // In this case we attach the letterbox to the task instead of the activity.
                mActivityRecord.getTask().getPosition(letterboxPosition);
            } else {
                mActivityRecord.getPosition(letterboxPosition);
            }

            // Get the bounds of the "space-to-fill". The transformed bounds have the highest
            // priority because the activity is launched in a rotated environment. In multi-window
            // mode, the taskFragment-level represents this for both split-screen
            // and activity-embedding. In fullscreen-mode, the task container does
            // (since the orientation letterbox is also applied to the task).
            final Rect transformedBounds = mActivityRecord.getFixedRotationTransformDisplayBounds();
            final Rect spaceToFill = transformedBounds != null
                    ? transformedBounds
                    : mActivityRecord.inMultiWindowMode()
                            ? mActivityRecord.getTaskFragment().getBounds()
                            : mActivityRecord.getRootTask().getParent().getBounds();
            // In case of translucent activities an option is to use the WindowState#getFrame() of
            // the first opaque activity beneath. In some cases (e.g. an opaque activity is using
            // non MATCH_PARENT layouts or a Dialog theme) this might not provide the correct
            // information and in particular it might provide a value for a smaller area making
            // the letterbox overlap with the translucent activity's frame.
            // If we use WindowState#getFrame() for the translucent activity's letterbox inner
            // frame, the letterbox will then be overlapped with the translucent activity's frame.
            // Because the surface layer of letterbox is lower than an activity window, this
            // won't crop the content, but it may affect other features that rely on values stored
            // in mLetterbox, e.g. transitions, a status bar scrim and recents preview in Launcher
            // For this reason we use ActivityRecord#getBounds() that the translucent activity
            // inherits from the first opaque activity beneath and also takes care of the scaling
            // in case of activities in size compat mode.
            final TransparentPolicy transparentPolicy =
                    mActivityRecord.mAppCompatController.getTransparentPolicy();
            final Rect innerFrame =
                    transparentPolicy.isRunning() ? mActivityRecord.getBounds() : w.getFrame();
            mLetterbox.layout(spaceToFill, innerFrame, letterboxPosition);
            if (mActivityRecord.mAppCompatController.getAppCompatReachabilityOverrides()
                    .isDoubleTapEvent()) {
                // We need to notify Shell that letterbox position has changed.
                mActivityRecord.getTask().dispatchTaskInfoChangedIfNeeded(true /* force */);
            }
        }

        /**
         * @return  {@code true} if the policy is running and so if the current activity is
         *          letterboxed.
         */
        boolean isRunning() {
            return mLetterbox != null;
        }

        void onMovedToDisplay(int displayId) {
            if (isRunning()) {
                mLetterbox.onMovedToDisplay(displayId);
            }
        }

        /** Cleans up {@link Letterbox} if it exists.*/
        void destroy() {
            if (isRunning()) {
                mLetterbox.destroy();
                mLetterbox = null;
            }
            mActivityRecord.mAppCompatController.getAppCompatReachabilityPolicy()
                    .setLetterboxInnerBoundsSupplier(null);
        }

        void updateLetterboxSurfaceIfNeeded(@NonNull WindowState winHint,
                @NonNull SurfaceControl.Transaction t,
                @NonNull SurfaceControl.Transaction inputT) {
            if (shouldNotLayoutLetterbox(winHint)) {
                return;
            }
            start(winHint);
            if (isRunning() && mLetterbox.needsApplySurfaceChanges()) {
                mLetterbox.applySurfaceChanges(t, inputT);
            }
        }

        void hide() {
            if (isRunning()) {
                mLetterbox.hide();
            }
        }

        /** Gets the letterbox insets. The insets will be empty if there is no letterbox. */
        @NonNull
        Rect getLetterboxInsets() {
            if (isRunning()) {
                return mLetterbox.getInsets();
            } else {
                return new Rect();
            }
        }

        /** Gets the inner bounds of letterbox. The bounds will be empty with no letterbox. */
        void getLetterboxInnerBounds(@NonNull Rect outBounds) {
            if (isRunning()) {
                outBounds.set(mLetterbox.getInnerFrame());
                final WindowState w = mActivityRecord.findMainWindow();
                if (w != null) {
                    adjustBoundsForTaskbar(w, outBounds);
                }
            } else {
                outBounds.setEmpty();
            }
        }

        /** Gets the outer bounds of letterbox. The bounds will be empty with no letterbox. */
        private void getLetterboxOuterBounds(@NonNull Rect outBounds) {
            if (isRunning()) {
                outBounds.set(mLetterbox.getOuterFrame());
            } else {
                outBounds.setEmpty();
            }
        }

        /**
         * @return {@code true} if bar shown within a given rectangle is allowed to be fully
         *          transparent when the current activity is displayed.
         */
        boolean isFullyTransparentBarAllowed(@NonNull Rect rect) {
            return !isRunning() || mLetterbox.notIntersectsOrFullyContains(rect);
        }

        @Nullable
        LetterboxDetails getLetterboxDetails() {
            final WindowState w = mActivityRecord.findMainWindow();
            if (!isRunning() || w == null || w.isLetterboxedForDisplayCutout()) {
                return null;
            }
            final Rect letterboxInnerBounds = new Rect();
            final Rect letterboxOuterBounds = new Rect();
            getLetterboxInnerBounds(letterboxInnerBounds);
            getLetterboxOuterBounds(letterboxOuterBounds);

            if (letterboxInnerBounds.isEmpty() || letterboxOuterBounds.isEmpty()) {
                return null;
            }

            return new LetterboxDetails(
                    letterboxInnerBounds,
                    letterboxOuterBounds,
                    w.mAttrs.insetsFlags.appearance
            );
        }

        @Nullable
        private SurfaceControl getLetterboxParentSurface() {
            if (mActivityRecord.isInLetterboxAnimation()) {
                return mActivityRecord.getTask().getSurfaceControl();
            }
            return mActivityRecord.getSurfaceControl();
        }

    }
}
