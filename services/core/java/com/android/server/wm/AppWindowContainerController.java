/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static com.android.server.wm.AppTransition.TRANSIT_UNSET;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManager.TaskSnapshot;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Trace;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.WindowManagerPolicy.StartingSurface;

import com.android.server.AttributeCache;
/**
 * Controller for the app window token container. This is created by activity manager to link
 * activity records to the app window token container they use in window manager.
 *
 * Test class: {@link AppWindowContainerControllerTests}
 */
public class AppWindowContainerController
        extends WindowContainerController<AppWindowToken, AppWindowContainerListener> {

    private static final int STARTING_WINDOW_TYPE_NONE = 0;
    private static final int STARTING_WINDOW_TYPE_SNAPSHOT = 1;
    private static final int STARTING_WINDOW_TYPE_SPLASH_SCREEN = 2;

    private final IApplicationToken mToken;
    private final Handler mHandler;

    private final Runnable mOnWindowsDrawn = () -> {
        if (mListener == null) {
            return;
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting drawn in "
                + AppWindowContainerController.this.mToken);
        mListener.onWindowsDrawn();
    };

    private final Runnable mOnWindowsVisible = () -> {
        if (mListener == null) {
            return;
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting visible in "
                + AppWindowContainerController.this.mToken);
        mListener.onWindowsVisible();
    };

    private final Runnable mOnWindowsGone = () -> {
        if (mListener == null) {
            return;
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting gone in "
                + AppWindowContainerController.this.mToken);
        mListener.onWindowsGone();
    };

    private final Runnable mRemoveStartingWindow = () -> {
        StartingSurface surface = null;
        synchronized (mWindowMap) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Remove starting " + mContainer
                    + ": startingWindow=" + mContainer.startingWindow
                    + " startingView=" + mContainer.startingSurface);
            if (mContainer == null) {
                return;
            }
            if (mContainer.startingWindow != null) {
                surface = mContainer.startingSurface;
                mContainer.startingData = null;
                mContainer.startingSurface = null;
                mContainer.startingWindow = null;
                mContainer.startingDisplayed = false;
            }
        }
        if (surface != null) {
            try {
                surface.remove();
            } catch (Exception e) {
                Slog.w(TAG_WM, "Exception when removing starting window", e);
            }
        }
    };

    private final Runnable mAddStartingWindow = () -> {
        final StartingData startingData;
        final AppWindowToken container;

        synchronized (mWindowMap) {
            if (mContainer == null) {
                return;
            }
            startingData = mContainer.startingData;
            container = mContainer;
        }

        if (startingData == null) {
            // Animation has been canceled... do nothing.
            return;
        }

        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Add starting "
                + this + ": startingData=" + container.startingData);

        StartingSurface surface = null;
        try {
            surface = startingData.createStartingSurface(container);
        } catch (Exception e) {
            Slog.w(TAG_WM, "Exception when adding starting window", e);
        }
        if (surface != null) {
            boolean abort = false;
            synchronized(mWindowMap) {
                if (container.removed || container.startingData == null) {
                    // If the window was successfully added, then
                    // we need to remove it.
                    if (container.startingWindow != null) {
                        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                                "Aborted starting " + container
                                        + ": removed=" + container.removed
                                        + " startingData=" + container.startingData);
                        container.startingWindow = null;
                        container.startingData = null;
                        abort = true;
                    }
                } else {
                    container.startingSurface = surface;
                }
                if (DEBUG_STARTING_WINDOW && !abort) Slog.v(TAG_WM,
                        "Added starting " + mContainer
                                + ": startingWindow="
                                + container.startingWindow + " startingView="
                                + container.startingSurface);
            }
            if (abort) {
                surface.remove();
            }
        }
    };

    public AppWindowContainerController(TaskWindowContainerController taskController,
            IApplicationToken token, AppWindowContainerListener listener, int index,
            int requestedOrientation, boolean fullscreen, boolean showForAllUsers, int configChanges,
            boolean voiceInteraction, boolean launchTaskBehind, boolean alwaysFocusable,
            int targetSdkVersion, int rotationAnimationHint, long inputDispatchingTimeoutNanos) {
        this(taskController, token, listener, index, requestedOrientation, fullscreen,
                showForAllUsers,
                configChanges, voiceInteraction, launchTaskBehind, alwaysFocusable,
                targetSdkVersion, rotationAnimationHint, inputDispatchingTimeoutNanos,
                WindowManagerService.getInstance());
    }

    public AppWindowContainerController(TaskWindowContainerController taskController,
            IApplicationToken token, AppWindowContainerListener listener, int index,
            int requestedOrientation, boolean fullscreen, boolean showForAllUsers, int configChanges,
            boolean voiceInteraction, boolean launchTaskBehind, boolean alwaysFocusable,
            int targetSdkVersion, int rotationAnimationHint, long inputDispatchingTimeoutNanos,
            WindowManagerService service) {
        super(listener, service);
        mHandler = new Handler(service.mH.getLooper());
        mToken = token;
        synchronized(mWindowMap) {
            AppWindowToken atoken = mRoot.getAppWindowToken(mToken.asBinder());
            if (atoken != null) {
                // TODO: Should this throw an exception instead?
                Slog.w(TAG_WM, "Attempted to add existing app token: " + mToken);
                return;
            }

            final Task task = taskController.mContainer;
            if (task == null) {
                throw new IllegalArgumentException("AppWindowContainerController: invalid "
                        + " controller=" + taskController);
            }

            atoken = new AppWindowToken(mService, token, voiceInteraction, task.getDisplayContent(),
                    inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdkVersion,
                    requestedOrientation, rotationAnimationHint, configChanges, launchTaskBehind,
                    alwaysFocusable, this);
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "addAppToken: " + atoken
                    + " controller=" + taskController + " at " + index);
            task.addChild(atoken, index);
        }
    }

    public void removeContainer(int displayId) {
        synchronized(mWindowMap) {
            final DisplayContent dc = mRoot.getDisplayContent(displayId);
            if (dc == null) {
                Slog.w(TAG_WM, "removeAppToken: Attempted to remove binder token: "
                        + mToken + " from non-existing displayId=" + displayId);
                return;
            }
            dc.removeAppToken(mToken.asBinder());
            super.removeContainer();
        }
    }

    @Override
    public void removeContainer() {
        throw new UnsupportedOperationException("Use removeContainer(displayId) instead.");
    }

    public Configuration setOrientation(int requestedOrientation, int displayId,
            Configuration displayConfig, boolean freezeScreenIfNeeded) {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM,
                        "Attempted to set orientation of non-existing app token: " + mToken);
                return null;
            }

            mContainer.setOrientation(requestedOrientation);

            final IBinder binder = freezeScreenIfNeeded ? mToken.asBinder() : null;
            return mService.updateOrientationFromAppTokens(displayConfig, binder, displayId);

        }
    }

    public int getOrientation() {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                return SCREEN_ORIENTATION_UNSPECIFIED;
            }

            return mContainer.getOrientationIgnoreVisibility();
        }
    }

    public void setVisibility(boolean visible) {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to set visibility of non-existing app token: "
                        + mToken);
                return;
            }

            final AppWindowToken wtoken = mContainer;

            if (DEBUG_APP_TRANSITIONS || DEBUG_ORIENTATION) Slog.v(TAG_WM, "setAppVisibility("
                    + mToken + ", visible=" + visible + "): " + mService.mAppTransition
                    + " hidden=" + wtoken.hidden + " hiddenRequested="
                    + wtoken.hiddenRequested + " Callers=" + Debug.getCallers(6));

            mService.mOpeningApps.remove(wtoken);
            mService.mClosingApps.remove(wtoken);
            wtoken.waitingToShow = false;
            wtoken.hiddenRequested = !visible;

            if (!visible) {
                // If the app is dead while it was visible, we kept its dead window on screen.
                // Now that the app is going invisible, we can remove it. It will be restarted
                // if made visible again.
                wtoken.removeDeadWindows();
                wtoken.setVisibleBeforeClientHidden();
            } else {
                if (!mService.mAppTransition.isTransitionSet()
                        && mService.mAppTransition.isReady()) {
                    // Add the app mOpeningApps if transition is unset but ready. This means
                    // we're doing a screen freeze, and the unfreeze will wait for all opening
                    // apps to be ready.
                    mService.mOpeningApps.add(wtoken);
                }
                wtoken.startingMoved = false;
                // If the token is currently hidden (should be the common case), or has been
                // stopped, then we need to set up to wait for its windows to be ready.
                if (wtoken.hidden || wtoken.mAppStopped) {
                    wtoken.clearAllDrawn();

                    // If the app was already visible, don't reset the waitingToShow state.
                    if (wtoken.hidden) {
                        wtoken.waitingToShow = true;
                    }

                    if (wtoken.clientHidden) {
                        // In the case where we are making an app visible
                        // but holding off for a transition, we still need
                        // to tell the client to make its windows visible so
                        // they get drawn.  Otherwise, we will wait on
                        // performing the transition until all windows have
                        // been drawn, they never will be, and we are sad.
                        wtoken.clientHidden = false;
                        wtoken.sendAppVisibilityToClients();
                    }
                }
                wtoken.requestUpdateWallpaperIfNeeded();

                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "No longer Stopped: " + wtoken);
                wtoken.mAppStopped = false;
            }

            // If we are preparing an app transition, then delay changing
            // the visibility of this token until we execute that transition.
            if (mService.okToDisplay() && mService.mAppTransition.isTransitionSet()) {
                // A dummy animation is a placeholder animation which informs others that an
                // animation is going on (in this case an application transition). If the animation
                // was transferred from another application/animator, no dummy animator should be
                // created since an animation is already in progress.
                if (wtoken.mAppAnimator.usingTransferredAnimation
                        && wtoken.mAppAnimator.animation == null) {
                    Slog.wtf(TAG_WM, "Will NOT set dummy animation on: " + wtoken
                            + ", using null transferred animation!");
                }
                if (!wtoken.mAppAnimator.usingTransferredAnimation &&
                        (!wtoken.startingDisplayed || mService.mSkipAppTransitionAnimation)) {
                    if (DEBUG_APP_TRANSITIONS) Slog.v(
                            TAG_WM, "Setting dummy animation on: " + wtoken);
                    wtoken.mAppAnimator.setDummyAnimation();
                }
                wtoken.inPendingTransaction = true;
                if (visible) {
                    mService.mOpeningApps.add(wtoken);
                    wtoken.mEnteringAnimation = true;
                } else {
                    mService.mClosingApps.add(wtoken);
                    wtoken.mEnteringAnimation = false;
                }
                if (mService.mAppTransition.getAppTransition()
                        == AppTransition.TRANSIT_TASK_OPEN_BEHIND) {
                    // We're launchingBehind, add the launching activity to mOpeningApps.
                    final WindowState win =
                            mService.getDefaultDisplayContentLocked().findFocusedWindow();
                    if (win != null) {
                        final AppWindowToken focusedToken = win.mAppToken;
                        if (focusedToken != null) {
                            if (DEBUG_APP_TRANSITIONS) Slog.d(TAG_WM, "TRANSIT_TASK_OPEN_BEHIND, "
                                    + " adding " + focusedToken + " to mOpeningApps");
                            // Force animation to be loaded.
                            focusedToken.hidden = true;
                            mService.mOpeningApps.add(focusedToken);
                        }
                    }
                }
                return;
            }

            wtoken.setVisibility(null, visible, TRANSIT_UNSET, true, wtoken.mVoiceInteraction);
            wtoken.updateReportedVisibilityLocked();
        }
    }

    /**
     * Notifies that we launched an app that might be visible or not visible depending on what kind
     * of Keyguard flags it's going to set on its windows.
     */
    public void notifyUnknownVisibilityLaunched() {
        synchronized(mWindowMap) {
            if (mContainer != null) {
                mService.mUnknownAppVisibilityController.notifyLaunched(mContainer);
            }
        }
    }

    public boolean addStartingWindow(String pkg, int theme, CompatibilityInfo compatInfo,
            CharSequence nonLocalizedLabel, int labelRes, int icon, int logo, int windowFlags,
            IBinder transferFrom, boolean newTask, boolean taskSwitch, boolean processRunning) {
        synchronized(mWindowMap) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "setAppStartingWindow: token=" + mToken
                    + " pkg=" + pkg + " transferFrom=" + transferFrom);

            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to set icon of non-existing app token: " + mToken);
                return false;
            }

            // If the display is frozen, we won't do anything until the actual window is
            // displayed so there is no reason to put in the starting window.
            if (!mService.okToDisplay()) {
                return false;
            }

            if (mContainer.startingData != null) {
                return false;
            }

            final int type = getStartingWindowType(newTask, taskSwitch, processRunning);

            if (type == STARTING_WINDOW_TYPE_SNAPSHOT) {
                return createSnapshot();
            }

            // If this is a translucent window, then don't show a starting window -- the current
            // effect (a full-screen opaque starting window that fades away to the real contents
            // when it is ready) does not work for this.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Checking theme of starting window: 0x"
                    + Integer.toHexString(theme));
            if (theme != 0) {
                AttributeCache.Entry ent = AttributeCache.instance().get(pkg, theme,
                        com.android.internal.R.styleable.Window, mService.mCurrentUserId);
                if (ent == null) {
                    // Whoops!  App doesn't exist. Um. Okay. We'll just pretend like we didn't
                    // see that.
                    return false;
                }
                final boolean windowIsTranslucent = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowIsTranslucent, false);
                final boolean windowIsFloating = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowIsFloating, false);
                final boolean windowShowWallpaper = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowShowWallpaper, false);
                final boolean windowDisableStarting = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowDisablePreview, false);
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Translucent=" + windowIsTranslucent
                        + " Floating=" + windowIsFloating
                        + " ShowWallpaper=" + windowShowWallpaper);
                if (windowIsTranslucent) {
                    return false;
                }
                if (windowIsFloating || windowDisableStarting) {
                    return false;
                }
                if (windowShowWallpaper) {
                    if (mContainer.getDisplayContent().mWallpaperController.getWallpaperTarget()
                            == null) {
                        // If this theme is requesting a wallpaper, and the wallpaper
                        // is not currently visible, then this effectively serves as
                        // an opaque window and our starting window transition animation
                        // can still work.  We just need to make sure the starting window
                        // is also showing the wallpaper.
                        windowFlags |= FLAG_SHOW_WALLPAPER;
                    } else {
                        return false;
                    }
                }
            }

            if (mContainer.transferStartingWindow(transferFrom)) {
                return true;
            }

            // There is no existing starting window, and we don't want to create a splash screen, so
            // that's it!
            if (type != STARTING_WINDOW_TYPE_SPLASH_SCREEN) {
                return false;
            }

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Creating StartingData");
            mContainer.startingData = new SplashScreenStartingData(mService, pkg, theme,
                    compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                    mContainer.getMergedOverrideConfiguration());
            scheduleAddStartingWindow();
        }
        return true;
    }

    private int getStartingWindowType(boolean newTask, boolean taskSwitch, boolean processRunning) {
        if (newTask || !processRunning) {
            return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else if (taskSwitch) {
            return STARTING_WINDOW_TYPE_SNAPSHOT;
        } else {
            return STARTING_WINDOW_TYPE_NONE;
        }
    }

    void scheduleAddStartingWindow() {
        // Note: we really want to do sendMessageAtFrontOfQueue() because we
        // want to process the message ASAP, before any other queued
        // messages.
        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Enqueueing ADD_STARTING");
        mHandler.postAtFrontOfQueue(mAddStartingWindow);
    }

    private boolean createSnapshot() {
        final TaskSnapshot snapshot = mService.mTaskSnapshotController.getSnapshot(
                mContainer.mTask.mTaskId, mContainer.mTask.mUserId, false /* restoreFromDisk */);

        if (snapshot == null) {
            return false;
        }

        mContainer.startingData = new SnapshotStartingData(mService, snapshot.getSnapshot());
        scheduleAddStartingWindow();
        return true;
    }

    public void removeStartingWindow() {
        synchronized (mWindowMap) {
            if (mHandler.hasCallbacks(mRemoveStartingWindow)) {
                // Already scheduled.
                return;
            }

            if (mContainer.startingWindow == null) {
                if (mContainer.startingData != null) {
                    // Starting window has not been added yet, but it is scheduled to be added.
                    // Go ahead and cancel the request.
                    if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                            "Clearing startingData for token=" + mContainer);
                    mContainer.startingData = null;
                }
                return;
            }

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, Debug.getCallers(1)
                    + ": Schedule remove starting " + mContainer
                    + " startingWindow=" + mContainer.startingWindow);
            mHandler.post(mRemoveStartingWindow);
        }
    }

    public void pauseKeyDispatching() {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mService.mInputMonitor.pauseDispatchingLw(mContainer);
            }
        }
    }

    public void resumeKeyDispatching() {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mService.mInputMonitor.resumeDispatchingLw(mContainer);
            }
        }
    }

    public void notifyAppResumed(boolean wasStopped, boolean allowSavedSurface) {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to notify resumed of non-existing app token: " + mToken);
                return;
            }
            mContainer.notifyAppResumed(wasStopped, allowSavedSurface);
        }
    }

    public void notifyAppStopped() {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to notify stopped of non-existing app token: "
                        + mToken);
                return;
            }
            mContainer.notifyAppStopped();
        }
    }

    public void startFreezingScreen(int configChanges) {
        synchronized(mWindowMap) {
            if (configChanges == 0 && mService.okToDisplay()) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Skipping set freeze of " + mToken);
                return;
            }

            if (mContainer == null) {
                Slog.w(TAG_WM,
                        "Attempted to freeze screen with non-existing app token: " + mContainer);
                return;
            }
            mContainer.startFreezingScreen();
        }
    }

    public void stopFreezingScreen(boolean force) {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                return;
            }
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Clear freezing of " + mToken + ": hidden="
                    + mContainer.hidden + " freezing=" + mContainer.mAppAnimator.freezingScreen);
            mContainer.stopFreezingScreen(true, force);
        }
    }

    /**
     * Takes a snapshot of the screen. In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the full screenshot.
     *
     * @param displayId the Display to take a screenshot of.
     * @param width the width of the target bitmap
     * @param height the height of the target bitmap
     * @param frameScale the scale to apply to the frame, only used when width = -1 and height = -1
     */
    public Bitmap screenshotApplications(int displayId, int width, int height, float frameScale) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "screenshotApplications");
            final DisplayContent dc;
            synchronized(mWindowMap) {
                dc = mRoot.getDisplayContentOrCreate(displayId);
                if (dc == null) {
                    if (DEBUG_SCREENSHOT) Slog.i(TAG_WM, "Screenshot of " + mToken
                            + ": returning null. No Display for displayId=" + displayId);
                    return null;
                }
            }
            return dc.screenshotApplications(mToken.asBinder(), width, height,
                    false /* includeFullDisplay */, frameScale, Bitmap.Config.RGB_565,
                    false /* wallpaperOnly */, false /* includeDecor */);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }


    void reportWindowsDrawn() {
        mHandler.post(mOnWindowsDrawn);
    }

    void reportWindowsVisible() {
        mHandler.post(mOnWindowsVisible);
    }

    void reportWindowsGone() {
        mHandler.post(mOnWindowsGone);
    }

    /** Calls directly into activity manager so window manager lock shouldn't held. */
    boolean keyDispatchingTimedOut(String reason) {
        return mListener != null && mListener.keyDispatchingTimedOut(reason);
    }

    @Override
    public String toString() {
        return "{AppWindowContainerController token=" + mToken + "}";
    }
}
