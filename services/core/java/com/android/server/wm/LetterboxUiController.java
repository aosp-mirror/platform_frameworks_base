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

import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;
import static com.android.server.wm.AppCompatConfiguration.letterboxBackgroundTypeToString;

import android.annotation.NonNull;

import java.io.PrintWriter;

/** Controls behaviour of the letterbox UI for {@link mActivityRecord}. */
// TODO(b/185262487): Improve test coverage of this class. Parts of it are tested in
// SizeCompatTests and LetterboxTests but not all.
final class LetterboxUiController {

    private final AppCompatConfiguration mAppCompatConfiguration;

    private final ActivityRecord mActivityRecord;

    // TODO(b/356385137): Remove these we added to make dependencies temporarily explicit.
    @NonNull
    private final AppCompatReachabilityOverrides mAppCompatReachabilityOverrides;
    @NonNull
    private final AppCompatLetterboxPolicy mAppCompatLetterboxPolicy;
    @NonNull
    private final AppCompatLetterboxOverrides mAppCompatLetterboxOverrides;

    LetterboxUiController(WindowManagerService wmService, ActivityRecord activityRecord) {
        mAppCompatConfiguration = wmService.mAppCompatConfiguration;
        // Given activityRecord may not be fully constructed since LetterboxUiController
        // is created in its constructor. It shouldn't be used in this constructor but it's safe
        // to use it after since controller is only used in ActivityRecord.
        mActivityRecord = activityRecord;
        // TODO(b/356385137): Remove these we added to make dependencies temporarily explicit.
        mAppCompatReachabilityOverrides = mActivityRecord.mAppCompatController
                .getAppCompatReachabilityOverrides();
        mAppCompatLetterboxPolicy = mActivityRecord.mAppCompatController
                .getAppCompatLetterboxPolicy();
        mAppCompatLetterboxOverrides = mActivityRecord.mAppCompatController
                .getAppCompatLetterboxOverrides();
    }

    void dump(PrintWriter pw, String prefix) {
        final WindowState mainWin = mActivityRecord.findMainWindow();
        if (mainWin == null) {
            return;
        }

        pw.println(prefix + "isTransparentPolicyRunning="
                + mActivityRecord.mAppCompatController.getTransparentPolicy().isRunning());

        boolean areBoundsLetterboxed = mainWin.areAppWindowBoundsLetterboxed();
        pw.println(prefix + "areBoundsLetterboxed=" + areBoundsLetterboxed);
        if (!areBoundsLetterboxed) {
            return;
        }

        pw.println(prefix + "  letterboxReason="
                + AppCompatUtils.getLetterboxReasonString(mActivityRecord, mainWin));
        pw.println(prefix + "  activityAspectRatio="
                + AppCompatUtils.computeAspectRatio(mActivityRecord.getBounds()));

        boolean shouldShowLetterboxUi = mAppCompatLetterboxPolicy.shouldShowLetterboxUi(mainWin);
        pw.println(prefix + "shouldShowLetterboxUi=" + shouldShowLetterboxUi);

        if (!shouldShowLetterboxUi) {
            return;
        }
        pw.println(prefix + "  isVerticalThinLetterboxed="
                + mAppCompatReachabilityOverrides.isVerticalThinLetterboxed());
        pw.println(prefix + "  isHorizontalThinLetterboxed="
                + mAppCompatReachabilityOverrides.isHorizontalThinLetterboxed());
        pw.println(prefix + "  letterboxBackgroundColor=" + Integer.toHexString(
                mAppCompatLetterboxOverrides.getLetterboxBackgroundColor().toArgb()));
        pw.println(prefix + "  letterboxBackgroundType="
                + letterboxBackgroundTypeToString(
                        mAppCompatConfiguration.getLetterboxBackgroundType()));
        pw.println(prefix + "  letterboxCornerRadius="
                + mAppCompatLetterboxPolicy.getRoundedCornersRadius(mainWin));
        if (mAppCompatConfiguration.getLetterboxBackgroundType()
                == LETTERBOX_BACKGROUND_WALLPAPER) {
            pw.println(prefix + "  isLetterboxWallpaperBlurSupported="
                    + mAppCompatLetterboxOverrides.isLetterboxWallpaperBlurSupported());
            pw.println(prefix + "  letterboxBackgroundWallpaperDarkScrimAlpha="
                    + mAppCompatLetterboxOverrides.getLetterboxWallpaperDarkScrimAlpha());
            pw.println(prefix + "  letterboxBackgroundWallpaperBlurRadius="
                    + mAppCompatLetterboxOverrides.getLetterboxWallpaperBlurRadiusPx());
        }
        final AppCompatReachabilityOverrides reachabilityOverrides = mActivityRecord
                .mAppCompatController.getAppCompatReachabilityOverrides();
        pw.println(prefix + "  isHorizontalReachabilityEnabled="
                + reachabilityOverrides.isHorizontalReachabilityEnabled());
        pw.println(prefix + "  isVerticalReachabilityEnabled="
                + reachabilityOverrides.isVerticalReachabilityEnabled());
        pw.println(prefix + "  letterboxHorizontalPositionMultiplier="
                + mAppCompatReachabilityOverrides.getHorizontalPositionMultiplier(mActivityRecord
                    .getParent().getConfiguration()));
        pw.println(prefix + "  letterboxVerticalPositionMultiplier="
                + mAppCompatReachabilityOverrides.getVerticalPositionMultiplier(mActivityRecord
                    .getParent().getConfiguration()));
        pw.println(prefix + "  letterboxPositionForHorizontalReachability="
                + AppCompatConfiguration.letterboxHorizontalReachabilityPositionToString(
                mAppCompatConfiguration.getLetterboxPositionForHorizontalReachability(false)));
        pw.println(prefix + "  letterboxPositionForVerticalReachability="
                + AppCompatConfiguration.letterboxVerticalReachabilityPositionToString(
                mAppCompatConfiguration.getLetterboxPositionForVerticalReachability(false)));
        pw.println(prefix + "  fixedOrientationLetterboxAspectRatio="
                + mAppCompatConfiguration.getFixedOrientationLetterboxAspectRatio());
        pw.println(prefix + "  defaultMinAspectRatioForUnresizableApps="
                + mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps());
        pw.println(prefix + "  isSplitScreenAspectRatioForUnresizableAppsEnabled="
                + mAppCompatConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled());
        pw.println(prefix + "  isDisplayAspectRatioEnabledForFixedOrientationLetterbox="
                + mAppCompatConfiguration
                .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox());
    }
}
