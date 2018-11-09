/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

import android.content.res.Configuration;
import android.graphics.GraphicBuffer;
import android.os.Binder;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.util.Slog;
import android.view.AppTransitionAnimationSpec;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowManager.TransitionType;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Controller for the display container. This is created by activity manager to link activity
 * displays to the display content they use in window manager.
 */
public class DisplayWindowController
        extends WindowContainerController<DisplayContent, WindowContainerListener> {

    private final int mDisplayId;

    public DisplayWindowController(Display display, WindowContainerListener listener) {
        super(listener, WindowManagerService.getInstance());
        mDisplayId = display.getDisplayId();

        synchronized (mGlobalLock) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mRoot.createDisplayContent(display, this /* controller */);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }

            if (mContainer == null) {
                throw new IllegalArgumentException("Trying to add display=" + display
                        + " dc=" + mRoot.getDisplayContent(mDisplayId));
            }
        }
    }

    @VisibleForTesting
    public DisplayWindowController(Display display, WindowManagerService service) {
        super(null, service);
        mDisplayId = display.getDisplayId();
    }

    @Override
    public void removeContainer() {
        synchronized (mGlobalLock) {
            if(mContainer == null) {
                if (DEBUG_DISPLAY) Slog.i(TAG_WM, "removeDisplay: could not find displayId="
                        + mDisplayId);
                return;
            }
            mContainer.removeIfPossible();
            super.removeContainer();
        }
    }

    @Override
    public void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
        synchronized (mGlobalLock) {
            if (mContainer != null) {
                mContainer.mService.setNewDisplayOverrideConfiguration(overrideConfiguration,
                        mContainer);
            }
        }
    }

    /**
     * Updates the docked/pinned controller resources to the current system context.
     */
    public void preOnConfigurationChanged() {
        synchronized (mGlobalLock) {
            if (mContainer != null) {
                mContainer.preOnConfigurationChanged();
            }
        }
    }

  /**
   * @see DisplayContent#applyRotationLocked(int, int)
   */
    public void applyRotation(int oldRotation, int newRotation) {
        synchronized (mGlobalLock) {
            if (mContainer != null) {
                mContainer.applyRotationLocked(oldRotation, newRotation);
            }
        }
    }

    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Called when the corresponding display receives
     * {@link android.hardware.display.DisplayManager.DisplayListener#onDisplayChanged(int)}.
     */
    public void onDisplayChanged() {
        synchronized (mGlobalLock) {
            if (mContainer == null) {
                if (DEBUG_DISPLAY) Slog.i(TAG_WM, "onDisplayChanged: could not find display="
                        + mDisplayId);
                return;
            }
            mContainer.updateDisplayInfo();
            mService.mWindowPlacerLocked.requestTraversal();
        }
    }

    /**
     * Positions the task stack at the given position in the task stack container.
     */
    public void positionChildAt(StackWindowController child, int position,
            boolean includingParents) {
        synchronized (mGlobalLock) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "positionTaskStackAt: positioning stack=" + child
                    + " at " + position);
            if (mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionTaskStackAt: could not find display=" + mContainer);
                return;
            }
            if (child.mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionTaskStackAt: could not find stack=" + this);
                return;
            }
            mContainer.positionStackAt(position, child.mContainer, includingParents);
        }
    }

    /**
     * Starts deferring the ability to update the IME target. This is needed when a call will
     * attempt to update the IME target before all information about the Windows have been updated.
     */
    public void deferUpdateImeTarget() {
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getDisplayContent(mDisplayId);
            if (dc != null) {
                dc.deferUpdateImeTarget();
            }
        }
    }

    /**
     * Resumes updating the IME target after deferring. See {@link #deferUpdateImeTarget()}
     */
    public void continueUpdateImeTarget() {
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getDisplayContent(mDisplayId);
            if (dc != null) {
                dc.continueUpdateImeTarget();
            }
        }
    }

    /**
     * Sets a focused app on this display.
     *
     * @param token Specifies which app should be focused.
     * @param moveFocusNow Specifies if we should update the focused window immediately.
     */
    public void setFocusedApp(IBinder token, boolean moveFocusNow) {
        synchronized (mGlobalLock) {
            if (mContainer == null) {
                if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "setFocusedApp: could not find displayId="
                        + mDisplayId);
                return;
            }
            final AppWindowToken newFocus;
            if (token == null) {
                if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "Clearing focused app, displayId="
                        + mDisplayId);
                newFocus = null;
            } else {
                newFocus = mRoot.getAppWindowToken(token);
                if (newFocus == null) {
                    Slog.w(TAG_WM, "Attempted to set focus to non-existing app token: " + token
                            + ", displayId=" + mDisplayId);
                }
                if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "Set focused app to: " + newFocus
                        + " moveFocusNow=" + moveFocusNow + " displayId=" + mDisplayId);
            }

            final boolean changed = mContainer.setFocusedApp(newFocus);
            if (moveFocusNow && changed) {
                mService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                        true /*updateInputWindows*/);
            }
        }
    }

    public void prepareAppTransition(@WindowManager.TransitionType int transit,
            boolean alwaysKeepCurrent) {
        prepareAppTransition(transit, alwaysKeepCurrent, 0 /* flags */, false /* forceOverride */);
    }

    /**
     * @param transit What kind of transition is happening. Use one of the constants
     *                AppTransition.TRANSIT_*.
     * @param alwaysKeepCurrent If true and a transition is already set, new transition will NOT
     *                          be set.
     * @param flags Additional flags for the app transition, Use a combination of the constants
     *              AppTransition.TRANSIT_FLAG_*.
     * @param forceOverride Always override the transit, not matter what was set previously.
     */
    public void prepareAppTransition(@WindowManager.TransitionType int transit,
            boolean alwaysKeepCurrent, @WindowManager.TransitionFlags int flags,
            boolean forceOverride) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId).prepareAppTransition(transit, alwaysKeepCurrent,
                    flags, forceOverride);
        }
    }

    public void executeAppTransition() {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId).executeAppTransition();
        }
    }

    public void overridePendingAppTransition(String packageName,
            int enterAnim, int exitAnim, IRemoteCallback startedCallback) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId).mAppTransition.overridePendingAppTransition(
                    packageName, enterAnim, exitAnim, startedCallback);
        }
    }

    public void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
            int startHeight) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId).mAppTransition.overridePendingAppTransitionScaleUp(
                    startX, startY, startWidth, startHeight);
        }
    }

    public void overridePendingAppTransitionClipReveal(int startX, int startY,
            int startWidth, int startHeight) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId)
                    .mAppTransition.overridePendingAppTransitionClipReveal(startX, startY,
                    startWidth, startHeight);
        }
    }

    public void overridePendingAppTransitionThumb(GraphicBuffer srcThumb, int startX,
            int startY, IRemoteCallback startedCallback, boolean scaleUp) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId)
                    .mAppTransition.overridePendingAppTransitionThumb(srcThumb, startX, startY,
                    startedCallback, scaleUp);
        }
    }

    public void overridePendingAppTransitionAspectScaledThumb(GraphicBuffer srcThumb, int startX,
            int startY, int targetWidth, int targetHeight, IRemoteCallback startedCallback,
            boolean scaleUp) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId)
                    .mAppTransition.overridePendingAppTransitionAspectScaledThumb(srcThumb, startX,
                    startY, targetWidth, targetHeight, startedCallback, scaleUp);
        }
    }

    public void overridePendingAppTransitionMultiThumb(AppTransitionAnimationSpec[] specs,
            IRemoteCallback onAnimationStartedCallback, IRemoteCallback onAnimationFinishedCallback,
            boolean scaleUp) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId)
                    .mAppTransition.overridePendingAppTransitionMultiThumb(specs,
                    onAnimationStartedCallback, onAnimationFinishedCallback, scaleUp);
        }
    }

    public void overridePendingAppTransitionStartCrossProfileApps() {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId)
                    .mAppTransition.overridePendingAppTransitionStartCrossProfileApps();
        }
    }

    public void overridePendingAppTransitionInPlace(String packageName, int anim) {
        synchronized (mGlobalLock) {
            mRoot.getDisplayContent(mDisplayId)
                    .mAppTransition.overrideInPlaceAppTransition(packageName, anim);
        }
    }

    /**
     * Get Pending App transition of display.
     *
     * @return The pending app transition of the display.
     */
    public @TransitionType int getPendingAppTransition() {
        synchronized (mGlobalLock) {
            return mRoot.getDisplayContent(mDisplayId).mAppTransition.getAppTransition();
        }
    }

    /**
     * Check if pending app transition is for activity / task launch.
     */
    public boolean isNextTransitionForward() {
        final int transit = getPendingAppTransition();
        return transit == TRANSIT_ACTIVITY_OPEN
                || transit == TRANSIT_TASK_OPEN
                || transit == TRANSIT_TASK_TO_FRONT;
    }

    /**
     * Checks if system decorations should be shown on this display.
     *
     * @see Display#FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     */
    public boolean supportsSystemDecorations() {
        synchronized (mGlobalLock) {
            return mContainer.supportsSystemDecorations();
        }
    }

    @Override
    public String toString() {
        return "{DisplayWindowController displayId=" + mDisplayId + "}";
    }
}
