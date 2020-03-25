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
import static android.window.WindowOrganizer.TaskOrganizer;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.ITaskOrganizer;

import java.util.ArrayList;

class SplitScreenTaskOrganizer extends ITaskOrganizer.Stub {
    private static final String TAG = "SplitScreenTaskOrganizer";
    private static final boolean DEBUG = Divider.DEBUG;

    RunningTaskInfo mPrimary;
    RunningTaskInfo mSecondary;
    SurfaceControl mPrimarySurface;
    SurfaceControl mSecondarySurface;
    SurfaceControl mPrimaryDim;
    SurfaceControl mSecondaryDim;
    ArrayList<SurfaceControl> mHomeAndRecentsSurfaces = new ArrayList<>();
    Rect mHomeBounds = new Rect();
    final Divider mDivider;

    SplitScreenTaskOrganizer(Divider divider) {
        mDivider = divider;
    }

    void init(SurfaceSession session) throws RemoteException {
        TaskOrganizer.registerOrganizer(this, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        TaskOrganizer.registerOrganizer(this, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        mPrimary = TaskOrganizer.createRootTask(Display.DEFAULT_DISPLAY,
                WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        mSecondary = TaskOrganizer.createRootTask(Display.DEFAULT_DISPLAY,
                WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        mPrimarySurface = mPrimary.token.getLeash();
        mSecondarySurface = mSecondary.token.getLeash();

        // Initialize dim surfaces:
        mPrimaryDim = new SurfaceControl.Builder(session).setParent(mPrimarySurface)
                .setColorLayer().setName("Primary Divider Dim").build();
        mSecondaryDim = new SurfaceControl.Builder(session).setParent(mSecondarySurface)
                .setColorLayer().setName("Secondary Divider Dim").build();
        SurfaceControl.Transaction t = getTransaction();
        t.setLayer(mPrimaryDim, Integer.MAX_VALUE);
        t.setColor(mPrimaryDim, new float[]{0f, 0f, 0f});
        t.setLayer(mSecondaryDim, Integer.MAX_VALUE);
        t.setColor(mSecondaryDim, new float[]{0f, 0f, 0f});
        t.apply();
        releaseTransaction(t);
    }

    SurfaceControl.Transaction getTransaction() {
        return mDivider.mTransactionPool.acquire();
    }

    void releaseTransaction(SurfaceControl.Transaction t) {
        mDivider.mTransactionPool.release(t);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo) {
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
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
        final boolean secondaryWasHomeOrRecents = mSecondary.topActivityType == ACTIVITY_TYPE_HOME
                || mSecondary.topActivityType == ACTIVITY_TYPE_RECENTS;
        final boolean primaryWasEmpty = mPrimary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryWasEmpty = mSecondary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        if (info.token.asBinder() == mPrimary.token.asBinder()) {
            mPrimary = info;
        } else if (info.token.asBinder() == mSecondary.token.asBinder()) {
            mSecondary = info;
        }
        final boolean primaryIsEmpty = mPrimary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryIsEmpty = mSecondary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryIsHomeOrRecents = mSecondary.topActivityType == ACTIVITY_TYPE_HOME
                || mSecondary.topActivityType == ACTIVITY_TYPE_RECENTS;
        if (DEBUG) {
            Log.d(TAG, "onTaskInfoChanged " + mPrimary + "  " + mSecondary);
        }
        if (primaryIsEmpty == primaryWasEmpty && secondaryWasEmpty == secondaryIsEmpty
                && secondaryWasHomeOrRecents == secondaryIsHomeOrRecents) {
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
            if (mDivider.inSplitMode()) {
                // Was in split-mode, which means we are leaving split, so continue that.
                // This happens when the stack in the primary-split is dismissed.
                if (DEBUG) {
                    Log.d(TAG, "    was in split, so this means leave it "
                            + mPrimary.topActivityType + "  " + mSecondary.topActivityType);
                }
                WindowManagerProxy.applyDismissSplit(this, true /* dismissOrMaximize */);
                mDivider.updateVisibility(false /* visible */);
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
        } else if (secondaryIsHomeOrRecents) {
            // Both splits are populated but the secondary split has a home/recents stack on top,
            // so enter minimized mode.
            mDivider.ensureMinimizedSplit();
        } else {
            // Both splits are populated by normal activities, so make sure we aren't minimized.
            mDivider.ensureNormalSplit();
        }
    }
}
