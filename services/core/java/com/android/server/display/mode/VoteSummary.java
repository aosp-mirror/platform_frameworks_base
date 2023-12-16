/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.mode;

import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.List;

final class VoteSummary {
    private static final float FLOAT_TOLERANCE = SurfaceControl.RefreshRateRange.FLOAT_TOLERANCE;
    private static final String TAG = "VoteSummary";

    public float minPhysicalRefreshRate;
    public float maxPhysicalRefreshRate;
    public float minRenderFrameRate;
    public float maxRenderFrameRate;
    public int width;
    public int height;
    public int minWidth;
    public int minHeight;
    public boolean disableRefreshRateSwitching;
    public float appRequestBaseModeRefreshRate;

    public List<SupportedModesVote.SupportedMode> supportedModes;

    final boolean mIsDisplayResolutionRangeVotingEnabled;

    private final boolean mSupportedModesVoteEnabled;
    private final boolean mSupportsFrameRateOverride;
    private final boolean mLoggingEnabled;

    VoteSummary(boolean isDisplayResolutionRangeVotingEnabled, boolean supportedModesVoteEnabled,
            boolean loggingEnabled, boolean supportsFrameRateOverride) {
        mIsDisplayResolutionRangeVotingEnabled = isDisplayResolutionRangeVotingEnabled;
        mSupportedModesVoteEnabled = supportedModesVoteEnabled;
        mLoggingEnabled = loggingEnabled;
        mSupportsFrameRateOverride = supportsFrameRateOverride;
        reset();
    }

    void applyVotes(SparseArray<Vote> votes,
            int lowestConsideredPriority, int highestConsideredPriority) {
        reset();
        for (int priority = highestConsideredPriority;
                priority >= lowestConsideredPriority;
                priority--) {
            Vote vote = votes.get(priority);
            if (vote == null) {
                continue;
            }
            vote.updateSummary(this);
        }
        if (mLoggingEnabled) {
            Slog.i(TAG, "applyVotes for range ["
                    + Vote.priorityToString(lowestConsideredPriority) + ", "
                    + Vote.priorityToString(highestConsideredPriority) + "]: "
                    + this);
        }
    }

    void adjustSize(Display.Mode defaultMode, Display.Mode[] modes) {
        // If we don't have anything specifying the width / height of the display, just use
        // the default width and height. We don't want these switching out from underneath
        // us since it's a pretty disruptive behavior.
        if (height == Vote.INVALID_SIZE || width == Vote.INVALID_SIZE) {
            width = defaultMode.getPhysicalWidth();
            height = defaultMode.getPhysicalHeight();
        } else if (mIsDisplayResolutionRangeVotingEnabled) {
            updateSummaryWithBestAllowedResolution(modes);
        }
        if (mLoggingEnabled) {
            Slog.i(TAG, "adjustSize: " + this);
        }
    }

    void limitRefreshRanges(VoteSummary otherSummary) {
        minPhysicalRefreshRate =
                Math.min(minPhysicalRefreshRate, otherSummary.minPhysicalRefreshRate);
        maxPhysicalRefreshRate =
                Math.max(maxPhysicalRefreshRate, otherSummary.maxPhysicalRefreshRate);
        minRenderFrameRate = Math.min(minRenderFrameRate, otherSummary.minRenderFrameRate);
        maxRenderFrameRate = Math.max(maxRenderFrameRate, otherSummary.maxRenderFrameRate);

        if (mLoggingEnabled) {
            Slog.i(TAG, "limitRefreshRanges: " + this);
        }
    }

    List<Display.Mode> filterModes(Display.Mode[] modes) {
        if (!isValid()) {
            return new ArrayList<>();
        }
        ArrayList<Display.Mode> availableModes = new ArrayList<>();
        boolean missingBaseModeRefreshRate = appRequestBaseModeRefreshRate > 0f;

        for (Display.Mode mode : modes) {
            if (!validateModeSupported(mode)) {
                continue;
            }
            if (!validateModeSize(mode)) {
                continue;
            }
            if (!validateModeWithinPhysicalRefreshRange(mode)) {
                continue;
            }
            if (!validateModeWithinRenderRefreshRange(mode)) {
                continue;
            }
            if (!validateModeRenderRateAchievable(mode)) {
                continue;
            }
            availableModes.add(mode);
            if (equalsWithinFloatTolerance(mode.getRefreshRate(), appRequestBaseModeRefreshRate)) {
                missingBaseModeRefreshRate = false;
            }
        }
        if (missingBaseModeRefreshRate) {
            return new ArrayList<>();
        }

        return availableModes;
    }

    Display.Mode selectBaseMode(List<Display.Mode> availableModes, Display.Mode defaultMode) {
        // The base mode should be as close as possible to the app requested mode. Since all the
        // available modes already have the same size, we just need to look for a matching refresh
        // rate. If the summary doesn't include an app requested refresh rate, we'll use the default
        // mode refresh rate. This is important because SurfaceFlinger can do only seamless switches
        // by default. Some devices (e.g. TV) don't support seamless switching so the mode we select
        // here won't be changed.
        float preferredRefreshRate =
                appRequestBaseModeRefreshRate > 0
                        ? appRequestBaseModeRefreshRate : defaultMode.getRefreshRate();
        for (Display.Mode availableMode : availableModes) {
            if (equalsWithinFloatTolerance(preferredRefreshRate, availableMode.getRefreshRate())) {
                return availableMode;
            }
        }

        // If we couldn't find a mode id based on the refresh rate, it means that the available
        // modes were filtered by the app requested size, which is different that the default mode
        // size, and the requested app refresh rate was dropped from the summary due to a higher
        // priority vote. Since we don't have any other hint about the refresh rate,
        // we just pick the first.
        return !availableModes.isEmpty() ? availableModes.get(0) : null;
    }

    void disableModeSwitching(float fps) {
        minPhysicalRefreshRate = maxPhysicalRefreshRate = fps;
        maxRenderFrameRate = Math.min(maxRenderFrameRate, fps);

        if (mLoggingEnabled) {
            Slog.i(TAG, "Disabled mode switching on summary: " + this);
        }
    }

    void disableRenderRateSwitching(float fps) {
        minRenderFrameRate = maxRenderFrameRate;

        if (!isRenderRateAchievable(fps)) {
            minRenderFrameRate = maxRenderFrameRate = fps;
        }

        if (mLoggingEnabled) {
            Slog.i(TAG, "Disabled render rate switching on summary: " + this);
        }
    }
    private boolean validateModeSize(Display.Mode mode) {
        if (mode.getPhysicalWidth() != width
                || mode.getPhysicalHeight() != height) {
            if (mLoggingEnabled) {
                Slog.w(TAG, "Discarding mode " + mode.getModeId() + ", wrong size"
                        + ": desiredWidth=" + width
                        + ": desiredHeight=" + height
                        + ": actualWidth=" + mode.getPhysicalWidth()
                        + ": actualHeight=" + mode.getPhysicalHeight());
            }
            return false;
        }
        return true;
    }

    private boolean validateModeWithinPhysicalRefreshRange(Display.Mode mode) {
        float refreshRate = mode.getRefreshRate();
        // Some refresh rates are calculated based on frame timings, so they aren't *exactly*
        // equal to expected refresh rate. Given that, we apply a bit of tolerance to this
        // comparison.
        if (refreshRate < (minPhysicalRefreshRate - FLOAT_TOLERANCE)
                || refreshRate > (maxPhysicalRefreshRate + FLOAT_TOLERANCE)) {
            if (mLoggingEnabled) {
                Slog.w(TAG, "Discarding mode " + mode.getModeId()
                        + ", outside refresh rate bounds"
                        + ": minPhysicalRefreshRate=" + minPhysicalRefreshRate
                        + ", maxPhysicalRefreshRate=" + maxPhysicalRefreshRate
                        + ", modeRefreshRate=" + refreshRate);
            }
            return false;
        }
        return true;
    }

    private boolean validateModeWithinRenderRefreshRange(Display.Mode mode) {
        float refreshRate = mode.getRefreshRate();
        // The physical refresh rate must be in the render frame rate range, unless
        // frame rate override is supported.
        if (!mSupportsFrameRateOverride) {
            if (refreshRate < (minRenderFrameRate - FLOAT_TOLERANCE)
                    || refreshRate > (maxRenderFrameRate + FLOAT_TOLERANCE)) {
                if (mLoggingEnabled) {
                    Slog.w(TAG, "Discarding mode " + mode.getModeId()
                            + ", outside render rate bounds"
                            + ": minRenderFrameRate=" + minRenderFrameRate
                            + ", maxRenderFrameRate=" + maxRenderFrameRate
                            + ", modeRefreshRate=" + refreshRate);
                }
                return false;
            }
        }
        return true;
    }

    private boolean validateModeRenderRateAchievable(Display.Mode mode) {
        float refreshRate = mode.getRefreshRate();
        if (!isRenderRateAchievable(refreshRate)) {
            if (mLoggingEnabled) {
                Slog.w(TAG, "Discarding mode " + mode.getModeId()
                        + ", outside frame rate bounds"
                        + ": minRenderFrameRate=" + minRenderFrameRate
                        + ", maxRenderFrameRate=" + maxRenderFrameRate
                        + ", modePhysicalRefreshRate=" + refreshRate);
            }
            return false;
        }
        return true;
    }

    private boolean validateModeSupported(Display.Mode mode) {
        if (supportedModes == null || !mSupportedModesVoteEnabled) {
            return true;
        }
        for (SupportedModesVote.SupportedMode supportedMode : supportedModes) {
            if (equalsWithinFloatTolerance(mode.getRefreshRate(), supportedMode.mPeakRefreshRate)
                    && equalsWithinFloatTolerance(mode.getVsyncRate(), supportedMode.mVsyncRate)) {
                return true;
            }
        }
        if (mLoggingEnabled) {
            Slog.w(TAG, "Discarding mode " + mode.getModeId()
                    + ", supportedMode not found"
                    + ": mode.refreshRate=" + mode.getRefreshRate()
                    + ", mode.vsyncRate=" + mode.getVsyncRate()
                    + ", supportedModes=" + supportedModes);
        }
        return false;
    }

    private boolean isRenderRateAchievable(float physicalRefreshRate) {
        // Check whether the render frame rate range is achievable by the mode's physical
        // refresh rate, meaning that if a divisor of the physical refresh rate is in range
        // of the render frame rate.
        // For example for the render frame rate [50, 70]:
        //   - 120Hz is in range as we can render at 60hz by skipping every other frame,
        //     which is within the render rate range
        //   - 90hz is not in range as none of the even divisors (i.e. 90, 45, 30)
        //     fall within the acceptable render range.
        final int divisor =
                (int) Math.ceil((physicalRefreshRate / maxRenderFrameRate)
                        - FLOAT_TOLERANCE);
        float adjustedPhysicalRefreshRate = physicalRefreshRate / divisor;
        return adjustedPhysicalRefreshRate >= (minRenderFrameRate - FLOAT_TOLERANCE);
    }

    private boolean isValid() {
        if (minRenderFrameRate > maxRenderFrameRate + FLOAT_TOLERANCE) {
            if (mLoggingEnabled) {
                Slog.w(TAG, "Vote summary resulted in empty set (invalid frame rate range)"
                        + ": minRenderFrameRate=" + minRenderFrameRate
                        + ", maxRenderFrameRate=" + maxRenderFrameRate);
            }
            return false;
        }

        if (supportedModes != null && mSupportedModesVoteEnabled && supportedModes.isEmpty()) {
            if (mLoggingEnabled) {
                Slog.w(TAG, "Vote summary resulted in empty set (empty supportedModes)");
            }
            return false;
        }
        return true;
    }

    private void updateSummaryWithBestAllowedResolution(final Display.Mode[] supportedModes) {
        int maxAllowedWidth = width;
        int maxAllowedHeight = height;
        width = Vote.INVALID_SIZE;
        height = Vote.INVALID_SIZE;
        int maxNumberOfPixels = 0;
        for (Display.Mode mode : supportedModes) {
            if (mode.getPhysicalWidth() > maxAllowedWidth
                    || mode.getPhysicalHeight() > maxAllowedHeight
                    || mode.getPhysicalWidth() < minWidth
                    || mode.getPhysicalHeight() < minHeight
                    || mode.getRefreshRate() < (minPhysicalRefreshRate - FLOAT_TOLERANCE)
                    || mode.getRefreshRate() > (maxPhysicalRefreshRate + FLOAT_TOLERANCE)
            ) {
                continue;
            }

            int numberOfPixels = mode.getPhysicalHeight() * mode.getPhysicalWidth();
            if (numberOfPixels > maxNumberOfPixels || (mode.getPhysicalWidth() == maxAllowedWidth
                    && mode.getPhysicalHeight() == maxAllowedHeight)) {
                maxNumberOfPixels = numberOfPixels;
                width = mode.getPhysicalWidth();
                height = mode.getPhysicalHeight();
            }
        }
    }

    private void reset() {
        minPhysicalRefreshRate = 0f;
        maxPhysicalRefreshRate = Float.POSITIVE_INFINITY;
        minRenderFrameRate = 0f;
        maxRenderFrameRate = Float.POSITIVE_INFINITY;
        width = Vote.INVALID_SIZE;
        height = Vote.INVALID_SIZE;
        minWidth = 0;
        minHeight = 0;
        disableRefreshRateSwitching = false;
        appRequestBaseModeRefreshRate = 0f;
        supportedModes = null;
        if (mLoggingEnabled) {
            Slog.i(TAG, "Summary reset: " + this);
        }
    }

    private static boolean equalsWithinFloatTolerance(float a, float b) {
        return a >= b - FLOAT_TOLERANCE && a <= b + FLOAT_TOLERANCE;
    }

    @Override
    public String toString() {
        return  "VoteSummary{ minPhysicalRefreshRate=" + minPhysicalRefreshRate
                + ", maxPhysicalRefreshRate=" + maxPhysicalRefreshRate
                + ", minRenderFrameRate=" + minRenderFrameRate
                + ", maxRenderFrameRate=" + maxRenderFrameRate
                + ", width=" + width
                + ", height=" + height
                + ", minWidth=" + minWidth
                + ", minHeight=" + minHeight
                + ", disableRefreshRateSwitching=" + disableRefreshRateSwitching
                + ", appRequestBaseModeRefreshRate=" + appRequestBaseModeRefreshRate
                + ", supportedModes=" + supportedModes
                + ", mIsDisplayResolutionRangeVotingEnabled="
                + mIsDisplayResolutionRangeVotingEnabled
                + ", mSupportedModesVoteEnabled=" + mSupportedModesVoteEnabled
                + ", mSupportsFrameRateOverride=" + mSupportsFrameRateOverride + " }";
    }
}
