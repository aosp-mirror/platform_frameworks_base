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

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_UNSET;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_SUBTLE_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;

import static com.android.server.am.KeyguardControllerProto.AOD_SHOWING;
import static com.android.server.am.KeyguardControllerProto.KEYGUARD_OCCLUDED_STATES;
import static com.android.server.am.KeyguardControllerProto.KEYGUARD_SHOWING;
import static com.android.server.am.KeyguardOccludedProto.DISPLAY_ID;
import static com.android.server.am.KeyguardOccludedProto.KEYGUARD_OCCLUDED;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.server.am.EventLogTags;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.ActivityTaskManagerInternal.SleepToken;

import java.io.PrintWriter;

/**
 * Controls Keyguard occluding, dismissing and transitions depending on what kind of activities are
 * currently visible.
 * <p>
 * Note that everything in this class should only be accessed with the AM lock being held.
 */
class KeyguardController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "KeyguardController" : TAG_ATM;

    private final ActivityStackSupervisor mStackSupervisor;
    private WindowManagerService mWindowManager;
    private boolean mKeyguardShowing;
    private boolean mAodShowing;
    private boolean mKeyguardGoingAway;
    private boolean mDismissalRequested;
    private int[] mSecondaryDisplayIdsShowing;
    private int mBeforeUnoccludeTransit;
    private int mVisibilityTransactionDepth;
    private final SparseArray<KeyguardDisplayState> mDisplayStates = new SparseArray<>();
    private final ActivityTaskManagerService mService;
    private RootActivityContainer mRootActivityContainer;

    KeyguardController(ActivityTaskManagerService service,
            ActivityStackSupervisor stackSupervisor) {
        mService = service;
        mStackSupervisor = stackSupervisor;
    }

    void setWindowManager(WindowManagerService windowManager) {
        mWindowManager = windowManager;
        mRootActivityContainer = mService.mRootActivityContainer;
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
        // If keyguard is going away, but SystemUI aborted the transition, need to reset state.
        final boolean keyguardChanged = keyguardShowing != mKeyguardShowing
                || mKeyguardGoingAway && keyguardShowing;
        final boolean aodChanged = aodShowing != mAodShowing;
        if (!keyguardChanged && !aodChanged) {
            return;
        }
        EventLog.writeEvent(EventLogTags.AM_SET_KEYGUARD_SHOWN,
                keyguardShowing ? 1 : 0,
                aodShowing ? 1 : 0,
                mKeyguardGoingAway ? 1 : 0,
                "setKeyguardShown");
        mKeyguardShowing = keyguardShowing;
        mAodShowing = aodShowing;
        mWindowManager.setAodShowing(aodShowing);

        if (keyguardChanged) {
            // Irrelevant to AOD.
            dismissDockedStackIfNeeded();
            setKeyguardGoingAway(false);
            if (keyguardShowing) {
                mDismissalRequested = false;
            }
        }
        // TODO(b/113840485): Check usage for non-default display
        mWindowManager.setKeyguardOrAodShowingOnDefaultDisplay(
                isKeyguardOrAodShowing(DEFAULT_DISPLAY));

        // Update the sleep token first such that ensureActivitiesVisible has correct sleep token
        // state when evaluating visibilities.
        updateKeyguardSleepToken();
        mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
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
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "keyguardGoingAway");
        mService.deferWindowLayout();
        try {
            setKeyguardGoingAway(true);
            EventLog.writeEvent(EventLogTags.AM_SET_KEYGUARD_SHOWN,
                    1 /* keyguardShowing */,
                    mAodShowing ? 1 : 0,
                    1 /* keyguardGoingAway */,
                    "keyguardGoingAway");
            mRootActivityContainer.getDefaultDisplay().mDisplayContent
                    .prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY,
                            false /* alwaysKeepCurrent */, convertTransitFlags(flags),
                            false /* forceOverride */);
            updateKeyguardSleepToken();

            // Some stack visibility might change (e.g. docked stack)
            mRootActivityContainer.resumeFocusedStacksTopActivities();
            mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
            mRootActivityContainer.addStartingWindowsForVisibleActivities(
                    true /* taskSwitch */);
            mWindowManager.executeAppTransition();
        } finally {
            Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "keyguardGoingAway: surfaceLayout");
            mService.continueWindowLayout();
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);

            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
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
            mStackSupervisor.wakeUp("dismissKeyguard");
        }

        mWindowManager.dismissKeyguard(callback, message);
    }

    private void setKeyguardGoingAway(boolean keyguardGoingAway) {
        mKeyguardGoingAway = keyguardGoingAway;
        mWindowManager.setKeyguardGoingAway(keyguardGoingAway);
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
     * Starts a batch of visibility updates.
     */
    void beginActivityVisibilityUpdate() {
        mVisibilityTransactionDepth++;
    }

    /**
     * Ends a batch of visibility updates. After all batches are done, this method makes sure to
     * update lockscreen occluded/dismiss state if needed.
     */
    void endActivityVisibilityUpdate() {
        mVisibilityTransactionDepth--;
        if (mVisibilityTransactionDepth == 0) {
            visibilitiesUpdated();
        }
    }

    /**
     * @return True if we may show an activity while Keyguard is showing because we are in the
     *         process of dismissing it anyways, false otherwise.
     */
    boolean canShowActivityWhileKeyguardShowing(ActivityRecord r, boolean dismissKeyguard) {

        // Allow to show it when we are about to dismiss Keyguard. This isn't allowed if r is
        // already the dismissing activity, in which case we don't allow it to repeatedly dismiss
        // Keyguard.
        return dismissKeyguard && canDismissKeyguard() && !mAodShowing
                && (mDismissalRequested
                || (r.canShowWhenLocked()
                        && getDisplay(r.getDisplayId()).mDismissingKeyguardActivity != r));
    }

    /**
     * @return True if we may show an activity while Keyguard is occluded, false otherwise.
     */
    boolean canShowWhileOccluded(boolean dismissKeyguard, boolean showWhenLocked) {
        return showWhenLocked || dismissKeyguard
                && !mWindowManager.isKeyguardSecure(mService.getCurrentUserId());
    }

    private void visibilitiesUpdated() {
        boolean requestDismissKeyguard = false;
        for (int displayNdx = mRootActivityContainer.getChildCount() - 1;
             displayNdx >= 0; displayNdx--) {
            final ActivityDisplay display = mRootActivityContainer.getChildAt(displayNdx);
            final KeyguardDisplayState state = getDisplay(display.mDisplayId);
            state.visibilitiesUpdated(this, display);
            requestDismissKeyguard |= state.mRequestDismissKeyguard;
        }

        // Dismissing Keyguard happens globally using the information from all displays.
        if (requestDismissKeyguard) {
            handleDismissKeyguard();
        }
    }

    /**
     * Called when occluded state changed.
     */
    private void handleOccludedChanged(int displayId) {
        // TODO(b/113840485): Handle app transition for individual display, and apply occluded
        // state change to secondary displays.
        // For now, only default display fully supports occluded change. Other displays only
        // updates keygaurd sleep token on that display.
        if (displayId != DEFAULT_DISPLAY) {
            updateKeyguardSleepToken(displayId);
            return;
        }

        mWindowManager.onKeyguardOccludedChanged(isDisplayOccluded(DEFAULT_DISPLAY));
        if (isKeyguardLocked()) {
            mService.deferWindowLayout();
            try {
                mRootActivityContainer.getDefaultDisplay().mDisplayContent
                        .prepareAppTransition(resolveOccludeTransit(),
                                false /* alwaysKeepCurrent */, 0 /* flags */,
                                true /* forceOverride */);
                updateKeyguardSleepToken(DEFAULT_DISPLAY);
                mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
                mWindowManager.executeAppTransition();
            } finally {
                mService.continueWindowLayout();
            }
        }
        dismissDockedStackIfNeeded();
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
        final DisplayContent dc =
                mRootActivityContainer.getDefaultDisplay().mDisplayContent;
        if (mKeyguardShowing && canDismissKeyguard()
                && dc.mAppTransition.getAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE) {
            dc.prepareAppTransition(mBeforeUnoccludeTransit, false /* alwaysKeepCurrent */,
                    0 /* flags */, true /* forceOverride */);
            mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
            mWindowManager.executeAppTransition();
        }
    }

    private boolean isDisplayOccluded(int displayId) {
        return getDisplay(displayId).mOccluded;
    }

    /**
     * @return true if Keyguard can be currently dismissed without entering credentials.
     */
    boolean canDismissKeyguard() {
        return mWindowManager.isKeyguardTrusted()
                || !mWindowManager.isKeyguardSecure(mService.getCurrentUserId());
    }

    private int resolveOccludeTransit() {
        final DisplayContent dc =
                mService.mRootActivityContainer.getDefaultDisplay().mDisplayContent;
        if (mBeforeUnoccludeTransit != TRANSIT_UNSET
                && dc.mAppTransition.getAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE
                // TODO(b/113840485): Handle app transition for individual display.
                && isDisplayOccluded(DEFAULT_DISPLAY)) {

            // Reuse old transit in case we are occluding Keyguard again, meaning that we never
            // actually occclude/unocclude Keyguard, but just run a normal transition.
            return mBeforeUnoccludeTransit;
            // TODO(b/113840485): Handle app transition for individual display.
        } else if (!isDisplayOccluded(DEFAULT_DISPLAY)) {

            // Save transit in case we dismiss/occlude Keyguard shortly after.
            mBeforeUnoccludeTransit = dc.mAppTransition.getAppTransition();
            return TRANSIT_KEYGUARD_UNOCCLUDE;
        } else {
            return TRANSIT_KEYGUARD_OCCLUDE;
        }
    }

    private void dismissDockedStackIfNeeded() {
        // TODO(b/113840485): Handle docked stack for individual display.
        if (mKeyguardShowing && isDisplayOccluded(DEFAULT_DISPLAY)) {
            // The lock screen is currently showing, but is occluded by a window that can
            // show on top of the lock screen. In this can we want to dismiss the docked
            // stack since it will be complicated/risky to try to put the activity on top
            // of the lock screen in the right fullscreen configuration.
            final ActivityStack stack =
                    mRootActivityContainer.getDefaultDisplay().getSplitScreenPrimaryStack();
            if (stack == null) {
                return;
            }
            mStackSupervisor.moveTasksToFullscreenStackLocked(stack,
                    stack.isFocusedStackOnDisplay());
        }
    }

    private void updateKeyguardSleepToken() {
        for (int displayNdx = mRootActivityContainer.getChildCount() - 1;
             displayNdx >= 0; displayNdx--) {
            final ActivityDisplay display = mRootActivityContainer.getChildAt(displayNdx);
            updateKeyguardSleepToken(display.mDisplayId);
        }
    }

    private void updateKeyguardSleepToken(int displayId) {
        final KeyguardDisplayState state = getDisplay(displayId);
        if (isKeyguardUnoccludedOrAodShowing(displayId) && state.mSleepToken == null) {
            state.acquiredSleepToken();
        } else if (!isKeyguardUnoccludedOrAodShowing(displayId) && state.mSleepToken != null) {
            state.releaseSleepToken();
        }
    }

    private KeyguardDisplayState getDisplay(int displayId) {
        KeyguardDisplayState state = mDisplayStates.get(displayId);
        if (state == null) {
            state = new KeyguardDisplayState(mService, displayId);
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
        private ActivityRecord mDismissingKeyguardActivity;
        private boolean mRequestDismissKeyguard;
        private final ActivityTaskManagerService mService;
        private SleepToken mSleepToken;

        KeyguardDisplayState(ActivityTaskManagerService service, int displayId) {
            mService = service;
            mDisplayId = displayId;
        }

        void onRemoved() {
            mDismissingKeyguardActivity = null;
            releaseSleepToken();
        }

        void acquiredSleepToken() {
            if (mSleepToken == null) {
                mSleepToken = mService.acquireSleepToken("keyguard", mDisplayId);
            }
        }

        void releaseSleepToken() {
            if (mSleepToken != null) {
                mSleepToken.release();
                mSleepToken = null;
            }
        }

        void visibilitiesUpdated(KeyguardController controller, ActivityDisplay display) {
            final boolean lastOccluded = mOccluded;
            final ActivityRecord lastDismissActivity = mDismissingKeyguardActivity;
            mRequestDismissKeyguard = false;
            mOccluded = false;
            mDismissingKeyguardActivity = null;

            final ActivityStack stack = getStackForControllingOccluding(display);
            if (stack != null) {
                final ActivityRecord topDismissing = stack.getTopDismissingKeyguardActivity();
                mOccluded = stack.topActivityOccludesKeyguard() || (topDismissing != null
                        && stack.topRunningActivityLocked() == topDismissing
                        && controller.canShowWhileOccluded(
                                true /* dismissKeyguard */,
                                false /* showWhenLocked */));
                if (stack.getTopDismissingKeyguardActivity() != null) {
                    mDismissingKeyguardActivity = stack.getTopDismissingKeyguardActivity();
                }
                // FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD only apply for secondary display.
                if (mDisplayId != DEFAULT_DISPLAY) {
                    mOccluded |= stack.canShowWithInsecureKeyguard()
                            && controller.canDismissKeyguard();
                }
            }
            // TODO(b/123372519): isShowingDream can only works on default display.
            if (mDisplayId == DEFAULT_DISPLAY) {
                mOccluded |= controller.mWindowManager.isShowingDream();
            }

            if (lastOccluded != mOccluded) {
                controller.handleOccludedChanged(mDisplayId);
            }
            if (lastDismissActivity != mDismissingKeyguardActivity && !mOccluded
                    && mDismissingKeyguardActivity != null
                    && controller.mWindowManager.isKeyguardSecure(
                            controller.mService.getCurrentUserId())) {
                mRequestDismissKeyguard = true;
            }
        }

        /**
         * Gets the stack used to check the occluded state.
         * <p>
         * Only the top non-pinned activity of the focusable stack on each display can control its
         * occlusion state.
         */
        private ActivityStack getStackForControllingOccluding(ActivityDisplay display) {
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                if (stack != null && stack.isFocusableAndVisible()
                        && !stack.inPinnedWindowingMode()) {
                    return stack;
                }
            }
            return null;
        }

        void dumpStatus(PrintWriter pw, String prefix) {
            final StringBuilder sb = new StringBuilder();
            sb.append(prefix);
            sb.append("  Occluded=").append(mOccluded)
                    .append(" DismissingKeyguardActivity=")
                    .append(mDismissingKeyguardActivity)
                    .append(" at display=")
                    .append(mDisplayId);
            pw.println(sb.toString());
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
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
        pw.println(prefix + "  mVisibilityTransactionDepth=" + mVisibilityTransactionDepth);
    }

    void writeToProto(ProtoOutputStream proto, long fieldId) {
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
            mDisplayStates.valueAt(i).writeToProto(proto, fieldId);
        }
    }
}
