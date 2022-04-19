/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.kidsmode;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.window.ITaskOrganizerController;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.KidsModeSettingsObserver;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.startingsurface.StartingWindowController;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A dedicated task organizer when kids mode is enabled.
 *  - Creates a root task with bounds that exclude the navigation bar area
 *  - Launch all task into the root task except for Launcher
 */
public class KidsModeTaskOrganizer extends ShellTaskOrganizer {
    private static final String TAG = "KidsModeTaskOrganizer";

    private static final int[] CONTROLLED_ACTIVITY_TYPES =
            {ACTIVITY_TYPE_UNDEFINED, ACTIVITY_TYPE_STANDARD};
    private static final int[] CONTROLLED_WINDOWING_MODES =
            {WINDOWING_MODE_FULLSCREEN, WINDOWING_MODE_UNDEFINED};

    private final Handler mMainHandler;
    private final Context mContext;
    private final SyncTransactionQueue mSyncQueue;
    private final DisplayController mDisplayController;
    private final DisplayInsetsController mDisplayInsetsController;

    @VisibleForTesting
    ActivityManager.RunningTaskInfo mLaunchRootTask;
    @VisibleForTesting
    SurfaceControl mLaunchRootLeash;
    @VisibleForTesting
    final IBinder mCookie = new Binder();

    private final InsetsState mInsetsState = new InsetsState();
    private int mDisplayWidth;
    private int mDisplayHeight;

    private KidsModeSettingsObserver mKidsModeSettingsObserver;
    private boolean mEnabled;

    DisplayController.OnDisplaysChangedListener mOnDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
        @Override
        public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
            if (displayId != DEFAULT_DISPLAY) {
                return;
            }
            final DisplayLayout displayLayout =
                    mDisplayController.getDisplayLayout(DEFAULT_DISPLAY);
            if (displayLayout == null) {
                return;
            }
            final int displayWidth = displayLayout.width();
            final int displayHeight = displayLayout.height();
            if (displayWidth == mDisplayWidth || displayHeight == mDisplayHeight) {
                return;
            }
            mDisplayWidth = displayWidth;
            mDisplayHeight = displayHeight;
            updateBounds();
        }
    };

    DisplayInsetsController.OnInsetsChangedListener mOnInsetsChangedListener =
            new DisplayInsetsController.OnInsetsChangedListener() {
        @Override
        public void insetsChanged(InsetsState insetsState) {
            // Update bounds only when the insets of navigation bar or task bar is changed.
            if (Objects.equals(insetsState.peekSource(InsetsState.ITYPE_NAVIGATION_BAR),
                    mInsetsState.peekSource(InsetsState.ITYPE_NAVIGATION_BAR))
                    && Objects.equals(insetsState.peekSource(
                            InsetsState.ITYPE_EXTRA_NAVIGATION_BAR),
                    mInsetsState.peekSource(InsetsState.ITYPE_EXTRA_NAVIGATION_BAR))) {
                return;
            }
            mInsetsState.set(insetsState);
            updateBounds();
        }
    };

    @VisibleForTesting
    KidsModeTaskOrganizer(
            ITaskOrganizerController taskOrganizerController,
            ShellExecutor mainExecutor,
            Handler mainHandler,
            Context context,
            SyncTransactionQueue syncTransactionQueue,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            Optional<RecentTasksController> recentTasks,
            KidsModeSettingsObserver kidsModeSettingsObserver) {
        super(taskOrganizerController, mainExecutor, context, /* compatUI= */ null, recentTasks);
        mContext = context;
        mMainHandler = mainHandler;
        mSyncQueue = syncTransactionQueue;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
        mKidsModeSettingsObserver = kidsModeSettingsObserver;
    }

    public KidsModeTaskOrganizer(
            ShellExecutor mainExecutor,
            Handler mainHandler,
            Context context,
            SyncTransactionQueue syncTransactionQueue,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            Optional<RecentTasksController> recentTasks) {
        super(mainExecutor, context, /* compatUI= */ null, recentTasks);
        mContext = context;
        mMainHandler = mainHandler;
        mSyncQueue = syncTransactionQueue;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
    }

    /**
     * Initializes kids mode status.
     */
    public void initialize(StartingWindowController startingWindowController) {
        initStartingWindow(startingWindowController);
        if (mKidsModeSettingsObserver == null) {
            mKidsModeSettingsObserver = new KidsModeSettingsObserver(
                    mMainHandler, mContext);
        }
        mKidsModeSettingsObserver.setOnChangeRunnable(() -> updateKidsModeState());
        updateKidsModeState();
        mKidsModeSettingsObserver.register();
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mEnabled && mLaunchRootTask == null && taskInfo.launchCookies != null
                && taskInfo.launchCookies.contains(mCookie)) {
            mLaunchRootTask = taskInfo;
            mLaunchRootLeash = leash;
            updateTask();
        }
        super.onTaskAppeared(taskInfo, leash);

        mSyncQueue.runInSync(t -> {
            // Reset several properties back to fullscreen (PiP, for example, leaves all these
            // properties in a bad state).
            t.setCrop(leash, null);
            t.setPosition(leash, 0, 0);
            t.setAlpha(leash, 1f);
            t.setMatrix(leash, 1, 0, 0, 1);
            t.show(leash);
        });
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (mLaunchRootTask != null && mLaunchRootTask.taskId == taskInfo.taskId
                && !taskInfo.equals(mLaunchRootTask)) {
            mLaunchRootTask = taskInfo;
        }

        super.onTaskInfoChanged(taskInfo);
    }

    @VisibleForTesting
    void updateKidsModeState() {
        final boolean enabled = mKidsModeSettingsObserver.isEnabled();
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        if (mEnabled) {
            enable();
        } else {
            disable();
        }
    }

    @VisibleForTesting
    void enable() {
        final DisplayLayout displayLayout = mDisplayController.getDisplayLayout(DEFAULT_DISPLAY);
        if (displayLayout != null) {
            mDisplayWidth = displayLayout.width();
            mDisplayHeight = displayLayout.height();
        }
        mInsetsState.set(mDisplayController.getInsetsState(DEFAULT_DISPLAY));
        mDisplayInsetsController.addInsetsChangedListener(DEFAULT_DISPLAY,
                mOnInsetsChangedListener);
        mDisplayController.addDisplayWindowListener(mOnDisplaysChangedListener);
        List<TaskAppearedInfo> taskAppearedInfos = registerOrganizer();
        for (int i = 0; i < taskAppearedInfos.size(); i++) {
            final TaskAppearedInfo info = taskAppearedInfos.get(i);
            onTaskAppeared(info.getTaskInfo(), info.getLeash());
        }
        createRootTask(DEFAULT_DISPLAY, WINDOWING_MODE_FULLSCREEN, mCookie);
        updateTask();
    }

    @VisibleForTesting
    void disable() {
        mDisplayInsetsController.removeInsetsChangedListener(DEFAULT_DISPLAY,
                mOnInsetsChangedListener);
        mDisplayController.removeDisplayWindowListener(mOnDisplaysChangedListener);
        updateTask();
        final WindowContainerToken token = mLaunchRootTask.token;
        if (token != null) {
            deleteRootTask(token);
        }
        mLaunchRootTask = null;
        mLaunchRootLeash = null;
        unregisterOrganizer();
    }

    private void updateTask() {
        updateTask(getWindowContainerTransaction());
    }

    private void updateTask(WindowContainerTransaction wct) {
        if (mLaunchRootTask == null || mLaunchRootLeash == null) {
            return;
        }
        final Rect taskBounds = calculateBounds();
        final WindowContainerToken rootToken = mLaunchRootTask.token;
        wct.setBounds(rootToken, mEnabled ? taskBounds : null);
        wct.setLaunchRoot(rootToken,
                mEnabled ? CONTROLLED_WINDOWING_MODES : null,
                mEnabled ? CONTROLLED_ACTIVITY_TYPES : null);
        wct.reparentTasks(
                mEnabled ? null : rootToken /* currentParent */,
                mEnabled ? rootToken : null /* newParent */,
                CONTROLLED_WINDOWING_MODES,
                CONTROLLED_ACTIVITY_TYPES,
                true /* onTop */);
        wct.reorder(rootToken, mEnabled /* onTop */);
        mSyncQueue.queue(wct);
        final SurfaceControl rootLeash = mLaunchRootLeash;
        mSyncQueue.runInSync(t -> {
            t.setPosition(rootLeash, taskBounds.left, taskBounds.top);
            t.setWindowCrop(rootLeash, taskBounds.width(), taskBounds.height());
        });
    }

    private Rect calculateBounds() {
        final Rect bounds = new Rect(0, 0, mDisplayWidth, mDisplayHeight);
        final InsetsSource navBarSource = mInsetsState.peekSource(InsetsState.ITYPE_NAVIGATION_BAR);
        final InsetsSource taskBarSource = mInsetsState.peekSource(
                InsetsState.ITYPE_EXTRA_NAVIGATION_BAR);
        if (navBarSource != null && !navBarSource.getFrame().isEmpty()) {
            bounds.inset(navBarSource.calculateInsets(bounds, false /* ignoreVisibility */));
        } else if (taskBarSource != null && !taskBarSource.getFrame().isEmpty()) {
            bounds.inset(taskBarSource.calculateInsets(bounds, false /* ignoreVisibility */));
        } else {
            bounds.setEmpty();
        }
        return bounds;
    }

    private void updateBounds() {
        if (mLaunchRootTask == null) {
            return;
        }
        final WindowContainerTransaction wct = getWindowContainerTransaction();
        final Rect taskBounds = calculateBounds();
        wct.setBounds(mLaunchRootTask.token, taskBounds);
        mSyncQueue.queue(wct);
        final SurfaceControl finalLeash = mLaunchRootLeash;
        mSyncQueue.runInSync(t -> {
            t.setPosition(finalLeash, taskBounds.left, taskBounds.top);
            t.setWindowCrop(finalLeash, taskBounds.width(), taskBounds.height());
        });
    }

    @VisibleForTesting
    WindowContainerTransaction getWindowContainerTransaction() {
        return new WindowContainerTransaction();
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + " mEnabled=" + mEnabled);
        pw.println(innerPrefix + " mLaunchRootTask=" + mLaunchRootTask);
        pw.println(innerPrefix + " mLaunchRootLeash=" + mLaunchRootLeash);
        pw.println(innerPrefix + " mDisplayWidth=" + mDisplayWidth);
        pw.println(innerPrefix + " mDisplayHeight=" + mDisplayHeight);
        pw.println(innerPrefix + " mInsetsState=" + mInsetsState);
        super.dump(pw, innerPrefix);
    }
}
