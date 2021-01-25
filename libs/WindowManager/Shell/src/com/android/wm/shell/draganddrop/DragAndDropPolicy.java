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

package com.android.wm.shell.draganddrop;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.ClipDescription.EXTRA_ACTIVITY_OPTIONS;
import static android.content.ClipDescription.EXTRA_PENDING_INTENT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_SHORTCUT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_TASK;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_SHORTCUT_ID;
import static android.content.Intent.EXTRA_TASK_ID;
import static android.content.Intent.EXTRA_USER;

import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_FULLSCREEN;
import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_SPLIT_BOTTOM;
import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_SPLIT_LEFT;
import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_SPLIT_RIGHT;
import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_SPLIT_TOP;
import static com.android.wm.shell.splitscreen.SplitScreen.SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.splitscreen.SplitScreen.SIDE_STAGE_POSITION_TOP_OR_LEFT;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.WindowConfiguration;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * The policy for handling drag and drop operations to shell.
 */
public class DragAndDropPolicy {

    private static final String TAG = DragAndDropPolicy.class.getSimpleName();

    private final Context mContext;
    private final ActivityTaskManager mActivityTaskManager;
    private final Starter mStarter;
    private final SplitScreen mSplitScreen;
    private final ArrayList<DragAndDropPolicy.Target> mTargets = new ArrayList<>();

    private DragSession mSession;

    public DragAndDropPolicy(Context context, SplitScreen splitScreen) {
        this(context, ActivityTaskManager.getInstance(), splitScreen,
                new DefaultStarter(context, splitScreen));
    }

    @VisibleForTesting
    DragAndDropPolicy(Context context, ActivityTaskManager activityTaskManager,
            SplitScreen splitScreen, Starter starter) {
        mContext = context;
        mActivityTaskManager = activityTaskManager;
        mSplitScreen = splitScreen;
        mStarter = starter;
    }

    /**
     * Starts a new drag session with the given initial drag data.
     */
    void start(DisplayLayout displayLayout, ClipData data) {
        mSession = new DragSession(mContext, mActivityTaskManager, displayLayout, data);
        // TODO(b/169894807): Also update the session data with task stack changes
        mSession.update();
    }

    /**
     * Returns the target's regions based on the current state of the device and display.
     */
    @NonNull
    ArrayList<Target> getTargets(Insets insets) {
        mTargets.clear();
        if (mSession == null) {
            // Return early if this isn't an app drag
            return mTargets;
        }

        final int w = mSession.displayLayout.width();
        final int h = mSession.displayLayout.height();
        final int iw = w - insets.left - insets.right;
        final int ih = h - insets.top - insets.bottom;
        final int l = insets.left;
        final int t = insets.top;
        final Rect displayRegion = new Rect(l, t, l + iw, t + ih);
        final Rect fullscreenDrawRegion = new Rect(displayRegion);
        final Rect fullscreenHitRegion = new Rect(displayRegion);
        final boolean inLandscape = mSession.displayLayout.isLandscape();
        final boolean inSplitScreen = mSplitScreen != null && mSplitScreen.isSplitScreenVisible();
        // We allow splitting if we are already in split-screen or the running task is a standard
        // task in fullscreen mode.
        final boolean allowSplit = inSplitScreen
                || (mSession.runningTaskActType == ACTIVITY_TYPE_STANDARD
                        && mSession.runningTaskWinMode == WINDOWING_MODE_FULLSCREEN);
        if (allowSplit) {
            // Already split, allow replacing existing split task
            final Rect topOrLeftBounds = new Rect();
            final Rect bottomOrRightBounds = new Rect();
            mSplitScreen.getStageBounds(topOrLeftBounds, bottomOrRightBounds);
            topOrLeftBounds.intersect(displayRegion);
            bottomOrRightBounds.intersect(displayRegion);

            if (inLandscape) {
                final Rect leftHitRegion = new Rect();
                final Rect leftDrawRegion = topOrLeftBounds;
                final Rect rightHitRegion = new Rect();
                final Rect rightDrawRegion = bottomOrRightBounds;

                displayRegion.splitVertically(leftHitRegion, fullscreenHitRegion, rightHitRegion);

                mTargets.add(
                        new Target(TYPE_FULLSCREEN, fullscreenHitRegion, fullscreenDrawRegion));
                mTargets.add(new Target(TYPE_SPLIT_LEFT, leftHitRegion, leftDrawRegion));
                mTargets.add(new Target(TYPE_SPLIT_RIGHT, rightHitRegion, rightDrawRegion));

            } else {
                final Rect topHitRegion = new Rect();
                final Rect topDrawRegion = topOrLeftBounds;
                final Rect bottomHitRegion = new Rect();
                final Rect bottomDrawRegion = bottomOrRightBounds;

                displayRegion.splitHorizontally(
                        topHitRegion, fullscreenHitRegion, bottomHitRegion);

                mTargets.add(
                        new Target(TYPE_FULLSCREEN, fullscreenHitRegion, fullscreenDrawRegion));
                mTargets.add(new Target(TYPE_SPLIT_TOP, topHitRegion, topDrawRegion));
                mTargets.add(new Target(TYPE_SPLIT_BOTTOM, bottomHitRegion, bottomDrawRegion));
            }
        } else {
            // Split-screen not allowed, so only show the fullscreen target
            mTargets.add(new Target(TYPE_FULLSCREEN, fullscreenHitRegion, fullscreenDrawRegion));
        }
        return mTargets;
    }

    /**
     * Returns the target at the given position based on the targets previously calculated.
     */
    @Nullable
    Target getTargetAtLocation(int x, int y) {
        for (int i = mTargets.size() - 1; i >= 0; i--) {
            DragAndDropPolicy.Target t = mTargets.get(i);
            if (t.hitRegion.contains(x, y)) {
                return t;
            }
        }
        return null;
    }

    @VisibleForTesting
    void handleDrop(Target target, ClipData data) {
        if (target == null || !mTargets.contains(target)) {
            return;
        }

        final ClipDescription description = data.getDescription();
        final boolean isTask = description.hasMimeType(MIMETYPE_APPLICATION_TASK);
        final boolean isShortcut = description.hasMimeType(MIMETYPE_APPLICATION_SHORTCUT);
        final Intent dragData = mSession.dragData;
        final boolean inSplitScreen = mSplitScreen != null && mSplitScreen.isSplitScreenVisible();
        final boolean leftOrTop = target.type == TYPE_SPLIT_TOP || target.type == TYPE_SPLIT_LEFT;
        final Bundle opts = dragData.hasExtra(EXTRA_ACTIVITY_OPTIONS)
                ? dragData.getBundleExtra(EXTRA_ACTIVITY_OPTIONS)
                : new Bundle();

        if (target.type == TYPE_FULLSCREEN) {
            // Exit split stages if needed
            mStarter.exitSplitScreen();
        } else if (mSplitScreen != null) {
            // Update launch options for the split side we are targeting.
            final int position = leftOrTop
                    ? SIDE_STAGE_POSITION_TOP_OR_LEFT : SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT;
            if (!inSplitScreen) {
                // Update the side stage position to match where we want to launch.
                mSplitScreen.setSideStagePosition(position);
            }
            mSplitScreen.updateActivityOptions(opts, position);
        }

        if (isTask) {
            mStarter.startTask(dragData.getIntExtra(EXTRA_TASK_ID, INVALID_TASK_ID), opts);
        } else if (isShortcut) {
            mStarter.startShortcut(dragData.getStringExtra(EXTRA_PACKAGE_NAME),
                    dragData.getStringExtra(EXTRA_SHORTCUT_ID),
                    opts, dragData.getParcelableExtra(EXTRA_USER));
        } else {
            mStarter.startIntent(dragData.getParcelableExtra(EXTRA_PENDING_INTENT), opts);
        }
    }

    /**
     * Per-drag session data.
     */
    private static class DragSession {
        private final Context mContext;
        private final ActivityTaskManager mActivityTaskManager;
        private final ClipData mInitialDragData;

        final DisplayLayout displayLayout;
        Intent dragData;
        int runningTaskId;
        @WindowConfiguration.WindowingMode
        int runningTaskWinMode = WINDOWING_MODE_UNDEFINED;
        @WindowConfiguration.ActivityType
        int runningTaskActType = ACTIVITY_TYPE_STANDARD;
        boolean runningTaskIsResizeable;
        boolean dragItemSupportsSplitscreen;
        boolean isPhone;

        DragSession(Context context, ActivityTaskManager activityTaskManager,
                DisplayLayout dispLayout, ClipData data) {
            mContext = context;
            mActivityTaskManager = activityTaskManager;
            mInitialDragData = data;
            displayLayout = dispLayout;
        }

        /**
         * Updates the session data based on the current state of the system.
         */
        void update() {

            List<ActivityManager.RunningTaskInfo> tasks =
                    mActivityTaskManager.getTasks(1, false /* filterOnlyVisibleRecents */);
            if (!tasks.isEmpty()) {
                final ActivityManager.RunningTaskInfo task = tasks.get(0);
                runningTaskWinMode = task.getWindowingMode();
                runningTaskActType = task.getActivityType();
                runningTaskId = task.taskId;
                runningTaskIsResizeable = task.isResizeable;
            }

            final ActivityInfo info = mInitialDragData.getItemAt(0).getActivityInfo();
            dragItemSupportsSplitscreen = info == null
                    || ActivityInfo.isResizeableMode(info.resizeMode);
            isPhone = mContext.getResources().getConfiguration().smallestScreenWidthDp < 600;
            dragData = mInitialDragData.getItemAt(0).getIntent();
        }
    }

    /**
     * Interface for actually committing the task launches.
     */
    @VisibleForTesting
    interface Starter {
        void startTask(int taskId, Bundle activityOptions);
        void startShortcut(String packageName, String shortcutId, Bundle activityOptions,
                UserHandle user);
        void startIntent(PendingIntent intent, Bundle activityOptions);
        void enterSplitScreen(int taskId, boolean leftOrTop);
        void exitSplitScreen();
    }

    /**
     * Default implementation of the starter which calls through the system services to launch the
     * tasks.
     */
    private static class DefaultStarter implements Starter {
        private final Context mContext;
        private final SplitScreen mSplitScreen;

        public DefaultStarter(Context context, SplitScreen splitScreen) {
            mContext = context;
            mSplitScreen = splitScreen;
        }

        @Override
        public void startTask(int taskId, Bundle activityOptions) {
            try {
                ActivityTaskManager.getService().startActivityFromRecents(taskId, activityOptions);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to launch task", e);
            }
        }

        @Override
        public void startShortcut(String packageName, String shortcutId, Bundle activityOptions,
                UserHandle user) {
            try {
                LauncherApps launcherApps =
                        mContext.getSystemService(LauncherApps.class);
                launcherApps.startShortcut(packageName, shortcutId, null /* sourceBounds */,
                        activityOptions, user);
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "Failed to launch shortcut", e);
            }
        }

        @Override
        public void startIntent(PendingIntent intent, Bundle activityOptions) {
            try {
                intent.send(null, 0, null, null, null, null, activityOptions);
            } catch (PendingIntent.CanceledException e) {
                Slog.e(TAG, "Failed to launch activity", e);
            }
        }

        @Override
        public void enterSplitScreen(int taskId, boolean leftOrTop) {
            mSplitScreen.moveToSideStage(taskId,
                    leftOrTop ? SIDE_STAGE_POSITION_TOP_OR_LEFT
                            : SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT);
        }

        @Override
        public void exitSplitScreen() {
            mSplitScreen.exitSplitScreen();
        }
    }

    /**
     * Represents a drop target.
     */
    static class Target {
        static final int TYPE_FULLSCREEN = 0;
        static final int TYPE_SPLIT_LEFT = 1;
        static final int TYPE_SPLIT_TOP = 2;
        static final int TYPE_SPLIT_RIGHT = 3;
        static final int TYPE_SPLIT_BOTTOM = 4;
        @IntDef(value = {
                TYPE_FULLSCREEN,
                TYPE_SPLIT_LEFT,
                TYPE_SPLIT_TOP,
                TYPE_SPLIT_RIGHT,
                TYPE_SPLIT_BOTTOM
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface Type{}

        final @Type int type;

        // The actual hit region for this region
        final Rect hitRegion;
        // The approximate visual region for where the task will start
        final Rect drawRegion;

        public Target(@Type int t, Rect hit, Rect draw) {
            type = t;
            hitRegion = hit;
            drawRegion = draw;
        }

        @Override
        public String toString() {
            return "Target {hit=" + hitRegion + " draw=" + drawRegion + "}";
        }
    }
}
