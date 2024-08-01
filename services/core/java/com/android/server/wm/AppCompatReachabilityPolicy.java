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

import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__BOTTOM_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_BOTTOM;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_LEFT;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_RIGHT;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_TOP;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__LEFT_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__RIGHT_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__TOP_TO_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;

import android.annotation.NonNull;
import android.graphics.Rect;

import com.android.internal.annotations.VisibleForTesting;

import java.util.function.Supplier;

/**
 * Encapsulate logic about app compat reachability.
 */
class AppCompatReachabilityPolicy {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final AppCompatConfiguration mAppCompatConfiguration;

    AppCompatReachabilityPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration) {
        mActivityRecord = activityRecord;
        mAppCompatConfiguration = appCompatConfiguration;
    }

    @VisibleForTesting
    void handleHorizontalDoubleTap(int x, @NonNull Supplier<Rect> innerFrameSupplier) {
        final AppCompatReachabilityOverrides reachabilityOverrides =
                mActivityRecord.mAppCompatController.getAppCompatReachabilityOverrides();
        if (!reachabilityOverrides.isHorizontalReachabilityEnabled()
                || mActivityRecord.isInTransition()) {
            return;
        }
        final Rect letterboxInnerFrame = innerFrameSupplier.get();
        if (letterboxInnerFrame.left <= x && letterboxInnerFrame.right >= x) {
            // Only react to clicks at the sides of the letterboxed app window.
            return;
        }
        final AppCompatDeviceStateQuery deviceStateQuery = mActivityRecord.mAppCompatController
                .getAppCompatDeviceStateQuery();
        final boolean isInFullScreenBookMode = deviceStateQuery
                    .isDisplayFullScreenAndSeparatingHinge()
                && mAppCompatConfiguration.getIsAutomaticReachabilityInBookModeEnabled();
        final int letterboxPositionForHorizontalReachability = mAppCompatConfiguration
                .getLetterboxPositionForHorizontalReachability(isInFullScreenBookMode);
        if (letterboxInnerFrame.left > x) {
            // Moving to the next stop on the left side of the app window: right > center > left.
            mAppCompatConfiguration.movePositionForHorizontalReachabilityToNextLeftStop(
                    isInFullScreenBookMode);
            int letterboxPositionChangeForLog =
                    letterboxPositionForHorizontalReachability
                            == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_LEFT
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__RIGHT_TO_CENTER;
            logLetterboxPositionChange(letterboxPositionChangeForLog);
            reachabilityOverrides.setDoubleTapEvent();
        } else if (letterboxInnerFrame.right < x) {
            // Moving to the next stop on the right side of the app window: left > center > right.
            mAppCompatConfiguration.movePositionForHorizontalReachabilityToNextRightStop(
                    isInFullScreenBookMode);
            final int letterboxPositionChangeForLog =
                    letterboxPositionForHorizontalReachability
                            == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_RIGHT
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__LEFT_TO_CENTER;
            logLetterboxPositionChange(letterboxPositionChangeForLog);
            reachabilityOverrides.setDoubleTapEvent();
        }
        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    @VisibleForTesting
    void handleVerticalDoubleTap(int y, @NonNull Supplier<Rect> innerFrameSupplier) {
        final AppCompatReachabilityOverrides reachabilityOverrides =
                mActivityRecord.mAppCompatController.getAppCompatReachabilityOverrides();
        if (!reachabilityOverrides.isVerticalReachabilityEnabled()
                || mActivityRecord.isInTransition()) {
            return;
        }
        final Rect letterboxInnerFrame = innerFrameSupplier.get();
        if (letterboxInnerFrame.top <= y && letterboxInnerFrame.bottom >= y) {
            // Only react to clicks at the top and bottom of the letterboxed app window.
            return;
        }
        final AppCompatDeviceStateQuery deviceStateQuery = mActivityRecord.mAppCompatController
                .getAppCompatDeviceStateQuery();
        final boolean isInFullScreenTabletopMode = deviceStateQuery
                .isDisplayFullScreenAndSeparatingHinge();
        final int letterboxPositionForVerticalReachability = mAppCompatConfiguration
                .getLetterboxPositionForVerticalReachability(isInFullScreenTabletopMode);
        if (letterboxInnerFrame.top > y) {
            // Moving to the next stop on the top side of the app window: bottom > center > top.
            mAppCompatConfiguration.movePositionForVerticalReachabilityToNextTopStop(
                    isInFullScreenTabletopMode);
            final int letterboxPositionChangeForLog =
                    letterboxPositionForVerticalReachability
                            == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_TOP
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__BOTTOM_TO_CENTER;
            logLetterboxPositionChange(letterboxPositionChangeForLog);
            reachabilityOverrides.setDoubleTapEvent();
        } else if (letterboxInnerFrame.bottom < y) {
            // Moving to the next stop on the bottom side of the app window: top > center > bottom.
            mAppCompatConfiguration.movePositionForVerticalReachabilityToNextBottomStop(
                    isInFullScreenTabletopMode);
            final int letterboxPositionChangeForLog =
                    letterboxPositionForVerticalReachability
                            == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_BOTTOM
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__TOP_TO_CENTER;
            logLetterboxPositionChange(letterboxPositionChangeForLog);
            reachabilityOverrides.setDoubleTapEvent();
        }
        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    /**
     * Logs letterbox position changes via {@link ActivityMetricsLogger#logLetterboxPositionChange}.
     */
    private void logLetterboxPositionChange(int letterboxPositionChangeForLog) {
        mActivityRecord.mTaskSupervisor.getActivityMetricsLogger()
                .logLetterboxPositionChange(mActivityRecord, letterboxPositionChangeForLog);
    }
}
