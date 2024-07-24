/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.hardware.display.DisplayManager.SWITCHING_TYPE_NONE;
import static android.hardware.display.DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY;

import static com.android.window.flags.Flags.explicitRefreshRateHints;

import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Display.Mode;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.RefreshRateRange;

import java.util.HashMap;
import java.util.Objects;

/**
 * Policy to select a lower refresh rate for the display if applicable.
 */
class RefreshRatePolicy {

    class PackageRefreshRate {
        private final HashMap<String, RefreshRateRange> mPackages = new HashMap<>();

        public void add(String s, float minRefreshRate, float maxRefreshRate) {
            float minSupportedRefreshRate =
                    Math.max(RefreshRatePolicy.this.mMinSupportedRefreshRate, minRefreshRate);
            float maxSupportedRefreshRate =
                    Math.min(RefreshRatePolicy.this.mMaxSupportedRefreshRate, maxRefreshRate);

            mPackages.put(s,
                    new RefreshRateRange(minSupportedRefreshRate, maxSupportedRefreshRate));
        }

        public RefreshRateRange get(String s) {
            return mPackages.get(s);
        }

        public void remove(String s) {
            mPackages.remove(s);
        }
    }

    private final DisplayInfo mDisplayInfo;
    private final Mode mDefaultMode;
    private final Mode mLowRefreshRateMode;
    private final PackageRefreshRate mNonHighRefreshRatePackages = new PackageRefreshRate();
    private final HighRefreshRateDenylist mHighRefreshRateDenylist;
    private final WindowManagerService mWmService;
    private float mMinSupportedRefreshRate;
    private float mMaxSupportedRefreshRate;

    /**
     * The following constants represent priority of the window. SF uses this information when
     * deciding which window has a priority when deciding about the refresh rate of the screen.
     * Priority 0 is considered the highest priority. -1 means that the priority is unset.
     */
    static final int LAYER_PRIORITY_UNSET = -1;
    /** Windows that are in focus and voted for the preferred mode ID have the highest priority. */
    static final int LAYER_PRIORITY_FOCUSED_WITH_MODE = 0;
    /**
     * This is a default priority for all windows that are in focus, but have not requested a
     * specific mode ID.
     */
    static final int LAYER_PRIORITY_FOCUSED_WITHOUT_MODE = 1;
    /**
     * Windows that are not in focus, but voted for a specific mode ID should be
     * acknowledged by SF. For example, there are two applications in a split screen.
     * One voted for a given mode ID, and the second one doesn't care. Even though the
     * second one might be in focus, we can honor the mode ID of the first one.
     */
    static final int LAYER_PRIORITY_NOT_FOCUSED_WITH_MODE = 2;

    RefreshRatePolicy(WindowManagerService wmService, DisplayInfo displayInfo,
            HighRefreshRateDenylist denylist) {
        mDisplayInfo = displayInfo;
        mDefaultMode = displayInfo.getDefaultMode();
        mLowRefreshRateMode = findLowRefreshRateMode(displayInfo, mDefaultMode);
        mHighRefreshRateDenylist = denylist;
        mWmService = wmService;
    }

    /**
     * Finds the mode id with the lowest refresh rate which is >= 60hz and same resolution as the
     * default mode.
     */
    private Mode findLowRefreshRateMode(DisplayInfo displayInfo, Mode defaultMode) {
        float[] refreshRates = displayInfo.getDefaultRefreshRates();
        float bestRefreshRate = defaultMode.getRefreshRate();
        mMinSupportedRefreshRate = bestRefreshRate;
        mMaxSupportedRefreshRate = bestRefreshRate;
        for (int i = refreshRates.length - 1; i >= 0; i--) {
            mMinSupportedRefreshRate = Math.min(mMinSupportedRefreshRate, refreshRates[i]);
            mMaxSupportedRefreshRate = Math.max(mMaxSupportedRefreshRate, refreshRates[i]);

            if (refreshRates[i] >= 60f && refreshRates[i] < bestRefreshRate) {
                bestRefreshRate = refreshRates[i];
            }
        }
        return displayInfo.findDefaultModeByRefreshRate(bestRefreshRate);
    }

    void addRefreshRateRangeForPackage(String packageName,
            float minRefreshRate, float maxRefreshRate) {
        mNonHighRefreshRatePackages.add(packageName, minRefreshRate, maxRefreshRate);
        mWmService.requestTraversal();
    }

    void removeRefreshRateRangeForPackage(String packageName) {
        mNonHighRefreshRatePackages.remove(packageName);
        mWmService.requestTraversal();
    }

    int getPreferredModeId(WindowState w) {
        final int preferredDisplayModeId = w.mAttrs.preferredDisplayModeId;
        if (preferredDisplayModeId <= 0) {
            // Unspecified, use default mode.
            return 0;
        }

        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate. But if the display size of default mode is different
        // from the using preferred mode, then still keep the preferred mode to avoid disturbing
        // the animation.
        if (!explicitRefreshRateHints() && w.isAnimationRunningSelfOrParent()) {
            Display.Mode preferredMode = null;
            for (Display.Mode mode : mDisplayInfo.supportedModes) {
                if (preferredDisplayModeId == mode.getModeId()) {
                    preferredMode = mode;
                    break;
                }
            }
            if (preferredMode != null) {
                final int pW = preferredMode.getPhysicalWidth();
                final int pH = preferredMode.getPhysicalHeight();
                if ((pW != mDefaultMode.getPhysicalWidth()
                        || pH != mDefaultMode.getPhysicalHeight())
                        && pW == mDisplayInfo.getNaturalWidth()
                        && pH == mDisplayInfo.getNaturalHeight()) {
                    // Prefer not to change display size when animating.
                    return preferredDisplayModeId;
                }
            }
            return 0;
        }

        return preferredDisplayModeId;
    }

    /**
     * Calculate the priority based on whether the window is in focus and whether the application
     * voted for a specific refresh rate.
     *
     * TODO(b/144307188): This is a very basic algorithm version. Explore other signals that might
     * be useful in edge cases when we are deciding which layer should get priority when deciding
     * about the refresh rate.
     */
    int calculatePriority(WindowState w) {
        boolean isFocused = w.isFocused();
        int preferredModeId = getPreferredModeId(w);

        if (!isFocused && preferredModeId > 0) {
            return LAYER_PRIORITY_NOT_FOCUSED_WITH_MODE;
        }
        if (isFocused && preferredModeId == 0) {
            return LAYER_PRIORITY_FOCUSED_WITHOUT_MODE;
        }
        if (isFocused && preferredModeId > 0) {
            return LAYER_PRIORITY_FOCUSED_WITH_MODE;
        }
        return LAYER_PRIORITY_UNSET;
    }

    public static class FrameRateVote {
        float mRefreshRate;
        @Surface.FrameRateCompatibility int mCompatibility;
        @SurfaceControl.FrameRateSelectionStrategy int mSelectionStrategy;



        FrameRateVote(float refreshRate, @Surface.FrameRateCompatibility int compatibility,
                      @SurfaceControl.FrameRateSelectionStrategy int selectionStrategy) {
            update(refreshRate, compatibility, selectionStrategy);
        }

        FrameRateVote() {
            reset();
        }

        boolean update(float refreshRate, @Surface.FrameRateCompatibility int compatibility,
                       @SurfaceControl.FrameRateSelectionStrategy int selectionStrategy) {
            if (!refreshRateEquals(refreshRate)
                    || mCompatibility != compatibility
                    || mSelectionStrategy != selectionStrategy) {
                mRefreshRate = refreshRate;
                mCompatibility = compatibility;
                mSelectionStrategy = selectionStrategy;
                return true;
            }
            return false;
        }

        boolean reset() {
            return update(0, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FrameRateVote)) {
                return false;
            }

            FrameRateVote other = (FrameRateVote) o;
            return refreshRateEquals(other.mRefreshRate)
                    && mCompatibility == other.mCompatibility
                    && mSelectionStrategy == other.mSelectionStrategy;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mRefreshRate, mCompatibility, mSelectionStrategy);

        }

        @Override
        public String toString() {
            return "mRefreshRate=" + mRefreshRate + ", mCompatibility=" + mCompatibility
                    + ", mSelectionStrategy=" + mSelectionStrategy;
        }

        private boolean refreshRateEquals(float refreshRate) {
            return mRefreshRate <= refreshRate + RefreshRateRange.FLOAT_TOLERANCE
                    && mRefreshRate >= refreshRate - RefreshRateRange.FLOAT_TOLERANCE;
        }
    }

    boolean updateFrameRateVote(WindowState w) {
        @DisplayManager.SwitchingType int refreshRateSwitchingType =
                mWmService.mDisplayManagerInternal.getRefreshRateSwitchingType();

        // If refresh rate switching is disabled there is no point to set the frame rate on the
        // surface as the refresh rate will be limited by display manager to a single value
        // and SurfaceFlinger wouldn't be able to change it anyways.
        if (refreshRateSwitchingType == SWITCHING_TYPE_NONE) {
            return w.mFrameRateVote.reset();
        }

        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate.
        if (!explicitRefreshRateHints() && w.isAnimationRunningSelfOrParent()) {
            return w.mFrameRateVote.reset();
        }

        // If the app set a preferredDisplayModeId, the preferred refresh rate is the refresh rate
        // of that mode id.
        if (refreshRateSwitchingType != SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY) {
            final int preferredModeId = w.mAttrs.preferredDisplayModeId;
            if (preferredModeId > 0) {
                for (Display.Mode mode : mDisplayInfo.supportedModes) {
                    if (preferredModeId == mode.getModeId()) {
                        return w.mFrameRateVote.update(mode.getRefreshRate(),
                                Surface.FRAME_RATE_COMPATIBILITY_EXACT,
                                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
                    }
                }
            }
        }

        if (w.mAttrs.preferredRefreshRate > 0) {
            return w.mFrameRateVote.update(w.mAttrs.preferredRefreshRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
        }

        // If the app didn't set a preferred mode id or refresh rate, but it is part of the deny
        // list, we return the low refresh rate as the preferred one.
        if (refreshRateSwitchingType != SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY) {
            final String packageName = w.getOwningPackage();
            if (mHighRefreshRateDenylist.isDenylisted(packageName)) {
                return w.mFrameRateVote.update(mLowRefreshRateMode.getRefreshRate(),
                        Surface.FRAME_RATE_COMPATIBILITY_EXACT,
                        SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
            }
        }

        return w.mFrameRateVote.reset();
    }

    float getPreferredMinRefreshRate(WindowState w) {
        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate.
        if (w.isAnimationRunningSelfOrParent()) {
            return 0;
        }

        if (w.mAttrs.preferredMinDisplayRefreshRate > 0) {
            return w.mAttrs.preferredMinDisplayRefreshRate;
        }

        String packageName = w.getOwningPackage();
        // If app is using Camera, we set both the min and max refresh rate to the camera's
        // preferred refresh rate to make sure we don't end up with a refresh rate lower
        // than the camera capture rate, which will lead to dropping camera frames.
        RefreshRateRange range = mNonHighRefreshRatePackages.get(packageName);
        if (range != null) {
            return range.min;
        }

        return 0;
    }

    float getPreferredMaxRefreshRate(WindowState w) {
        // If app is animating, it's not able to control refresh rate because we want the animation
        // to run in default refresh rate.
        if (w.isAnimationRunningSelfOrParent()) {
            return 0;
        }

        if (w.mAttrs.preferredMaxDisplayRefreshRate > 0) {
            return w.mAttrs.preferredMaxDisplayRefreshRate;
        }

        final String packageName = w.getOwningPackage();
        // If app is using Camera, force it to default (lower) refresh rate.
        RefreshRateRange range = mNonHighRefreshRatePackages.get(packageName);
        if (range != null) {
            return range.max;
        }

        return 0;
    }
}
