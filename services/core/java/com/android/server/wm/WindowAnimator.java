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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE;

import android.content.Context;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.Choreographer;
import android.view.SurfaceControl;

import com.android.server.AnimationThread;
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
    private boolean mAnimating;
    private boolean mLastRootAnimating;

    final Choreographer.FrameCallback mAnimationFrameCallback;

    /** Time of current animation step. Reset on each iteration */
    long mCurrentTime;

    int mBulkUpdateParams = 0;
    Object mLastWindowFreezeSource;

    SparseArray<DisplayContentsAnimator> mDisplayContentsAnimators = new SparseArray<>(2);

    private boolean mInitialized = false;

    // When set to true the animator will go over all windows after an animation frame is posted and
    // check if some got replaced and can be removed.
    private boolean mRemoveReplacedWindows = false;

    private Choreographer mChoreographer;

    /**
     * Indicates whether we have an animation frame callback scheduled, which will happen at
     * vsync-app and then schedule the animation tick at the right time (vsync-sf).
     */
    private boolean mAnimationFrameCallbackScheduled;

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
        AnimationThread.getHandler().runWithScissors(
                () -> mChoreographer = Choreographer.getSfInstance(), 0 /* timeout */);

        mAnimationFrameCallback = frameTimeNs -> {
            synchronized (mService.mGlobalLock) {
                mAnimationFrameCallbackScheduled = false;
            }
            animate(frameTimeNs);
        };
    }

    void addDisplayLocked(final int displayId) {
        // Create the DisplayContentsAnimator object by retrieving it if the associated
        // {@link DisplayContent} exists.
        getDisplayContentsAnimatorLocked(displayId);
    }

    void removeDisplayLocked(final int displayId) {
        mDisplayContentsAnimators.delete(displayId);
    }

    void ready() {
        mInitialized = true;
    }

    /**
     * DO NOT HOLD THE WINDOW MANAGER LOCK WHILE CALLING THIS METHOD. Reason: the method closes
     * an animation transaction, that might be blocking until the next sf-vsync, so we want to make
     * sure other threads can make progress if this happens.
     */
    private void animate(long frameTimeNs) {

        synchronized (mService.mGlobalLock) {
            if (!mInitialized) {
                return;
            }

            // Schedule next frame already such that back-pressure happens continuously
            scheduleAnimation();
        }

        synchronized (mService.mGlobalLock) {
            mCurrentTime = frameTimeNs / TimeUtils.NANOS_PER_MS;
            mBulkUpdateParams = SET_ORIENTATION_CHANGE_COMPLETE;
            mAnimating = false;
            if (DEBUG_WINDOW_TRACE) {
                Slog.i(TAG, "!!! animate: entry time=" + mCurrentTime);
            }

            if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION animate");
            mService.openSurfaceTransaction();
            try {
                final AccessibilityController accessibilityController =
                        mService.mAccessibilityController;
                final int numDisplays = mDisplayContentsAnimators.size();
                for (int i = 0; i < numDisplays; i++) {
                    final int displayId = mDisplayContentsAnimators.keyAt(i);
                    final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);
                    // Update animations of all applications, including those
                    // associated with exiting/removed apps
                    dc.updateWindowsForAnimator();
                    dc.prepareSurfaces();
                }

                for (int i = 0; i < numDisplays; i++) {
                    final int displayId = mDisplayContentsAnimators.keyAt(i);
                    final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);

                    dc.checkAppWindowsReadyToShow();
                    orAnimating(dc.getDockedDividerController().animate(mCurrentTime));
                    if (accessibilityController != null) {
                        accessibilityController.drawMagnifiedRegionBorderIfNeededLocked(displayId);
                    }
                }

                if (!mAnimating) {
                    cancelAnimation();
                }

                if (mService.mWatermark != null) {
                    mService.mWatermark.drawIfNeeded();
                }

                SurfaceControl.mergeToGlobalTransaction(mTransaction);
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
            } finally {
                mService.closeSurfaceTransaction("WindowAnimator");
                if (SHOW_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION animate");
            }

            boolean hasPendingLayoutChanges = mService.mRoot.hasPendingLayoutChanges(this);
            boolean doRequest = false;
            if (mBulkUpdateParams != 0) {
                doRequest = mService.mRoot.copyAnimToLayoutParams();
            }

            if (hasPendingLayoutChanges || doRequest) {
                mService.mWindowPlacerLocked.requestTraversal();
            }

            final boolean rootAnimating = mService.mRoot.isSelfOrChildAnimating();
            if (rootAnimating && !mLastRootAnimating) {

                // Usually app transitions but quite a load onto the system already (with all the
                // things happening in app), so pause task snapshot persisting to not increase the
                // load.
                mService.mTaskSnapshotController.setPersisterPaused(true);
                Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "animating", 0);
            }
            if (!rootAnimating && mLastRootAnimating) {
                mService.mWindowPlacerLocked.requestTraversal();
                mService.mTaskSnapshotController.setPersisterPaused(false);
                Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER, "animating", 0);
            }

            mLastRootAnimating = rootAnimating;

            if (mRemoveReplacedWindows) {
                mService.mRoot.removeReplacedWindows();
                mRemoveReplacedWindows = false;
            }

            mService.destroyPreservedSurfaceLocked();

            executeAfterPrepareSurfacesRunnables();

            if (DEBUG_WINDOW_TRACE) {
                Slog.i(TAG, "!!! animate: exit mAnimating=" + mAnimating
                        + " mBulkUpdateParams=" + Integer.toHexString(mBulkUpdateParams)
                        + " hasPendingLayoutChanges=" + hasPendingLayoutChanges);
            }
        }
    }

    private static String bulkUpdateParamsToString(int bulkUpdateParams) {
        StringBuilder builder = new StringBuilder(128);
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_UPDATE_ROTATION) != 0) {
            builder.append(" UPDATE_ROTATION");
        }
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE) != 0) {
            builder.append(" ORIENTATION_CHANGE_COMPLETE");
        }
        return builder.toString();
    }

    public void dumpLocked(PrintWriter pw, String prefix, boolean dumpAll) {
        final String subPrefix = "  " + prefix;

        for (int i = 0; i < mDisplayContentsAnimators.size(); i++) {
            pw.print(prefix); pw.print("DisplayContentsAnimator #");
                    pw.print(mDisplayContentsAnimators.keyAt(i));
                    pw.println(":");
            final DisplayContent dc =
                    mService.mRoot.getDisplayContent(mDisplayContentsAnimators.keyAt(i));
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

    private DisplayContentsAnimator getDisplayContentsAnimatorLocked(int displayId) {
        if (displayId < 0) {
            return null;
        }

        DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);

        // It is possible that this underlying {@link DisplayContent} has been removed. In this
        // case, we do not want to create an animator associated with it as {link #animate} will
        // fail.
        if (displayAnimator == null && mService.mRoot.getDisplayContent(displayId) != null) {
            displayAnimator = new DisplayContentsAnimator();
            mDisplayContentsAnimators.put(displayId, displayAnimator);
        }
        return displayAnimator;
    }

    void requestRemovalOfReplacedWindows(WindowState win) {
        mRemoveReplacedWindows = true;
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

    private class DisplayContentsAnimator {
    }

    boolean isAnimating() {
        return mAnimating;
    }

    boolean isAnimationScheduled() {
        return mAnimationFrameCallbackScheduled;
    }

    Choreographer getChoreographer() {
        return mChoreographer;
    }

    void setAnimating(boolean animating) {
        mAnimating = animating;
    }

    void orAnimating(boolean animating) {
        mAnimating |= animating;
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
