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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__BOTTOM;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__LEFT;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__RIGHT;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__TOP;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__UNKNOWN_POSITION;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.window.flags.Flags;

/**
 * Encapsulate overrides and configurations about app compat reachability.
 */
class AppCompatReachabilityOverrides {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final AppCompatConfiguration mAppCompatConfiguration;
    @NonNull
    private final AppCompatDeviceStateQuery mAppCompatDeviceStateQuery;
    @NonNull
    private final ReachabilityState mReachabilityState;

    AppCompatReachabilityOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration,
            @NonNull AppCompatDeviceStateQuery appCompatDeviceStateQuery) {
        mActivityRecord = activityRecord;
        mAppCompatConfiguration = appCompatConfiguration;
        mAppCompatDeviceStateQuery = appCompatDeviceStateQuery;
        mReachabilityState = new ReachabilityState();
    }

    boolean isFromDoubleTap() {
        return mReachabilityState.isFromDoubleTap();
    }

    boolean isDoubleTapEvent() {
        return mReachabilityState.mIsDoubleTapEvent;
    }

    void setDoubleTapEvent() {
        mReachabilityState.mIsDoubleTapEvent = true;
    }

    /**
     * Provides the multiplier to use when calculating the position of a letterboxed app after
     * an horizontal reachability event (double tap). The method takes the current state of the
     * device (e.g. device in book mode) into account.
     * </p>
     * @param parentConfiguration The parent {@link Configuration}.
     * @return The value to use for calculating the letterbox horizontal position.
     */
    float getHorizontalPositionMultiplier(@NonNull Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean bookModeEnabled = isFullScreenAndBookModeEnabled();
        return isHorizontalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mAppCompatConfiguration.getHorizontalMultiplierForReachability(bookModeEnabled)
                : mAppCompatConfiguration.getLetterboxHorizontalPositionMultiplier(bookModeEnabled);
    }

    /**
     * Provides the multiplier to use when calculating the position of a letterboxed app after
     * a vertical reachability event (double tap). The method takes the current state of the
     * device (e.g. device posture) into account.
     * </p>
     * @param parentConfiguration The parent {@link Configuration}.
     * @return The value to use for calculating the letterbox horizontal position.
     */
    float getVerticalPositionMultiplier(@NonNull Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean tabletopMode = mAppCompatDeviceStateQuery
                .isDisplayFullScreenAndInPosture(/* isTabletop */ true);
        return isVerticalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mAppCompatConfiguration.getVerticalMultiplierForReachability(tabletopMode)
                : mAppCompatConfiguration.getLetterboxVerticalPositionMultiplier(tabletopMode);
    }

    boolean isHorizontalReachabilityEnabled() {
        return isHorizontalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    boolean isVerticalReachabilityEnabled() {
        return isVerticalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    boolean isLetterboxDoubleTapEducationEnabled() {
        return isHorizontalReachabilityEnabled() || isVerticalReachabilityEnabled();
    }

    @AppCompatConfiguration.LetterboxVerticalReachabilityPosition
    int getLetterboxPositionForVerticalReachability() {
        final boolean isInFullScreenTabletopMode =
                mAppCompatDeviceStateQuery.isDisplayFullScreenAndSeparatingHinge();
        return mAppCompatConfiguration.getLetterboxPositionForVerticalReachability(
                isInFullScreenTabletopMode);
    }

    @AppCompatConfiguration.LetterboxHorizontalReachabilityPosition
    int getLetterboxPositionForHorizontalReachability() {
        final boolean isInFullScreenBookMode = isFullScreenAndBookModeEnabled();
        return mAppCompatConfiguration.getLetterboxPositionForHorizontalReachability(
                isInFullScreenBookMode);
    }

    int getLetterboxPositionForLogging() {
        int positionToLog = APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__UNKNOWN_POSITION;
        if (isHorizontalReachabilityEnabled()) {
            int letterboxPositionForHorizontalReachability = mAppCompatConfiguration
                    .getLetterboxPositionForHorizontalReachability(mAppCompatDeviceStateQuery
                            .isDisplayFullScreenAndInPosture(/* isTabletop */ false));
            positionToLog = letterboxHorizontalReachabilityPositionToLetterboxPositionForLogging(
                    letterboxPositionForHorizontalReachability);
        } else if (isVerticalReachabilityEnabled()) {
            int letterboxPositionForVerticalReachability = mAppCompatConfiguration
                    .getLetterboxPositionForVerticalReachability(mAppCompatDeviceStateQuery
                            .isDisplayFullScreenAndInPosture(/* isTabletop */ true));
            positionToLog = letterboxVerticalReachabilityPositionToLetterboxPositionForLogging(
                    letterboxPositionForVerticalReachability);
        }
        return positionToLog;
    }

    /**
     * @return {@value true} if the vertical reachability should be allowed in case of
     * thin letterboxing.
     */
    boolean allowVerticalReachabilityForThinLetterbox() {
        if (!Flags.disableThinLetterboxingPolicy()) {
            return true;
        }
        // When the flag is enabled we allow vertical reachability only if the
        // app is not thin letterboxed vertically.
        return !isVerticalThinLetterboxed();
    }

    /**
     * @return {@value true} if the horizontal reachability should be enabled in case of
     * thin letterboxing.
     */
    boolean allowHorizontalReachabilityForThinLetterbox() {
        if (!Flags.disableThinLetterboxingPolicy()) {
            return true;
        }
        // When the flag is enabled we allow horizontal reachability only if the
        // app is not thin pillarboxed.
        return !isHorizontalThinLetterboxed();
    }

    /**
     * @return {@value true} if the resulting app is letterboxed in a way defined as thin.
     */
    boolean isVerticalThinLetterboxed() {
        final int thinHeight = mAppCompatConfiguration.getThinLetterboxHeightPx();
        if (thinHeight < 0) {
            return false;
        }
        final Task task = mActivityRecord.getTask();
        if (task == null) {
            return false;
        }
        final int padding = Math.abs(
                task.getBounds().height() - mActivityRecord.getBounds().height()) / 2;
        return padding <= thinHeight;
    }

    /**
     * @return {@value true} if the resulting app is pillarboxed in a way defined as thin.
     */
    boolean isHorizontalThinLetterboxed() {
        final int thinWidth = mAppCompatConfiguration.getThinLetterboxWidthPx();
        if (thinWidth < 0) {
            return false;
        }
        final Task task = mActivityRecord.getTask();
        if (task == null) {
            return false;
        }
        final int padding = Math.abs(
                task.getBounds().width() - mActivityRecord.getBounds().width()) / 2;
        return padding <= thinWidth;
    }

    // Note that we check the task rather than the parent as with ActivityEmbedding the parent might
    // be a TaskFragment, and its windowing mode is always MULTI_WINDOW, even if the task is
    // actually fullscreen.
    private boolean isDisplayFullScreenAndSeparatingHinge() {
        Task task = mActivityRecord.getTask();
        return mActivityRecord.mDisplayContent != null
                && mActivityRecord.mDisplayContent.getDisplayRotation().isDisplaySeparatingHinge()
                && task != null
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }

    private int letterboxHorizontalReachabilityPositionToLetterboxPositionForLogging(
            @AppCompatConfiguration.LetterboxHorizontalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__LEFT;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__RIGHT;
            default:
                throw new AssertionError(
                        "Unexpected letterbox horizontal reachability position type: "
                                + position);
        }
    }

    private int letterboxVerticalReachabilityPositionToLetterboxPositionForLogging(
            @AppCompatConfiguration.LetterboxVerticalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__TOP;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__BOTTOM;
            default:
                throw new AssertionError(
                        "Unexpected letterbox vertical reachability position type: "
                                + position);
        }
    }

    private boolean isFullScreenAndBookModeEnabled() {
        return mAppCompatDeviceStateQuery.isDisplayFullScreenAndInPosture(/* isTabletop */ false)
                && mAppCompatConfiguration.getIsAutomaticReachabilityInBookModeEnabled();
    }

    /**
     * Whether horizontal reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Windowing mode is fullscreen.
     *   <li>Horizontal Reachability is enabled.
     *   <li>First top opaque activity fills parent vertically, but not horizontally.
     * </ul>
     */
    private boolean isHorizontalReachabilityEnabled(@NonNull Configuration parentConfiguration) {
        if (!allowHorizontalReachabilityForThinLetterbox()) {
            return false;
        }
        final Rect parentAppBoundsOverride = mActivityRecord.getParentAppBoundsOverride();
        final Rect parentAppBounds = parentAppBoundsOverride != null
                ? parentAppBoundsOverride : parentConfiguration.windowConfiguration.getAppBounds();
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty
        final Rect opaqueActivityBounds = mActivityRecord.mAppCompatController
                .getTransparentPolicy().getFirstOpaqueActivity()
                .map(ActivityRecord::getScreenResolvedBounds)
                .orElse(mActivityRecord.getScreenResolvedBounds());
        return mAppCompatConfiguration.getIsHorizontalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                == WINDOWING_MODE_FULLSCREEN
                // Check whether the activity fills the parent vertically.
                && parentAppBounds.height() <= opaqueActivityBounds.height()
                && parentAppBounds.width() > opaqueActivityBounds.width();
    }

    /**
     * Whether vertical reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Windowing mode is fullscreen.
     *   <li>Vertical Reachability is enabled.
     *   <li>First top opaque activity fills parent horizontally but not vertically.
     * </ul>
     */
    private boolean isVerticalReachabilityEnabled(@NonNull Configuration parentConfiguration) {
        if (!allowVerticalReachabilityForThinLetterbox()) {
            return false;
        }
        final Rect parentAppBoundsOverride = mActivityRecord.getParentAppBoundsOverride();
        final Rect parentAppBounds = parentAppBoundsOverride != null
                ? parentAppBoundsOverride : parentConfiguration.windowConfiguration.getAppBounds();
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty.
        final Rect opaqueActivityBounds = mActivityRecord.mAppCompatController
                .getTransparentPolicy().getFirstOpaqueActivity()
                .map(ActivityRecord::getScreenResolvedBounds)
                .orElse(mActivityRecord.getScreenResolvedBounds());
        return mAppCompatConfiguration.getIsVerticalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                    == WINDOWING_MODE_FULLSCREEN
                // Check whether the activity fills the parent horizontally.
                && parentAppBounds.width() <= opaqueActivityBounds.width()
                && parentAppBounds.height() > opaqueActivityBounds.height();
    }

    private static class ReachabilityState {
        // If the current event is a double tap.
        private boolean mIsDoubleTapEvent;

        boolean isFromDoubleTap() {
            final boolean isFromDoubleTap = mIsDoubleTapEvent;
            mIsDoubleTapEvent = false;
            return isFromDoubleTap;
        }
    }

}
