/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.view.Display.DEFAULT_DISPLAY;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.TaskOrganizer;

class SplitScreenTaskOrganizer extends TaskOrganizer {
    private static final String TAG = "SplitScreenTaskOrg";
    private static final boolean DEBUG = Divider.DEBUG;

    RunningTaskInfo mPrimary;
    RunningTaskInfo mSecondary;
    SurfaceControl mPrimarySurface;
    SurfaceControl mSecondarySurface;
    SurfaceControl mPrimaryDim;
    SurfaceControl mSecondaryDim;
    Rect mHomeBounds = new Rect();
    final Divider mDivider;
    private boolean mSplitScreenSupported = false;

    final SurfaceSession mSurfaceSession = new SurfaceSession();

    SplitScreenTaskOrganizer(Divider divider) {
        mDivider = divider;
    }

    void init() throws RemoteException {
        registerOrganizer(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        registerOrganizer(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        synchronized (this) {
            try {
                mPrimary = TaskOrganizer.createRootTask(Display.DEFAULT_DISPLAY,
                        WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
                mSecondary = TaskOrganizer.createRootTask(Display.DEFAULT_DISPLAY,
                        WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
            } catch (Exception e) {
                // teardown to prevent callbacks
                unregisterOrganizer();
                throw e;
            }
        }
    }

    boolean isSplitScreenSupported() {
        return mSplitScreenSupported;
    }

    SurfaceControl.Transaction getTransaction() {
        return mDivider.mTransactionPool.acquire();
    }

    void releaseTransaction(SurfaceControl.Transaction t) {
        mDivider.mTransactionPool.release(t);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        synchronized (this) {
            if (mPrimary == null || mSecondary == null) {
                Log.w(TAG, "Received onTaskAppeared before creating root tasks " + taskInfo);
                return;
            }

            if (taskInfo.token.equals(mPrimary.token)) {
                mPrimarySurface = leash;
            } else if (taskInfo.token.equals(mSecondary.token)) {
                mSecondarySurface = leash;
            }

            if (!mSplitScreenSupported && mPrimarySurface != null && mSecondarySurface != null) {
                mSplitScreenSupported = true;

                // Initialize dim surfaces:
                mPrimaryDim = new SurfaceControl.Builder(mSurfaceSession)
                        .setParent(mPrimarySurface).setColorLayer()
                        .setName("Primary Divider Dim")
                        .setCallsite("SplitScreenTaskOrganizer.onTaskAppeared")
                        .build();
                mSecondaryDim = new SurfaceControl.Builder(mSurfaceSession)
                        .setParent(mSecondarySurface).setColorLayer()
                        .setName("Secondary Divider Dim")
                        .setCallsite("SplitScreenTaskOrganizer.onTaskAppeared")
                        .build();
                SurfaceControl.Transaction t = getTransaction();
                t.setLayer(mPrimaryDim, Integer.MAX_VALUE);
                t.setColor(mPrimaryDim, new float[]{0f, 0f, 0f});
                t.setLayer(mSecondaryDim, Integer.MAX_VALUE);
                t.setColor(mSecondaryDim, new float[]{0f, 0f, 0f});
                t.apply();
                releaseTransaction(t);
            }
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        synchronized (this) {
            final boolean isPrimaryTask = mPrimary != null
                    && taskInfo.token.equals(mPrimary.token);
            final boolean isSecondaryTask = mSecondary != null
                    && taskInfo.token.equals(mSecondary.token);

            if (mSplitScreenSupported && (isPrimaryTask || isSecondaryTask)) {
                mSplitScreenSupported = false;

                SurfaceControl.Transaction t = getTransaction();
                t.remove(mPrimaryDim);
                t.remove(mSecondaryDim);
                t.remove(mPrimarySurface);
                t.remove(mSecondarySurface);
                t.apply();
                releaseTransaction(t);

                mDivider.onTaskVanished();
            }
        }
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        if (taskInfo.displayId != DEFAULT_DISPLAY) {
            return;
        }
        mDivider.getHandler().post(() -> handleTaskInfoChanged(taskInfo));
    }

    /**
     * This is effectively a finite state machine which moves between the various split-screen
     * presentations based on the contents of the split regions.
     */
    private void handleTaskInfoChanged(RunningTaskInfo info) {
        if (!mSplitScreenSupported) {
            // This shouldn't happen; but apparently there is a chance that SysUI crashes without
            // system server receiving binder-death (or maybe it receives binder-death too late?).
            // In this situation, when sys-ui restarts, the split root-tasks will still exist so
            // there is a small window of time during init() where WM might send messages here
            // before init() fails. So, avoid a cycle of crashes by returning early.
            Log.e(TAG, "Got handleTaskInfoChanged when not initialized: " + info);
            return;
        }
        final boolean secondaryImpliedMinimize = mSecondary.topActivityType == ACTIVITY_TYPE_HOME
                || (mSecondary.topActivityType == ACTIVITY_TYPE_RECENTS
                        && mDivider.isHomeStackResizable());
        final boolean primaryWasEmpty = mPrimary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryWasEmpty = mSecondary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        if (info.token.asBinder() == mPrimary.token.asBinder()) {
            mPrimary = info;
        } else if (info.token.asBinder() == mSecondary.token.asBinder()) {
            mSecondary = info;
        }
        final boolean primaryIsEmpty = mPrimary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryIsEmpty = mSecondary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryImpliesMinimize = mSecondary.topActivityType == ACTIVITY_TYPE_HOME
                || (mSecondary.topActivityType == ACTIVITY_TYPE_RECENTS
                        && mDivider.isHomeStackResizable());
        if (DEBUG) {
            Log.d(TAG, "onTaskInfoChanged " + mPrimary + "  " + mSecondary);
        }
        if (primaryIsEmpty == primaryWasEmpty && secondaryWasEmpty == secondaryIsEmpty
                && secondaryImpliedMinimize == secondaryImpliesMinimize) {
            // No relevant changes
            return;
        }
        if (primaryIsEmpty || secondaryIsEmpty) {
            // At-least one of the splits is empty which means we are currently transitioning
            // into or out-of split-screen mode.
            if (DEBUG) {
                Log.d(TAG, " at-least one split empty " + mPrimary.topActivityType
                        + "  " + mSecondary.topActivityType);
            }
            if (mDivider.isDividerVisible()) {
                // Was in split-mode, which means we are leaving split, so continue that.
                // This happens when the stack in the primary-split is dismissed.
                if (DEBUG) {
                    Log.d(TAG, "    was in split, so this means leave it "
                            + mPrimary.topActivityType + "  " + mSecondary.topActivityType);
                }
                mDivider.startDismissSplit();
            } else if (!primaryIsEmpty && primaryWasEmpty && secondaryWasEmpty) {
                // Wasn't in split-mode (both were empty), but now that the primary split is
                // populated, we should fully enter split by moving everything else into secondary.
                // This just tells window-manager to reparent things, the UI will respond
                // when it gets new task info for the secondary split.
                if (DEBUG) {
                    Log.d(TAG, "   was not in split, but primary is populated, so enter it");
                }
                mDivider.startEnterSplit();
            }
        } else if (secondaryImpliesMinimize) {
            // Both splits are populated but the secondary split has a home/recents stack on top,
            // so enter minimized mode.
            mDivider.ensureMinimizedSplit();
        } else {
            // Both splits are populated by normal activities, so make sure we aren't minimized.
            mDivider.ensureNormalSplit();
        }
    }
}
