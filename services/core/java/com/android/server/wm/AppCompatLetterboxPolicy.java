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
import static com.android.server.wm.AppCompatConfiguration.letterboxBackgroundTypeToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.server.wm.AppCompatConfiguration.LetterboxBackgroundType;

import java.io.PrintWriter;

/**
 * Encapsulates the logic for the Letterboxing policy.
 */
class AppCompatLetterboxPolicy {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final LetterboxPolicyState mLetterboxPolicyState;
    @NonNull
    private final AppCompatRoundedCorners mAppCompatRoundedCorners;
    @NonNull
    private final AppCompatConfiguration mAppCompatConfiguration;

    private boolean mLastShouldShowLetterboxUi;

    AppCompatLetterboxPolicy(@NonNull ActivityRecord  activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration) {
        mActivityRecord = activityRecord;
        mLetterboxPolicyState = new LetterboxPolicyState();
        // TODO (b/358334569) Improve cutout logic dependency on app compat.
        mAppCompatRoundedCorners = new AppCompatRoundedCorners(mActivityRecord,
                this::isLetterboxedNotForDisplayCutout);
        mAppCompatConfiguration = appCompatConfiguration;
    }

    /** Cleans up {@link Letterbox} if it exists.*/
    void stop() {
        mLetterboxPolicyState.stop();
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
        mAppCompatRoundedCorners.updateRoundedCornersIfNeeded(w);
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
        return mAppCompatRoundedCorners.getCropBoundsIfNeeded(mainWindow);
    }

    /**
     * Returns rounded corners radius the letterboxed activity should have based on override in
     * R.integer.config_letterboxActivityCornersRadius or min device bottom corner radii.
     * Device corners can be different on the right and left sides, but we use the same radius
     * for all corners for consistency and pick a minimal bottom one for consistency with a
     * taskbar rounded corners.
     *
     * @param mainWindow    The {@link WindowState} to consider for the rounded corners calculation.
     */
    int getRoundedCornersRadius(@NonNull final WindowState mainWindow) {
        return mAppCompatRoundedCorners.getRoundedCornersRadius(mainWindow);
    }

    void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        final WindowState mainWin = mActivityRecord.findMainWindow();
        if (mainWin == null) {
            return;
        }
        boolean areBoundsLetterboxed = mainWin.areAppWindowBoundsLetterboxed();
        pw.println(prefix + "areBoundsLetterboxed=" + areBoundsLetterboxed);
        pw.println(prefix + "isLetterboxRunning=" + isRunning());
        if (!areBoundsLetterboxed) {
            return;
        }
        pw.println(prefix + "  letterboxReason="
                + AppCompatUtils.getLetterboxReasonString(mActivityRecord, mainWin));
        mActivityRecord.mAppCompatController.getAppCompatReachabilityPolicy().dump(pw, prefix);
        final AppCompatLetterboxOverrides letterboxOverride = mActivityRecord.mAppCompatController
                .getAppCompatLetterboxOverrides();
        pw.println(prefix + "  letterboxBackgroundColor=" + Integer.toHexString(
                letterboxOverride.getLetterboxBackgroundColor().toArgb()));
        pw.println(prefix + "  letterboxBackgroundType="
                + letterboxBackgroundTypeToString(letterboxOverride.getLetterboxBackgroundType()));
        pw.println(prefix + "  letterboxCornerRadius=" + getRoundedCornersRadius(mainWin));
        if (letterboxOverride.getLetterboxBackgroundType() == LETTERBOX_BACKGROUND_WALLPAPER) {
            pw.println(prefix + "  isLetterboxWallpaperBlurSupported="
                    + letterboxOverride.isLetterboxWallpaperBlurSupported());
            pw.println(prefix + "  letterboxBackgroundWallpaperDarkScrimAlpha="
                    + letterboxOverride.getLetterboxWallpaperDarkScrimAlpha());
            pw.println(prefix + "  letterboxBackgroundWallpaperBlurRadius="
                    + letterboxOverride.getLetterboxWallpaperBlurRadiusPx());
        }
        mAppCompatConfiguration.dump(pw, prefix);
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
        void stop() {
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
                    AppCompatUtils.adjustBoundsForTaskbar(w, outBounds);
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
