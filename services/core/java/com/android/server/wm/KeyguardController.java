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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_APPEARING;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_LAUNCHER_CLEAR_SNAPSHOT;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_OCCLUDING;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_UNOCCLUDING;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_SUBTLE_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.KeyguardControllerProto.AOD_SHOWING;
import static com.android.server.wm.KeyguardControllerProto.KEYGUARD_GOING_AWAY;
import static com.android.server.wm.KeyguardControllerProto.KEYGUARD_PER_DISPLAY;
import static com.android.server.wm.KeyguardControllerProto.KEYGUARD_SHOWING;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.WindowManager;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.window.flags.Flags;

import java.io.PrintWriter;

/**
 * Controls Keyguard occluding, dismissing and transitions depending on what kind of activities are
 * currently visible.
 * <p>
 * Note that everything in this class should only be accessed with the AM lock being held.
 */
class KeyguardController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "KeyguardController" : TAG_ATM;

    static final String KEYGUARD_SLEEP_TOKEN_TAG = "keyguard";

    private static final int DEFER_WAKE_TRANSITION_TIMEOUT_MS = 5000;

    private final ActivityTaskSupervisor mTaskSupervisor;
    private WindowManagerService mWindowManager;

    private final SparseArray<KeyguardDisplayState> mDisplayStates = new SparseArray<>();
    private final ActivityTaskManagerService mService;
    private RootWindowContainer mRootWindowContainer;
    private final ActivityTaskManagerInternal.SleepTokenAcquirer mSleepTokenAcquirer;
    private boolean mWaitingForWakeTransition;
    private Transition.ReadyCondition mWaitAodHide = null;

    KeyguardController(ActivityTaskManagerService service,
            ActivityTaskSupervisor taskSupervisor) {
        mService = service;
        mTaskSupervisor = taskSupervisor;
        mSleepTokenAcquirer = mService.new SleepTokenAcquirerImpl(KEYGUARD_SLEEP_TOKEN_TAG);
    }

    void setWindowManager(WindowManagerService windowManager) {
        mWindowManager = windowManager;
        mRootWindowContainer = mService.mRootWindowContainer;
    }

    boolean isAodShowing(int displayId) {
        return getDisplayState(displayId).mAodShowing;
    }

    /**
     * @return true if either Keyguard or AOD are showing, not going away, and not being occluded
     *         on the given display, false otherwise.
     */
    boolean isKeyguardOrAodShowing(int displayId) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        return (state.mKeyguardShowing || state.mAodShowing)
                && !state.mKeyguardGoingAway
                && !state.mOccluded;
    }

    /**
     * @return {@code true} for default display when AOD is showing, not going away. Otherwise, same
     *         as {@link #isKeyguardOrAodShowing(int)}
     * TODO(b/125198167): Replace isKeyguardOrAodShowing() by this logic.
     */
    boolean isKeyguardUnoccludedOrAodShowing(int displayId) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        if (displayId == DEFAULT_DISPLAY && state.mAodShowing) {
            return !state.mKeyguardGoingAway;
        }
        return isKeyguardOrAodShowing(displayId);
    }

    /**
     * @return true if Keyguard is showing, not going away, and not being occluded on the given
     *         display, false otherwise
     */
    boolean isKeyguardShowing(int displayId) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        return state.mKeyguardShowing && !state.mKeyguardGoingAway && !state.mOccluded;
    }

    /**
     * @return true if Keyguard is either showing or occluded, but not going away
     */
    boolean isKeyguardLocked(int displayId) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        return state.mKeyguardShowing && !state.mKeyguardGoingAway;
    }

    /** Returns {code @true} if Keyguard is occluded while it is showing and not going away. */
    boolean isKeyguardOccluded(int displayId) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        return state.mKeyguardShowing && !state.mKeyguardGoingAway && state.mOccluded;
    }

    /**
     *
     * @return true if the activity is controlling keyguard state.
     */
    boolean topActivityOccludesKeyguard(ActivityRecord r) {
        return getDisplayState(r.getDisplayId()).mTopOccludesActivity == r;
    }

    /**
     * @return {@code true} if the keyguard is going away, {@code false} otherwise.
     */
    boolean isKeyguardGoingAway(int displayId) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        // Also check keyguard showing in case value is stale.
        return state.mKeyguardGoingAway && state.mKeyguardShowing;
    }

    /**
     * Update the Keyguard showing state.
     */
    void setKeyguardShown(int displayId, boolean keyguardShowing, boolean aodShowing) {
        final DisplayContent dc = mRootWindowContainer.getDisplayContent(displayId);

        if (dc == null) {
            Slog.w(TAG, "setKeyguardShown called on non-existent display " + displayId);
            return;
        }
        if (dc.isKeyguardAlwaysUnlocked()) {
            Slog.i(TAG, "setKeyguardShown ignoring always unlocked display " + displayId);
            return;
        }

        final KeyguardDisplayState state = getDisplayState(displayId);
        final boolean aodChanged = aodShowing != state.mAodShowing;
        final boolean aodRemoved = state.mAodShowing && !aodShowing;
        final boolean goingAwayRemoved = state.mKeyguardGoingAway && keyguardShowing;
        // If keyguard is going away, but SystemUI aborted the transition, need to reset state.
        // Do not reset keyguardChanged status when only AOD is removed.
        final boolean keyguardChanged = (keyguardShowing != state.mKeyguardShowing)
                || (goingAwayRemoved && !aodRemoved);
        if (aodRemoved) {
            updateDeferTransitionForAod(false /* waiting */);
        }
        if (!keyguardChanged && !aodChanged) {
            setWakeTransitionReady();
            return;
        }
        EventLogTags.writeWmSetKeyguardShown(
                displayId,
                keyguardShowing ? 1 : 0,
                aodShowing ? 1 : 0,
                state.mKeyguardGoingAway ? 1 : 0,
                state.mOccluded ? 1 : 0,
                "setKeyguardShown");

        // Update the task snapshot if the screen will not be turned off. To make sure that the
        // unlocking animation can animate consistent content. The conditions are:
        // - Either AOD or keyguard changes to be showing. So if the states change individually,
        //   the later one can be skipped to avoid taking snapshot again. While it still accepts
        //   if both of them change to show at the same time.
        // - Keyguard was not going away. Because if it was, the closing transition is able to
        //   handle the snapshot.
        // - The display state is ON. Because if AOD is not on or pulsing, the display state will
        //   be OFF or DOZE (the path of screen off may have handled it).
        if (displayId == DEFAULT_DISPLAY
                && ((aodShowing ^ keyguardShowing) || (aodShowing && aodChanged && keyguardChanged))
                && !state.mKeyguardGoingAway
                && Display.isOnState(dc.getDisplayInfo().state)) {
            mWindowManager.mTaskSnapshotController.snapshotForSleeping(DEFAULT_DISPLAY);
        }

        state.mKeyguardShowing = keyguardShowing;
        state.mAodShowing = aodShowing;

        if (keyguardChanged) {
            // Irrelevant to AOD.
            state.mKeyguardGoingAway = false;
            if (keyguardShowing) {
                state.mDismissalRequested = false;
            }
            if (goingAwayRemoved
                    || (Flags.keyguardAppearTransition() && keyguardShowing
                            && !Display.isOffState(dc.getDisplayInfo().state))) {
                // Keyguard decided to show or stopped going away. Send a transition to animate back
                // to the locked state before holding the sleep token again
                final DisplayContent transitionDc = Flags.keyguardAppearTransition()
                        ? dc
                        : mRootWindowContainer.getDefaultDisplay();
                transitionDc.requestTransitionAndLegacyPrepare(
                        TRANSIT_TO_FRONT, TRANSIT_FLAG_KEYGUARD_APPEARING);
                if (Flags.keyguardAppearTransition()) {
                    dc.mWallpaperController.adjustWallpaperWindows();
                }
                transitionDc.executeAppTransition();
            }
        }

        // Update the sleep token first such that ensureActivitiesVisible has correct sleep token
        // state when evaluating visibilities.
        updateKeyguardSleepToken();
        mRootWindowContainer.ensureActivitiesVisible();
        InputMethodManagerInternal.get().updateImeWindowStatus(false /* disableImeIcon */,
                displayId);
        setWakeTransitionReady();
        if (aodChanged) {
            // Ensure the new state takes effect.
            mWindowManager.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    private void setWakeTransitionReady() {
        if (mWindowManager.mAtmService.getTransitionController().getCollectingTransitionType()
                == WindowManager.TRANSIT_WAKE) {
            mWindowManager.mAtmService.getTransitionController().setReady(
                    mRootWindowContainer.getDefaultDisplay());
        }
    }

    /**
     * Called when Keyguard is going away.
     *
     * @param flags See {@link WindowManagerPolicy#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     */
    void keyguardGoingAway(int displayId, int flags) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        if (!state.mKeyguardShowing || state.mKeyguardGoingAway) {
            return;
        }
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "keyguardGoingAway");
        mService.deferWindowLayout();
        state.mKeyguardGoingAway = true;
        try {
            EventLogTags.writeWmSetKeyguardShown(
                    displayId,
                    state.mKeyguardShowing ? 1 : 0,
                    state.mAodShowing ? 1 : 0,
                    1 /* keyguardGoingAway */,
                    state.mOccluded ? 1 : 0,
                    "keyguardGoingAway");
            final int transitFlags = convertTransitFlags(flags);
            final DisplayContent dc = mRootWindowContainer.getDefaultDisplay();
            dc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, transitFlags);
            // We are deprecating TRANSIT_KEYGUARD_GOING_AWAY for Shell transition and use
            // TRANSIT_FLAG_KEYGUARD_GOING_AWAY to indicate that it should animate keyguard going
            // away.
            dc.mAtmService.getTransitionController().requestTransitionIfNeeded(
                    TRANSIT_TO_BACK, transitFlags, null /* trigger */, dc);
            updateKeyguardSleepToken();

            // Some stack visibility might change (e.g. docked stack)
            mRootWindowContainer.resumeFocusedTasksTopActivities();
            mRootWindowContainer.ensureActivitiesVisible();
            mRootWindowContainer.addStartingWindowsForVisibleActivities();
            mWindowManager.executeAppTransition();
        } finally {
            mService.continueWindowLayout();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback, CharSequence message) {
        final ActivityRecord activityRecord = ActivityRecord.forTokenLocked(token);
        if (activityRecord == null || !activityRecord.visibleIgnoringKeyguard) {
            failCallback(callback);
            return;
        }
        Slog.i(TAG, "Activity requesting to dismiss Keyguard: " + activityRecord);

        // If the client has requested to dismiss the keyguard and the Activity has the flag to
        // turn the screen on, wakeup the screen if it's the top Activity.
        if (activityRecord.getTurnScreenOnFlag() && activityRecord.isTopRunningActivity()) {
            mTaskSupervisor.wakeUp("dismissKeyguard");
        }

        mWindowManager.dismissKeyguard(callback, message);
    }

    private void failCallback(IKeyguardDismissCallback callback) {
        try {
            callback.onDismissError();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call callback", e);
        }
    }

    private int convertTransitFlags(int keyguardGoingAwayFlags) {
        int result = TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
        if ((keyguardGoingAwayFlags & KEYGUARD_GOING_AWAY_FLAG_TO_SHADE) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
        }
        if ((keyguardGoingAwayFlags & KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
        }
        if ((keyguardGoingAwayFlags & KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
        }
        if ((keyguardGoingAwayFlags & KEYGUARD_GOING_AWAY_FLAG_SUBTLE_WINDOW_ANIMATIONS) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
        }
        if ((keyguardGoingAwayFlags
                & KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_LAUNCHER_CLEAR_SNAPSHOT;
        }
        return result;
    }

    /**
     * @return True if we may show an activity while Keyguard is showing because we are in the
     *         process of dismissing it anyways, false otherwise.
     */
    boolean canShowActivityWhileKeyguardShowing(ActivityRecord r) {
        // Allow to show it when we are about to dismiss Keyguard. This isn't allowed if r is
        // already the dismissing activity, in which case we don't allow it to repeatedly dismiss
        // Keyguard.
        final KeyguardDisplayState state = getDisplayState(r.getDisplayId());
        return r.containsDismissKeyguardWindow() && canDismissKeyguard() && !state.mAodShowing
                && (state.mDismissalRequested
                || (r.canShowWhenLocked() && state.mDismissingKeyguardActivity != r));
    }

    /**
     * @return True if we may show an activity while Keyguard is occluded, false otherwise.
     */
    boolean canShowWhileOccluded(boolean dismissKeyguard, boolean showWhenLocked) {
        return showWhenLocked || dismissKeyguard
                && !mWindowManager.isKeyguardSecure(mService.getCurrentUserId());
    }

    /**
     * Checks whether {@param r} should be visible depending on Keyguard state.
     *
     * @return true if {@param r} is visible taken Keyguard state into account, false otherwise
     */
    boolean checkKeyguardVisibility(ActivityRecord r) {
        if (r.mDisplayContent.canShowWithInsecureKeyguard() && canDismissKeyguard()) {
            return true;
        }

        if (isKeyguardOrAodShowing(r.mDisplayContent.getDisplayId())) {
            // If keyguard is showing, nothing is visible, except if we are able to dismiss Keyguard
            // right away and AOD isn't visible.
            return canShowActivityWhileKeyguardShowing(r);
        } else if (isKeyguardLocked(r.getDisplayId())) {
            return canShowWhileOccluded(r.containsDismissKeyguardWindow(), r.canShowWhenLocked());
        } else {
            return true;
        }
    }

    /**
     * Makes sure to update lockscreen occluded/dismiss/turnScreenOn state if needed before
     * completing set all visibility
     * ({@link ActivityTaskSupervisor#beginActivityVisibilityUpdate}).
     */
    void updateVisibility() {
        for (int displayNdx = mRootWindowContainer.getChildCount() - 1;
             displayNdx >= 0; displayNdx--) {
            final DisplayContent display = mRootWindowContainer.getChildAt(displayNdx);
            if (display.isRemoving() || display.isRemoved()) continue;
            final KeyguardDisplayState state = getDisplayState(display.mDisplayId);
            state.updateVisibility(this, display);
            if (state.mRequestDismissKeyguard) {
                handleDismissKeyguard(display.getDisplayId());
            }
        }
    }

    /**
     * Called when occluded state changed.
     *
     * @param topActivity the activity that controls the state whether keyguard should
     *      be occluded. That is the activity to be shown on top of keyguard if it requests so.
     */
    private void handleOccludedChanged(int displayId, @Nullable ActivityRecord topActivity) {
        // TODO(b/113840485): Handle app transition for individual display, and apply occluded
        // state change to secondary displays.
        // For now, only default display fully supports occluded change. Other displays only
        // updates keyguard sleep token on that display.
        if (displayId != DEFAULT_DISPLAY) {
            updateKeyguardSleepToken(displayId);
            return;
        }

        final TransitionController tc = mRootWindowContainer.mTransitionController;

        final boolean occluded = getDisplayState(displayId).mOccluded;
        final boolean performTransition = isKeyguardLocked(displayId);
        final boolean executeTransition = performTransition && !tc.isCollecting();

        mWindowManager.mPolicy.onKeyguardOccludedChangedLw(occluded);
        mService.deferWindowLayout();
        try {
            if (isKeyguardLocked(displayId)) {
                final int type = occluded ? TRANSIT_KEYGUARD_OCCLUDE : TRANSIT_KEYGUARD_UNOCCLUDE;
                final int flag = occluded ? TRANSIT_FLAG_KEYGUARD_OCCLUDING
                        : TRANSIT_FLAG_KEYGUARD_UNOCCLUDING;
                if (tc.isShellTransitionsEnabled()) {
                    final Task trigger = (occluded && topActivity != null)
                            ? topActivity.getRootTask() : null;
                    Transition transition = tc.requestTransitionIfNeeded(type, flag, trigger,
                            mRootWindowContainer.getDefaultDisplay());
                    if (trigger != null) {
                        if (transition == null) {
                            transition = tc.getCollectingTransition();
                        }
                        transition.collect(trigger);
                    }
                } else {
                    mRootWindowContainer.getDefaultDisplay().prepareAppTransition(type, flag);
                }
            } else {
                if (tc.inTransition()) {
                    tc.mStateValidators.add(mWindowManager.mPolicy::applyKeyguardOcclusionChange);
                } else {
                    mWindowManager.mPolicy.applyKeyguardOcclusionChange();
                }
            }
            updateKeyguardSleepToken(displayId);
            if (performTransition && executeTransition) {
                mWindowManager.executeAppTransition();
            }
        } finally {
            mService.continueWindowLayout();
        }
    }

    /**
     * Called when keyguard going away state changed.
     */
    private void handleKeyguardGoingAwayChanged(DisplayContent dc) {
        mService.deferWindowLayout();
        try {
            dc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, 0 /* transitFlags */);
            // We are deprecating TRANSIT_KEYGUARD_GOING_AWAY for Shell transition and use
            // TRANSIT_FLAG_KEYGUARD_GOING_AWAY to indicate that it should animate keyguard going
            // away.
            dc.mAtmService.getTransitionController().requestTransitionIfNeeded(
                    TRANSIT_OPEN, TRANSIT_FLAG_KEYGUARD_GOING_AWAY, null /* trigger */, dc);
            updateKeyguardSleepToken();
            mWindowManager.executeAppTransition();
        } finally {
            mService.continueWindowLayout();
        }
    }

    /**
     * Called when somebody wants to dismiss the Keyguard via the flag.
     */
    private void handleDismissKeyguard(int displayId) {
        // We only allow dismissing Keyguard via the flag when Keyguard is secure for legacy
        // reasons, because that's how apps used to dismiss Keyguard in the secure case. In the
        // insecure case, we actually show it on top of the lockscreen. See #canShowWhileOccluded.
        if (!mWindowManager.isKeyguardSecure(mService.getCurrentUserId())) {
            return;
        }

        mWindowManager.dismissKeyguard(null /* callback */, null /* message */);
        final KeyguardDisplayState state = getDisplayState(displayId);
        state.mDismissalRequested = true;

        // If we are about to unocclude the Keyguard, but we can dismiss it without security,
        // we immediately dismiss the Keyguard so the activity gets shown without a flicker.
        final DisplayContent dc = mRootWindowContainer.getDefaultDisplay();
        if (state.mKeyguardShowing && canDismissKeyguard()
                && dc.mAppTransition.containsTransitRequest(TRANSIT_KEYGUARD_UNOCCLUDE)) {
            mWindowManager.executeAppTransition();
        }
    }

    ActivityRecord getTopOccludingActivity(int displayId) {
        return getDisplayState(displayId).mTopOccludesActivity;
    }

    ActivityRecord getDismissKeyguardActivity(int displayId) {
        return getDisplayState(displayId).mDismissingKeyguardActivity;
    }

    /**
     * @return true if Keyguard can be currently dismissed without entering credentials.
     */
    boolean canDismissKeyguard() {
        return mWindowManager.mPolicy.isKeyguardTrustedLw()
                || !mWindowManager.isKeyguardSecure(mService.getCurrentUserId());
    }

    private void updateKeyguardSleepToken() {
        for (int displayNdx = mRootWindowContainer.getChildCount() - 1;
             displayNdx >= 0; displayNdx--) {
            final DisplayContent display = mRootWindowContainer.getChildAt(displayNdx);
            updateKeyguardSleepToken(display.mDisplayId);
        }
    }

    private void updateKeyguardSleepToken(int displayId) {
        final KeyguardDisplayState state = getDisplayState(displayId);
        if (isKeyguardUnoccludedOrAodShowing(displayId)) {
            state.mSleepTokenAcquirer.acquire(displayId);
        } else {
            state.mSleepTokenAcquirer.release(displayId);
        }
    }

    private KeyguardDisplayState getDisplayState(int displayId) {
        KeyguardDisplayState state = mDisplayStates.get(displayId);
        if (state == null) {
            state = new KeyguardDisplayState(mService, displayId, mSleepTokenAcquirer);
            mDisplayStates.append(displayId, state);
        }
        return state;
    }

    void onDisplayRemoved(int displayId) {
        final KeyguardDisplayState state = mDisplayStates.get(displayId);
        if (state != null) {
            state.onRemoved();
            mDisplayStates.remove(displayId);
        }
    }

    private final Runnable mResetWaitTransition = () -> {
        synchronized (mWindowManager.mGlobalLock) {
            updateDeferTransitionForAod(false /* waiting */);
        }
    };

    // Defer transition until AOD dismissed.
    void updateDeferTransitionForAod(boolean waiting) {
        if (mService.getTransitionController().useFullReadyTracking()) {
            if (waiting == (mWaitAodHide != null)) {
                return;
            }
        } else {
            if (waiting == mWaitingForWakeTransition) {
                return;
            }
        }
        if (!mService.getTransitionController().isCollecting()) {
            return;
        }
        // if AOD is showing, defer the wake transition until AOD state changed.
        if (waiting && isAodShowing(DEFAULT_DISPLAY)) {
            mWaitingForWakeTransition = true;
            mWindowManager.mAtmService.getTransitionController().deferTransitionReady();
            mWaitAodHide = new Transition.ReadyCondition("AOD hidden");
            mWindowManager.mAtmService.getTransitionController().waitFor(mWaitAodHide);
            mWindowManager.mH.postDelayed(mResetWaitTransition, DEFER_WAKE_TRANSITION_TIMEOUT_MS);
        } else if (!waiting) {
            // dismiss the deferring if the AOD state change or cancel awake.
            mWaitingForWakeTransition = false;
            mWindowManager.mAtmService.getTransitionController().continueTransitionReady();
            mWindowManager.mH.removeCallbacks(mResetWaitTransition);
            final Transition.ReadyCondition waitAodHide = mWaitAodHide;
            mWaitAodHide = null;
            waitAodHide.meet();
        }
    }


    /** Represents Keyguard state per individual display. */
    private static class KeyguardDisplayState {
        private final int mDisplayId;
        private boolean mKeyguardShowing;
        private boolean mAodShowing;
        private boolean mKeyguardGoingAway;
        private boolean mDismissalRequested;

        /**
         * True if the top activity on the display can occlude keyguard or the device is dreaming.
         * Note that this can be true even if the keyguard is disabled or not showing.
         */
        private boolean mOccluded;

        private ActivityRecord mTopOccludesActivity;
        private ActivityRecord mDismissingKeyguardActivity;
        private ActivityRecord mTopTurnScreenOnActivity;

        private boolean mRequestDismissKeyguard;
        private final ActivityTaskManagerService mService;
        private final ActivityTaskManagerInternal.SleepTokenAcquirer mSleepTokenAcquirer;

        KeyguardDisplayState(ActivityTaskManagerService service, int displayId,
                ActivityTaskManagerInternal.SleepTokenAcquirer acquirer) {
            mService = service;
            mDisplayId = displayId;
            mSleepTokenAcquirer = acquirer;
        }

        void onRemoved() {
            mTopOccludesActivity = null;
            mDismissingKeyguardActivity = null;
            mTopTurnScreenOnActivity = null;
            mSleepTokenAcquirer.release(mDisplayId);
        }

        /**
         * Updates keyguard status if the top task could be visible. The top task may occlude
         * keyguard, request to dismiss keyguard or make insecure keyguard go away based on its
         * properties.
         */
        void updateVisibility(KeyguardController controller, DisplayContent display) {
            final boolean lastOccluded = mOccluded;
            final boolean lastKeyguardGoingAway = mKeyguardGoingAway;

            final ActivityRecord lastDismissKeyguardActivity = mDismissingKeyguardActivity;
            final ActivityRecord lastTurnScreenOnActivity = mTopTurnScreenOnActivity;

            mRequestDismissKeyguard = false;
            mOccluded = false;

            mTopOccludesActivity = null;
            mDismissingKeyguardActivity = null;
            mTopTurnScreenOnActivity = null;

            boolean occludedByActivity = false;
            final Task task = getRootTaskForControllingOccluding(display);
            final ActivityRecord top = task != null ? task.getTopNonFinishingActivity() : null;
            if (top != null) {
                if (top.containsDismissKeyguardWindow()) {
                    mDismissingKeyguardActivity = top;
                }
                if (top.getTurnScreenOnFlag() && top.currentLaunchCanTurnScreenOn()) {
                    mTopTurnScreenOnActivity = top;
                }

                final boolean isKeyguardSecure = controller.mWindowManager.isKeyguardSecure(
                        controller.mService.getCurrentUserId());
                if (top.mDismissKeyguardIfInsecure && mKeyguardShowing && !isKeyguardSecure) {
                    mKeyguardGoingAway = true;
                } else if (top.canShowWhenLocked()) {
                    mTopOccludesActivity = top;
                }
                top.mDismissKeyguardIfInsecure = false;

                // Only the top activity may control occluded, as we can't occlude the Keyguard
                // if the top app doesn't want to occlude it.
                mOccluded = mTopOccludesActivity != null
                        || (mDismissingKeyguardActivity != null
                        && task.topRunningActivity() == mDismissingKeyguardActivity
                        && controller.canShowWhileOccluded(
                                true /* dismissKeyguard */, false /* showWhenLocked */));
                // FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD only apply for secondary display.
                if (mDisplayId != DEFAULT_DISPLAY) {
                    mOccluded |= display.canShowWithInsecureKeyguard()
                            && controller.canDismissKeyguard();
                }
            }

            mRequestDismissKeyguard = lastDismissKeyguardActivity != mDismissingKeyguardActivity
                    && !mOccluded && !mKeyguardGoingAway
                    && mDismissingKeyguardActivity != null;
            if (mOccluded && mKeyguardShowing && !display.isSleeping() && !top.fillsParent()
                    && display.mWallpaperController.getWallpaperTarget() == null) {
                // The occluding activity may be translucent or not fill screen. Then let wallpaper
                // to check whether it should set itself as target to avoid blank background.
                display.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            }

            if (mTopTurnScreenOnActivity != null
                    && !mService.mWindowManager.mPowerManager.isInteractive()
                    && (mRequestDismissKeyguard || occludedByActivity)) {
                controller.mTaskSupervisor.wakeUp("handleTurnScreenOn");
                mTopTurnScreenOnActivity.setCurrentLaunchCanTurnScreenOn(false);
            }

            boolean hasChange = false;
            if (lastOccluded != mOccluded) {
                if (mDisplayId == DEFAULT_DISPLAY) {
                    EventLogTags.writeWmSetKeyguardShown(
                            mDisplayId,
                            mKeyguardShowing ? 1 : 0,
                            mAodShowing ? 1 : 0,
                            mKeyguardGoingAway ? 1 : 0,
                            mOccluded ? 1 : 0,
                            "updateVisibility");
                }
                controller.handleOccludedChanged(mDisplayId, mTopOccludesActivity);
                hasChange = true;
            } else if (!lastKeyguardGoingAway && mKeyguardGoingAway) {
                controller.handleKeyguardGoingAwayChanged(display);
                hasChange = true;
            }
            // Collect the participates for shell transition, so that transition won't happen too
            // early since the transition was set ready.
            if (hasChange && top != null && (mOccluded || mKeyguardGoingAway)) {
                display.mTransitionController.collect(top);
            }
        }

        /**
         * Gets the stack used to check the occluded state.
         * <p>
         * Only the top non-pinned activity of the focusable stack on each display can control its
         * occlusion state.
         */
        @Nullable
        private Task getRootTaskForControllingOccluding(DisplayContent display) {
            return display.getRootTask(task ->
                    task != null && task.isFocusableAndVisible() && !task.inPinnedWindowingMode());
        }

        void dumpStatus(PrintWriter pw, String prefix) {
            final StringBuilder sb = new StringBuilder();
            sb.append(prefix);
            sb.append(" KeyguardShowing=")
                    .append(mKeyguardShowing)
                    .append(" AodShowing=")
                    .append(mAodShowing)
                    .append(" KeyguardGoingAway=")
                    .append(mKeyguardGoingAway)
                    .append(" DismissalRequested=")
                    .append(mDismissalRequested)
                    .append("  Occluded=")
                    .append(mOccluded)
                    .append(" DismissingKeyguardActivity=")
                    .append(mDismissingKeyguardActivity)
                    .append(" TurnScreenOnActivity=")
                    .append(mTopTurnScreenOnActivity)
                    .append(" at display=")
                    .append(mDisplayId);
            pw.println(sb.toString());
        }

        void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(KeyguardPerDisplayProto.DISPLAY_ID, mDisplayId);
            proto.write(KeyguardPerDisplayProto.KEYGUARD_SHOWING, mKeyguardShowing);
            proto.write(KeyguardPerDisplayProto.AOD_SHOWING, mAodShowing);
            proto.write(KeyguardPerDisplayProto.KEYGUARD_OCCLUDED, mOccluded);
            proto.write(KeyguardPerDisplayProto.KEYGUARD_GOING_AWAY, mKeyguardGoingAway);
            proto.end(token);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        final KeyguardDisplayState default_state = getDisplayState(DEFAULT_DISPLAY);
        pw.println(prefix + "KeyguardController:");
        pw.println(prefix + "  mKeyguardShowing=" + default_state.mKeyguardShowing);
        pw.println(prefix + "  mAodShowing=" + default_state.mAodShowing);
        pw.println(prefix + "  mKeyguardGoingAway=" + default_state.mKeyguardGoingAway);
        dumpDisplayStates(pw, prefix);
        pw.println(prefix + "  mDismissalRequested=" + default_state.mDismissalRequested);
        pw.println();
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final KeyguardDisplayState default_state = getDisplayState(DEFAULT_DISPLAY);
        final long token = proto.start(fieldId);
        proto.write(AOD_SHOWING, default_state.mAodShowing);
        proto.write(KEYGUARD_SHOWING, default_state.mKeyguardShowing);
        proto.write(KEYGUARD_GOING_AWAY, default_state.mKeyguardGoingAway);
        writeDisplayStatesToProto(proto, KEYGUARD_PER_DISPLAY);
        proto.end(token);
    }

    private void dumpDisplayStates(PrintWriter pw, String prefix) {
        for (int i = 0; i < mDisplayStates.size(); i++) {
            mDisplayStates.valueAt(i).dumpStatus(pw, prefix);
        }
    }

    private void writeDisplayStatesToProto(ProtoOutputStream proto, long fieldId) {
        for (int i = 0; i < mDisplayStates.size(); i++) {
            mDisplayStates.valueAt(i).dumpDebug(proto, fieldId);
        }
    }
}
