/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.app.ActivityManager.StackId;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.WINDOW_REPLACEMENT_TIMEOUT_DURATION;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_ACTIVITY_DRAWN;

import com.android.server.input.InputApplicationHandle;
import com.android.server.wm.WindowManagerService.H;

import android.annotation.NonNull;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.View;
import android.view.WindowManager;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;

class AppTokenList extends ArrayList<AppWindowToken> {
}

/**
 * Version of WindowToken that is specifically for a particular application (or
 * really activity) that is displaying windows.
 */
class AppWindowToken extends WindowToken {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppWindowToken" : TAG_WM;

    // Non-null only for application tokens.
    final IApplicationToken appToken;

    // All of the windows and child windows that are included in this
    // application token.  Note this list is NOT sorted!
    final WindowList allAppWindows = new WindowList();
    @NonNull final AppWindowAnimator mAppAnimator;

    final boolean voiceInteraction;

    Task mTask;
    boolean appFullscreen;
    int requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    boolean layoutConfigChanges;
    boolean showForAllUsers;
    int targetSdk;

    // The input dispatching timeout for this application token in nanoseconds.
    long inputDispatchingTimeoutNanos;

    // These are used for determining when all windows associated with
    // an activity have been drawn, so they can be made visible together
    // at the same time.
    // initialize so that it doesn't match mTransactionSequence which is an int.
    long lastTransactionSequence = Long.MIN_VALUE;
    int numInterestingWindows;
    int numDrawnWindows;
    boolean inPendingTransaction;
    boolean allDrawn;
    // Set to true when this app creates a surface while in the middle of an animation. In that
    // case do not clear allDrawn until the animation completes.
    boolean deferClearAllDrawn;

    // These are to track the app's real drawing status if there were no saved surfaces.
    boolean allDrawnExcludingSaved;
    int numInterestingWindowsExcludingSaved;
    int numDrawnWindowsExclusingSaved;

    // Is this window's surface needed?  This is almost like hidden, except
    // it will sometimes be true a little earlier: when the token has
    // been shown, but is still waiting for its app transition to execute
    // before making its windows shown.
    boolean hiddenRequested;

    // Have we told the window clients to hide themselves?
    boolean clientHidden;

    // Last visibility state we reported to the app token.
    boolean reportedVisible;

    // Last drawn state we reported to the app token.
    boolean reportedDrawn;

    // Set to true when the token has been removed from the window mgr.
    boolean removed;

    // Information about an application starting window if displayed.
    StartingData startingData;
    WindowState startingWindow;
    View startingView;
    boolean startingDisplayed;
    boolean startingMoved;
    boolean firstWindowDrawn;

    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    boolean mIsExiting;

    boolean mLaunchTaskBehind;
    boolean mEnteringAnimation;

    boolean mAlwaysFocusable;

    boolean mAppStopped;
    int mPendingRelaunchCount;

    private ArrayList<WindowSurfaceController.SurfaceControlWithBackground> mSurfaceViewBackgrounds =
        new ArrayList<WindowSurfaceController.SurfaceControlWithBackground>();

    ArrayDeque<Rect> mFrozenBounds = new ArrayDeque<>();
    ArrayDeque<Configuration> mFrozenMergedConfig = new ArrayDeque<>();

    AppWindowToken(WindowManagerService _service, IApplicationToken _token,
            boolean _voiceInteraction) {
        super(_service, _token.asBinder(),
                WindowManager.LayoutParams.TYPE_APPLICATION, true);
        appWindowToken = this;
        appToken = _token;
        voiceInteraction = _voiceInteraction;
        mInputApplicationHandle = new InputApplicationHandle(this);
        mAppAnimator = new AppWindowAnimator(this);
    }

    void sendAppVisibilityToClients() {
        final int N = allAppWindows.size();
        for (int i=0; i<N; i++) {
            WindowState win = allAppWindows.get(i);
            if (win == startingWindow && clientHidden) {
                // Don't hide the starting window.
                continue;
            }
            try {
                if (DEBUG_VISIBILITY) Slog.v(TAG,
                        "Setting visibility of " + win + ": " + (!clientHidden));
                win.mClient.dispatchAppVisibility(!clientHidden);
            } catch (RemoteException e) {
            }
        }
    }

    void setVisibleBeforeClientHidden() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            w.setVisibleBeforeClientHidden();
        }
    }

    void onFirstWindowDrawn(WindowState win, WindowStateAnimator winAnimator) {
        firstWindowDrawn = true;

        // We now have a good window to show, remove dead placeholders
        removeAllDeadWindows();

        if (startingData != null) {
            if (DEBUG_STARTING_WINDOW || DEBUG_ANIM) Slog.v(TAG, "Finish starting "
                    + win.mToken + ": first real window is shown, no animation");
            // If this initial window is animating, stop it -- we will do an animation to reveal
            // it from behind the starting window, so there is no need for it to also be doing its
            // own stuff.
            winAnimator.clearAnimation();
            winAnimator.mService.mFinishedStarting.add(this);
            winAnimator.mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
        }
        updateReportedVisibilityLocked();
    }

    void updateReportedVisibilityLocked() {
        if (appToken == null) {
            return;
        }

        int numInteresting = 0;
        int numVisible = 0;
        int numDrawn = 0;
        boolean nowGone = true;

        if (DEBUG_VISIBILITY) Slog.v(TAG,
                "Update reported visibility: " + this);
        final int N = allAppWindows.size();
        for (int i=0; i<N; i++) {
            WindowState win = allAppWindows.get(i);
            if (win == startingWindow || win.mAppFreezing
                    || win.mViewVisibility != View.VISIBLE
                    || win.mAttrs.type == TYPE_APPLICATION_STARTING
                    || win.mDestroying) {
                continue;
            }
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG, "Win " + win + ": isDrawn="
                        + win.isDrawnLw()
                        + ", isAnimationSet=" + win.mWinAnimator.isAnimationSet());
                if (!win.isDrawnLw()) {
                    Slog.v(TAG, "Not displayed: s=" +
                            win.mWinAnimator.mSurfaceController
                            + " pv=" + win.mPolicyVisibility
                            + " mDrawState=" + win.mWinAnimator.mDrawState
                            + " ah=" + win.mAttachedHidden
                            + " th="
                            + (win.mAppToken != null
                                    ? win.mAppToken.hiddenRequested : false)
                            + " a=" + win.mWinAnimator.mAnimating);
                }
            }
            numInteresting++;
            if (win.isDrawnLw()) {
                numDrawn++;
                if (!win.mWinAnimator.isAnimationSet()) {
                    numVisible++;
                }
                nowGone = false;
            } else if (win.mWinAnimator.isAnimationSet()) {
                nowGone = false;
            }
        }

        boolean nowDrawn = numInteresting > 0 && numDrawn >= numInteresting;
        boolean nowVisible = numInteresting > 0 && numVisible >= numInteresting;
        if (!nowGone) {
            // If the app is not yet gone, then it can only become visible/drawn.
            if (!nowDrawn) {
                nowDrawn = reportedDrawn;
            }
            if (!nowVisible) {
                nowVisible = reportedVisible;
            }
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG, "VIS " + this + ": interesting="
                + numInteresting + " visible=" + numVisible);
        if (nowDrawn != reportedDrawn) {
            if (nowDrawn) {
                Message m = service.mH.obtainMessage(
                        H.REPORT_APPLICATION_TOKEN_DRAWN, this);
                service.mH.sendMessage(m);
            }
            reportedDrawn = nowDrawn;
        }
        if (nowVisible != reportedVisible) {
            if (DEBUG_VISIBILITY) Slog.v(
                    TAG, "Visibility changed in " + this
                    + ": vis=" + nowVisible);
            reportedVisible = nowVisible;
            Message m = service.mH.obtainMessage(
                    H.REPORT_APPLICATION_TOKEN_WINDOWS,
                    nowVisible ? 1 : 0,
                    nowGone ? 1 : 0,
                    this);
            service.mH.sendMessage(m);
        }
    }

    WindowState findMainWindow() {
        WindowState candidate = null;
        int j = windows.size();
        while (j > 0) {
            j--;
            WindowState win = windows.get(j);
            if (win.mAttrs.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                    || win.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
                // In cases where there are multiple windows, we prefer the non-exiting window. This
                // happens for example when replacing windows during an activity relaunch. When
                // constructing the animation, we want the new window, not the exiting one.
                if (win.mAnimatingExit) {
                    candidate = win;
                } else {
                    return win;
                }
            }
        }
        return candidate;
    }

    boolean windowsAreFocusable() {
        return StackId.canReceiveKeys(mTask.mStack.mStackId) || mAlwaysFocusable;
    }

    boolean isVisible() {
        final int N = allAppWindows.size();
        for (int i=0; i<N; i++) {
            WindowState win = allAppWindows.get(i);
            // If we're animating with a saved surface, we're already visible.
            // Return true so that the alpha doesn't get cleared.
            if (!win.mAppFreezing
                    && (win.mViewVisibility == View.VISIBLE || win.isAnimatingWithSavedSurface()
                            || (win.mWinAnimator.isAnimationSet()
                                    && !service.mAppTransition.isTransitionSet()))
                    && !win.mDestroying
                    && win.isDrawnLw()) {
                return true;
            }
        }
        return false;
    }

    void removeAppFromTaskLocked() {
        mIsExiting = false;
        removeAllWindows();

        // Use local variable because removeAppToken will null out mTask.
        final Task task = mTask;
        if (task != null) {
            if (!task.removeAppToken(this)) {
                Slog.e(TAG, "removeAppFromTaskLocked: token=" + this
                        + " not found.");
            }
            task.mStack.mExitingAppTokens.remove(this);
        }
    }

    // Here we destroy surfaces which have been marked as eligible by the animator, taking care
    // to ensure the client has finished with them. If the client could still be using them
    // we will skip destruction and try again when the client has stopped.
    void destroySurfaces() {
        final ArrayList<WindowState> allWindows = (ArrayList<WindowState>) allAppWindows.clone();
        final DisplayContentList displayList = new DisplayContentList();
        for (int i = allWindows.size() - 1; i >= 0; i--) {
            final WindowState win = allWindows.get(i);

            if (!(mAppStopped || win.mWindowRemovalAllowed)) {
                continue;
            }

            win.mWinAnimator.destroyPreservedSurfaceLocked();

            if (!win.mDestroying) {
                continue;
            }

            if (DEBUG_ADD_REMOVE) Slog.e(TAG_WM, "win=" + win
                    + " destroySurfaces: mAppStopped=" + mAppStopped
                    + " win.mWindowRemovalAllowed=" + win.mWindowRemovalAllowed
                    + " win.mRemoveOnExit=" + win.mRemoveOnExit);

            win.destroyOrSaveSurface();
            if (win.mRemoveOnExit) {
                service.removeWindowInnerLocked(win);
            }
            final DisplayContent displayContent = win.getDisplayContent();
            if (displayContent != null && !displayList.contains(displayContent)) {
                displayList.add(displayContent);
            }
            win.mDestroying = false;
        }
        for (int i = 0; i < displayList.size(); i++) {
            final DisplayContent displayContent = displayList.get(i);
            service.mLayersController.assignLayersLocked(displayContent.getWindowList());
            displayContent.layoutNeeded = true;
        }
    }

    /**
     * If the application has stopped it is okay to destroy any surfaces which were keeping alive
     * in case they were still being used.
     */
    void notifyAppStopped(boolean stopped) {
        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "notifyAppStopped: stopped=" + stopped + " " + this);
        mAppStopped = stopped;

        if (stopped) {
            destroySurfaces();
            // Remove any starting window that was added for this app if they are still around.
            mTask.mService.scheduleRemoveStartingWindowLocked(this);
        }
    }

    /**
     * Checks whether we should save surfaces for this app.
     *
     * @return true if the surfaces should be saved, false otherwise.
     */
    boolean shouldSaveSurface() {
        // We want to save surface if the app's windows are "allDrawn".
        // (If we started entering animation early with saved surfaces, allDrawn
        // should have been restored to true. So we'll save again in that case
        // even if app didn't actually finish drawing.)
        return allDrawn;
    }

    boolean canRestoreSurfaces() {
        for (int i = allAppWindows.size() -1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            if (w.canRestoreSurface()) {
                return true;
            }
        }
        return false;
    }

    void clearVisibleBeforeClientHidden() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            w.clearVisibleBeforeClientHidden();
        }
    }

    /**
     * Whether the app has some window that is invisible in layout, but
     * animating with saved surface.
     */
    boolean isAnimatingInvisibleWithSavedSurface() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            if (w.isAnimatingInvisibleWithSavedSurface()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hide all window surfaces that's still invisible in layout but animating
     * with a saved surface, and mark them destroying.
     */
    void stopUsingSavedSurfaceLocked() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            if (w.isAnimatingInvisibleWithSavedSurface()) {
                if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.d(TAG,
                        "stopUsingSavedSurfaceLocked: " + w);
                w.clearAnimatingWithSavedSurface();
                w.mDestroying = true;
                w.mWinAnimator.hide("stopUsingSavedSurfaceLocked");
                w.mWinAnimator.mWallpaperControllerLocked.hideWallpapers(w);
            }
        }
        destroySurfaces();
    }

    void markSavedSurfaceExiting() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            if (w.isAnimatingInvisibleWithSavedSurface()) {
                w.mAnimatingExit = true;
                w.mWinAnimator.mAnimating = true;
            }
        }
    }

    void restoreSavedSurfaces() {
        if (!canRestoreSurfaces()) {
            clearVisibleBeforeClientHidden();
            return;
        }
        // Check if we have enough drawn windows to mark allDrawn= true.
        int numInteresting = 0;
        int numDrawn = 0;
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            WindowState w = allAppWindows.get(i);
            if (w != startingWindow && !w.mAppDied && w.wasVisibleBeforeClientHidden()
                    && (!mAppAnimator.freezingScreen || !w.mAppFreezing)) {
                numInteresting++;
                if (w.hasSavedSurface()) {
                    w.restoreSavedSurface();
                }
                if (w.isDrawnLw()) {
                    numDrawn++;
                }
            }
        }

        if (!allDrawn) {
            allDrawn = (numInteresting > 0) && (numInteresting == numDrawn);
            if (allDrawn) {
                service.mH.obtainMessage(NOTIFY_ACTIVITY_DRAWN, token).sendToTarget();
            }
        }
        clearVisibleBeforeClientHidden();

        if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.d(TAG,
                "restoreSavedSurfaces: " + appWindowToken + " allDrawn=" + allDrawn
                + " numInteresting=" + numInteresting + " numDrawn=" + numDrawn);
    }

    void destroySavedSurfaces() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            WindowState win = allAppWindows.get(i);
            win.destroySavedSurface();
        }
    }

    void clearAllDrawn() {
        allDrawn = false;
        deferClearAllDrawn = false;
        allDrawnExcludingSaved = false;
    }

    @Override
    void removeAllWindows() {
        for (int winNdx = allAppWindows.size() - 1; winNdx >= 0;
                // removeWindowLocked at bottom of loop may remove multiple entries from
                // allAppWindows if the window to be removed has child windows. It also may
                // not remove any windows from allAppWindows at all if win is exiting and
                // currently animating away. This ensures that winNdx is monotonically decreasing
                // and never beyond allAppWindows bounds.
                winNdx = Math.min(winNdx - 1, allAppWindows.size() - 1)) {
            WindowState win = allAppWindows.get(winNdx);
            if (DEBUG_WINDOW_MOVEMENT) {
                Slog.w(TAG, "removeAllWindows: removing win=" + win);
            }

            service.removeWindowLocked(win);
        }
        allAppWindows.clear();
        windows.clear();
    }

    void removeAllDeadWindows() {
        for (int winNdx = allAppWindows.size() - 1; winNdx >= 0;
            // removeWindowLocked at bottom of loop may remove multiple entries from
            // allAppWindows if the window to be removed has child windows. It also may
            // not remove any windows from allAppWindows at all if win is exiting and
            // currently animating away. This ensures that winNdx is monotonically decreasing
            // and never beyond allAppWindows bounds.
            winNdx = Math.min(winNdx - 1, allAppWindows.size() - 1)) {
            WindowState win = allAppWindows.get(winNdx);
            if (win.mAppDied) {
                if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) {
                    Slog.w(TAG, "removeAllDeadWindows: " + win);
                }
                // Set mDestroying, we don't want any animation or delayed removal here.
                win.mDestroying = true;
                service.removeWindowLocked(win);
            }
        }
    }

    boolean hasWindowsAlive() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            if (!allAppWindows.get(i).mAppDied) {
                return true;
            }
        }
        return false;
    }

    void setReplacingWindows(boolean animate) {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM, "Marking app token " + appWindowToken
                + " with replacing windows.");

        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            w.setReplacing(animate);
        }
        if (animate) {
            // Set-up dummy animation so we can start treating windows associated with this
            // token like they are in transition before the new app window is ready for us to
            // run the real transition animation.
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM,
                    "setReplacingWindow() Setting dummy animation on: " + this);
            mAppAnimator.setDummyAnimation();
        }
    }

    void setReplacingChildren() {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM, "Marking app token " + appWindowToken
                + " with replacing child windows.");
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            if (w.shouldBeReplacedWithChildren()) {
                w.setReplacing(false /* animate */);
            }
        }
    }

    void resetReplacingWindows() {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM, "Resetting app token " + appWindowToken
                + " of replacing window marks.");

        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            w.resetReplacing();
        }
    }

    void requestUpdateWallpaperIfNeeded() {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            final WindowState w = allAppWindows.get(i);
            w.requestUpdateWallpaperIfNeeded();
        }
    }

    boolean isRelaunching() {
        return mPendingRelaunchCount > 0;
    }

    void startRelaunching() {
        if (canFreezeBounds()) {
            freezeBounds();
        }
        mPendingRelaunchCount++;
    }

    void finishRelaunching() {
        if (canFreezeBounds()) {
            unfreezeBounds();
        }
        if (mPendingRelaunchCount > 0) {
            mPendingRelaunchCount--;
        }
    }

    void addWindow(WindowState w) {
        for (int i = allAppWindows.size() - 1; i >= 0; i--) {
            WindowState candidate = allAppWindows.get(i);
            if (candidate.mWillReplaceWindow && candidate.mReplacingWindow == null &&
                    candidate.getWindowTag().toString().equals(w.getWindowTag().toString())) {
                candidate.mReplacingWindow = w;
                w.mSkipEnterAnimationForSeamlessReplacement = !candidate.mAnimateReplacingWindow;

                // if we got a replacement window, reset the timeout to give drawing more time
                service.scheduleReplacingWindowTimeouts(this);
            }
        }
        allAppWindows.add(w);
    }

    boolean waitingForReplacement() {
        for (int i = allAppWindows.size() -1; i >= 0; i--) {
            WindowState candidate = allAppWindows.get(i);
            if (candidate.mWillReplaceWindow) {
                return true;
            }
        }
        return false;
    }

    void clearTimedoutReplacesLocked() {
        for (int i = allAppWindows.size() - 1; i >= 0;
             // removeWindowLocked at bottom of loop may remove multiple entries from
             // allAppWindows if the window to be removed has child windows. It also may
             // not remove any windows from allAppWindows at all if win is exiting and
             // currently animating away. This ensures that winNdx is monotonically decreasing
             // and never beyond allAppWindows bounds.
             i = Math.min(i - 1, allAppWindows.size() - 1)) {
            WindowState candidate = allAppWindows.get(i);
            if (candidate.mWillReplaceWindow == false) {
                continue;
            }
            candidate.mWillReplaceWindow = false;
            if (candidate.mReplacingWindow != null) {
                candidate.mReplacingWindow.mSkipEnterAnimationForSeamlessReplacement = false;
            }
            // Since the window already timed out, remove it immediately now.
            // Use removeWindowInnerLocked() instead of removeWindowLocked(), as the latter
            // delays removal on certain conditions, which will leave the stale window in the
            // stack and marked mWillReplaceWindow=false, so the window will never be removed.
            service.removeWindowInnerLocked(candidate);
        }
    }

    private boolean canFreezeBounds() {
        // For freeform windows, we can't freeze the bounds at the moment because this would make
        // the resizing unresponsive.
        return mTask != null && !mTask.inFreeformWorkspace();
    }

    /**
     * Freezes the task bounds. The size of this task reported the app will be fixed to the bounds
     * freezed by {@link Task#prepareFreezingBounds} until {@link #unfreezeBounds} gets called, even
     * if they change in the meantime. If the bounds are already frozen, the bounds will be frozen
     * with a queue.
     */
    private void freezeBounds() {
        mFrozenBounds.offer(new Rect(mTask.mPreparedFrozenBounds));

        if (mTask.mPreparedFrozenMergedConfig.equals(Configuration.EMPTY)) {
            // We didn't call prepareFreezingBounds on the task, so use the current value.
            final Configuration config = new Configuration(service.mCurConfiguration);
            config.updateFrom(mTask.mOverrideConfig);
            mFrozenMergedConfig.offer(config);
        } else {
            mFrozenMergedConfig.offer(new Configuration(mTask.mPreparedFrozenMergedConfig));
        }
        mTask.mPreparedFrozenMergedConfig.setToDefaults();
    }

    /**
     * Unfreezes the previously frozen bounds. See {@link #freezeBounds}.
     */
    private void unfreezeBounds() {
        mFrozenBounds.remove();
        mFrozenMergedConfig.remove();
        for (int i = windows.size() - 1; i >= 0; i--) {
            final WindowState win = windows.get(i);
            if (!win.mHasSurface) {
                continue;
            }
            win.mLayoutNeeded = true;
            win.setDisplayLayoutNeeded();
            if (!service.mResizingWindows.contains(win)) {
                service.mResizingWindows.add(win);
            }
        }
        service.mWindowPlacerLocked.performSurfacePlacement();
    }

    void addSurfaceViewBackground(WindowSurfaceController.SurfaceControlWithBackground background) {
        mSurfaceViewBackgrounds.add(background);
    }

    void removeSurfaceViewBackground(WindowSurfaceController.SurfaceControlWithBackground background) {
        mSurfaceViewBackgrounds.remove(background);
        updateSurfaceViewBackgroundVisibilities();
    }

    // We use DimLayers behind SurfaceViews to prevent holes while resizing and creating.
    // However, we need to ensure one SurfaceView doesn't cover another when they are both placed
    // below the main app window (as traditionally a SurfaceView which is never drawn
    // to is totally translucent). So we look at all our SurfaceView backgrounds and only enable
    // the background for the SurfaceView with lowest Z order
    void updateSurfaceViewBackgroundVisibilities() {
        WindowSurfaceController.SurfaceControlWithBackground bottom = null;
        int bottomLayer = Integer.MAX_VALUE;
        for (int i = 0; i < mSurfaceViewBackgrounds.size(); i++) {
            WindowSurfaceController.SurfaceControlWithBackground sc = mSurfaceViewBackgrounds.get(i);
            if (sc.mVisible && sc.mLayer < bottomLayer) {
                bottomLayer = sc.mLayer;
                bottom = sc;
            }
        }
        for (int i = 0; i < mSurfaceViewBackgrounds.size(); i++) {
            WindowSurfaceController.SurfaceControlWithBackground sc = mSurfaceViewBackgrounds.get(i);
            sc.updateBackgroundVisibility(sc != bottom);
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        if (appToken != null) {
            pw.print(prefix); pw.print("app=true voiceInteraction="); pw.println(voiceInteraction);
        }
        if (allAppWindows.size() > 0) {
            pw.print(prefix); pw.print("allAppWindows="); pw.println(allAppWindows);
        }
        pw.print(prefix); pw.print("task="); pw.println(mTask);
        pw.print(prefix); pw.print(" appFullscreen="); pw.print(appFullscreen);
                pw.print(" requestedOrientation="); pw.println(requestedOrientation);
        pw.print(prefix); pw.print("hiddenRequested="); pw.print(hiddenRequested);
                pw.print(" clientHidden="); pw.print(clientHidden);
                pw.print(" reportedDrawn="); pw.print(reportedDrawn);
                pw.print(" reportedVisible="); pw.println(reportedVisible);
        if (paused) {
            pw.print(prefix); pw.print("paused="); pw.println(paused);
        }
        if (mAppStopped) {
            pw.print(prefix); pw.print("mAppStopped="); pw.println(mAppStopped);
        }
        if (numInterestingWindows != 0 || numDrawnWindows != 0
                || allDrawn || mAppAnimator.allDrawn) {
            pw.print(prefix); pw.print("numInterestingWindows=");
                    pw.print(numInterestingWindows);
                    pw.print(" numDrawnWindows="); pw.print(numDrawnWindows);
                    pw.print(" inPendingTransaction="); pw.print(inPendingTransaction);
                    pw.print(" allDrawn="); pw.print(allDrawn);
                    pw.print(" (animator="); pw.print(mAppAnimator.allDrawn);
                    pw.println(")");
        }
        if (inPendingTransaction) {
            pw.print(prefix); pw.print("inPendingTransaction=");
                    pw.println(inPendingTransaction);
        }
        if (startingData != null || removed || firstWindowDrawn || mIsExiting) {
            pw.print(prefix); pw.print("startingData="); pw.print(startingData);
                    pw.print(" removed="); pw.print(removed);
                    pw.print(" firstWindowDrawn="); pw.print(firstWindowDrawn);
                    pw.print(" mIsExiting="); pw.println(mIsExiting);
        }
        if (startingWindow != null || startingView != null
                || startingDisplayed || startingMoved) {
            pw.print(prefix); pw.print("startingWindow="); pw.print(startingWindow);
                    pw.print(" startingView="); pw.print(startingView);
                    pw.print(" startingDisplayed="); pw.print(startingDisplayed);
                    pw.print(" startingMoved="); pw.println(startingMoved);
        }
        if (!mFrozenBounds.isEmpty()) {
            pw.print(prefix); pw.print("mFrozenBounds="); pw.println(mFrozenBounds);
            pw.print(prefix); pw.print("mFrozenMergedConfig="); pw.println(mFrozenMergedConfig);
        }
        if (mPendingRelaunchCount != 0) {
            pw.print(prefix); pw.print("mPendingRelaunchCount="); pw.println(mPendingRelaunchCount);
        }
    }

    @Override
    public String toString() {
        if (stringName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("AppWindowToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" token="); sb.append(token); sb.append('}');
            stringName = sb.toString();
        }
        return stringName;
    }
}
