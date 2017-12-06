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
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.os.Build.VERSION_CODES.O_MR1;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.AppTransition.TRANSIT_UNSET;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_ACTIVITY_DRAWN;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.logWithStack;

import android.annotation.NonNull;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerPolicy.StartingSurface;

import com.android.internal.util.ToBooleanFunction;
import com.android.server.input.InputApplicationHandle;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;

import static android.os.Build.VERSION_CODES.O;

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

    final boolean mVoiceInteraction;

    /** @see WindowContainer#fillsParent() */
    private boolean mFillsParent;
    boolean layoutConfigChanges;
    boolean mShowForAllUsers;
    int mTargetSdk;

    // Flag set while reparenting to prevent actions normally triggered by an individual parent
    // change.
    private boolean mReparenting;

    // True if we are current in the process of removing this app token from the display
    private boolean mRemovingFromDisplay = false;

    // The input dispatching timeout for this application token in nanoseconds.
    long mInputDispatchingTimeoutNanos;

    // These are used for determining when all windows associated with
    // an activity have been drawn, so they can be made visible together
    // at the same time.
    // initialize so that it doesn't match mTransactionSequence which is an int.
    private long mLastTransactionSequence = Long.MIN_VALUE;
    private int mNumInterestingWindows;
    private int mNumDrawnWindows;
    boolean inPendingTransaction;
    boolean allDrawn;
    // Set to true when this app creates a surface while in the middle of an animation. In that
    // case do not clear allDrawn until the animation completes.
    boolean deferClearAllDrawn;

    /**
     * These are to track the app's real drawing status if there were no saved surfaces.
     * @see #updateDrawnWindowStates
     */
    boolean allDrawnExcludingSaved;
    private int mNumInterestingWindowsExcludingSaved;
    private int mNumDrawnWindowsExcludingSaved;

    // Is this window's surface needed?  This is almost like hidden, except
    // it will sometimes be true a little earlier: when the token has
    // been shown, but is still waiting for its app transition to execute
    // before making its windows shown.
    boolean hiddenRequested;

    // Have we told the window clients to hide themselves?
    private boolean mClientHidden;

    // If true we will defer setting mClientHidden to true and reporting to the client that it is
    // hidden.
    boolean mDeferHidingClient;

    // Last visibility state we reported to the app token.
    boolean reportedVisible;

    // Last drawn state we reported to the app token.
    private boolean reportedDrawn;

    // Set to true when the token has been removed from the window mgr.
    boolean removed;

    // Information about an application starting window if displayed.
    StartingData startingData;
    WindowState startingWindow;
    StartingSurface startingSurface;
    boolean startingDisplayed;
    boolean startingMoved;
    // True if the hidden state of this token was forced to false due to a transferred starting
    // window.
    private boolean mHiddenSetFromTransferredStartingWindow;
    boolean firstWindowDrawn;
    private final WindowState.UpdateReportedVisibilityResults mReportedVisibilityResults =
            new WindowState.UpdateReportedVisibilityResults();

    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    // TODO: Have a WindowContainer state for tracking exiting/deferred removal.
    boolean mIsExiting;

    boolean mLaunchTaskBehind;
    boolean mEnteringAnimation;

    private boolean mAlwaysFocusable;

    boolean mAppStopped;
    int mRotationAnimationHint;
    private int mPendingRelaunchCount;

    private boolean mLastContainsShowWhenLockedWindow;
    private boolean mLastContainsDismissKeyguardWindow;

    // The bounds of this activity. Mainly used for aspect-ratio compatibility.
    // TODO(b/36505427): Every level on WindowContainer now has bounds information, which directly
    // affects the configuration. We should probably move this into that class.
    private final Rect mBounds = new Rect();

    ArrayDeque<Rect> mFrozenBounds = new ArrayDeque<>();
    ArrayDeque<Configuration> mFrozenMergedConfig = new ArrayDeque<>();

    private boolean mDisablePreviewScreenshots;

    Task mLastParent;

    /**
     * See {@link #canTurnScreenOn()}
     */
    private boolean mCanTurnScreenOn = true;

    AppWindowToken(WindowManagerService service, IApplicationToken token, boolean voiceInteraction,
            DisplayContent dc, long inputDispatchingTimeoutNanos, boolean fullscreen,
            boolean showForAllUsers, int targetSdk, int orientation, int rotationAnimationHint,
            int configChanges, boolean launchTaskBehind, boolean alwaysFocusable,
            AppWindowContainerController controller, Configuration overrideConfig, Rect bounds) {
        this(service, token, voiceInteraction, dc, fullscreen, overrideConfig, bounds);
        setController(controller);
        mInputDispatchingTimeoutNanos = inputDispatchingTimeoutNanos;
        mShowForAllUsers = showForAllUsers;
        mTargetSdk = targetSdk;
        mOrientation = orientation;
        layoutConfigChanges = (configChanges & (CONFIG_SCREEN_SIZE | CONFIG_ORIENTATION)) != 0;
        mLaunchTaskBehind = launchTaskBehind;
        mAlwaysFocusable = alwaysFocusable;
        mRotationAnimationHint = rotationAnimationHint;

        // Application tokens start out hidden.
        hidden = true;
        hiddenRequested = true;
    }

    AppWindowToken(WindowManagerService service, IApplicationToken token, boolean voiceInteraction,
            DisplayContent dc, boolean fillsParent, Configuration overrideConfig, Rect bounds) {
        super(service, token != null ? token.asBinder() : null, TYPE_APPLICATION, true, dc,
                false /* ownerCanManageAppTokens */);
        appToken = token;
        mVoiceInteraction = voiceInteraction;
        mFillsParent = fillsParent;
        mInputApplicationHandle = new InputApplicationHandle(this);
        mAppAnimator = new AppWindowAnimator(this, service);
        if (overrideConfig != null) {
            onOverrideConfigurationChanged(overrideConfig);
        }
        if (bounds != null) {
            mBounds.set(bounds);
        }
    }

    void onOverrideConfigurationChanged(Configuration overrideConfiguration, Rect bounds) {
        onOverrideConfigurationChanged(overrideConfiguration);
        if (mBounds.equals(bounds)) {
            return;
        }
        // TODO(b/36505427): If bounds is in WC, then we can automatically call onResize() when set.
        mBounds.set(bounds);
        onResize();
    }

    void getBounds(Rect outBounds) {
        outBounds.set(mBounds);
    }

    boolean hasBounds() {
        return !mBounds.isEmpty();
    }

    void onFirstWindowDrawn(WindowState win, WindowStateAnimator winAnimator) {
        firstWindowDrawn = true;

        // We now have a good window to show, remove dead placeholders
        removeDeadWindows();

        if (startingWindow != null) {
            if (DEBUG_STARTING_WINDOW || DEBUG_ANIM) Slog.v(TAG, "Finish starting "
                    + win.mToken + ": first real window is shown, no animation");
            // If this initial window is animating, stop it -- we will do an animation to reveal
            // it from behind the starting window, so there is no need for it to also be doing its
            // own stuff.
            winAnimator.clearAnimation();
            if (getController() != null) {
                getController().removeStartingWindow();
            }
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
        boolean nowVisible = numInteresting > 0 && numVisible >= numInteresting && !hidden;
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
        final AppWindowContainerController controller = getController();
        if (nowDrawn != reportedDrawn) {
            if (nowDrawn) {
                if (controller != null) {
                    controller.reportWindowsDrawn();
                }
            }
            reportedDrawn = nowDrawn;
        }
        if (nowVisible != reportedVisible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Visibility changed in " + this + ": vis=" + nowVisible);
            reportedVisible = nowVisible;
            if (controller != null) {
                if (nowVisible) {
                    controller.reportWindowsVisible();
                } else {
                    controller.reportWindowsGone();
                }
            }
        }
    }

    boolean isClientHidden() {
        return mClientHidden;
    }

    void setClientHidden(boolean hideClient) {
        if (mClientHidden == hideClient || (hideClient && mDeferHidingClient)) {
            return;
        }
        mClientHidden = hideClient;
        sendAppVisibilityToClients();
    }

    boolean setVisibility(WindowManager.LayoutParams lp,
            boolean visible, int transit, boolean performLayout, boolean isVoiceInteraction) {

        boolean delayed = false;
        inPendingTransaction = false;
        // Reset the state of mHiddenSetFromTransferredStartingWindow since visibility is actually
        // been set by the app now.
        mHiddenSetFromTransferredStartingWindow = false;
        setClientHidden(!visible);

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

            if (mAppAnimator.animation == AppWindowAnimator.sDummyAnimation) {
                mAppAnimator.setNullAnimation();
            }
            if (transit != AppTransition.TRANSIT_UNSET) {
                if (mService.applyAnimationLocked(this, lp, transit, visible, isVoiceInteraction)) {
                    delayed = runningAppAnimation = true;
                }
                final WindowState window = findMainWindow();
                //TODO (multidisplay): Magnification is supported only for the default display.
                if (window != null && accessibilityController != null
                        && getDisplayContent().getDisplayId() == DEFAULT_DISPLAY) {
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

                // We are becoming visible, so better freeze the screen with the windows that are
                // getting visible so we also wait for them.
                forAllWindows(mService::makeWindowFreezingScreenIfNeededLocked, true);
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

            // If we are hidden but there is no delay needed we immediately
            // apply the Surface transaction so that the ActivityManager
            // can have some guarantee on the Surface state following
            // setting the visibility. This captures cases like dismissing
            // the docked or pinned stack where there is no app transition.
            //
            // In the case of a "Null" animation, there will be
            // no animation but there will still be a transition set.
            // We still need to delay hiding the surface such that it
            // can be synchronized with showing the next surface in the transition.
            if (hidden && !delayed && !mService.mAppTransition.isTransitionSet()) {
                SurfaceControl.openTransaction();
                for (int i = mChildren.size() - 1; i >= 0; i--) {
                    mChildren.get(i).mWinAnimator.hide("immediately hidden");
                }
                SurfaceControl.closeTransaction();
            }

            if (!mService.mClosingApps.contains(this) && !mService.mOpeningApps.contains(this)) {
                // The token is not closing nor opening, so even if there is an animation set, that
                // doesn't mean that it goes through the normal app transition cycle so we have
                // to inform the docked controller about visibility change.
                // TODO(multi-display): notify docked divider on all displays where visibility was
                // affected.
                mService.getDefaultDisplayContentLocked().getDockedDividerController()
                        .notifyAppVisibilityChanged();
                mService.mTaskSnapshotController.notifyAppVisibilityChanged(this, visible);
            }
        }

        return delayed;
    }

    /**
     * @return The to top most child window for which {@link LayoutParams#isFullscreen()} returns
     *         true.
     */
    WindowState getTopFullscreenWindow() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            if (win != null && win.mAttrs.isFullscreen()) {
                return win;
            }
        }
        return null;
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
        return StackId.canReceiveKeys(getTask().mStack.mStackId) || mAlwaysFocusable;
    }

    AppWindowContainerController getController() {
        final WindowContainerController controller = super.getController();
        return controller != null ? (AppWindowContainerController) controller : null;
    }

    @Override
    boolean isVisible() {
        // If the app token isn't hidden then it is considered visible and there is no need to check
        // its children windows to see if they are visible.
        return !hidden;
    }

    @Override
    void removeImmediately() {
        onRemovedFromDisplay();
        super.removeImmediately();
    }

    @Override
    void removeIfPossible() {
        mIsExiting = false;
        removeAllWindowsIfPossible();
        removeImmediately();
    }

    @Override
    boolean checkCompleteDeferredRemoval() {
        if (mIsExiting) {
            removeIfPossible();
        }
        return super.checkCompleteDeferredRemoval();
    }

    void onRemovedFromDisplay() {
        if (mRemovingFromDisplay) {
            return;
        }
        mRemovingFromDisplay = true;

        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "Removing app token: " + this);

        boolean delayed = setVisibility(null, false, TRANSIT_UNSET, true, mVoiceInteraction);

        mService.mOpeningApps.remove(this);
        mService.mUnknownAppVisibilityController.appRemovedOrHidden(this);
        mService.mTaskSnapshotController.onAppRemoved(this);
        waitingToShow = false;
        if (mService.mClosingApps.contains(this)) {
            delayed = true;
        } else if (mService.mAppTransition.isTransitionSet()) {
            mService.mClosingApps.add(this);
            delayed = true;
        }

        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "Removing app " + this + " delayed=" + delayed
                + " animation=" + mAppAnimator.animation + " animating=" + mAppAnimator.animating);

        if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG_WM, "removeAppToken: "
                + this + " delayed=" + delayed + " Callers=" + Debug.getCallers(4));

        if (startingData != null && getController() != null) {
            getController().removeStartingWindow();
        }

        // If this window was animating, then we need to ensure that the app transition notifies
        // that animations have completed in WMS.handleAnimatingStoppedAndTransitionLocked(), so
        // add to that list now
        if (mAppAnimator.animating) {
            mService.mNoAnimationNotifyOnTransitionFinished.add(token);
        }

        final TaskStack stack = getStack();
        if (delayed && !isEmpty()) {
            // set the token aside because it has an active animation to be finished
            if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG_WM,
                    "removeAppToken make exiting: " + this);
            if (stack != null) {
                stack.mExitingAppTokens.add(this);
            }
            mIsExiting = true;
        } else {
            // Make sure there is no animation running on this token, so any windows associated
            // with it will be removed as soon as their animations are complete
            mAppAnimator.clearAnimation();
            mAppAnimator.animating = false;
            if (stack != null) {
                stack.mExitingAppTokens.remove(this);
            }
            removeIfPossible();
        }

        removed = true;
        stopFreezingScreen(true, true);

        if (mService.mFocusedApp == this) {
            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "Removing focused app token:" + this);
            mService.mFocusedApp = null;
            mService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
            mService.mInputMonitor.setFocusedAppLw(null);
        }

        if (!delayed) {
            updateReportedVisibilityLocked();
        }

        mRemovingFromDisplay = false;
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
        boolean destroyedSomething = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            destroyedSomething |= win.destroySurface(cleanupOnResume, mAppStopped);
        }
        if (destroyedSomething) {
            final DisplayContent dc = getDisplayContent();
            dc.assignWindowLayers(true /*setLayoutNeeded*/);
        }
    }

    /**
     * Notify that the app is now resumed, and it was not stopped before, perform a clean
     * up of the surfaces
     */
    void notifyAppResumed(boolean wasStopped) {
        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "notifyAppResumed: wasStopped=" + wasStopped
                + " " + this);
        mAppStopped = false;
        // Allow the window to turn the screen on once the app is resumed again.
        setCanTurnScreenOn(true);
        if (!wasStopped) {
            destroySurfaces(true /*cleanupOnResume*/);
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
        if (getController() != null) {
            getController().removeStartingWindow();
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

    Task getTask() {
        return (Task) getParent();
    }

    TaskStack getStack() {
        final Task task = getTask();
        if (task != null) {
            return task.mStack;
        } else {
            return null;
        }
    }

    @Override
    void onParentSet() {
        super.onParentSet();

        final Task task = getTask();

        // When the associated task is {@code null}, the {@link AppWindowToken} can no longer
        // access visual elements like the {@link DisplayContent}. We must remove any associations
        // such as animations.
        if (!mReparenting) {
            if (task == null) {
                // It is possible we have been marked as a closing app earlier. We must remove ourselves
                // from this list so we do not participate in any future animations.
                mService.mClosingApps.remove(this);
            } else if (mLastParent != null && mLastParent.mStack != null) {
                task.mStack.mExitingAppTokens.remove(this);
            }
        }
        mLastParent = task;
    }

    void postWindowRemoveStartingWindowCleanup(WindowState win) {
        // TODO: Something smells about the code below...Is there a better way?
        if (startingWindow == win) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Notify removed startingWindow " + win);
            if (getController() != null) {
                getController().removeStartingWindow();
            }
        } else if (mChildren.size() == 0) {
            // If this is the last window and we had requested a starting transition window,
            // well there is no point now.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Nulling last startingData");
            startingData = null;
            if (mHiddenSetFromTransferredStartingWindow) {
                // We set the hidden state to false for the token from a transferred starting window.
                // We now reset it back to true since the starting window was the last window in the
                // token.
                hidden = true;
            }
        } else if (mChildren.size() == 1 && startingSurface != null && !isRelaunching()) {
            // If this is the last window except for a starting transition window,
            // we need to get rid of the starting transition.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Last window, removing starting window "
                    + win);
            if (getController() != null) {
                getController().removeStartingWindow();
            }
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

    boolean shouldFreezeBounds() {
        final Task task = getTask();

        // For freeform windows, we can't freeze the bounds at the moment because this would make
        // the resizing unresponsive.
        if (task == null || task.inFreeformWorkspace()) {
            return false;
        }

        // We freeze the bounds while drag resizing to deal with the time between
        // the divider/drag handle being released, and the handling it's new
        // configuration. If we are relaunched outside of the drag resizing state,
        // we need to be careful not to do this.
        return getTask().isDragResizing();
    }

    void startRelaunching() {
        if (shouldFreezeBounds()) {
            freezeBounds();
        }

        // In the process of tearing down before relaunching, the app will
        // try and clean up it's child surfaces. We need to prevent this from
        // happening, so we sever the children, transfering their ownership
        // from the client it-self to the parent surface (owned by us).
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.mWinAnimator.detachChildren();
        }

        mPendingRelaunchCount++;
    }

    void finishRelaunching() {
        unfreezeBounds();

        if (mPendingRelaunchCount > 0) {
            mPendingRelaunchCount--;
        } else {
            // Update keyguard flags upon finishing relaunch.
            checkKeyguardFlagsChanged();
        }
    }

    void clearRelaunching() {
        if (mPendingRelaunchCount == 0) {
            return;
        }
        unfreezeBounds();
        mPendingRelaunchCount = 0;
    }

    /**
     * Returns true if the new child window we are adding to this token is considered greater than
     * the existing child window in this token in terms of z-order.
     */
    @Override
    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow,
            WindowState existingWindow) {
        final int type1 = newWindow.mAttrs.type;
        final int type2 = existingWindow.mAttrs.type;

        // Base application windows should be z-ordered BELOW all other windows in the app token.
        if (type1 == TYPE_BASE_APPLICATION && type2 != TYPE_BASE_APPLICATION) {
            return false;
        } else if (type1 != TYPE_BASE_APPLICATION && type2 == TYPE_BASE_APPLICATION) {
            return true;
        }

        // Starting windows should be z-ordered ABOVE all other windows in the app token.
        if (type1 == TYPE_APPLICATION_STARTING && type2 != TYPE_APPLICATION_STARTING) {
            return true;
        } else if (type1 != TYPE_APPLICATION_STARTING && type2 == TYPE_APPLICATION_STARTING) {
            return false;
        }

        // Otherwise the new window is greater than the existing window.
        return true;
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
        checkKeyguardFlagsChanged();
    }

    @Override
    void removeChild(WindowState child) {
        super.removeChild(child);
        checkKeyguardFlagsChanged();
    }

    private boolean waitingForReplacement() {
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

    void reparent(Task task, int position) {
        final Task currentTask = getTask();
        if (task == currentTask) {
            throw new IllegalArgumentException(
                    "window token=" + this + " already child of task=" + currentTask);
        }

        if (currentTask.mStack != task.mStack) {
            throw new IllegalArgumentException(
                    "window token=" + this + " current task=" + currentTask
                        + " belongs to a different stack than " + task);
        }

        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "reParentWindowToken: removing window token=" + this
                + " from task=" + currentTask);
        final DisplayContent prevDisplayContent = getDisplayContent();

        mReparenting = true;

        getParent().removeChild(this);
        task.addChild(this, position);

        mReparenting = false;

        // Relayout display(s).
        final DisplayContent displayContent = task.getDisplayContent();
        displayContent.setLayoutNeeded();
        if (prevDisplayContent != displayContent) {
            onDisplayChanged(displayContent);
            prevDisplayContent.setLayoutNeeded();
        }
    }

    /**
     * Freezes the task bounds. The size of this task reported the app will be fixed to the bounds
     * freezed by {@link Task#prepareFreezingBounds} until {@link #unfreezeBounds} gets called, even
     * if they change in the meantime. If the bounds are already frozen, the bounds will be frozen
     * with a queue.
     */
    private void freezeBounds() {
        final Task task = getTask();
        mFrozenBounds.offer(new Rect(task.mPreparedFrozenBounds));

        if (task.mPreparedFrozenMergedConfig.equals(Configuration.EMPTY)) {
            // We didn't call prepareFreezingBounds on the task, so use the current value.
            mFrozenMergedConfig.offer(new Configuration(task.getConfiguration()));
        } else {
            mFrozenMergedConfig.offer(new Configuration(task.mPreparedFrozenMergedConfig));
        }
        // Calling unset() to make it equal to Configuration.EMPTY.
        task.mPreparedFrozenMergedConfig.unset();
    }

    /**
     * Unfreezes the previously frozen bounds. See {@link #freezeBounds}.
     */
    private void unfreezeBounds() {
        if (mFrozenBounds.isEmpty()) {
            return;
        }
        mFrozenBounds.remove();
        if (!mFrozenMergedConfig.isEmpty()) {
            mFrozenMergedConfig.remove();
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            win.onUnfreezeBounds();
        }
        mService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void setAppLayoutChanges(int changes, String reason) {
        if (!mChildren.isEmpty()) {
            final DisplayContent dc = getDisplayContent();
            dc.pendingLayoutChanges |= changes;
            if (DEBUG_LAYOUT_REPEATS) {
                mService.mWindowPlacerLocked.debugLayoutRepeats(reason, dc.pendingLayoutChanges);
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
                    mService.startFreezingDisplayLocked(false, 0, 0, getDisplayContent());
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
        final AppWindowToken fromToken = getDisplayContent().getAppWindowToken(transferFrom);
        if (fromToken == null) {
            return false;
        }

        final WindowState tStartingWindow = fromToken.startingWindow;
        if (tStartingWindow != null && fromToken.startingSurface != null) {
            // In this case, the starting icon has already been displayed, so start
            // letting windows get shown immediately without any more transitions.
            mService.mSkipAppTransitionAnimation = true;

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Moving existing starting " + tStartingWindow
                    + " from " + fromToken + " to " + this);

            final long origId = Binder.clearCallingIdentity();

            // Transfer the starting window over to the new token.
            startingData = fromToken.startingData;
            startingSurface = fromToken.startingSurface;
            startingDisplayed = fromToken.startingDisplayed;
            fromToken.startingDisplayed = false;
            startingWindow = tStartingWindow;
            reportedVisible = fromToken.reportedVisible;
            fromToken.startingData = null;
            fromToken.startingSurface = null;
            fromToken.startingWindow = null;
            fromToken.startingMoved = true;
            tStartingWindow.mToken = this;
            tStartingWindow.mAppToken = this;

            if (DEBUG_ADD_REMOVE || DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                    "Removing starting " + tStartingWindow + " from " + fromToken);
            fromToken.removeChild(tStartingWindow);
            fromToken.postWindowRemoveStartingWindowCleanup(tStartingWindow);
            fromToken.mHiddenSetFromTransferredStartingWindow = false;
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
                mHiddenSetFromTransferredStartingWindow = true;
            }
            setClientHidden(fromToken.mClientHidden);
            fromToken.mAppAnimator.transferCurrentAnimation(
                    mAppAnimator, tStartingWindow.mWinAnimator);

            mService.updateFocusedWindowLocked(
                    UPDATE_FOCUS_WILL_PLACE_SURFACES, true /*updateInputWindows*/);
            getDisplayContent().setLayoutNeeded();
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
            if (getController() != null) {
                getController().scheduleAddStartingWindow();
            }
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
    int getOrientation(int candidate) {
        // We do not allow non-fullscreen apps to influence orientation starting in O-MR1. While we
        // do throw an exception in {@link Activity#onCreate} and
        // {@link Activity#setRequestedOrientation}, we also ignore the orientation here so that
        // other calculations aren't affected.
        if (!fillsParent() && mTargetSdk >= O_MR1) {
            // Can't specify orientation if app doesn't fill parent.
            return SCREEN_ORIENTATION_UNSET;
        }

        if (candidate == SCREEN_ORIENTATION_BEHIND) {
            // Allow app to specify orientation regardless of its visibility state if the current
            // candidate want us to use orientation behind. I.e. the visible app on-top of this one
            // wants us to use the orientation of the app behind it.
            return mOrientation;
        }

        // The {@link AppWindowToken} should only specify an orientation when it is not closing or
        // going to the bottom. Allowing closing {@link AppWindowToken} to participate can lead to
        // an Activity in another task being started in the wrong orientation during the transition.
        if (!(sendingToBottom || mService.mClosingApps.contains(this))
                && (isVisible() || mService.mOpeningApps.contains(this) || isOnTop())) {
            return mOrientation;
        }

        return SCREEN_ORIENTATION_UNSET;
    }

    /** Returns the app's preferred orientation regardless of its currently visibility state. */
    int getOrientationIgnoreVisibility() {
        return mOrientation;
    }

    @Override
    void checkAppWindowsReadyToShow() {
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
                    + " numInteresting=" + mNumInterestingWindows + " numDrawn=" + mNumDrawnWindows);
            // This will set mOrientationChangeComplete and cause a pass through layout.
            setAppLayoutChanges(FINISH_LAYOUT_REDO_WALLPAPER,
                    "checkAppWindowsReadyToShow: freezingScreen");
        } else {
            setAppLayoutChanges(FINISH_LAYOUT_REDO_ANIM, "checkAppWindowsReadyToShow");

            // We can now show all of the drawn windows!
            if (!mService.mOpeningApps.contains(this)) {
                mService.mAnimator.orAnimating(mAppAnimator.showAllWindowsLocked());
            }
        }
    }

    /**
     * Returns whether the drawn window states of this {@link AppWindowToken} has considered every
     * child {@link WindowState}. A child is considered if it has been passed into
     * {@link #updateDrawnWindowStates(WindowState)} after being added. This is used to determine
     * whether states, such as {@code allDrawn}, can be set, which relies on state variables such as
     * {@code mNumInterestingWindows}, which depend on all {@link WindowState}s being considered.
     *
     * @return {@code true} If all children have been considered, {@code false}.
     */
    private boolean allDrawnStatesConsidered() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState child = mChildren.get(i);
            if (child.mightAffectAllDrawn(false /*visibleOnly*/ )
                    && !child.getDrawnStateEvaluated()) {
                return false;
            }
        }
        return true;
    }

    /**
     *  Determines if the token has finished drawing. This should only be called from
     *  {@link DisplayContent#applySurfaceChangesTransaction}
     */
    void updateAllDrawn() {
        if (!allDrawn) {
            // Number of drawn windows can be less when a window is being relaunched, wait for
            // all windows to be launched and drawn for this token be considered all drawn.
            final int numInteresting = mNumInterestingWindows;

            // We must make sure that all present children have been considered (determined by
            // {@link #allDrawnStatesConsidered}) before evaluating whether everything has been
            // drawn.
            if (numInteresting > 0 && allDrawnStatesConsidered()
                    && mNumDrawnWindows >= numInteresting && !isRelaunching()) {
                if (DEBUG_VISIBILITY) Slog.v(TAG, "allDrawn: " + this
                        + " interesting=" + numInteresting + " drawn=" + mNumDrawnWindows);
                allDrawn = true;
                // Force an additional layout pass where
                // WindowStateAnimator#commitFinishDrawingLocked() will call performShowLocked().
                if (mDisplayContent != null) {
                    mDisplayContent.setLayoutNeeded();
                }
                mService.mH.obtainMessage(NOTIFY_ACTIVITY_DRAWN, token).sendToTarget();

                // Notify the pinned stack upon all windows drawn. If there was an animation in
                // progress then this signal will resume that animation.
                final TaskStack pinnedStack = mDisplayContent.getStackById(PINNED_STACK_ID);
                if (pinnedStack != null) {
                    pinnedStack.onAllWindowsDrawn();
                }
            }
        }

        if (!allDrawnExcludingSaved) {
            int numInteresting = mNumInterestingWindowsExcludingSaved;
            if (numInteresting > 0 && mNumDrawnWindowsExcludingSaved >= numInteresting) {
                if (DEBUG_VISIBILITY) Slog.v(TAG, "allDrawnExcludingSaved: " + this
                        + " interesting=" + numInteresting
                        + " drawn=" + mNumDrawnWindowsExcludingSaved);
                allDrawnExcludingSaved = true;
                if (mDisplayContent != null) {
                    mDisplayContent.setLayoutNeeded();
                }
                if (isAnimatingInvisibleWithSavedSurface()
                        && !mService.mFinishedEarlyAnim.contains(this)) {
                    mService.mFinishedEarlyAnim.add(this);
                }
            }
        }
    }

    /**
     * Updated this app token tracking states for interesting and drawn windows based on the window.
     *
     * @return Returns true if the input window is considered interesting and drawn while all the
     *         windows in this app token where not considered drawn as of the last pass.
     */
    boolean updateDrawnWindowStates(WindowState w) {
        w.setDrawnStateEvaluated(true /*evaluated*/);

        if (DEBUG_STARTING_WINDOW_VERBOSE && w == startingWindow) {
            Slog.d(TAG, "updateWindows: starting " + w + " isOnScreen=" + w.isOnScreen()
                    + " allDrawn=" + allDrawn + " freezingScreen=" + mAppAnimator.freezingScreen);
        }

        if (allDrawn && allDrawnExcludingSaved && !mAppAnimator.freezingScreen) {
            return false;
        }

        if (mLastTransactionSequence != mService.mTransactionSequence) {
            mLastTransactionSequence = mService.mTransactionSequence;
            mNumInterestingWindows = mNumDrawnWindows = 0;
            mNumInterestingWindowsExcludingSaved = 0;
            mNumDrawnWindowsExcludingSaved = 0;
            startingDisplayed = false;
        }

        final WindowStateAnimator winAnimator = w.mWinAnimator;

        boolean isInterestingAndDrawn = false;

        if (!allDrawn && w.mightAffectAllDrawn(false /* visibleOnly */)) {
            if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) {
                Slog.v(TAG, "Eval win " + w + ": isDrawn=" + w.isDrawnLw()
                        + ", isAnimationSet=" + winAnimator.isAnimationSet());
                if (!w.isDrawnLw()) {
                    Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurfaceController
                            + " pv=" + w.mPolicyVisibility
                            + " mDrawState=" + winAnimator.drawStateToString()
                            + " ph=" + w.isParentWindowHidden() + " th=" + hiddenRequested
                            + " a=" + winAnimator.mAnimating);
                }
            }

            if (w != startingWindow) {
                if (w.isInteresting()) {
                    mNumInterestingWindows++;
                    if (w.isDrawnLw()) {
                        mNumDrawnWindows++;

                        if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) Slog.v(TAG, "tokenMayBeDrawn: "
                                + this + " w=" + w + " numInteresting=" + mNumInterestingWindows
                                + " freezingScreen=" + mAppAnimator.freezingScreen
                                + " mAppFreezing=" + w.mAppFreezing);

                        isInterestingAndDrawn = true;
                    }
                }
            } else if (w.isDrawnLw()) {
                if (getController() != null) {
                    getController().reportStartingWindowDrawn();
                }
                startingDisplayed = true;
            }
        }

        if (!allDrawnExcludingSaved && w.mightAffectAllDrawn(true /* visibleOnly */)) {
            if (w != startingWindow && w.isInteresting()) {
                mNumInterestingWindowsExcludingSaved++;
                if (w.isDrawnLw() && !w.isAnimatingWithSavedSurface()) {
                    mNumDrawnWindowsExcludingSaved++;

                    if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) Slog.v(TAG,
                            "tokenMayBeDrawnExcludingSaved: " + this + " w=" + w
                            + " numInteresting=" + mNumInterestingWindowsExcludingSaved
                            + " freezingScreen=" + mAppAnimator.freezingScreen
                            + " mAppFreezing=" + w.mAppFreezing);

                    isInterestingAndDrawn = true;
                }
            }
        }

        return isInterestingAndDrawn;
    }

    @Override
    void stepAppWindowsAnimation(long currentTime) {
        mAppAnimator.wasAnimating = mAppAnimator.animating;
        if (mAppAnimator.stepAnimationLocked(currentTime)) {
            mAppAnimator.animating = true;
            mService.mAnimator.setAnimating(true);
            mService.mAnimator.mAppWindowAnimating = true;
        } else if (mAppAnimator.wasAnimating) {
            // stopped animating, do one more pass through the layout
            setAppLayoutChanges(FINISH_LAYOUT_REDO_WALLPAPER,
                    DEBUG_LAYOUT_REPEATS ? "appToken " + this + " done" : null);
            if (DEBUG_ANIM) Slog.v(TAG, "updateWindowsApps...: done animating " + this);
        }
    }

    @Override
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        // For legacy reasons we process the TaskStack.mExitingAppTokens first in DisplayContent
        // before the non-exiting app tokens. So, we skip the exiting app tokens here.
        // TODO: Investigate if we need to continue to do this or if we can just process them
        // in-order.
        if (mIsExiting && !waitingForReplacement()) {
            return false;
        }
        return forAllWindowsUnchecked(callback, traverseTopToBottom);
    }

    boolean forAllWindowsUnchecked(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        return super.forAllWindows(callback, traverseTopToBottom);
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

    boolean containsDismissKeyguardWindow() {
        // Window state is transient during relaunch. We are not guaranteed to be frozen during the
        // entirety of the relaunch.
        if (isRelaunching()) {
            return mLastContainsDismissKeyguardWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_DISMISS_KEYGUARD) != 0) {
                return true;
            }
        }
        return false;
    }

    boolean containsShowWhenLockedWindow() {
        // When we are relaunching, it is possible for us to be unfrozen before our previous
        // windows have been added back. Using the cached value ensures that our previous
        // showWhenLocked preference is honored until relaunching is complete.
        if (isRelaunching()) {
            return mLastContainsShowWhenLockedWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_SHOW_WHEN_LOCKED) != 0) {
                return true;
            }
        }

        return false;
    }

    void checkKeyguardFlagsChanged() {
        final boolean containsDismissKeyguard = containsDismissKeyguardWindow();
        final boolean containsShowWhenLocked = containsShowWhenLockedWindow();
        if (containsDismissKeyguard != mLastContainsDismissKeyguardWindow
                || containsShowWhenLocked != mLastContainsShowWhenLockedWindow) {
            mService.notifyKeyguardFlagsChanged(null /* callback */);
        }
        mLastContainsDismissKeyguardWindow = containsDismissKeyguard;
        mLastContainsShowWhenLockedWindow = containsShowWhenLocked;
    }

    WindowState getImeTargetBelowWindow(WindowState w) {
        final int index = mChildren.indexOf(w);
        if (index > 0) {
            final WindowState target = mChildren.get(index - 1);
            if (target.canBeImeTarget()) {
                return target;
            }
        }
        return null;
    }

    int getLowestAnimLayer() {
        for (int i = 0; i < mChildren.size(); i++) {
            final WindowState w = mChildren.get(i);
            if (w.mRemoved) {
                continue;
            }
            return w.mWinAnimator.mAnimLayer;
        }
        return Integer.MAX_VALUE;
    }

    WindowState getHighestAnimLayerWindow(WindowState currentTarget) {
        WindowState candidate = null;
        for (int i = mChildren.indexOf(currentTarget); i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            if (w.mRemoved) {
                continue;
            }
            if (candidate == null || w.mWinAnimator.mAnimLayer >
                    candidate.mWinAnimator.mAnimLayer) {
                candidate = w;
            }
        }
        return candidate;
    }

    /**
     * See {@link Activity#setDisablePreviewScreenshots}.
     */
    void setDisablePreviewScreenshots(boolean disable) {
        mDisablePreviewScreenshots = disable;
    }

    /**
     * Sets whether the current launch can turn the screen on. See {@link #canTurnScreenOn()}
     */
    void setCanTurnScreenOn(boolean canTurnScreenOn) {
        mCanTurnScreenOn = canTurnScreenOn;
    }

    /**
     * Indicates whether the current launch can turn the screen on. This is to prevent multiple
     * relayouts from turning the screen back on. The screen should only turn on at most
     * once per activity resume.
     *
     * @return true if the screen can be turned on.
     */
    boolean canTurnScreenOn() {
        return mCanTurnScreenOn;
    }

    /**
     * Retrieves whether we'd like to generate a snapshot that's based solely on the theme. This is
     * the case when preview screenshots are disabled {@link #setDisablePreviewScreenshots} or when
     * we can't take a snapshot for other reasons, for example, if we have a secure window.
     *
     * @return True if we need to generate an app theme snapshot, false if we'd like to take a real
     *         screenshot.
     */
    boolean shouldUseAppThemeSnapshot() {
        return mDisablePreviewScreenshots || forAllWindows(w -> (w.mAttrs.flags & FLAG_SECURE) != 0,
                true /* topToBottom */);
    }

    @Override
    int getAnimLayerAdjustment() {
        return mAppAnimator.animLayerAdjustment;
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        if (appToken != null) {
            pw.println(prefix + "app=true mVoiceInteraction=" + mVoiceInteraction);
        }
        pw.print(prefix); pw.print("task="); pw.println(getTask());
        pw.print(prefix); pw.print(" mFillsParent="); pw.print(mFillsParent);
                pw.print(" mOrientation="); pw.println(mOrientation);
        pw.println(prefix + "hiddenRequested=" + hiddenRequested + " mClientHidden=" + mClientHidden
            + ((mDeferHidingClient) ? " mDeferHidingClient=" + mDeferHidingClient : "")
            + " reportedDrawn=" + reportedDrawn + " reportedVisible=" + reportedVisible);
        if (paused) {
            pw.print(prefix); pw.print("paused="); pw.println(paused);
        }
        if (mAppStopped) {
            pw.print(prefix); pw.print("mAppStopped="); pw.println(mAppStopped);
        }
        if (mNumInterestingWindows != 0 || mNumDrawnWindows != 0
                || allDrawn || mAppAnimator.allDrawn) {
            pw.print(prefix); pw.print("mNumInterestingWindows=");
                    pw.print(mNumInterestingWindows);
                    pw.print(" mNumDrawnWindows="); pw.print(mNumDrawnWindows);
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
        if (startingWindow != null || startingSurface != null
                || startingDisplayed || startingMoved || mHiddenSetFromTransferredStartingWindow) {
            pw.print(prefix); pw.print("startingWindow="); pw.print(startingWindow);
                    pw.print(" startingSurface="); pw.print(startingSurface);
                    pw.print(" startingDisplayed="); pw.print(startingDisplayed);
                    pw.print(" startingMoved="); pw.print(startingMoved);
                    pw.println(" mHiddenSetFromTransferredStartingWindow="
                            + mHiddenSetFromTransferredStartingWindow);
        }
        if (!mFrozenBounds.isEmpty()) {
            pw.print(prefix); pw.print("mFrozenBounds="); pw.println(mFrozenBounds);
            pw.print(prefix); pw.print("mFrozenMergedConfig="); pw.println(mFrozenMergedConfig);
        }
        if (mPendingRelaunchCount != 0) {
            pw.print(prefix); pw.print("mPendingRelaunchCount="); pw.println(mPendingRelaunchCount);
        }
        if (getController() != null) {
            pw.print(prefix); pw.print("controller="); pw.println(getController());
        }
        if (mRemovingFromDisplay) {
            pw.println(prefix + "mRemovingFromDisplay=" + mRemovingFromDisplay);
        }
        if (mAppAnimator.isAnimating()) {
            mAppAnimator.dump(pw, prefix + "  ");
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
        return stringName + ((mIsExiting) ? " mIsExiting=" : "");
    }
}
