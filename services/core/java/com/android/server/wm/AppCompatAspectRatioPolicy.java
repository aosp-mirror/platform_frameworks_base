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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.server.wm.AppCompatConfiguration.DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW;
import static com.android.server.wm.AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.window.flags.Flags;

/**
 * Encapsulate app compat policy logic related to aspect ratio.
 */
class AppCompatAspectRatioPolicy {

    // Rounding tolerance to be used in aspect ratio computations
    private static final float ASPECT_RATIO_ROUNDING_TOLERANCE = 0.005f;

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final TransparentPolicy mTransparentPolicy;
    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;
    @NonNull
    private final AppCompatAspectRatioState mAppCompatAspectRatioState;

    private final Rect mTmpBounds = new Rect();

    AppCompatAspectRatioPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull TransparentPolicy transparentPolicy,
            @NonNull AppCompatOverrides appCompatOverrides) {
        mActivityRecord = activityRecord;
        mTransparentPolicy = transparentPolicy;
        mAppCompatOverrides = appCompatOverrides;
        mAppCompatAspectRatioState = new AppCompatAspectRatioState();
    }

    /**
     * Starts the evaluation of app compat aspect ratio when a new configuration needs to be
     * resolved.
     */
    void reset() {
        mAppCompatAspectRatioState.reset();
    }

    private float getDesiredAspectRatio(@NonNull Configuration newParentConfig,
            @NonNull Rect parentBounds) {
        final float letterboxAspectRatioOverride =
                mAppCompatOverrides.getAspectRatioOverrides()
                        .getFixedOrientationLetterboxAspectRatio(newParentConfig);
        // Aspect ratio as suggested by the system. Apps requested mix/max aspect ratio will
        // be respected in #applyAspectRatio.
        if (isDefaultMultiWindowLetterboxAspectRatioDesired(newParentConfig)) {
            return DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW;
        } else if (letterboxAspectRatioOverride > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO) {
            return letterboxAspectRatioOverride;
        }
        return AppCompatUtils.computeAspectRatio(parentBounds);
    }

    void applyDesiredAspectRatio(@NonNull Configuration newParentConfig, @NonNull Rect parentBounds,
            @NonNull Rect resolvedBounds, @NonNull Rect containingBoundsWithInsets,
            @NonNull Rect containingBounds) {
        final float desiredAspectRatio = getDesiredAspectRatio(newParentConfig, parentBounds);
        mAppCompatAspectRatioState.mIsAspectRatioApplied = applyAspectRatio(resolvedBounds,
                containingBoundsWithInsets, containingBounds, desiredAspectRatio);
    }

    void applyAspectRatioForLetterbox(Rect outBounds, Rect containingAppBounds,
            Rect containingBounds) {
        mAppCompatAspectRatioState.mIsAspectRatioApplied = applyAspectRatio(outBounds,
                containingAppBounds, containingBounds, 0 /* desiredAspectRatio */);
    }

    /**
     * @return {@code true} when an app compat aspect ratio has been applied.
     */
    boolean isAspectRatioApplied() {
        return mAppCompatAspectRatioState.mIsAspectRatioApplied;
    }

    /**
     * Returns the min aspect ratio of this activity.
     */
    float getMinAspectRatio() {
        if (mTransparentPolicy.isRunning()) {
            return mTransparentPolicy.getInheritedMinAspectRatio();
        }

        final ActivityInfo info = mActivityRecord.info;

        // If in camera compat mode, aspect ratio from the camera compat policy has priority over
        // the default aspect ratio.
        if (AppCompatCameraPolicy.shouldCameraCompatControlAspectRatio(mActivityRecord)) {
            return Math.max(AppCompatCameraPolicy.getCameraCompatMinAspectRatio(mActivityRecord),
                    info.getMinAspectRatio());
        }

        final AppCompatAspectRatioOverrides aspectRatioOverrides =
                mAppCompatOverrides.getAspectRatioOverrides();
        if (aspectRatioOverrides.shouldApplyUserMinAspectRatioOverride()) {
            return aspectRatioOverrides.getUserMinAspectRatio();
        }
        if (!aspectRatioOverrides.shouldOverrideMinAspectRatio()
                && !AppCompatCameraPolicy.shouldOverrideMinAspectRatioForCamera(mActivityRecord)) {
            final float minAspectRatio = info.getMinAspectRatio();
            if (minAspectRatio == 0 || mActivityRecord.isUniversalResizeable()) {
                return 0;
            }
            return minAspectRatio;
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY)
                && !ActivityInfo.isFixedOrientationPortrait(
                    mActivityRecord.getOverrideOrientation())) {
            return info.getMinAspectRatio();
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN)
                && isParentFullscreenPortrait()) {
            // We are using the parent configuration here as this is the most recent one that gets
            // passed to onConfigurationChanged when a relevant change takes place
            return info.getMinAspectRatio();
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN)) {
            return Math.max(aspectRatioOverrides.getSplitScreenAspectRatio(),
                    info.getMinAspectRatio());
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_LARGE)) {
            return Math.max(ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                    info.getMinAspectRatio());
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_MEDIUM)) {
            return Math.max(ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE,
                    info.getMinAspectRatio());
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_SMALL)) {
            return Math.max(ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL_VALUE,
                    info.getMinAspectRatio());
        }
        return info.getMinAspectRatio();
    }

    float getMaxAspectRatio() {
        if (mTransparentPolicy.isRunning()) {
            return mTransparentPolicy.getInheritedMaxAspectRatio();
        }
        final float maxAspectRatio = mActivityRecord.info.getMaxAspectRatio();
        if (maxAspectRatio == 0 || mActivityRecord.isUniversalResizeable()) {
            return 0;
        }
        return maxAspectRatio;
    }

    @Nullable
    Rect getLetterboxedContainerBounds() {
        return mAppCompatAspectRatioState.getLetterboxedContainerBounds();
    }

    /**
     * Whether this activity is letterboxed for fixed orientation. If letterboxed due to fixed
     * orientation then aspect ratio restrictions are also already respected.
     *
     * <p>This happens when an activity has fixed orientation which doesn't match orientation of the
     * parent because a display setting 'ignoreOrientationRequest' is set to true. See {@link
     * WindowManagerService#getIgnoreOrientationRequest} for more context.
     */
    boolean isLetterboxedForFixedOrientationAndAspectRatio() {
        return mAppCompatAspectRatioState.isLetterboxedForFixedOrientationAndAspectRatio();
    }

    boolean isLetterboxedForAspectRatioOnly() {
        return mAppCompatAspectRatioState.isLetterboxedForAspectRatioOnly();
    }

    void setLetterboxBoundsForFixedOrientationAndAspectRatio(@NonNull Rect bounds) {
        mAppCompatAspectRatioState.mLetterboxBoundsForFixedOrientationAndAspectRatio = bounds;
    }

    void setLetterboxBoundsForAspectRatio(@NonNull Rect bounds) {
        mAppCompatAspectRatioState.mLetterboxBoundsForAspectRatio = bounds;
    }

    /**
     * Returns true if the activity has maximum or minimum aspect ratio.
     */
    boolean hasFixedAspectRatio() {
        return getMaxAspectRatio() != 0 || getMinAspectRatio() != 0;
    }

    /**
     * Resolves aspect ratio restrictions for an activity. If the bounds are restricted by
     * aspect ratio, the position will be adjusted later in {@link #updateResolvedBoundsPosition}
     * within parent's app bounds to balance the visual appearance. The policy of aspect ratio has
     * higher priority than the requested override bounds.
     */
    void resolveAspectRatioRestrictionIfNeeded(@NonNull Configuration newParentConfiguration) {
        // If activity in fullscreen mode is letterboxed because of fixed orientation then bounds
        // are already calculated in resolveFixedOrientationConfiguration.
        // Don't apply aspect ratio if app is overridden to fullscreen by device user/manufacturer.
        if (isLetterboxedForFixedOrientationAndAspectRatio()
                || getOverrides().hasFullscreenOverride()) {
            return;
        }
        final Configuration resolvedConfig = mActivityRecord.getResolvedOverrideConfiguration();
        final Rect parentAppBounds =
                mActivityRecord.mResolveConfigHint.mParentAppBoundsOverride;
        final Rect parentBounds = newParentConfiguration.windowConfiguration.getBounds();
        final Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();
        // Use tmp bounds to calculate aspect ratio so we can know whether the activity should
        // use restricted size (resolved bounds may be the requested override bounds).
        mTmpBounds.setEmpty();
        applyAspectRatioForLetterbox(mTmpBounds, parentAppBounds, parentBounds);
        // If the out bounds is not empty, it means the activity cannot fill parent's app
        // bounds, then they should be aligned later in #updateResolvedBoundsPosition().
        if (!mTmpBounds.isEmpty()) {
            resolvedBounds.set(mTmpBounds);
        }
        if (!resolvedBounds.isEmpty() && !resolvedBounds.equals(parentBounds)) {
            // Compute the configuration based on the resolved bounds. If aspect ratio doesn't
            // restrict, the bounds should be the requested override bounds.
            // TODO(b/384473893): Improve ActivityRecord usage here.
            mActivityRecord.mResolveConfigHint.mTmpOverrideDisplayInfo =
                    mActivityRecord.getFixedRotationTransformDisplayInfo();
            mActivityRecord.computeConfigByResolveHint(resolvedConfig, newParentConfiguration);
            setLetterboxBoundsForAspectRatio(new Rect(resolvedBounds));
        }
    }

    private boolean isParentFullscreenPortrait() {
        final WindowContainer<?> parent = mActivityRecord.getParent();
        return parent != null
                && parent.getConfiguration().orientation == ORIENTATION_PORTRAIT
                && parent.getWindowConfiguration().getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }

    /**
     * Applies aspect ratio restrictions to outBounds. If no restrictions, then no change is
     * made to outBounds.
     *
     * @return {@code true} if aspect ratio restrictions were applied.
     */
    private boolean applyAspectRatio(Rect outBounds, Rect containingAppBounds,
            Rect containingBounds, float desiredAspectRatio) {
        final float maxAspectRatio = getMaxAspectRatio();
        final Task rootTask = mActivityRecord.getRootTask();
        final Task task = mActivityRecord.getTask();
        final float minAspectRatio = getMinAspectRatio();
        final TaskFragment organizedTf = mActivityRecord.getOrganizedTaskFragment();
        float aspectRatioToApply = desiredAspectRatio;
        if (task == null || rootTask == null
                || (maxAspectRatio < 1 && minAspectRatio < 1 && aspectRatioToApply < 1)
                // Don't set aspect ratio if we are in VR mode.
                || AppCompatUtils.isInVrUiMode(mActivityRecord.getConfiguration())
                // TODO(b/232898850): Always respect aspect ratio requests.
                // Don't set aspect ratio for activity in ActivityEmbedding split.
                || (organizedTf != null && !organizedTf.fillsParent())
                // Don't set aspect ratio for resizeable activities in freeform.
                // {@link ActivityRecord#shouldCreateAppCompatDisplayInsets()} will be false for
                // both activities that are naturally resizeable and activities that have been
                // forced resizeable.
                // Camera compat mode is an exception to this, where the activity is letterboxed
                // to an aspect ratio commonly found on phones, e.g. 16:9, to avoid issues like
                // stretching of the camera preview.
                || (Flags.ignoreAspectRatioRestrictionsForResizeableFreeformActivities()
                    && task.getWindowingMode() == WINDOWING_MODE_FREEFORM
                    && !mActivityRecord.shouldCreateAppCompatDisplayInsets()
                    && !AppCompatCameraPolicy.shouldCameraCompatControlAspectRatio(
                            mActivityRecord))) {
            return false;
        }

        final int containingAppWidth = containingAppBounds.width();
        final int containingAppHeight = containingAppBounds.height();
        final float containingRatio = AppCompatUtils.computeAspectRatio(containingAppBounds);

        if (aspectRatioToApply < 1) {
            aspectRatioToApply = containingRatio;
        }

        if (maxAspectRatio >= 1 && aspectRatioToApply > maxAspectRatio) {
            aspectRatioToApply = maxAspectRatio;
        } else if (minAspectRatio >= 1 && aspectRatioToApply < minAspectRatio) {
            aspectRatioToApply = minAspectRatio;
        }

        int activityWidth = containingAppWidth;
        int activityHeight = containingAppHeight;

        if (containingRatio - aspectRatioToApply > ASPECT_RATIO_ROUNDING_TOLERANCE) {
            if (containingAppWidth < containingAppHeight) {
                // Width is the shorter side, so we use that to figure-out what the max. height
                // should be given the aspect ratio.
                activityHeight = (int) ((activityWidth * aspectRatioToApply) + 0.5f);
            } else {
                // Height is the shorter side, so we use that to figure-out what the max. width
                // should be given the aspect ratio.
                activityWidth = (int) ((activityHeight * aspectRatioToApply) + 0.5f);
            }
        } else if (aspectRatioToApply - containingRatio > ASPECT_RATIO_ROUNDING_TOLERANCE) {
            boolean adjustWidth;
            switch (mActivityRecord.getRequestedConfigurationOrientation()) {
                case ORIENTATION_LANDSCAPE:
                    // Width should be the longer side for this landscape app, so we use the width
                    // to figure-out what the max. height should be given the aspect ratio.
                    adjustWidth = false;
                    break;
                case ORIENTATION_PORTRAIT:
                    // Height should be the longer side for this portrait app, so we use the height
                    // to figure-out what the max. width should be given the aspect ratio.
                    adjustWidth = true;
                    break;
                default:
                    // This app doesn't have a preferred orientation, so we keep the length of the
                    // longer side, and use it to figure-out the length of the shorter side.
                    if (containingAppWidth < containingAppHeight) {
                        // Width is the shorter side, so we use the height to figure-out what the
                        // max. width should be given the aspect ratio.
                        adjustWidth = true;
                    } else {
                        // Height is the shorter side, so we use the width to figure-out what the
                        // max. height should be given the aspect ratio.
                        adjustWidth = false;
                    }
                    break;
            }
            if (adjustWidth) {
                activityWidth = (int) ((activityHeight / aspectRatioToApply) + 0.5f);
            } else {
                activityHeight = (int) ((activityWidth / aspectRatioToApply) + 0.5f);
            }
        }

        if (containingAppWidth <= activityWidth && containingAppHeight <= activityHeight) {
            // The display matches or is less than the activity aspect ratio, so nothing else to do.
            return false;
        }

        // Compute configuration based on max or min supported width and height.
        // Also account for the insets (e.g. display cutouts, navigation bar), which will be
        // clipped away later in {@link Task#computeConfigResourceOverrides()}, i.e., the out
        // bounds are the app bounds restricted by aspect ratio + clippable insets. Otherwise,
        // the app bounds would end up too small. To achieve this we will also add clippable insets
        // when the corresponding dimension fully fills the parent

        int right = activityWidth + containingAppBounds.left;
        int left = containingAppBounds.left;
        if (right >= containingAppBounds.right) {
            right = containingBounds.right;
            left = containingBounds.left;
        }
        int bottom = activityHeight + containingAppBounds.top;
        int top = containingAppBounds.top;
        if (bottom >= containingAppBounds.bottom) {
            bottom = containingBounds.bottom;
            top = containingBounds.top;
        }
        outBounds.set(left, top, right, bottom);
        return true;
    }

    /**
     * Returns {@code true} if the default aspect ratio for a letterboxed app in multi-window mode
     * should be used.
     */
    private boolean isDefaultMultiWindowLetterboxAspectRatioDesired(
            @NonNull Configuration parentConfig) {
        final DisplayContent dc = mActivityRecord.mDisplayContent;
        if (dc == null) {
            return false;
        }
        final int windowingMode = parentConfig.windowConfiguration.getWindowingMode();
        return WindowConfiguration.inMultiWindowMode(windowingMode)
                && !dc.getIgnoreOrientationRequest();
    }

    @NonNull
    private AppCompatAspectRatioOverrides getOverrides() {
        return mActivityRecord.mAppCompatController.getAspectRatioOverrides();
    }

    private static class AppCompatAspectRatioState {
        // Whether the aspect ratio restrictions applied to the activity bounds
        // in applyAspectRatio().
        private boolean mIsAspectRatioApplied = false;

        // Bounds populated in resolveAspectRatioRestriction when this activity is letterboxed for
        // aspect ratio. If not null, they are used as parent container in
        // resolveSizeCompatModeConfiguration and in a constructor of CompatDisplayInsets.
        @Nullable
        private Rect mLetterboxBoundsForAspectRatio;
        // Bounds populated in resolveFixedOrientationConfiguration when this activity is
        // letterboxed for fixed orientation. If not null, they are used as parent container in
        // resolveSizeCompatModeConfiguration and in a constructor of CompatDisplayInsets. If
        // letterboxed due to fixed orientation then aspect ratio restrictions are also respected.
        // This happens when an activity has fixed orientation which doesn't match orientation of
        // the parent because a display is ignoring orientation request or fixed to user rotation.
        // See WindowManagerService#getIgnoreOrientationRequest and
        // WindowManagerService#getFixedToUserRotation for more context.
        @Nullable
        private Rect mLetterboxBoundsForFixedOrientationAndAspectRatio;

        @Nullable
        Rect getLetterboxedContainerBounds() {
            return mLetterboxBoundsForFixedOrientationAndAspectRatio != null
                    ? mLetterboxBoundsForFixedOrientationAndAspectRatio
                    : mLetterboxBoundsForAspectRatio;
        }

        void reset() {
            mIsAspectRatioApplied = false;
            mLetterboxBoundsForFixedOrientationAndAspectRatio = null;
            mLetterboxBoundsForAspectRatio = null;
        }

        boolean isLetterboxedForFixedOrientationAndAspectRatio() {
            return mLetterboxBoundsForFixedOrientationAndAspectRatio != null;
        }

        boolean isLetterboxedForAspectRatioOnly() {
            return mLetterboxBoundsForAspectRatio != null;
        }
    }
}
