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
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_ACTIVITY_DRAWN;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_NONE;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.logWithStack;

import com.android.server.input.InputApplicationHandle;
import com.android.server.wm.WindowManagerService.H;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;

class AppTokenList extends ArrayList<AppWindowToken> {
}

/**
 * Version of WindowToken that is specifically for a particular application (or
 * really activity) that is displaying windows.
 */
class AppWindowToken extends WindowToken implements WindowManagerService.AppFreezeListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppWindowToken" : TAG_WM;

    // Non-null only for application tokens.
    final IApplicationToken appToken;

    @NonNull final AppWindowAnimator mAppAnimator;

    final boolean voiceInteraction;

    Task mTask;
    /** @see WindowContainer#fillsParent() */
    private boolean mFillsParent;
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
    int numDrawnWindowsExcludingSaved;

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
    private final WindowState.UpdateReportedVisibilityResults mReportedVisibilityResults =
            new WindowState.UpdateReportedVisibilityResults();

    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    // TODO: Have a WindowContainer state for tracking exiting/deferred removal.
    boolean mIsExiting;

    boolean mLaunchTaskBehind;
    boolean mEnteringAnimation;

    boolean mAlwaysFocusable;

    boolean mAppStopped;
    int mRotationAnimationHint;
    int mPendingRelaunchCount;

    private ArrayList<WindowSurfaceController.SurfaceControlWithBackground> mSurfaceViewBackgrounds =
        new ArrayList<>();

    ArrayDeque<Rect> mFrozenBounds = new ArrayDeque<>();
    ArrayDeque<Configuration> mFrozenMergedConfig = new ArrayDeque<>();

    AppWindowToken(WindowManagerService service, IApplicationToken token, boolean _voiceInteraction) {
        super(service, token != null ? token.asBinder() : null, TYPE_APPLICATION, true);
        appToken = token;
        voiceInteraction = _voiceInteraction;
        mInputApplicationHandle = new InputApplicationHandle(this);
        mAppAnimator = new AppWindowAnimator(this, service);
    }

    void onFirstWindowDrawn(WindowState win, WindowStateAnimator winAnimator) {
        firstWindowDrawn = true;

        // We now have a good window to show, remove dead placeholders
        removeDeadWindows();

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

        if (DEBUG_VISIBILITY) Slog.v(TAG, "Update reported visibility: " + this);
        final int count = mChildren.size();

        mReportedVisibilityResults.reset();

        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            win.updateReportedVisibility(mReportedVisibilityResults);
        }

        int numInteresting = mReportedVisibilityResults.numInteresting;
        int numVisible = mReportedVisibilityResults.numVisible;
        int numDrawn = mReportedVisibilityResults.numDrawn;
        boolean nowGone = mReportedVisibilityResults.nowGone;

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
                mService.mH.obtainMessage(H.REPORT_APPLICATION_TOKEN_DRAWN, this).sendToTarget();
            }
            reportedDrawn = nowDrawn;
        }
        if (nowVisible != reportedVisible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Visibility changed in " + this + ": vis=" + nowVisible);
            reportedVisible = nowVisible;
            mService.mH.obtainMessage(H.REPORT_APPLICATION_TOKEN_WINDOWS,
                    nowVisible ? 1 : 0, nowGone ? 1 : 0, this).sendToTarget();
        }
    }

    boolean setVisibility(WindowManager.LayoutParams lp,
            boolean visible, int transit, boolean performLayout, boolean isVoiceInteraction) {

        boolean delayed = false;
        inPendingTransaction = false;

        if (clientHidden == visible) {
            clientHidden = !visible;
            sendAppVisibilityToClients();
        }

        // Allow for state changes and animation to be applied if:
        // * token is transitioning visibility state
        // * or the token was marked as hidden and is exiting before we had a chance to play the
        // transition animation
        // * or this is an opening app and windows are being replaced.
        boolean visibilityChanged = false;
        if (hidden == visible || (hidden && mIsExiting) || (visible && waitingForReplacement())) {
            final AccessibilityController accessibilityController = mService.mAccessibilityController;
            boolean changed = false;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM,
                    "Changing app " + this + " hidden=" + hidden + " performLayout=" + performLayout);

            boolean runningAppAnimation = false;

            if (transit != AppTransition.TRANSIT_UNSET) {
                if (mAppAnimator.animation == AppWindowAnimator.sDummyAnimation) {
                    mAppAnimator.setNullAnimation();
                }
                if (mService.applyAnimationLocked(this, lp, transit, visible, isVoiceInteraction)) {
                    delayed = runningAppAnimation = true;
                }
                final WindowState window = findMainWindow();
                //TODO (multidisplay): Magnification is supported only for the default display.
                if (window != null && accessibilityController != null
                        && window.getDisplayId() == DEFAULT_DISPLAY) {
                    accessibilityController.onAppWindowTransitionLocked(window, transit);
                }
                changed = true;
            }

            final int windowsCount = mChildren.size();
            for (int i = 0; i < windowsCount; i++) {
                final WindowState win = mChildren.get(i);
                changed |= win.onAppVisibilityChanged(visible, runningAppAnimation);
            }

            hidden = hiddenRequested = !visible;
            visibilityChanged = true;
            if (!visible) {
                stopFreezingScreen(true, true);
            } else {
                // If we are being set visible, and the starting window is not yet displayed,
                // then make sure it doesn't get displayed.
                if (startingWindow != null && !startingWindow.isDrawnLw()) {
                    startingWindow.mPolicyVisibility = false;
                    startingWindow.mPolicyVisibilityAfterAnim = false;
                }
            }

            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "setVisibility: " + this
                    + ": hidden=" + hidden + " hiddenRequested=" + hiddenRequested);

            if (changed) {
                mService.mInputMonitor.setUpdateInputWindowsNeededLw();
                if (performLayout) {
                    mService.updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                            false /*updateInputWindows*/);
                    mService.mWindowPlacerLocked.performSurfacePlacement();
                }
                mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
            }
        }

        if (mAppAnimator.animation != null) {
            delayed = true;
        }

        for (int i = mChildren.size() - 1; i >= 0 && !delayed; i--) {
            if ((mChildren.get(i)).isWindowAnimationSet()) {
                delayed = true;
            }
        }

        if (visibilityChanged) {
            if (visible && !delayed) {
                // The token was made immediately visible, there will be no entrance animation.
                // We need to inform the client the enter animation was finished.
                mEnteringAnimation = true;
                mService.mActivityManagerAppTransitionNotifier.onAppTransitionFinishedLocked(token);
            }

            if (!mService.mClosingApps.contains(this) && !mService.mOpeningApps.contains(this)) {
                // The token is not closing nor opening, so even if there is an animation set, that
                // doesn't mean that it goes through the normal app transition cycle so we have
                // to inform the docked controller about visibility change.
                mService.getDefaultDisplayContentLocked().getDockedDividerController()
                        .notifyAppVisibilityChanged();
            }
        }

        return delayed;
    }

    WindowState findMainWindow() {
        WindowState candidate = null;
        int j = mChildren.size();
        while (j > 0) {
            j--;
            final WindowState win = mChildren.get(j);
            final int type = win.mAttrs.type;
            // No need to loop through child window as base application and starting types can't be
            // child windows.
            if (type == TYPE_BASE_APPLICATION || type == TYPE_APPLICATION_STARTING) {
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

    @Override
    boolean isVisible() {
        if (hidden) {
            // TODO: Should this be checking hiddenRequested instead of hidden?
            return false;
        }
        return super.isVisible();
    }

    @Override
    void removeIfPossible() {
        mIsExiting = false;
        removeAllWindows();
        if (mTask != null) {
            mTask.detachChild(this);
        }
    }

    @Override
    boolean checkCompleteDeferredRemoval() {
        if (mIsExiting) {
            removeIfPossible();
        }
        return super.checkCompleteDeferredRemoval();
    }

    void clearAnimatingFlags() {
        boolean wallpaperMightChange = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            wallpaperMightChange |= win.clearAnimatingFlags();
        }
        if (wallpaperMightChange) {
            requestUpdateWallpaperIfNeeded();
        }
    }

    void destroySurfaces() {
        destroySurfaces(false /*cleanupOnResume*/);
    }

    /**
     * Destroy surfaces which have been marked as eligible by the animator, taking care to ensure
     * the client has finished with them.
     *
     * @param cleanupOnResume whether this is done when app is resumed without fully stopped. If
     * set to true, destroy only surfaces of removed windows, and clear relevant flags of the
     * others so that they are ready to be reused. If set to false (common case), destroy all
     * surfaces that's eligible, if the app is already stopped.
     */
    private void destroySurfaces(boolean cleanupOnResume) {
        final DisplayContentList displayList = new DisplayContentList();
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            final boolean destroyed = win.destroySurface(cleanupOnResume, mAppStopped);

            if (destroyed) {
                final DisplayContent displayContent = win.getDisplayContent();
                if (displayContent != null && !displayList.contains(displayContent)) {
                    displayList.add(displayContent);
                }
            }
        }
        for (int i = 0; i < displayList.size(); i++) {
            final DisplayContent displayContent = displayList.get(i);
            mService.mLayersController.assignLayersLocked(displayContent.getWindowList());
            displayContent.layoutNeeded = true;
        }
    }

    /**
     * Notify that the app is now resumed, and it was not stopped before, perform a clean
     * up of the surfaces
     */
    void notifyAppResumed(boolean wasStopped, boolean allowSavedSurface) {
        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "notifyAppResumed: wasStopped=" + wasStopped
                + " allowSavedSurface=" + allowSavedSurface + " " + this);
        mAppStopped = false;
        if (!wasStopped) {
            destroySurfaces(true /*cleanupOnResume*/);
        }
        if (!allowSavedSurface) {
            destroySavedSurfaces();
        }
    }

    /**
     * Notify that the app has stopped, and it is okay to destroy any surfaces which were
     * keeping alive in case they were still being used.
     */
    void notifyAppStopped() {
        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "notifyAppStopped: " + this);
        mAppStopped = true;
        destroySurfaces();
        // Remove any starting window that was added for this app if they are still around.
        mTask.mService.scheduleRemoveStartingWindowLocked(this);
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

    private boolean canRestoreSurfaces() {
        for (int i = mChildren.size() -1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            if (w.canRestoreSurface()) {
                return true;
            }
        }
        return false;
    }

    private void clearWasVisibleBeforeClientHidden() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.clearWasVisibleBeforeClientHidden();
        }
    }

    /**
     * Whether the app has some window that is invisible in layout, but
     * animating with saved surface.
     */
    boolean isAnimatingInvisibleWithSavedSurface() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
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
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.stopUsingSavedSurface();
        }
        destroySurfaces();
    }

    void markSavedSurfaceExiting() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.markSavedSurfaceExiting();
        }
    }

    void restoreSavedSurfaceForInterestingWindows() {
        if (!canRestoreSurfaces()) {
            clearWasVisibleBeforeClientHidden();
            return;
        }

        // Check if all interesting windows are drawn and we can mark allDrawn=true.
        int interestingNotDrawn = -1;

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            interestingNotDrawn = w.restoreSavedSurfaceForInterestingWindow();
        }

        if (!allDrawn) {
            allDrawn = (interestingNotDrawn == 0);
            if (allDrawn) {
                mService.mH.obtainMessage(NOTIFY_ACTIVITY_DRAWN, token).sendToTarget();
            }
        }
        clearWasVisibleBeforeClientHidden();

        if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.d(TAG,
                "restoreSavedSurfaceForInterestingWindows: " + this + " allDrawn=" + allDrawn
                + " interestingNotDrawn=" + interestingNotDrawn);
    }

    void destroySavedSurfaces() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            win.destroySavedSurface();
        }
    }

    void clearAllDrawn() {
        allDrawn = false;
        deferClearAllDrawn = false;
        allDrawnExcludingSaved = false;
    }

    @Override
    void removeWindow(WindowState win) {
        super.removeWindow(win);

        // TODO: Something smells about the code below...Is there a better way?
        if (startingWindow == win) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Notify removed startingWindow " + win);
            mService.scheduleRemoveStartingWindowLocked(this);
        } else if (mChildren.size() == 0 && startingData != null) {
            // If this is the last window and we had requested a starting transition window,
            // well there is no point now.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Nulling last startingWindow");
            startingData = null;
        } else if (mChildren.size() == 1 && startingView != null) {
            // If this is the last window except for a starting transition window,
            // we need to get rid of the starting transition.
            mService.scheduleRemoveStartingWindowLocked(this);
        }
    }

    void removeDeadWindows() {
        for (int winNdx = mChildren.size() - 1; winNdx >= 0; --winNdx) {
            WindowState win = mChildren.get(winNdx);
            if (win.mAppDied) {
                if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.w(TAG,
                        "removeDeadWindows: " + win);
                // Set mDestroying, we don't want any animation or delayed removal here.
                win.mDestroying = true;
                // Also removes child windows.
                win.removeIfPossible();
            }
        }
    }

    boolean hasWindowsAlive() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            // No need to loop through child windows as the answer should be the same as that of the
            // parent window.
            if (!(mChildren.get(i)).mAppDied) {
                return true;
            }
        }
        return false;
    }

    void setWillReplaceWindows(boolean animate) {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM,
                "Marking app token " + this + " with replacing windows.");

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.setWillReplaceWindow(animate);
        }
        if (animate) {
            // Set-up dummy animation so we can start treating windows associated with this
            // token like they are in transition before the new app window is ready for us to
            // run the real transition animation.
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM,
                    "setWillReplaceWindow() Setting dummy animation on: " + this);
            mAppAnimator.setDummyAnimation();
        }
    }

    void setWillReplaceChildWindows() {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM, "Marking app token " + this
                + " with replacing child windows.");
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.setWillReplaceChildWindows();
        }
    }

    void clearWillReplaceWindows() {
        if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM,
                "Resetting app token " + this + " of replacing window marks.");

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.clearWillReplaceWindow();
        }
    }

    void requestUpdateWallpaperIfNeeded() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
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

    void clearRelaunching() {
        if (mPendingRelaunchCount == 0) {
            return;
        }
        if (canFreezeBounds()) {
            unfreezeBounds();
        }
        mPendingRelaunchCount = 0;
    }

    @Override
    void addWindow(WindowState w) {
        super.addWindow(w);

        boolean gotReplacementWindow = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState candidate = mChildren.get(i);
            gotReplacementWindow |= candidate.setReplacementWindowIfNeeded(w);
        }

        // if we got a replacement window, reset the timeout to give drawing more time
        if (gotReplacementWindow) {
            mService.scheduleWindowReplacementTimeouts(this);
        }
    }

    boolean waitingForReplacement() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState candidate = mChildren.get(i);
            if (candidate.waitingForReplacement()) {
                return true;
            }
        }
        return false;
    }

    void onWindowReplacementTimeout() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            (mChildren.get(i)).onWindowReplacementTimeout();
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
            final Configuration config = new Configuration(mService.mGlobalConfiguration);
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
        if (!mFrozenBounds.isEmpty()) {
            mFrozenBounds.remove();
        }
        if (!mFrozenMergedConfig.isEmpty()) {
            mFrozenMergedConfig.remove();
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            win.onUnfreezeBounds();
        }
        mService.mWindowPlacerLocked.performSurfacePlacement();
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

    /**
     * See {@link WindowManagerService#overridePlayingAppAnimationsLw}
     */
    void overridePlayingAppAnimations(Animation a) {
        if (mAppAnimator.isAnimating()) {
            final WindowState win = findMainWindow();
            final int width = win.mContainingFrame.width();
            final int height = win.mContainingFrame.height();
            mAppAnimator.setAnimation(a, width, height, false, STACK_CLIP_NONE);
        }
    }

    void resetJustMovedInStack() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            (mChildren.get(i)).resetJustMovedInStack();
        }
    }

    void notifyMovedInStack() {
        for (int winNdx = mChildren.size() - 1; winNdx >= 0; --winNdx) {
            final WindowState win = mChildren.get(winNdx);
            win.notifyMovedInStack();
        }
    }

    void setAppLayoutChanges(int changes, String reason, int displayId) {
        final WindowAnimator windowAnimator = mAppAnimator.mAnimator;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            // Child windows will be on the same display as their parents.
            if (displayId == (mChildren.get(i)).getDisplayId()) {
                windowAnimator.setPendingLayoutChanges(displayId, changes);
                if (DEBUG_LAYOUT_REPEATS) {
                    mService.mWindowPlacerLocked.debugLayoutRepeats(
                            reason, windowAnimator.getPendingLayoutChanges(displayId));
                }
                break;
            }
        }
    }

    void removeReplacedWindowIfNeeded(WindowState replacement) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            if (win.removeReplacedWindowIfNeeded(replacement)) {
                return;
            }
        }
    }

    void startFreezingScreen() {
        if (DEBUG_ORIENTATION) logWithStack(TAG, "Set freezing of " + appToken + ": hidden="
                + hidden + " freezing=" + mAppAnimator.freezingScreen + " hiddenRequested="
                + hiddenRequested);
        if (!hiddenRequested) {
            if (!mAppAnimator.freezingScreen) {
                mAppAnimator.freezingScreen = true;
                mService.registerAppFreezeListener(this);
                mAppAnimator.lastFreezeDuration = 0;
                mService.mAppsFreezingScreen++;
                if (mService.mAppsFreezingScreen == 1) {
                    mService.startFreezingDisplayLocked(false, 0, 0);
                    mService.mH.removeMessages(H.APP_FREEZE_TIMEOUT);
                    mService.mH.sendEmptyMessageDelayed(H.APP_FREEZE_TIMEOUT, 2000);
                }
            }
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                final WindowState w = mChildren.get(i);
                w.onStartFreezingScreen();
            }
        }
    }

    void stopFreezingScreen(boolean unfreezeSurfaceNow, boolean force) {
        if (!mAppAnimator.freezingScreen) {
            return;
        }
        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Clear freezing of " + this + " force=" + force);
        final int count = mChildren.size();
        boolean unfrozeWindows = false;
        for (int i = 0; i < count; i++) {
            final WindowState w = mChildren.get(i);
            unfrozeWindows |= w.onStopFreezingScreen();
        }
        if (force || unfrozeWindows) {
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "No longer freezing: " + this);
            mAppAnimator.freezingScreen = false;
            mService.unregisterAppFreezeListener(this);
            mAppAnimator.lastFreezeDuration =
                    (int)(SystemClock.elapsedRealtime() - mService.mDisplayFreezeTime);
            mService.mAppsFreezingScreen--;
            mService.mLastFinishedFreezeSource = this;
        }
        if (unfreezeSurfaceNow) {
            if (unfrozeWindows) {
                mService.mWindowPlacerLocked.performSurfacePlacement();
            }
            mService.stopFreezingDisplayLocked();
        }
    }

    @Override
    public void onAppFreezeTimeout() {
        Slog.w(TAG_WM, "Force clearing freeze: " + this);
        stopFreezingScreen(true, true);
    }

    boolean transferStartingWindow(IBinder transferFrom) {
        final AppWindowToken fromToken = mService.findAppWindowToken(transferFrom);
        if (fromToken == null) {
            return false;
        }

        final WindowState tStartingWindow = fromToken.startingWindow;
        if (tStartingWindow != null && fromToken.startingView != null) {
            // In this case, the starting icon has already been displayed, so start
            // letting windows get shown immediately without any more transitions.
            mService.mSkipAppTransitionAnimation = true;

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Moving existing starting " + tStartingWindow
                    + " from " + fromToken + " to " + this);

            final long origId = Binder.clearCallingIdentity();

            // Transfer the starting window over to the new token.
            startingData = fromToken.startingData;
            startingView = fromToken.startingView;
            startingDisplayed = fromToken.startingDisplayed;
            fromToken.startingDisplayed = false;
            startingWindow = tStartingWindow;
            reportedVisible = fromToken.reportedVisible;
            fromToken.startingData = null;
            fromToken.startingView = null;
            fromToken.startingWindow = null;
            fromToken.startingMoved = true;
            tStartingWindow.mToken = this;
            tStartingWindow.mAppToken = this;

            if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE || DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                    "Removing starting window: " + tStartingWindow);
            tStartingWindow.getWindowList().remove(tStartingWindow);
            mService.mWindowsChanged = true;
            if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                    "Removing starting " + tStartingWindow + " from " + fromToken);
            fromToken.removeWindow(tStartingWindow);
            addWindow(tStartingWindow);

            // Propagate other interesting state between the tokens. If the old token is displayed,
            // we should immediately force the new one to be displayed. If it is animating, we need
            // to move that animation to the new one.
            if (fromToken.allDrawn) {
                allDrawn = true;
                deferClearAllDrawn = fromToken.deferClearAllDrawn;
            }
            if (fromToken.firstWindowDrawn) {
                firstWindowDrawn = true;
            }
            if (!fromToken.hidden) {
                hidden = false;
                hiddenRequested = false;
            }
            if (clientHidden != fromToken.clientHidden) {
                clientHidden = fromToken.clientHidden;
                sendAppVisibilityToClients();
            }
            fromToken.mAppAnimator.transferCurrentAnimation(
                    mAppAnimator, tStartingWindow.mWinAnimator);

            mService.updateFocusedWindowLocked(
                    UPDATE_FOCUS_WILL_PLACE_SURFACES, true /*updateInputWindows*/);
            mService.getDefaultDisplayContentLocked().layoutNeeded = true;
            mService.mWindowPlacerLocked.performSurfacePlacement();
            Binder.restoreCallingIdentity(origId);
            return true;
        } else if (fromToken.startingData != null) {
            // The previous app was getting ready to show a
            // starting window, but hasn't yet done so.  Steal it!
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                    "Moving pending starting from " + fromToken + " to " + this);
            startingData = fromToken.startingData;
            fromToken.startingData = null;
            fromToken.startingMoved = true;
            final Message m = mService.mH.obtainMessage(H.ADD_STARTING, this);
            // Note: we really want to do sendMessageAtFrontOfQueue() because we want to process the
            // message ASAP, before any other queued messages.
            mService.mH.sendMessageAtFrontOfQueue(m);
            return true;
        }

        final AppWindowAnimator tAppAnimator = fromToken.mAppAnimator;
        final AppWindowAnimator wAppAnimator = mAppAnimator;
        if (tAppAnimator.thumbnail != null) {
            // The old token is animating with a thumbnail, transfer that to the new token.
            if (wAppAnimator.thumbnail != null) {
                wAppAnimator.thumbnail.destroy();
            }
            wAppAnimator.thumbnail = tAppAnimator.thumbnail;
            wAppAnimator.thumbnailLayer = tAppAnimator.thumbnailLayer;
            wAppAnimator.thumbnailAnimation = tAppAnimator.thumbnailAnimation;
            tAppAnimator.thumbnail = null;
        }
        return false;
    }

    boolean isLastWindow(WindowState win) {
        return mChildren.size() == 1 && mChildren.get(0) == win;
    }

    void setAllAppWinAnimators() {
        final ArrayList<WindowStateAnimator> allAppWinAnimators = mAppAnimator.mAllAppWinAnimators;
        allAppWinAnimators.clear();

        final int windowsCount = mChildren.size();
        for (int j = 0; j < windowsCount; j++) {
            (mChildren.get(j)).addWinAnimatorToList(allAppWinAnimators);
        }
    }

    @Override
    void onAppTransitionDone() {
        sendingToBottom = false;
    }

    /**
     * We override because this class doesn't want its children affecting its reported orientation
     * in anyway.
     */
    @Override
    int getOrientation() {
        if (hidden || hiddenRequested) {
            return SCREEN_ORIENTATION_UNSET;
        }
        return mOrientation;
    }

    @Override
    void checkAppWindowsReadyToShow(int displayId) {
        if (allDrawn == mAppAnimator.allDrawn) {
            return;
        }

        mAppAnimator.allDrawn = allDrawn;
        if (!allDrawn) {
            return;
        }

        // The token has now changed state to having all windows shown...  what to do, what to do?
        if (mAppAnimator.freezingScreen) {
            mAppAnimator.showAllWindowsLocked();
            stopFreezingScreen(false, true);
            if (DEBUG_ORIENTATION) Slog.i(TAG,
                    "Setting mOrientationChangeComplete=true because wtoken " + this
                    + " numInteresting=" + numInterestingWindows + " numDrawn=" + numDrawnWindows);
            // This will set mOrientationChangeComplete and cause a pass through layout.
            setAppLayoutChanges(FINISH_LAYOUT_REDO_WALLPAPER,
                    "checkAppWindowsReadyToShow: freezingScreen", displayId);
        } else {
            setAppLayoutChanges(FINISH_LAYOUT_REDO_ANIM, "checkAppWindowsReadyToShow", displayId);

            // We can now show all of the drawn windows!
            if (!mService.mOpeningApps.contains(this)) {
                mService.mAnimator.orAnimating(mAppAnimator.showAllWindowsLocked());
            }
        }
    }

    @Override
    void updateAllDrawn(int displayId) {
        final DisplayContent displayContent = mService.getDisplayContentLocked(displayId);

        if (!allDrawn) {
            final int numInteresting = numInterestingWindows;
            if (numInteresting > 0 && numDrawnWindows >= numInteresting) {
                if (DEBUG_VISIBILITY) Slog.v(TAG, "allDrawn: " + this
                        + " interesting=" + numInteresting + " drawn=" + numDrawnWindows);
                allDrawn = true;
                // Force an additional layout pass where
                // WindowStateAnimator#commitFinishDrawingLocked() will call performShowLocked().
                displayContent.layoutNeeded = true;
                mService.mH.obtainMessage(NOTIFY_ACTIVITY_DRAWN, token).sendToTarget();
            }
        }
        if (!allDrawnExcludingSaved) {
            int numInteresting = numInterestingWindowsExcludingSaved;
            if (numInteresting > 0 && numDrawnWindowsExcludingSaved >= numInteresting) {
                if (DEBUG_VISIBILITY) Slog.v(TAG, "allDrawnExcludingSaved: " + this
                        + " interesting=" + numInteresting
                        + " drawn=" + numDrawnWindowsExcludingSaved);
                allDrawnExcludingSaved = true;
                displayContent.layoutNeeded = true;
                if (isAnimatingInvisibleWithSavedSurface()
                        && !mService.mFinishedEarlyAnim.contains(this)) {
                    mService.mFinishedEarlyAnim.add(this);
                }
            }
        }
    }

    @Override
    void stepAppWindowsAnimation(long currentTime, int displayId) {
        mAppAnimator.wasAnimating = mAppAnimator.animating;
        if (mAppAnimator.stepAnimationLocked(currentTime, displayId)) {
            mAppAnimator.animating = true;
            mService.mAnimator.setAnimating(true);
            mService.mAnimator.mAppWindowAnimating = true;
        } else if (mAppAnimator.wasAnimating) {
            // stopped animating, do one more pass through the layout
            setAppLayoutChanges(
                    FINISH_LAYOUT_REDO_WALLPAPER, "appToken " + this + " done", displayId);
            if (DEBUG_ANIM) Slog.v(TAG, "updateWindowsApps...: done animating " + this);
        }
    }

    @Override
    int rebuildWindowList(DisplayContent dc, int addIndex) {
        if (mIsExiting && !waitingForReplacement()) {
            return addIndex;
        }
        return super.rebuildWindowList(dc, addIndex);
    }

    @Override
    AppWindowToken asAppWindowToken() {
        // I am an app window token!
        return this;
    }

    @Override
    boolean fillsParent() {
        return mFillsParent;
    }

    void setFillsParent(boolean fillsParent) {
        mFillsParent = fillsParent;
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        if (appToken != null) {
            pw.print(prefix); pw.print("app=true voiceInteraction="); pw.println(voiceInteraction);
        }
        pw.print(prefix); pw.print("task="); pw.println(mTask);
        pw.print(prefix); pw.print(" mFillsParent="); pw.print(mFillsParent);
                pw.print(" mOrientation="); pw.println(mOrientation);
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
