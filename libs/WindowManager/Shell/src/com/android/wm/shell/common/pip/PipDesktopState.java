/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.common.pip;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.app.ActivityManager;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.window.flags.Flags;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.desktopmode.DesktopRepository;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.desktopmode.desktopwallpaperactivity.DesktopWallpaperActivityTokenProvider;
import com.android.wm.shell.pip2.phone.PipTransition;

import java.util.Optional;

/** Helper class for PiP on Desktop Mode. */
public class PipDesktopState {
    private final PipDisplayLayoutState mPipDisplayLayoutState;
    private final Optional<DesktopUserRepositories> mDesktopUserRepositoriesOptional;
    private final Optional<DesktopWallpaperActivityTokenProvider>
            mDesktopWallpaperActivityTokenProviderOptional;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;

    public PipDesktopState(PipDisplayLayoutState pipDisplayLayoutState,
            Optional<DesktopUserRepositories> desktopUserRepositoriesOptional,
            Optional<DesktopWallpaperActivityTokenProvider>
                    desktopWallpaperActivityTokenProviderOptional,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        mPipDisplayLayoutState = pipDisplayLayoutState;
        mDesktopUserRepositoriesOptional = desktopUserRepositoriesOptional;
        mDesktopWallpaperActivityTokenProviderOptional =
                desktopWallpaperActivityTokenProviderOptional;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
    }

    /**
     * Returns whether PiP in Desktop Windowing is enabled by checking the following:
     * - Desktop Windowing in PiP flag is enabled
     * - DesktopWallpaperActivityTokenProvider is injected
     * - DesktopUserRepositories is injected
     */
    public boolean isDesktopWindowingPipEnabled() {
        return Flags.enableDesktopWindowingPip()
                && mDesktopWallpaperActivityTokenProviderOptional.isPresent()
                && mDesktopUserRepositoriesOptional.isPresent();
    }

    /** Returns whether PiP in Connected Displays is enabled by checking the flag. */
    public boolean isConnectedDisplaysPipEnabled() {
        return Flags.enableConnectedDisplaysPip();
    }

    /** Returns whether the display with the PiP task is in freeform windowing mode. */
    private boolean isDisplayInFreeform() {
        final DisplayAreaInfo tdaInfo = mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(
                mPipDisplayLayoutState.getDisplayId());
        if (tdaInfo != null) {
            return tdaInfo.configuration.windowConfiguration.getWindowingMode()
                    == WINDOWING_MODE_FREEFORM;
        }
        return false;
    }

    /** Returns whether PiP is exiting while we're in a Desktop Mode session. */
    private boolean isPipExitingToDesktopMode() {
        // Early return if PiP in Desktop Windowing is not supported.
        if (!isDesktopWindowingPipEnabled()) {
            return false;
        }
        final int displayId = mPipDisplayLayoutState.getDisplayId();
        return getDesktopRepository().getVisibleTaskCount(displayId) > 0
                || getDesktopWallpaperActivityTokenProvider().isWallpaperActivityVisible(displayId)
                || isDisplayInFreeform();
    }

    /** Returns whether {@param pipTask} would be entering in a Desktop Mode session. */
    public boolean isPipEnteringInDesktopMode(ActivityManager.RunningTaskInfo pipTask) {
        // Early return if PiP in Desktop Windowing is not supported.
        if (!isDesktopWindowingPipEnabled()) {
            return false;
        }
        final DesktopRepository desktopRepository = getDesktopRepository();
        return desktopRepository.getVisibleTaskCount(pipTask.getDisplayId()) > 0
                || desktopRepository.isMinimizedPipPresentInDisplay(pipTask.getDisplayId());
    }

    /**
     * Invoked when an EXIT_PiP transition is detected in {@link PipTransition}.
     * Returns whether the PiP exiting should also trigger the active Desktop Mode session to exit.
     */
    public boolean shouldExitPipExitDesktopMode() {
        // Early return if PiP in Desktop Windowing is not supported.
        if (!isDesktopWindowingPipEnabled()) {
            return false;
        }
        final int displayId = mPipDisplayLayoutState.getDisplayId();
        return getDesktopRepository().getVisibleTaskCount(displayId) == 0
                && getDesktopWallpaperActivityTokenProvider().isWallpaperActivityVisible(displayId);
    }

    /**
     * Returns a {@link WindowContainerTransaction} that reorders the {@link WindowContainerToken}
     * of the DesktopWallpaperActivity for the display with the given {@param displayId}.
     */
    public WindowContainerTransaction getWallpaperActivityTokenWct(int displayId) {
        return new WindowContainerTransaction().reorder(
                getDesktopWallpaperActivityTokenProvider().getToken(displayId), /* onTop= */ false);
    }

    /**
     * The windowing mode to restore to when resizing out of PIP direction.
     * Defaults to undefined and can be overridden to restore to an alternate windowing mode.
     */
    public int getOutPipWindowingMode() {
        // If we are exiting PiP while the device is in Desktop mode (the task should expand to
        // freeform windowing mode):
        // 1) If the display windowing mode is freeform, set windowing mode to UNDEFINED so it will
        //    resolve the windowing mode to the display's windowing mode.
        // 2) If the display windowing mode is not FREEFORM, set windowing mode to FREEFORM.
        if (isPipExitingToDesktopMode()) {
            if (isDisplayInFreeform()) {
                return WINDOWING_MODE_UNDEFINED;
            } else {
                return WINDOWING_MODE_FREEFORM;
            }
        }

        // By default, or if the task is going to fullscreen, reset the windowing mode to undefined.
        return WINDOWING_MODE_UNDEFINED;
    }

    private DesktopRepository getDesktopRepository() {
        return mDesktopUserRepositoriesOptional.get().getCurrent();
    }

    private DesktopWallpaperActivityTokenProvider getDesktopWallpaperActivityTokenProvider() {
        return mDesktopWallpaperActivityTokenProviderOptional.get();
    }
}
