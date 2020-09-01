/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.REPORT_WINDOWS_CHANGE;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;

import android.os.Debug;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * Positions windows and their surfaces.
 *
 * It sets positions of windows by calculating their frames and then applies this by positioning
 * surfaces according to these frames. Z layer is still assigned withing WindowManagerService.
 */
class WindowSurfacePlacer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfacePlacer" : TAG_WM;
    private final WindowManagerService mService;

    private boolean mInLayout = false;

    /** Only do a maximum of 6 repeated layouts. After that quit */
    private int mLayoutRepeatCount;

    static final int SET_UPDATE_ROTATION                = 1 << 0;
    static final int SET_ORIENTATION_CHANGE_COMPLETE    = 1 << 2;
    static final int SET_WALLPAPER_ACTION_PENDING       = 1 << 3;

    private boolean mTraversalScheduled;
    private int mDeferDepth = 0;
    /** The number of layout requests when deferring. */
    private int mDeferredRequests;

    private class Traverser implements Runnable {
        @Override
        public void run() {
            synchronized (mService.mGlobalLock) {
                performSurfacePlacement();
            }
        }
    }

    private final Traverser mPerformSurfacePlacement = new Traverser();

    WindowSurfacePlacer(WindowManagerService service) {
        mService = service;
    }

    /**
     * Starts deferring layout passes. Useful when doing multiple changes but to optimize
     * performance, only one layout pass should be done. This can be called multiple times, and
     * layouting will be resumed once the last caller has called {@link #continueLayout}.
     */
    void deferLayout() {
        mDeferDepth++;
    }

    /**
     * Resumes layout passes after deferring them. If there is a deferred direct invocation of
     * {@link #performSurfacePlacement} ({@link #mDeferredRequests} > 0), when the defer is
     * done, it will continue to perform layout.
     *
     * @param hasChanges Something has changed. That means whether to call
     *                   {@link #performSurfacePlacement} when {@link #mDeferDepth} becomes zero.
     * @see #deferLayout
     */
    void continueLayout(boolean hasChanges) {
        mDeferDepth--;
        if (mDeferDepth > 0) {
            return;
        }

        if (hasChanges || mDeferredRequests > 0) {
            if (DEBUG) {
                Slog.i(TAG, "continueLayout hasChanges=" + hasChanges
                        + " deferredRequests=" + mDeferredRequests + " " + Debug.getCallers(2, 3));
            }
            performSurfacePlacement();
            mDeferredRequests = 0;
        } else if (DEBUG) {
            Slog.i(TAG, "Cancel continueLayout " + Debug.getCallers(2, 3));
        }
    }

    boolean isLayoutDeferred() {
        return mDeferDepth > 0;
    }

    void performSurfacePlacementIfScheduled() {
        if (mTraversalScheduled) {
            performSurfacePlacement();
        }
    }

    final void performSurfacePlacement() {
        performSurfacePlacement(false /* force */);
    }

    final void performSurfacePlacement(boolean force) {
        if (mDeferDepth > 0 && !force) {
            mDeferredRequests++;
            return;
        }
        int loopCount = 6;
        do {
            mTraversalScheduled = false;
            performSurfacePlacementLoop();
            mService.mAnimationHandler.removeCallbacks(mPerformSurfacePlacement);
            loopCount--;
        } while (mTraversalScheduled && loopCount > 0);
        mService.mRoot.mWallpaperActionPending = false;
    }

    private void performSurfacePlacementLoop() {
        if (mInLayout) {
            if (DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Slog.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout. Callers="
                    + Debug.getCallers(3));
            return;
        }

        // TODO(multi-display):
        final DisplayContent defaultDisplay = mService.getDefaultDisplayContentLocked();
        if (defaultDisplay.mWaitingForConfig) {
            // Our configuration has changed (most likely rotation), but we
            // don't yet have the complete configuration to report to
            // applications.  Don't do any window layout until we have it.
            return;
        }

        if (!mService.mDisplayReady) {
            // Not yet initialized, nothing to do.
            return;
        }

        mInLayout = true;

        if (!mService.mForceRemoves.isEmpty()) {
            // Wait a little bit for things to settle down, and off we go.
            while (!mService.mForceRemoves.isEmpty()) {
                final WindowState ws = mService.mForceRemoves.remove(0);
                Slog.i(TAG, "Force removing: " + ws);
                ws.removeImmediately();
            }
            Slog.w(TAG, "Due to memory failure, waiting a bit for next layout");
            Object tmp = new Object();
            synchronized (tmp) {
                try {
                    tmp.wait(250);
                } catch (InterruptedException e) {
                }
            }
        }

        try {
            mService.mRoot.performSurfacePlacement();

            mInLayout = false;

            if (mService.mRoot.isLayoutNeeded()) {
                if (++mLayoutRepeatCount < 6) {
                    requestTraversal();
                } else {
                    Slog.e(TAG, "Performed 6 layouts in a row. Skipping");
                    mLayoutRepeatCount = 0;
                }
            } else {
                mLayoutRepeatCount = 0;
            }

            if (mService.mWindowsChanged && !mService.mWindowChangeListeners.isEmpty()) {
                mService.mH.removeMessages(REPORT_WINDOWS_CHANGE);
                mService.mH.sendEmptyMessage(REPORT_WINDOWS_CHANGE);
            }
        } catch (RuntimeException e) {
            mInLayout = false;
            Slog.wtf(TAG, "Unhandled exception while laying out windows", e);
        }
    }

    void debugLayoutRepeats(final String msg, int pendingLayoutChanges) {
        if (mLayoutRepeatCount >= LAYOUT_REPEAT_THRESHOLD) {
            Slog.v(TAG, "Layouts looping: " + msg +
                    ", mPendingLayoutChanges = 0x" + Integer.toHexString(pendingLayoutChanges));
        }
    }

    boolean isInLayout() {
        return mInLayout;
    }

    void requestTraversal() {
        if (mTraversalScheduled) {
            return;
        }

        // Set as scheduled even the request will be deferred because mDeferredRequests is also
        // increased, then the end of deferring will perform the request.
        mTraversalScheduled = true;
        if (mDeferDepth > 0) {
            mDeferredRequests++;
            if (DEBUG) Slog.i(TAG, "Defer requestTraversal " + Debug.getCallers(3));
            return;
        }
        mService.mAnimationHandler.post(mPerformSurfacePlacement);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mTraversalScheduled=" + mTraversalScheduled);
        pw.println(prefix + "mHoldScreenWindow=" + mService.mRoot.mHoldScreenWindow);
        pw.println(prefix + "mObscuringWindow=" + mService.mRoot.mObscuringWindow);
    }
}
