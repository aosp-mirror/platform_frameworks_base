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

package com.android.wm.shell.splitscreen;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_MAIN;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.window.IRemoteTransition;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.split.SplitLayout.SplitPosition;
import com.android.wm.shell.draganddrop.DragAndDropPolicy;
import com.android.wm.shell.splitscreen.ISplitScreenListener;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;

/**
 * Class manages split-screen multitasking mode and implements the main interface
 * {@link SplitScreen}.
 * @see StageCoordinator
 */
public class SplitScreenController implements DragAndDropPolicy.Starter,
        RemoteCallable<SplitScreenController> {
    private static final String TAG = SplitScreenController.class.getSimpleName();

    private final ShellTaskOrganizer mTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Context mContext;
    private final RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    private final ShellExecutor mMainExecutor;
    private final SplitScreenImpl mImpl = new SplitScreenImpl();
    private final DisplayImeController mDisplayImeController;
    private final Transitions mTransitions;
    private final TransactionPool mTransactionPool;

    private StageCoordinator mStageCoordinator;

    public SplitScreenController(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer,
            ShellExecutor mainExecutor, DisplayImeController displayImeController,
            Transitions transitions, TransactionPool transactionPool) {
        mTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mContext = context;
        mRootTDAOrganizer = rootTDAOrganizer;
        mMainExecutor = mainExecutor;
        mDisplayImeController = displayImeController;
        mTransitions = transitions;
        mTransactionPool = transactionPool;
    }

    public SplitScreen asSplitScreen() {
        return mImpl;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    public void onOrganizerRegistered() {
        if (mStageCoordinator == null) {
            // TODO: Multi-display
            mStageCoordinator = new StageCoordinator(mContext, DEFAULT_DISPLAY, mSyncQueue,
                    mRootTDAOrganizer, mTaskOrganizer, mDisplayImeController, mTransitions,
                    mTransactionPool);
        }
    }

    public boolean isSplitScreenVisible() {
        return mStageCoordinator.isSplitScreenVisible();
    }

    public boolean moveToSideStage(int taskId, @SplitPosition int sideStagePosition) {
        final ActivityManager.RunningTaskInfo task = mTaskOrganizer.getRunningTaskInfo(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown taskId" + taskId);
        }
        return moveToSideStage(task, sideStagePosition);
    }

    public boolean moveToSideStage(ActivityManager.RunningTaskInfo task,
            @SplitPosition int sideStagePosition) {
        return mStageCoordinator.moveToSideStage(task, sideStagePosition);
    }

    public boolean removeFromSideStage(int taskId) {
        return mStageCoordinator.removeFromSideStage(taskId);
    }

    public void setSideStagePosition(@SplitPosition int sideStagePosition) {
        mStageCoordinator.setSideStagePosition(sideStagePosition);
    }

    public void setSideStageVisibility(boolean visible) {
        mStageCoordinator.setSideStageVisibility(visible);
    }

    public void enterSplitScreen(int taskId, boolean leftOrTop) {
        moveToSideStage(taskId,
                leftOrTop ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT);
    }

    public void exitSplitScreen() {
        mStageCoordinator.exitSplitScreen();
    }

    public void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
        mStageCoordinator.exitSplitScreenOnHide(exitSplitScreenOnHide);
    }

    public void getStageBounds(Rect outTopOrLeftBounds, Rect outBottomOrRightBounds) {
        mStageCoordinator.getStageBounds(outTopOrLeftBounds, outBottomOrRightBounds);
    }

    public void registerSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        mStageCoordinator.registerSplitScreenListener(listener);
    }

    public void unregisterSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        mStageCoordinator.unregisterSplitScreenListener(listener);
    }

    public void startTask(int taskId, @SplitScreen.StageType int stage,
            @SplitPosition int position, @Nullable Bundle options) {
        options = resolveStartStage(stage, position, options);

        try {
            ActivityTaskManager.getService().startActivityFromRecents(taskId, options);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to launch task", e);
        }
    }

    public void startShortcut(String packageName, String shortcutId,
            @SplitScreen.StageType int stage, @SplitPosition int position,
            @Nullable Bundle options, UserHandle user) {
        options = resolveStartStage(stage, position, options);

        try {
            LauncherApps launcherApps =
                    mContext.getSystemService(LauncherApps.class);
            launcherApps.startShortcut(packageName, shortcutId, null /* sourceBounds */,
                    options, user);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "Failed to launch shortcut", e);
        }
    }

    public void startIntent(PendingIntent intent, Intent fillInIntent,
            @SplitScreen.StageType int stage, @SplitPosition int position,
            @Nullable Bundle options) {
        options = resolveStartStage(stage, position, options);

        try {
            intent.send(mContext, 0, fillInIntent, null, null, null, options);
        } catch (PendingIntent.CanceledException e) {
            Slog.e(TAG, "Failed to launch activity", e);
        }
    }

    private Bundle resolveStartStage(@SplitScreen.StageType int stage,
            @SplitPosition int position, @Nullable Bundle options) {
        switch (stage) {
            case STAGE_TYPE_UNDEFINED: {
                // Use the stage of the specified position is valid.
                if (position != SPLIT_POSITION_UNDEFINED) {
                    if (position == mStageCoordinator.getSideStagePosition()) {
                        options = resolveStartStage(STAGE_TYPE_SIDE, position, options);
                    } else {
                        options = resolveStartStage(STAGE_TYPE_MAIN, position, options);
                    }
                } else {
                    // Exit split-screen and launch fullscreen since stage wasn't specified.
                    mStageCoordinator.exitSplitScreen();
                }
                break;
            }
            case STAGE_TYPE_SIDE: {
                if (position != SPLIT_POSITION_UNDEFINED) {
                    mStageCoordinator.setSideStagePosition(position);
                } else {
                    position = mStageCoordinator.getSideStagePosition();
                }
                if (options == null) {
                    options = new Bundle();
                }
                mStageCoordinator.updateActivityOptions(options, position);
                break;
            }
            case STAGE_TYPE_MAIN: {
                if (position != SPLIT_POSITION_UNDEFINED) {
                    // Set the side stage opposite of what we want to the main stage.
                    final int sideStagePosition = position == SPLIT_POSITION_TOP_OR_LEFT
                            ? SPLIT_POSITION_BOTTOM_OR_RIGHT : SPLIT_POSITION_TOP_OR_LEFT;
                    mStageCoordinator.setSideStagePosition(sideStagePosition);
                } else {
                    position = mStageCoordinator.getMainStagePosition();
                }
                if (options == null) {
                    options = new Bundle();
                }
                mStageCoordinator.updateActivityOptions(options, position);
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown stage=" + stage);
        }

        return options;
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        pw.println(prefix + TAG);
        if (mStageCoordinator != null) {
            mStageCoordinator.dump(pw, prefix);
        }
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class SplitScreenImpl implements SplitScreen {
        private ISplitScreenImpl mISplitScreen;

        @Override
        public ISplitScreen createExternalInterface() {
            if (mISplitScreen != null) {
                mISplitScreen.invalidate();
            }
            mISplitScreen = new ISplitScreenImpl(SplitScreenController.this);
            return mISplitScreen;
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class ISplitScreenImpl extends ISplitScreen.Stub {
        private SplitScreenController mController;
        private ISplitScreenListener mListener;
        private final SplitScreen.SplitScreenListener mSplitScreenListener =
                new SplitScreen.SplitScreenListener() {
                    @Override
                    public void onStagePositionChanged(int stage, int position) {
                        try {
                            if (mListener != null) {
                                mListener.onStagePositionChanged(stage, position);
                            }
                        } catch (RemoteException e) {
                            Slog.e(TAG, "onStagePositionChanged", e);
                        }
                    }

                    @Override
                    public void onTaskStageChanged(int taskId, int stage, boolean visible) {
                        try {
                            if (mListener != null) {
                                mListener.onTaskStageChanged(taskId, stage, visible);
                            }
                        } catch (RemoteException e) {
                            Slog.e(TAG, "onTaskStageChanged", e);
                        }
                    }
                };
        private final IBinder.DeathRecipient mListenerDeathRecipient =
                new IBinder.DeathRecipient() {
                    @Override
                    @BinderThread
                    public void binderDied() {
                        final SplitScreenController controller = mController;
                        controller.getRemoteCallExecutor().execute(() -> {
                            mListener = null;
                            controller.unregisterSplitScreenListener(mSplitScreenListener);
                        });
                    }
                };

        public ISplitScreenImpl(SplitScreenController controller) {
            mController = controller;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        void invalidate() {
            mController = null;
        }

        @Override
        public void registerSplitScreenListener(ISplitScreenListener listener) {
            executeRemoteCallWithTaskPermission(mController, "registerSplitScreenListener",
                    (controller) -> {
                        if (mListener != null) {
                            mListener.asBinder().unlinkToDeath(mListenerDeathRecipient,
                                    0 /* flags */);
                        }
                        if (listener != null) {
                            try {
                                listener.asBinder().linkToDeath(mListenerDeathRecipient,
                                        0 /* flags */);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Failed to link to death");
                                return;
                            }
                        }
                        mListener = listener;
                        controller.registerSplitScreenListener(mSplitScreenListener);
                    });
        }

        @Override
        public void unregisterSplitScreenListener(ISplitScreenListener listener) {
            executeRemoteCallWithTaskPermission(mController, "unregisterSplitScreenListener",
                    (controller) -> {
                        if (mListener != null) {
                            mListener.asBinder().unlinkToDeath(mListenerDeathRecipient,
                                    0 /* flags */);
                        }
                        mListener = null;
                        controller.unregisterSplitScreenListener(mSplitScreenListener);
                    });
        }

        @Override
        public void exitSplitScreen() {
            executeRemoteCallWithTaskPermission(mController, "exitSplitScreen",
                    (controller) -> {
                        controller.exitSplitScreen();
                    });
        }

        @Override
        public void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
            executeRemoteCallWithTaskPermission(mController, "exitSplitScreenOnHide",
                    (controller) -> {
                        controller.exitSplitScreenOnHide(exitSplitScreenOnHide);
                    });
        }

        @Override
        public void setSideStageVisibility(boolean visible) {
            executeRemoteCallWithTaskPermission(mController, "setSideStageVisibility",
                    (controller) -> {
                        controller.setSideStageVisibility(visible);
                    });
        }

        @Override
        public void removeFromSideStage(int taskId) {
            executeRemoteCallWithTaskPermission(mController, "removeFromSideStage",
                    (controller) -> {
                        controller.removeFromSideStage(taskId);
                    });
        }

        @Override
        public void startTask(int taskId, int stage, int position, @Nullable Bundle options) {
            executeRemoteCallWithTaskPermission(mController, "startTask",
                    (controller) -> {
                        controller.startTask(taskId, stage, position, options);
                    });
        }

        @Override
        public void startTasks(int mainTaskId, @Nullable Bundle mainOptions,
                int sideTaskId, @Nullable Bundle sideOptions,
                @SplitPosition int sidePosition,
                @Nullable IRemoteTransition remoteTransition) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasks(mainTaskId, mainOptions,
                            sideTaskId, sideOptions, sidePosition, remoteTransition));
        }

        @Override
        public void startShortcut(String packageName, String shortcutId, int stage, int position,
                @Nullable Bundle options, UserHandle user) {
            executeRemoteCallWithTaskPermission(mController, "startShortcut",
                    (controller) -> {
                        controller.startShortcut(packageName, shortcutId, stage, position,
                                options, user);
                    });
        }

        @Override
        public void startIntent(PendingIntent intent, Intent fillInIntent, int stage, int position,
                @Nullable Bundle options) {
            executeRemoteCallWithTaskPermission(mController, "startIntent",
                    (controller) -> {
                        controller.startIntent(intent, fillInIntent, stage, position, options);
                    });
        }
    }
}
