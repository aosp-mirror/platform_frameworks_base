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

import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static android.view.WindowManager.TRANSIT_UNSET;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManager.TaskSnapshot;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AttributeCache;
import com.android.server.policy.WindowManagerPolicy.StartingSurface;

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

    private final class H extends Handler {
        public static final int NOTIFY_WINDOWS_DRAWN = 1;
        public static final int NOTIFY_STARTING_WINDOW_DRAWN = 2;

        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case NOTIFY_WINDOWS_DRAWN:
                    if (mListener == null) {
                        return;
                    }
                    if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting drawn in "
                            + AppWindowContainerController.this.mToken);
                    mListener.onWindowsDrawn(msg.getWhen());
                    break;
                case NOTIFY_STARTING_WINDOW_DRAWN:
                    if (mListener == null) {
                        return;
                    }
                    if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting drawn in "
                            + AppWindowContainerController.this.mToken);
                    mListener.onStartingWindowDrawn(msg.getWhen());
                    break;
                default:
                    break;
            }
        }
    }

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

    private final Runnable mAddStartingWindow = new Runnable() {

        @Override
        public void run() {
            final StartingData startingData;
            final AppWindowToken container;

            synchronized (mWindowMap) {
                if (mContainer == null) {
                    if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "mContainer was null while trying to"
                            + " add starting window");
                    return;
                }

                // There can only be one adding request, silly caller!
                mService.mAnimationHandler.removeCallbacks(this);

                startingData = mContainer.startingData;
                container = mContainer;
            }

            if (startingData == null) {
                // Animation has been canceled... do nothing.
                if (DEBUG_STARTING_WINDOW)
                    Slog.v(TAG_WM, "startingData was nulled out before handling"
                            + " mAddStartingWindow: " + mContainer);
                return;
            }

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Add starting "
                    + AppWindowContainerController.this + ": startingData="
                    + container.startingData);

            StartingSurface surface = null;
            try {
                surface = startingData.createStartingSurface(container);
            } catch (Exception e) {
                Slog.w(TAG_WM, "Exception when adding starting window", e);
            }
            if (surface != null) {
                boolean abort = false;
                synchronized (mWindowMap) {
                    // If the window was successfully added, then
                    // we need to remove it.
                    if (container.removed || container.startingData == null) {
                        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                                "Aborted starting " + container
                                        + ": removed=" + container.removed
                                        + " startingData=" + container.startingData);
                        container.startingWindow = null;
                        container.startingData = null;
                        abort = true;
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
            } else if (DEBUG_STARTING_WINDOW) {
                Slog.v(TAG_WM, "Surface returned was null: " + mContainer);
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
        mHandler = new H(service.mH.getLooper());
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

            atoken = createAppWindow(mService, token, voiceInteraction, task.getDisplayContent(),
                    inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdkVersion,
                    requestedOrientation, rotationAnimationHint, configChanges, launchTaskBehind,
                    alwaysFocusable, this);
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "addAppToken: " + atoken
                    + " controller=" + taskController + " at " + index);
            task.addChild(atoken, index);
        }
    }

    @VisibleForTesting
    AppWindowToken createAppWindow(WindowManagerService service, IApplicationToken token,
            boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
            boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
            int rotationAnimationHint, int configChanges, boolean launchTaskBehind,
            boolean alwaysFocusable, AppWindowContainerController controller) {
        return new AppWindowToken(service, token, voiceInteraction, dc,
                inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk, orientation,
                rotationAnimationHint, configChanges, launchTaskBehind, alwaysFocusable,
                controller);
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

    public void reparent(TaskWindowContainerController taskController, int position) {
        synchronized (mWindowMap) {
            if (DEBUG_ADD_REMOVE) Slog.i(TAG_WM, "reparent: moving app token=" + mToken
                    + " to task=" + taskController + " at " + position);
            if (mContainer == null) {
                if (DEBUG_ADD_REMOVE) Slog.i(TAG_WM,
                        "reparent: could not find app token=" + mToken);
                return;
            }
            final Task task = taskController.mContainer;
            if (task == null) {
                throw new IllegalArgumentException("reparent: could not find task="
                        + taskController);
            }
            mContainer.reparent(task, position);
            mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
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

    public void setDisablePreviewScreenshots(boolean disable) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to set disable screenshots of non-existing app"
                        + " token: " + mToken);
                return;
            }
            mContainer.setDisablePreviewScreenshots(disable);
        }
    }

    public void setVisibility(boolean visible, boolean deferHidingClient) {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to set visibility of non-existing app token: "
                        + mToken);
                return;
            }

            final AppWindowToken wtoken = mContainer;

            // Don't set visibility to false if we were already not visible. This prevents WM from
            // adding the app to the closing app list which doesn't make sense for something that is
            // already not visible. However, set visibility to true even if we are already visible.
            // This makes sure the app is added to the opening apps list so that the right
            // transition can be selected.
            // TODO: Probably a good idea to separate the concept of opening/closing apps from the
            // concept of setting visibility...
            if (!visible && wtoken.hiddenRequested) {

                if (!deferHidingClient && wtoken.mDeferHidingClient) {
                    // We previously deferred telling the client to hide itself when visibility was
                    // initially set to false. Now we would like it to hide, so go ahead and set it.
                    wtoken.mDeferHidingClient = deferHidingClient;
                    wtoken.setClientHidden(true);
                }
                return;
            }

            if (DEBUG_APP_TRANSITIONS || DEBUG_ORIENTATION) Slog.v(TAG_WM, "setAppVisibility("
                    + mToken + ", visible=" + visible + "): " + mService.mAppTransition
                    + " hidden=" + wtoken.isHidden() + " hiddenRequested="
                    + wtoken.hiddenRequested + " Callers=" + Debug.getCallers(6));

            mService.mOpeningApps.remove(wtoken);
            mService.mClosingApps.remove(wtoken);
            wtoken.waitingToShow = false;
            wtoken.hiddenRequested = !visible;
            wtoken.mDeferHidingClient = deferHidingClient;

            if (!visible) {
                // If the app is dead while it was visible, we kept its dead window on screen.
                // Now that the app is going invisible, we can remove it. It will be restarted
                // if made visible again.
                wtoken.removeDeadWindows();
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
                if (wtoken.isHidden() || wtoken.mAppStopped) {
                    wtoken.clearAllDrawn();

                    // If the app was already visible, don't reset the waitingToShow state.
                    if (wtoken.isHidden()) {
                        wtoken.waitingToShow = true;
                    }
                }

                // In the case where we are making an app visible but holding off for a transition,
                // we still need to tell the client to make its windows visible so they get drawn.
                // Otherwise, we will wait on performing the transition until all windows have been
                // drawn, they never will be, and we are sad.
                wtoken.setClientHidden(false);

                wtoken.requestUpdateWallpaperIfNeeded();

                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "No longer Stopped: " + wtoken);
                wtoken.mAppStopped = false;

                mContainer.transferStartingWindowFromHiddenAboveTokenIfNeeded();
            }

            // If we are preparing an app transition, then delay changing
            // the visibility of this token until we execute that transition.
            if (wtoken.okToAnimate() && mService.mAppTransition.isTransitionSet()) {
                wtoken.inPendingTransaction = true;
                if (visible) {
                    mService.mOpeningApps.add(wtoken);
                    wtoken.mEnteringAnimation = true;
                } else {
                    mService.mClosingApps.add(wtoken);
                    wtoken.mEnteringAnimation = false;
                }
                if (mService.mAppTransition.getAppTransition()
                        == WindowManager.TRANSIT_TASK_OPEN_BEHIND) {
                    // We're launchingBehind, add the launching activity to mOpeningApps.
                    final WindowState win =
                            mService.getDefaultDisplayContentLocked().findFocusedWindow();
                    if (win != null) {
                        final AppWindowToken focusedToken = win.mAppToken;
                        if (focusedToken != null) {
                            if (DEBUG_APP_TRANSITIONS) Slog.d(TAG_WM, "TRANSIT_TASK_OPEN_BEHIND, "
                                    + " adding " + focusedToken + " to mOpeningApps");
                            // Force animation to be loaded.
                            focusedToken.setHidden(true);
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
            IBinder transferFrom, boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents) {
        synchronized(mWindowMap) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "setAppStartingWindow: token=" + mToken
                    + " pkg=" + pkg + " transferFrom=" + transferFrom + " newTask=" + newTask
                    + " taskSwitch=" + taskSwitch + " processRunning=" + processRunning
                    + " allowTaskSnapshot=" + allowTaskSnapshot);

            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to set icon of non-existing app token: " + mToken);
                return false;
            }

            // If the display is frozen, we won't do anything until the actual window is
            // displayed so there is no reason to put in the starting window.
            if (!mContainer.okToDisplay()) {
                return false;
            }

            if (mContainer.startingData != null) {
                return false;
            }

            final WindowState mainWin = mContainer.findMainWindow();
            if (mainWin != null && mainWin.mWinAnimator.getShown()) {
                // App already has a visible window...why would you want a starting window?
                return false;
            }

            final TaskSnapshot snapshot = mService.mTaskSnapshotController.getSnapshot(
                    mContainer.getTask().mTaskId, mContainer.getTask().mUserId,
                    false /* restoreFromDisk */, false /* reducedResolution */);
            final int type = getStartingWindowType(newTask, taskSwitch, processRunning,
                    allowTaskSnapshot, activityCreated, fromRecents, snapshot);

            if (type == STARTING_WINDOW_TYPE_SNAPSHOT) {
                return createSnapshot(snapshot);
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

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Creating SplashScreenStartingData");
            mContainer.startingData = new SplashScreenStartingData(mService, pkg, theme,
                    compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                    mContainer.getMergedOverrideConfiguration());
            scheduleAddStartingWindow();
        }
        return true;
    }

    private int getStartingWindowType(boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents,
            TaskSnapshot snapshot) {
        if (mService.mAppTransition.getAppTransition() == TRANSIT_DOCK_TASK_FROM_RECENTS) {
            // TODO(b/34099271): Remove this statement to add back the starting window and figure
            // out why it causes flickering, the starting window appears over the thumbnail while
            // the docked from recents transition occurs
            return STARTING_WINDOW_TYPE_NONE;
        } else if (newTask || !processRunning || (taskSwitch && !activityCreated)) {
            return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else if (taskSwitch && allowTaskSnapshot) {
            return snapshot == null ? STARTING_WINDOW_TYPE_NONE
                    : snapshotOrientationSameAsTask(snapshot) || fromRecents
                            ? STARTING_WINDOW_TYPE_SNAPSHOT : STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else {
            return STARTING_WINDOW_TYPE_NONE;
        }
    }

    void scheduleAddStartingWindow() {
        // Note: we really want to do sendMessageAtFrontOfQueue() because we
        // want to process the message ASAP, before any other queued
        // messages.
        if (!mService.mAnimationHandler.hasCallbacks(mAddStartingWindow)) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Enqueueing ADD_STARTING");
            mService.mAnimationHandler.postAtFrontOfQueue(mAddStartingWindow);
        }
    }

    private boolean createSnapshot(TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Creating SnapshotStartingData");
        mContainer.startingData = new SnapshotStartingData(mService, snapshot);
        scheduleAddStartingWindow();
        return true;
    }

    private boolean snapshotOrientationSameAsTask(TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return mContainer.getTask().getConfiguration().orientation == snapshot.getOrientation();
    }

    public void removeStartingWindow() {
        synchronized (mWindowMap) {
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

            final StartingSurface surface;
            if (mContainer.startingData != null) {
                surface = mContainer.startingSurface;
                mContainer.startingData = null;
                mContainer.startingSurface = null;
                mContainer.startingWindow = null;
                mContainer.startingDisplayed = false;
                if (surface == null) {
                    if (DEBUG_STARTING_WINDOW) {
                        Slog.v(TAG_WM, "startingWindow was set but startingSurface==null, couldn't "
                                + "remove");
                    }
                    return;
                }
            } else {
                if (DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG_WM, "Tried to remove starting window but startingWindow was null:"
                            + mContainer);
                }
                return;
            }

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Schedule remove starting " + mContainer
                    + " startingWindow=" + mContainer.startingWindow
                    + " startingView=" + mContainer.startingSurface
                    + " Callers=" + Debug.getCallers(5));

            // Use the same thread to remove the window as we used to add it, as otherwise we end up
            // with things in the view hierarchy being called from different threads.
            mService.mAnimationHandler.post(() -> {
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Removing startingView=" + surface);
                try {
                    surface.remove();
                } catch (Exception e) {
                    Slog.w(TAG_WM, "Exception when removing starting window", e);
                }
            });
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

    public void notifyAppResumed(boolean wasStopped) {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to notify resumed of non-existing app token: " + mToken);
                return;
            }
            mContainer.notifyAppResumed(wasStopped);
        }
    }

    public void notifyAppStopping() {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to notify stopping on non-existing app token: "
                        + mToken);
                return;
            }
            mContainer.detachChildren();
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
            if (mContainer == null) {
                Slog.w(TAG_WM,
                        "Attempted to freeze screen with non-existing app token: " + mContainer);
                return;
            }

            if (configChanges == 0 && mContainer.okToDisplay()) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Skipping set freeze of " + mToken);
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
                    + mContainer.isHidden() + " freezing=" + mContainer.isFreezingScreen());
            mContainer.stopFreezingScreen(true, force);
        }
    }

    public void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "Attempted to register remote animations with non-existing app"
                        + " token: " + mToken);
                return;
            }
            mContainer.registerRemoteAnimations(definition);
        }
    }

    void reportStartingWindowDrawn() {
        mHandler.sendMessage(mHandler.obtainMessage(H.NOTIFY_STARTING_WINDOW_DRAWN));
    }

    void reportWindowsDrawn() {
        mHandler.sendMessage(mHandler.obtainMessage(H.NOTIFY_WINDOWS_DRAWN));
    }

    void reportWindowsVisible() {
        mHandler.post(mOnWindowsVisible);
    }

    void reportWindowsGone() {
        mHandler.post(mOnWindowsGone);
    }

    /** Calls directly into activity manager so window manager lock shouldn't held. */
    boolean keyDispatchingTimedOut(String reason, int windowPid) {
        return mListener != null && mListener.keyDispatchingTimedOut(reason, windowPid);
    }

    @Override
    public String toString() {
        return "AppWindowContainerController{"
                + " token=" + mToken
                + " mContainer=" + mContainer
                + " mListener=" + mListener
                + "}";
    }
}
