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

package com.android.server.am;

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.am.KeyguardControllerProto.KEYGUARD_OCCLUDED;
import static com.android.server.am.KeyguardControllerProto.KEYGUARD_SHOWING;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_UNSET;

import android.app.ActivityManagerInternal.SleepToken;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.WindowManagerService;

import java.io.PrintWriter;

/**
 * Controls Keyguard occluding, dismissing and transitions depending on what kind of activities are
 * currently visible.
 * <p>
 * Note that everything in this class should only be accessed with the AM lock being held.
 */
class KeyguardController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "KeyguardController" : TAG_AM;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private WindowManagerService mWindowManager;
    private boolean mKeyguardShowing;
    private boolean mAodShowing;
    private boolean mKeyguardGoingAway;
    private boolean mOccluded;
    private boolean mDismissalRequested;
    private ActivityRecord mDismissingKeyguardActivity;
    private int mBeforeUnoccludeTransit;
    private int mVisibilityTransactionDepth;
    private SleepToken mSleepToken;
    private int mSecondaryDisplayShowing = INVALID_DISPLAY;

    KeyguardController(ActivityManagerService service,
            ActivityStackSupervisor stackSupervisor) {
        mService = service;
        mStackSupervisor = stackSupervisor;
    }

    void setWindowManager(WindowManagerService windowManager) {
        mWindowManager = windowManager;
    }

    /**
     * @return true if either Keyguard or AOD are showing, not going away, and not being occluded
     *         on the given display, false otherwise
     */
    boolean isKeyguardOrAodShowing(int displayId) {
        return (mKeyguardShowing || mAodShowing) && !mKeyguardGoingAway &&
                (displayId == DEFAULT_DISPLAY ? !mOccluded : displayId == mSecondaryDisplayShowing);
    }

    /**
     * @return true if Keyguard is showing, not going away, and not being occluded on the given
     *         display, false otherwise
     */
    boolean isKeyguardShowing(int displayId) {
        return mKeyguardShowing && !mKeyguardGoingAway &&
                (displayId == DEFAULT_DISPLAY ? !mOccluded : displayId == mSecondaryDisplayShowing);
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
    void setKeyguardShown(boolean keyguardShowing, boolean aodShowing,
            int secondaryDisplayShowing) {
        boolean showingChanged = keyguardShowing != mKeyguardShowing || aodShowing != mAodShowing;
        // If keyguard is going away, but SystemUI aborted the transition, need to reset state.
        showingChanged |= mKeyguardGoingAway && keyguardShowing;
        if (!showingChanged && secondaryDisplayShowing == mSecondaryDisplayShowing) {
            return;
        }
        mKeyguardShowing = keyguardShowing;
        mAodShowing = aodShowing;
        mSecondaryDisplayShowing = secondaryDisplayShowing;
        mWindowManager.setAodShowing(aodShowing);
        if (showingChanged) {
            dismissDockedStackIfNeeded();
            setKeyguardGoingAway(false);
            mWindowManager.setKeyguardOrAodShowingOnDefaultDisplay(
                    isKeyguardOrAodShowing(DEFAULT_DISPLAY));
            if (keyguardShowing) {
                mDismissalRequested = false;
            }
        }
        mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        updateKeyguardSleepToken();
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
        mWindowManager.deferSurfaceLayout();
        try {
            setKeyguardGoingAway(true);
            mWindowManager.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY,
                    false /* alwaysKeepCurrent */, convertTransitFlags(flags),
                    false /* forceOverride */);
            updateKeyguardSleepToken();

            // Some stack visibility might change (e.g. docked stack)
            mStackSupervisor.resumeFocusedStackTopActivityLocked();
            mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
            mStackSupervisor.addStartingWindowsForVisibleActivities(true /* taskSwitch */);
            mWindowManager.executeAppTransition();
        } finally {
            Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "keyguardGoingAway: surfaceLayout");
            mWindowManager.continueSurfaceLayout();
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
                && (mDismissalRequested || r != mDismissingKeyguardActivity);
    }

    /**
     * @return True if we may show an activity while Keyguard is occluded, false otherwise.
     */
    boolean canShowWhileOccluded(boolean dismissKeyguard, boolean showWhenLocked) {
        return showWhenLocked || dismissKeyguard && !mWindowManager.isKeyguardSecure();
    }

    private void visibilitiesUpdated() {
        final boolean lastOccluded = mOccluded;
        final ActivityRecord lastDismissingKeyguardActivity = mDismissingKeyguardActivity;
        mOccluded = false;
        mDismissingKeyguardActivity = null;

        for (int displayNdx = mStackSupervisor.getChildCount() - 1; displayNdx >= 0; displayNdx--) {
            final ActivityDisplay display = mStackSupervisor.getChildAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);

                // Only the top activity of the focused stack on the default display may control
                // occluded state.
                if (display.mDisplayId == DEFAULT_DISPLAY
                        && mStackSupervisor.isFocusedStack(stack)) {

                    // A dismissing activity occludes Keyguard in the insecure case for legacy
                    // reasons.
                    final ActivityRecord topDismissing = stack.getTopDismissingKeyguardActivity();
                    mOccluded =
                            stack.topActivityOccludesKeyguard()
                                    || (topDismissing != null
                                            && stack.topRunningActivityLocked() == topDismissing
                                            && canShowWhileOccluded(
                                                    true /* dismissKeyguard */,
                                                    false /* showWhenLocked */));
                }

                if (mDismissingKeyguardActivity == null
                        && stack.getTopDismissingKeyguardActivity() != null) {
                    mDismissingKeyguardActivity = stack.getTopDismissingKeyguardActivity();
                }
            }
        }
        mOccluded |= mWindowManager.isShowingDream();
        if (mOccluded != lastOccluded) {
            handleOccludedChanged();
        }
        if (mDismissingKeyguardActivity != lastDismissingKeyguardActivity) {
            handleDismissKeyguard();
        }
    }

    /**
     * Called when occluded state changed.
     */
    private void handleOccludedChanged() {
        mWindowManager.onKeyguardOccludedChanged(mOccluded);
        if (isKeyguardLocked()) {
            mWindowManager.deferSurfaceLayout();
            try {
                mWindowManager.prepareAppTransition(resolveOccludeTransit(),
                        false /* alwaysKeepCurrent */, 0 /* flags */, true /* forceOverride */);
                updateKeyguardSleepToken();
                mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                mWindowManager.executeAppTransition();
            } finally {
                mWindowManager.continueSurfaceLayout();
            }
        }
        dismissDockedStackIfNeeded();
    }

    /**
     * Called when somebody might want to dismiss the Keyguard.
     */
    private void handleDismissKeyguard() {
        // We only allow dismissing Keyguard via the flag when Keyguard is secure for legacy
        // reasons, because that's how apps used to dismiss Keyguard in the secure case. In the
        // insecure case, we actually show it on top of the lockscreen. See #canShowWhileOccluded.
        if (!mOccluded && mDismissingKeyguardActivity != null
                && mWindowManager.isKeyguardSecure()) {
            mWindowManager.dismissKeyguard(null /* callback */, null /* message */);
            mDismissalRequested = true;

            // If we are about to unocclude the Keyguard, but we can dismiss it without security,
            // we immediately dismiss the Keyguard so the activity gets shown without a flicker.
            if (mKeyguardShowing && canDismissKeyguard()
                    && mWindowManager.getPendingAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE) {
                mWindowManager.prepareAppTransition(mBeforeUnoccludeTransit,
                        false /* alwaysKeepCurrent */, 0 /* flags */, true /* forceOverride */);
                mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                mWindowManager.executeAppTransition();
            }
        }
    }

    /**
     * @return true if Keyguard can be currently dismissed without entering credentials.
     */
    boolean canDismissKeyguard() {
        return mWindowManager.isKeyguardTrusted() || !mWindowManager.isKeyguardSecure();
    }

    private int resolveOccludeTransit() {
        if (mBeforeUnoccludeTransit != TRANSIT_UNSET
                && mWindowManager.getPendingAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE
                && mOccluded) {

            // Reuse old transit in case we are occluding Keyguard again, meaning that we never
            // actually occclude/unocclude Keyguard, but just run a normal transition.
            return mBeforeUnoccludeTransit;
        } else if (!mOccluded) {

            // Save transit in case we dismiss/occlude Keyguard shortly after.
            mBeforeUnoccludeTransit = mWindowManager.getPendingAppTransition();
            return TRANSIT_KEYGUARD_UNOCCLUDE;
        } else {
            return TRANSIT_KEYGUARD_OCCLUDE;
        }
    }

    private void dismissDockedStackIfNeeded() {
        if (mKeyguardShowing && mOccluded) {
            // The lock screen is currently showing, but is occluded by a window that can
            // show on top of the lock screen. In this can we want to dismiss the docked
            // stack since it will be complicated/risky to try to put the activity on top
            // of the lock screen in the right fullscreen configuration.
            final ActivityStack stack = mStackSupervisor.getDefaultDisplay().getSplitScreenPrimaryStack();
            if (stack == null) {
                return;
            }
            mStackSupervisor.moveTasksToFullscreenStackLocked(stack,
                    mStackSupervisor.mFocusedStack == stack);
        }
    }

    private void updateKeyguardSleepToken() {
        if (mSleepToken == null && isKeyguardOrAodShowing(DEFAULT_DISPLAY)) {
            mSleepToken = mService.acquireSleepToken("Keyguard", DEFAULT_DISPLAY);
        } else if (mSleepToken != null && !isKeyguardOrAodShowing(DEFAULT_DISPLAY)) {
            mSleepToken.release();
            mSleepToken = null;
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "KeyguardController:");
        pw.println(prefix + "  mKeyguardShowing=" + mKeyguardShowing);
        pw.println(prefix + "  mAodShowing=" + mAodShowing);
        pw.println(prefix + "  mKeyguardGoingAway=" + mKeyguardGoingAway);
        pw.println(prefix + "  mOccluded=" + mOccluded);
        pw.println(prefix + "  mDismissingKeyguardActivity=" + mDismissingKeyguardActivity);
        pw.println(prefix + "  mDismissalRequested=" + mDismissalRequested);
        pw.println(prefix + "  mVisibilityTransactionDepth=" + mVisibilityTransactionDepth);
    }

    void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(KEYGUARD_SHOWING, mKeyguardShowing);
        proto.write(KEYGUARD_OCCLUDED, mOccluded);
        proto.end(token);
    }
}
