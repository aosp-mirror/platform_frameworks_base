/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_ALL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_SCREEN_ROTATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.Context;
import android.os.Trace;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Choreographer;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Singleton class that carries out the animations and Surface operations in a separate task
 * on behalf of WindowManagerService.
 */
public class WindowAnimator {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowAnimator" : TAG_WM;

    final WindowManagerService mService;
    final Context mContext;
    final WindowManagerPolicy mPolicy;

    /** Is any window animating? */
    private boolean mLastRootAnimating;

    /** True if we are running any animations that require expensive composition. */
    private boolean mRunningExpensiveAnimations;

    final Choreographer.FrameCallback mAnimationFrameCallback;

    /** Time of current animation step. Reset on each iteration */
    long mCurrentTime;

    int mBulkUpdateParams = 0;
    Object mLastWindowFreezeSource;

    private boolean mInitialized = false;

    private Choreographer mChoreographer;

    /**
     * Indicates whether we have an animation frame callback scheduled, which will happen at
     * vsync-app and then schedule the animation tick at the right time (vsync-sf).
     */
    private boolean mAnimationFrameCallbackScheduled;
    boolean mNotifyWhenNoAnimation = false;

    /**
     * A list of runnable that need to be run after {@link WindowContainer#prepareSurfaces} is
     * executed and the corresponding transaction is closed and applied.
     */
    private final ArrayList<Runnable> mAfterPrepareSurfacesRunnables = new ArrayList<>();
    private boolean mInExecuteAfterPrepareSurfacesRunnables;

    private final SurfaceControl.Transaction mTransaction;

    WindowAnimator(final WindowManagerService service) {
        mService = service;
        mContext = service.mContext;
        mPolicy = service.mPolicy;
        mTransaction = service.mTransactionFactory.get();
        service.mAnimationHandler.runWithScissors(
                () -> mChoreographer = Choreographer.getSfInstance(), 0 /* timeout */);

        mAnimationFrameCallback = frameTimeNs -> {
            synchronized (mService.mGlobalLock) {
                mAnimationFrameCallbackScheduled = false;
                animate(frameTimeNs);
                if (mNotifyWhenNoAnimation && !mLastRootAnimating) {
                    mService.mGlobalLock.notifyAll();
                }
            }
        };
    }

    void ready() {
        mInitialized = true;
    }

    private void animate(long frameTimeNs) {
        if (!mInitialized) {
            return;
        }

        // Schedule next frame already such that back-pressure happens continuously.
        scheduleAnimation();

        final RootWindowContainer root = mService.mRoot;
        final boolean useShellTransition = root.mTransitionController.isShellTransitionsEnabled();
        final int animationFlags = useShellTransition ? CHILDREN : (TRANSITION | CHILDREN);
        boolean rootAnimating = false;
        mCurrentTime = frameTimeNs / TimeUtils.NANOS_PER_MS;
        mBulkUpdateParams = 0;
        root.mOrientationChangeComplete = true;
        if (DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: entry time=" + mCurrentTime);
        }

        ProtoLog.i(WM_SHOW_TRANSACTIONS, ">>> OPEN TRANSACTION animate");
        try {
            // Remove all deferred displays, tasks, and activities.
            root.handleCompleteDeferredRemoval();

            final AccessibilityController accessibilityController =
                    mService.mAccessibilityController;
            final int numDisplays = root.getChildCount();
            for (int i = 0; i < numDisplays; i++) {
                final DisplayContent dc = root.getChildAt(i);
                // Update animations of all applications, including those associated with
                // exiting/removed apps.
                dc.updateWindowsForAnimator();
                dc.prepareSurfaces();
            }

            for (int i = 0; i < numDisplays; i++) {
                final DisplayContent dc = root.getChildAt(i);

                if (!useShellTransition) {
                    dc.checkAppWindowsReadyToShow();
                }
                if (accessibilityController.hasCallbacks()) {
                    accessibilityController.drawMagnifiedRegionBorderIfNeeded(dc.mDisplayId);
                }

                if (dc.isAnimating(animationFlags, ANIMATION_TYPE_ALL)) {
                    rootAnimating = true;
                    if (!dc.mLastContainsRunningSurfaceAnimator) {
                        dc.mLastContainsRunningSurfaceAnimator = true;
                        dc.enableHighFrameRate(true);
                    }
                } else if (dc.mLastContainsRunningSurfaceAnimator) {
                    dc.mLastContainsRunningSurfaceAnimator = false;
                    dc.enableHighFrameRate(false);
                }
                mTransaction.merge(dc.getPendingTransaction());
            }

            cancelAnimation();

            if (mService.mWatermark != null) {
                mService.mWatermark.drawIfNeeded();
            }

        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        }

        final boolean hasPendingLayoutChanges = root.hasPendingLayoutChanges(this);
        final boolean doRequest = (mBulkUpdateParams != 0 || root.mOrientationChangeComplete)
                && root.copyAnimToLayoutParams();
        if (hasPendingLayoutChanges || doRequest) {
            mService.mWindowPlacerLocked.requestTraversal();
        }

        if (rootAnimating && !mLastRootAnimating) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "animating", 0);
        }
        if (!rootAnimating && mLastRootAnimating) {
            mService.mWindowPlacerLocked.requestTraversal();
            Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER, "animating", 0);
        }
        mLastRootAnimating = rootAnimating;

        // APP_TRANSITION, SCREEN_ROTATION, TYPE_RECENTS are handled by shell transition.
        if (!useShellTransition) {
            updateRunningExpensiveAnimationsLegacy();
        }

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "applyTransaction");
        mTransaction.apply();
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        mService.mWindowTracing.logState("WindowAnimator");
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "<<< CLOSE TRANSACTION animate");

        mService.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        executeAfterPrepareSurfacesRunnables();

        if (DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: exit"
                    + " mBulkUpdateParams=" + Integer.toHexString(mBulkUpdateParams)
                    + " hasPendingLayoutChanges=" + hasPendingLayoutChanges);
        }
    }

    private void updateRunningExpensiveAnimationsLegacy() {
        final boolean runningExpensiveAnimations =
                mService.mRoot.isAnimating(TRANSITION | CHILDREN /* flags */,
                        ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_SCREEN_ROTATION
                                | ANIMATION_TYPE_RECENTS /* typesToCheck */);
        if (runningExpensiveAnimations && !mRunningExpensiveAnimations) {
            mService.mSnapshotController.setPause(true);
            mTransaction.setEarlyWakeupStart();
        } else if (!runningExpensiveAnimations && mRunningExpensiveAnimations) {
            mService.mSnapshotController.setPause(false);
            mTransaction.setEarlyWakeupEnd();
        }
        mRunningExpensiveAnimations = runningExpensiveAnimations;
    }

    private static String bulkUpdateParamsToString(int bulkUpdateParams) {
        StringBuilder builder = new StringBuilder(128);
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_UPDATE_ROTATION) != 0) {
            builder.append(" UPDATE_ROTATION");
        }
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_WALLPAPER_ACTION_PENDING) != 0) {
            builder.append(" SET_WALLPAPER_ACTION_PENDING");
        }
        return builder.toString();
    }

    public void dumpLocked(PrintWriter pw, String prefix, boolean dumpAll) {
        final String subPrefix = "  " + prefix;

        for (int i = 0; i < mService.mRoot.getChildCount(); i++) {
            final DisplayContent dc = mService.mRoot.getChildAt(i);
            pw.print(prefix); pw.print(dc); pw.println(":");
            dc.dumpWindowAnimators(pw, subPrefix);
            pw.println();
        }

        pw.println();

        if (dumpAll) {
            pw.print(prefix); pw.print("mCurrentTime=");
                    pw.println(TimeUtils.formatUptime(mCurrentTime));
        }
        if (mBulkUpdateParams != 0) {
            pw.print(prefix); pw.print("mBulkUpdateParams=0x");
                    pw.print(Integer.toHexString(mBulkUpdateParams));
                    pw.println(bulkUpdateParamsToString(mBulkUpdateParams));
        }
    }

    void scheduleAnimation() {
        if (!mAnimationFrameCallbackScheduled) {
            mAnimationFrameCallbackScheduled = true;
            mChoreographer.postFrameCallback(mAnimationFrameCallback);
        }
    }

    private void cancelAnimation() {
        if (mAnimationFrameCallbackScheduled) {
            mAnimationFrameCallbackScheduled = false;
            mChoreographer.removeFrameCallback(mAnimationFrameCallback);
        }
    }

    boolean isAnimationScheduled() {
        return mAnimationFrameCallbackScheduled;
    }

    Choreographer getChoreographer() {
        return mChoreographer;
    }

    /**
     * Adds a runnable to be executed after {@link WindowContainer#prepareSurfaces} is called and
     * the corresponding transaction is closed and applied.
     */
    void addAfterPrepareSurfacesRunnable(Runnable r) {
        // If runnables are already being handled in executeAfterPrepareSurfacesRunnable, then just
        // immediately execute the runnable passed in.
        if (mInExecuteAfterPrepareSurfacesRunnables) {
            r.run();
            return;
        }

        mAfterPrepareSurfacesRunnables.add(r);
        scheduleAnimation();
    }

    void executeAfterPrepareSurfacesRunnables() {

        // Don't even think about to start recursing!
        if (mInExecuteAfterPrepareSurfacesRunnables) {
            return;
        }
        mInExecuteAfterPrepareSurfacesRunnables = true;

        // Traverse in order they were added.
        final int size = mAfterPrepareSurfacesRunnables.size();
        for (int i = 0; i < size; i++) {
            mAfterPrepareSurfacesRunnables.get(i).run();
        }
        mAfterPrepareSurfacesRunnables.clear();
        mInExecuteAfterPrepareSurfacesRunnables = false;
    }
}
