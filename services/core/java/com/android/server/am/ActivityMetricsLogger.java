package com.android.server.am;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static com.android.server.am.ActivityStack.STACK_INVISIBLE;

import android.app.ActivityManager.StackId;
import android.content.Context;
import android.os.SystemClock;

import com.android.internal.logging.MetricsLogger;

/**
 * Handles logging into Tron.
 */
class ActivityMetricsLogger {
    // Window modes we are interested in logging. If we ever introduce a new type, we need to add
    // a value here and increase the {@link #TRON_WINDOW_STATE_VARZ_STRINGS} array.
    private static final int WINDOW_STATE_STANDARD = 0;
    private static final int WINDOW_STATE_SIDE_BY_SIDE = 1;
    private static final int WINDOW_STATE_FREEFORM = 2;
    private static final int WINDOW_STATE_INVALID = -1;

    // Preallocated strings we are sending to tron, so we don't have to allocate a new one every
    // time we log.
    private static final String[] TRON_WINDOW_STATE_VARZ_STRINGS = {
            "window_time_0", "window_time_1", "window_time_2"};

    private int mWindowState = WINDOW_STATE_STANDARD;
    private long mLastLogTimeSecs;
    private final ActivityStackSupervisor mSupervisor;
    private final Context mContext;

    ActivityMetricsLogger(ActivityStackSupervisor supervisor, Context context) {
        mLastLogTimeSecs = SystemClock.elapsedRealtime() / 1000;
        mSupervisor = supervisor;
        mContext = context;
    }

    void logWindowState() {
        final long now = SystemClock.elapsedRealtime() / 1000;
        if (mWindowState != WINDOW_STATE_INVALID) {
            // We log even if the window state hasn't changed, because the user might remain in
            // home/fullscreen move forever and we would like to track this kind of behavior
            // too.
            MetricsLogger.count(mContext, TRON_WINDOW_STATE_VARZ_STRINGS[mWindowState],
                    (int) (now - mLastLogTimeSecs));
        }
        mLastLogTimeSecs = now;

        ActivityStack stack = mSupervisor.getStack(DOCKED_STACK_ID);
        if (stack != null && stack.getStackVisibilityLocked() != STACK_INVISIBLE) {
            mWindowState = WINDOW_STATE_SIDE_BY_SIDE;
            return;
        }
        mWindowState = WINDOW_STATE_INVALID;
        stack = mSupervisor.getFocusedStack();
        if (stack.mStackId == PINNED_STACK_ID) {
            stack = mSupervisor.findStackBehind(stack);
        }
        if (stack.mStackId == HOME_STACK_ID
                || stack.mStackId == FULLSCREEN_WORKSPACE_STACK_ID) {
            mWindowState = WINDOW_STATE_STANDARD;
        } else if (stack.mStackId == DOCKED_STACK_ID) {
            throw new IllegalStateException("Docked stack shouldn't be the focused stack, "
                    + "because it reported not being visible.");
        } else if (stack.mStackId == FREEFORM_WORKSPACE_STACK_ID) {
            mWindowState = WINDOW_STATE_FREEFORM;
        } else if (StackId.isStaticStack(stack.mStackId)) {
            throw new IllegalStateException("Unknown stack=" + stack);
        }
    }
}
