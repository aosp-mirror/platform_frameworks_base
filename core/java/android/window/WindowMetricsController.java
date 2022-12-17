/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;

import android.annotation.NonNull;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowMetrics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A controller to handle {@link android.view.WindowMetrics} related APIs, which are
 * <ol>
 *     <li>{@link WindowManager#getCurrentWindowMetrics()}</li>
 *     <li>{@link WindowManager#getMaximumWindowMetrics()}</li>
 *     <li>{@link WindowManager#getPossibleMaximumWindowMetrics(int)}</li>
 * </ol>
 *
 * @hide
 */
public final class WindowMetricsController {
    private final Context mContext;

    public WindowMetricsController(@NonNull Context context) {
        mContext = context;
    }

    /** @see WindowManager#getCurrentWindowMetrics() */
    public WindowMetrics getCurrentWindowMetrics() {
        final Rect bounds = getCurrentBounds(mContext);

        // TODO(b/187712731): Provide density for WindowMetrics.
        return new WindowMetrics(bounds, computeWindowInsets(bounds));
    }

    private static Rect getCurrentBounds(Context context) {
        synchronized (ResourcesManager.getInstance()) {
            return context.getResources().getConfiguration().windowConfiguration.getBounds();
        }
    }

    /** @see WindowManager#getMaximumWindowMetrics() */
    public WindowMetrics getMaximumWindowMetrics() {
        final Rect maxBounds = getMaximumBounds(mContext);

        // TODO(b/187712731): Provide density for WindowMetrics.
        return new WindowMetrics(maxBounds, computeWindowInsets(maxBounds));
    }

    private static Rect getMaximumBounds(Context context) {
        synchronized (ResourcesManager.getInstance()) {
            return context.getResources().getConfiguration().windowConfiguration.getMaxBounds();
        }
    }

    private WindowInsets computeWindowInsets(Rect bounds) {
        // Initialize params which used for obtaining all system insets.
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.token = Context.getToken(mContext);
        return getWindowInsetsFromServerForCurrentDisplay(params, bounds);
    }

    private WindowInsets getWindowInsetsFromServerForCurrentDisplay(
            WindowManager.LayoutParams attrs, Rect bounds) {
        final boolean isScreenRound;
        final int windowingMode;
        synchronized (ResourcesManager.getInstance()) {
            final Configuration config = mContext.getResources().getConfiguration();
            isScreenRound = config.isScreenRound();
            windowingMode = config.windowConfiguration.getWindowingMode();
        }
        return getWindowInsetsFromServerForDisplay(mContext.getDisplayId(), attrs, bounds,
                isScreenRound, windowingMode);
    }

    /**
     * Retrieves WindowInsets for the given context and display, given the window bounds.
     *
     * @param displayId the ID of the logical display to calculate insets for
     * @param attrs the LayoutParams for the calling app
     * @param bounds the window bounds to calculate insets for
     * @param isScreenRound if the display identified by displayId is round
     * @param windowingMode the windowing mode of the window to calculate insets for
     * @return WindowInsets calculated for the given window bounds, on the given display
     */
    private static WindowInsets getWindowInsetsFromServerForDisplay(int displayId,
            WindowManager.LayoutParams attrs, Rect bounds, boolean isScreenRound,
            int windowingMode) {
        try {
            final InsetsState insetsState = new InsetsState();
            final boolean alwaysConsumeSystemBars = WindowManagerGlobal.getWindowManagerService()
                    .getWindowInsets(attrs, displayId, insetsState);
            return insetsState.calculateInsets(bounds, null /* ignoringVisibilityState*/,
                    isScreenRound, alwaysConsumeSystemBars, SOFT_INPUT_ADJUST_NOTHING, attrs.flags,
                    SYSTEM_UI_FLAG_VISIBLE, attrs.type, windowingMode,
                    null /* typeSideMap */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see WindowManager#getPossibleMaximumWindowMetrics(int) */
    @NonNull
    public Set<WindowMetrics> getPossibleMaximumWindowMetrics(int displayId) {
        List<DisplayInfo> possibleDisplayInfos;
        try {
            possibleDisplayInfos = WindowManagerGlobal.getWindowManagerService()
                    .getPossibleDisplayInfo(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        Set<WindowMetrics> maxMetrics = new HashSet<>();
        WindowInsets windowInsets;
        DisplayInfo currentDisplayInfo;
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        for (int i = 0; i < possibleDisplayInfos.size(); i++) {
            currentDisplayInfo = possibleDisplayInfos.get(i);

            // Calculate max bounds for this rotation and state.
            Rect maxBounds = new Rect(0, 0, currentDisplayInfo.logicalWidth,
                    currentDisplayInfo.logicalHeight);

            // Calculate insets for the rotated max bounds.
            final boolean isScreenRound = (currentDisplayInfo.flags & Display.FLAG_ROUND) != 0;
            // Initialize insets based upon display rotation. Note any window-provided insets
            // will not be set.
            windowInsets = getWindowInsetsFromServerForDisplay(
                    currentDisplayInfo.displayId, params,
                    new Rect(0, 0, currentDisplayInfo.getNaturalWidth(),
                            currentDisplayInfo.getNaturalHeight()), isScreenRound,
                    WINDOWING_MODE_FULLSCREEN);
            // Set the hardware-provided insets.
            windowInsets = new WindowInsets.Builder(windowInsets).setRoundedCorners(
                            currentDisplayInfo.roundedCorners)
                    .setDisplayCutout(currentDisplayInfo.displayCutout).build();

            maxMetrics.add(new WindowMetrics(maxBounds, windowInsets));
        }
        return maxMetrics;
    }
}
