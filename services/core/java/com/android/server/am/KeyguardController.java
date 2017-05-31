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

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.view.WindowManagerPolicy.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicy.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
import static android.view.WindowManagerPolicy.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_GOING_AWAY;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_OCCLUDE;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_UNOCCLUDE;
import static com.android.server.wm.AppTransition.TRANSIT_NONE;
import static com.android.server.wm.AppTransition.TRANSIT_UNSET;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.server.wm.WindowManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;

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
    private boolean mKeyguardGoingAway;
    private boolean mOccluded;
    private boolean mDismissalRequested;
    private ActivityRecord mDismissingKeyguardActivity;
    private int mBeforeUnoccludeTransit;
    private int mVisibilityTransactionDepth;

    KeyguardController(ActivityManagerService service,
            ActivityStackSupervisor stackSupervisor) {
        mService = service;
        mStackSupervisor = stackSupervisor;
    }

    void setWindowManager(WindowManagerService windowManager) {
        mWindowManager = windowManager;
    }

    /**
     * @return true if Keyguard is showing, not going away, and not being occluded, false otherwise
     */
    boolean isKeyguardShowing() {
        return mKeyguardShowing && !mKeyguardGoingAway && !mOccluded;
    }

    /**
     * @return true if Keyguard is either showing or occluded, but not going away
     */
    boolean isKeyguardLocked() {
        return mKeyguardShowing && !mKeyguardGoingAway;
    }

    /**
     * Update the Keyguard showing state.
     */
    void setKeyguardShown(boolean showing) {
        if (showing == mKeyguardShowing) {
            return;
        }
        mKeyguardShowing = showing;
        dismissDockedStackIfNeeded();
        if (showing) {
            setKeyguardGoingAway(false);
            mDismissalRequested = false;
        }
        mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        mService.updateSleepIfNeededLocked();
    }

    /**
     * Called when Keyguard is going away.
     *
     * @param flags See {@link android.view.WindowManagerPolicy#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     */
    void keyguardGoingAway(int flags) {
        if (mKeyguardShowing) {
            mWindowManager.deferSurfaceLayout();
            try {
                setKeyguardGoingAway(true);
                mWindowManager.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY,
                        false /* alwaysKeepCurrent */, convertTransitFlags(flags),
                        false /* forceOverride */);
                mService.updateSleepIfNeededLocked();

                // Some stack visibility might change (e.g. docked stack)
                mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                mStackSupervisor.addStartingWindowsForVisibleActivities(true /* taskSwitch */);
                mWindowManager.executeAppTransition();
            } finally {
                mWindowManager.continueSurfaceLayout();
            }
        }
    }

    void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback) {
        final ActivityRecord activityRecord = ActivityRecord.forTokenLocked(token);
        if (activityRecord == null || !activityRecord.visibleIgnoringKeyguard) {
            failCallback(callback);
            return;
        }
        mWindowManager.dismissKeyguard(callback);
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
        return dismissKeyguard && canDismissKeyguard() &&
                (mDismissalRequested || r != mDismissingKeyguardActivity);
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
        final ArrayList<ActivityStack> stacks = mStackSupervisor.getStacksOnDefaultDisplay();
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = stacks.get(stackNdx);

            // Only the focused stack top activity may control occluded state
            if (mStackSupervisor.isFocusedStack(stack)) {

                // A dismissing activity occludes Keyguard in the insecure case for legacy reasons.
                final ActivityRecord topDismissing = stack.getTopDismissingKeyguardActivity();
                mOccluded = stack.topActivityOccludesKeyguard()
                        || (topDismissing != null
                                && stack.topRunningActivityLocked() == topDismissing
                                && canShowWhileOccluded(true /* dismissKeyguard */,
                                        false /* showWhenLocked */));
            }
            if (mDismissingKeyguardActivity == null
                    && stack.getTopDismissingKeyguardActivity() != null) {
                mDismissingKeyguardActivity = stack.getTopDismissingKeyguardActivity();
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
                mService.updateSleepIfNeededLocked();
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
            mWindowManager.dismissKeyguard(null /* callback */);
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
            mStackSupervisor.moveTasksToFullscreenStackLocked(DOCKED_STACK_ID,
                    mStackSupervisor.mFocusedStack.getStackId() == DOCKED_STACK_ID);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "KeyguardController:");
        pw.println(prefix + "  mKeyguardShowing=" + mKeyguardShowing);
        pw.println(prefix + "  mKeyguardGoingAway=" + mKeyguardGoingAway);
        pw.println(prefix + "  mOccluded=" + mOccluded);
        pw.println(prefix + "  mDismissingKeyguardActivity=" + mDismissingKeyguardActivity);
        pw.println(prefix + "  mDismissalRequested=" + mDismissalRequested);
        pw.println(prefix + "  mVisibilityTransactionDepth=" + mVisibilityTransactionDepth);
    }
}
