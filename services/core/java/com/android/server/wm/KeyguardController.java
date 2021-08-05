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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_SUBTLE_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.KeyguardControllerProto.AOD_SHOWING;
import static com.android.server.wm.KeyguardControllerProto.KEYGUARD_OCCLUDED_STATES;
import static com.android.server.wm.KeyguardControllerProto.KEYGUARD_SHOWING;
import static com.android.server.wm.KeyguardOccludedProto.DISPLAY_ID;
import static com.android.server.wm.KeyguardOccludedProto.KEYGUARD_OCCLUDED;

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

    private final ActivityTaskSupervisor mTaskSupervisor;
    private WindowManagerService mWindowManager;
    private boolean mKeyguardShowing;
    private boolean mAodShowing;
    private boolean mKeyguardGoingAway;
    private boolean mDismissalRequested;
    private final SparseArray<KeyguardDisplayState> mDisplayStates = new SparseArray<>();
    private final ActivityTaskManagerService mService;
    private RootWindowContainer mRootWindowContainer;
    private final ActivityTaskManagerInternal.SleepTokenAcquirer mSleepTokenAcquirer;


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

    boolean isAodShowing() {
        return mAodShowing;
    }

    /**
     * @return true if either Keyguard or AOD are showing, not going away, and not being occluded
     *         on the given display, false otherwise.
     */
    boolean isKeyguardOrAodShowing(int displayId) {
        return (mKeyguardShowing || mAodShowing) && !mKeyguardGoingAway
                && !isDisplayOccluded(displayId);
    }

    /**
     * @return {@code true} for default display when AOD is showing. Otherwise, same as
     *         {@link #isKeyguardOrAodShowing(int)}
     * TODO(b/125198167): Replace isKeyguardOrAodShowing() by this logic.
     */
    boolean isKeyguardUnoccludedOrAodShowing(int displayId) {
        if (displayId == DEFAULT_DISPLAY && mAodShowing) {
            return true;
        }
        return isKeyguardOrAodShowing(displayId);
    }

    /**
     * @return true if Keyguard is showing, not going away, and not being occluded on the given
     *         display, false otherwise
     */
    boolean isKeyguardShowing(int displayId) {
        return mKeyguardShowing && !mKeyguardGoingAway && !isDisplayOccluded(displayId);
    }

    /**
     * @return true if Keyguard is either showing or occluded, but not going away
     */
    boolean isKeyguardLocked() {
        return mKeyguardShowing && !mKeyguardGoingAway;
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
    boolean isKeyguardGoingAway() {
        // Also check keyguard showing in case value is stale.
        return mKeyguardGoingAway && mKeyguardShowing;
    }

    /**
     * Update the Keyguard showing state.
     */
    void setKeyguardShown(boolean keyguardShowing, boolean aodShowing) {
        final boolean aodChanged = aodShowing != mAodShowing;
        // If keyguard is going away, but SystemUI aborted the transition, need to reset state.
        // Do not reset keyguardChanged status if this is aodChanged.
        final boolean keyguardChanged = (keyguardShowing != mKeyguardShowing)
                || (mKeyguardGoingAway && keyguardShowing && !aodChanged);
        if (!keyguardChanged && !aodChanged) {
            setWakeTransitionReady();
            return;
        }
        EventLogTags.writeWmSetKeyguardShown(
                keyguardShowing ? 1 : 0,
                aodShowing ? 1 : 0,
                mKeyguardGoingAway ? 1 : 0,
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
        if (((aodShowing ^ keyguardShowing) || (aodShowing && aodChanged && keyguardChanged))
                && !mKeyguardGoingAway && Display.isOnState(
                        mRootWindowContainer.getDefaultDisplay().getDisplayInfo().state)) {
            mWindowManager.mTaskSnapshotController.snapshotForSleeping(DEFAULT_DISPLAY);
        }

        mKeyguardShowing = keyguardShowing;
        mAodShowing = aodShowing;
        if (aodChanged) {
            // Ensure the new state takes effect.
            mWindowManager.mWindowPlacerLocked.performSurfacePlacement();
        }

        if (keyguardChanged) {
            // Irrelevant to AOD.
            dismissMultiWindowModeForTaskIfNeeded(null /* currentTaskControllsingOcclusion */,
                    false /* turningScreenOn */);
            mKeyguardGoingAway = false;
            if (keyguardShowing) {
                mDismissalRequested = false;
            }
        }

        // Update the sleep token first such that ensureActivitiesVisible has correct sleep token
        // state when evaluating visibilities.
        updateKeyguardSleepToken();
        mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        InputMethodManagerInternal.get().updateImeWindowStatus(false /* disableImeIcon */);
        setWakeTransitionReady();
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
    void keyguardGoingAway(int flags) {
        if (!mKeyguardShowing) {
            return;
        }
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "keyguardGoingAway");
        mService.deferWindowLayout();
        mKeyguardGoingAway = true;
        try {
            EventLogTags.writeWmSetKeyguardShown(
                    1 /* keyguardShowing */,
                    mAodShowing ? 1 : 0,
                    1 /* keyguardGoingAway */,
                    "keyguardGoingAway");
            mRootWindowContainer.getDefaultDisplay().requestTransitionAndLegacyPrepare(
                    TRANSIT_KEYGUARD_GOING_AWAY, convertTransitFlags(flags));
            updateKeyguardSleepToken();

            // Some stack visibility might change (e.g. docked stack)
            mRootWindowContainer.resumeFocusedTasksTopActivities();
            mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
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
        int result = 0;
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
        return r.containsDismissKeyguardWindow() && canDismissKeyguard() && !mAodShowing
                && (mDismissalRequested
                || (r.canShowWhenLocked()
                        && getDisplayState(r.getDisplayId()).mDismissingKeyguardActivity != r));
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
        } else if (isKeyguardLocked()) {
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
        boolean requestDismissKeyguard = false;
        for (int displayNdx = mRootWindowContainer.getChildCount() - 1;
             displayNdx >= 0; displayNdx--) {
            final DisplayContent display = mRootWindowContainer.getChildAt(displayNdx);
            if (display.isRemoving() || display.isRemoved()) continue;
            final KeyguardDisplayState state = getDisplayState(display.mDisplayId);
            state.updateVisibility(this, display);
            requestDismissKeyguard |= state.mRequestDismissKeyguard;
        }

        // Dismissing Keyguard happens globally using the information from all displays.
        if (requestDismissKeyguard) {
            handleDismissKeyguard();
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
        // updates keygaurd sleep token on that display.
        if (displayId != DEFAULT_DISPLAY) {
            updateKeyguardSleepToken(displayId);
            return;
        }

        mWindowManager.mPolicy.onKeyguardOccludedChangedLw(isDisplayOccluded(DEFAULT_DISPLAY));
        if (isKeyguardLocked()) {
            mService.deferWindowLayout();
            try {
                mRootWindowContainer.getDefaultDisplay()
                        .requestTransitionAndLegacyPrepare(
                                isDisplayOccluded(DEFAULT_DISPLAY)
                                        ? TRANSIT_KEYGUARD_OCCLUDE
                                        : TRANSIT_KEYGUARD_UNOCCLUDE, 0 /* flags */);
                // When the occluding activity also turns on the display, visibility of the activity
                // can be committed before KEYGUARD_OCCLUDE transition is handled.
                // Set mRequestForceTransition flag to make sure that the app transition animation
                // is applied for such case.
                // TODO(b/194243906): Fix this before enabling the remote keyguard animation.
                if (WindowManagerService.sEnableRemoteKeyguardGoingAwayAnimation
                        && topActivity != null) {
                    topActivity.mRequestForceTransition = true;
                }
                updateKeyguardSleepToken(DEFAULT_DISPLAY);
                mWindowManager.executeAppTransition();
            } finally {
                mService.continueWindowLayout();
            }
        }
    }

    /**
     * Called when somebody wants to dismiss the Keyguard via the flag.
     */
    private void handleDismissKeyguard() {
        // We only allow dismissing Keyguard via the flag when Keyguard is secure for legacy
        // reasons, because that's how apps used to dismiss Keyguard in the secure case. In the
        // insecure case, we actually show it on top of the lockscreen. See #canShowWhileOccluded.
        if (!mWindowManager.isKeyguardSecure(mService.getCurrentUserId())) {
            return;
        }

        mWindowManager.dismissKeyguard(null /* callback */, null /* message */);
        mDismissalRequested = true;

        // If we are about to unocclude the Keyguard, but we can dismiss it without security,
        // we immediately dismiss the Keyguard so the activity gets shown without a flicker.
        final DisplayContent dc = mRootWindowContainer.getDefaultDisplay();
        if (mKeyguardShowing && canDismissKeyguard()
                && dc.mAppTransition.containsTransitRequest(TRANSIT_KEYGUARD_UNOCCLUDE)) {
            mWindowManager.executeAppTransition();
        }
    }

    /**
     * Called when somebody wants to turn screen on.
     */
    private void handleTurnScreenOn(int displayId) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }

        mTaskSupervisor.wakeUp("handleTurnScreenOn");
        if (mKeyguardShowing && canDismissKeyguard()) {
            mWindowManager.dismissKeyguard(null /* callback */, null /* message */);
            mDismissalRequested = true;
        }
    }

    boolean isDisplayOccluded(int displayId) {
        return getDisplayState(displayId).mOccluded;
    }

    /**
     * @return true if Keyguard can be currently dismissed without entering credentials.
     */
    boolean canDismissKeyguard() {
        return mWindowManager.mPolicy.isKeyguardTrustedLw()
                || !mWindowManager.isKeyguardSecure(mService.getCurrentUserId());
    }

    private void dismissMultiWindowModeForTaskIfNeeded(
            @Nullable Task currentTaskControllingOcclusion, boolean turningScreenOn) {
        // If turningScreenOn is true, it means that the visibility state has changed from
        // currentTaskControllingOcclusion and we should update windowing mode.
        // TODO(b/113840485): Handle docked stack for individual display.
        if (!turningScreenOn && (!mKeyguardShowing || !isDisplayOccluded(DEFAULT_DISPLAY))) {
            return;
        }

        // Dismiss split screen
        // The lock screen is currently showing, but is occluded by a window that can
        // show on top of the lock screen. In this can we want to dismiss the docked
        // stack since it will be complicated/risky to try to put the activity on top
        // of the lock screen in the right fullscreen configuration.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        if (taskDisplayArea.isSplitScreenModeActivated()) {
            taskDisplayArea.onSplitScreenModeDismissed();
        }

        // Dismiss freeform windowing mode
        if (currentTaskControllingOcclusion == null) {
            return;
        }
        if (currentTaskControllingOcclusion.inFreeformWindowingMode()) {
            currentTaskControllingOcclusion.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        }
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
        } else if (!isKeyguardUnoccludedOrAodShowing(displayId)) {
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

    /** Represents Keyguard state per individual display. */
    private static class KeyguardDisplayState {
        private final int mDisplayId;
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
         * Updates {@link #mOccluded}, {@link #mTopTurnScreenOnActivity} and
         * {@link #mDismissingKeyguardActivity} if the top task could be visible.
         */
        void updateVisibility(KeyguardController controller, DisplayContent display) {
            final boolean lastOccluded = mOccluded;

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

                final boolean showWhenLocked = top.canShowWhenLocked();
                if (showWhenLocked) {
                    mTopOccludesActivity = top;
                }

                // Only the top activity may control occluded, as we can't occlude the Keyguard
                // if the top app doesn't want to occlude it.
                occludedByActivity = showWhenLocked || (mDismissingKeyguardActivity != null
                        && task.topRunningActivity() == mDismissingKeyguardActivity
                        && controller.canShowWhileOccluded(
                                true /* dismissKeyguard */, false /* showWhenLocked */));
                // FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD only apply for secondary display.
                if (mDisplayId != DEFAULT_DISPLAY) {
                    occludedByActivity |= display.canShowWithInsecureKeyguard()
                            && controller.canDismissKeyguard();
                }
            }

            final boolean dreaming = display.getDisplayPolicy().isShowingDreamLw() && (top != null
                    && top.getActivityType() == ACTIVITY_TYPE_DREAM);
            mOccluded = dreaming || occludedByActivity;
            mRequestDismissKeyguard = lastDismissKeyguardActivity != mDismissingKeyguardActivity
                    && !mOccluded
                    && mDismissingKeyguardActivity != null
                    && controller.mWindowManager.isKeyguardSecure(
                    controller.mService.getCurrentUserId());

            boolean occludingChange = false;
            boolean turningScreenOn = false;
            if (mTopTurnScreenOnActivity != lastTurnScreenOnActivity
                    && mTopTurnScreenOnActivity != null
                    && !mService.mWindowManager.mPowerManager.isInteractive()
                    && (mRequestDismissKeyguard || occludedByActivity
                        || controller.canDismissKeyguard())) {
                turningScreenOn = true;
                controller.handleTurnScreenOn(mDisplayId);
                mTopTurnScreenOnActivity.setCurrentLaunchCanTurnScreenOn(false);
            }

            if (lastOccluded != mOccluded) {
                occludingChange = true;
                controller.handleOccludedChanged(mDisplayId, mTopOccludesActivity);
            }

            if (occludingChange || turningScreenOn) {
                controller.dismissMultiWindowModeForTaskIfNeeded(task, turningScreenOn);
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
            sb.append("  Occluded=").append(mOccluded)
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
            proto.write(DISPLAY_ID, mDisplayId);
            proto.write(KEYGUARD_OCCLUDED, mOccluded);
            proto.end(token);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "KeyguardController:");
        pw.println(prefix + "  mKeyguardShowing=" + mKeyguardShowing);
        pw.println(prefix + "  mAodShowing=" + mAodShowing);
        pw.println(prefix + "  mKeyguardGoingAway=" + mKeyguardGoingAway);
        dumpDisplayStates(pw, prefix);
        pw.println(prefix + "  mDismissalRequested=" + mDismissalRequested);
        pw.println();
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(AOD_SHOWING, mAodShowing);
        proto.write(KEYGUARD_SHOWING, mKeyguardShowing);
        writeDisplayStatesToProto(proto, KEYGUARD_OCCLUDED_STATES);
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
