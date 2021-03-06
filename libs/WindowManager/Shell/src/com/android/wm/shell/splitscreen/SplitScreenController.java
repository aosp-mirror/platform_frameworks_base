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

import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_POSITION_UNDEFINED;
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
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.draganddrop.DragAndDropPolicy;

import java.io.PrintWriter;

/**
 * Class manages split-screen multitasking mode and implements the main interface
 * {@link SplitScreen}.
 * @see StageCoordinator
 */
public class SplitScreenController implements DragAndDropPolicy.Starter {
    private static final String TAG = SplitScreenController.class.getSimpleName();

    private final ShellTaskOrganizer mTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Context mContext;
    private final RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    private final ShellExecutor mMainExecutor;
    private final SplitScreenImpl mImpl = new SplitScreenImpl();
    private final DisplayImeController mDisplayImeController;

    private StageCoordinator mStageCoordinator;

    public SplitScreenController(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer,
            ShellExecutor mainExecutor, DisplayImeController displayImeController) {
        mTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mContext = context;
        mRootTDAOrganizer = rootTDAOrganizer;
        mMainExecutor = mainExecutor;
        mDisplayImeController = displayImeController;
    }

    public SplitScreen asSplitScreen() {
        return mImpl;
    }

    public void onOrganizerRegistered() {
        if (mStageCoordinator == null) {
            // TODO: Multi-display
            mStageCoordinator = new StageCoordinator(mContext, DEFAULT_DISPLAY, mSyncQueue,
                    mRootTDAOrganizer, mTaskOrganizer, mDisplayImeController);
        }
    }

    public boolean isSplitScreenVisible() {
        return mStageCoordinator.isSplitScreenVisible();
    }

    public boolean moveToSideStage(int taskId, @SplitScreen.StagePosition int sideStagePosition) {
        final ActivityManager.RunningTaskInfo task = mTaskOrganizer.getRunningTaskInfo(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown taskId" + taskId);
        }
        return moveToSideStage(task, sideStagePosition);
    }

    public boolean moveToSideStage(ActivityManager.RunningTaskInfo task,
            @SplitScreen.StagePosition int sideStagePosition) {
        return mStageCoordinator.moveToSideStage(task, sideStagePosition);
    }

    public boolean removeFromSideStage(int taskId) {
        return mStageCoordinator.removeFromSideStage(taskId);
    }

    public void setSideStagePosition(@SplitScreen.StagePosition int sideStagePosition) {
        mStageCoordinator.setSideStagePosition(sideStagePosition);
    }

    public void setSideStageVisibility(boolean visible) {
        mStageCoordinator.setSideStageVisibility(visible);
    }

    public void enterSplitScreen(int taskId, boolean leftOrTop) {
        moveToSideStage(taskId,
                leftOrTop ? STAGE_POSITION_TOP_OR_LEFT : STAGE_POSITION_BOTTOM_OR_RIGHT);
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
            @SplitScreen.StagePosition int position, @Nullable Bundle options) {
        options = resolveStartStage(stage, position, options);

        try {
            ActivityTaskManager.getService().startActivityFromRecents(taskId, options);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to launch task", e);
        }
    }

    public void startShortcut(String packageName, String shortcutId,
            @SplitScreen.StageType int stage, @SplitScreen.StagePosition int position,
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

    public void startIntent(PendingIntent intent, Context context,
            Intent fillInIntent, @SplitScreen.StageType int stage,
            @SplitScreen.StagePosition int position, @Nullable Bundle options) {
        options = resolveStartStage(stage, position, options);

        try {
            intent.send(context, 0, fillInIntent, null, null, null, options);
        } catch (PendingIntent.CanceledException e) {
            Slog.e(TAG, "Failed to launch activity", e);
        }
    }

    private Bundle resolveStartStage(@SplitScreen.StageType int stage,
            @SplitScreen.StagePosition int position, @Nullable Bundle options) {
        switch (stage) {
            case STAGE_TYPE_UNDEFINED: {
                // Use the stage of the specified position is valid.
                if (position != STAGE_POSITION_UNDEFINED) {
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
                if (position != STAGE_POSITION_UNDEFINED) {
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
                if (position != STAGE_POSITION_UNDEFINED) {
                    // Set the side stage opposite of what we want to the main stage.
                    final int sideStagePosition = position == STAGE_POSITION_TOP_OR_LEFT
                            ? STAGE_POSITION_BOTTOM_OR_RIGHT : STAGE_POSITION_TOP_OR_LEFT;
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

    private class SplitScreenImpl implements SplitScreen {
        @Override
        public boolean isSplitScreenVisible() {
            return mMainExecutor.executeBlockingForResult(() -> {
                return SplitScreenController.this.isSplitScreenVisible();
            }, Boolean.class);
        }

        @Override
        public boolean moveToSideStage(int taskId, int sideStagePosition) {
            return mMainExecutor.executeBlockingForResult(() -> {
                return SplitScreenController.this.moveToSideStage(taskId, sideStagePosition);
            }, Boolean.class);
        }

        @Override
        public boolean moveToSideStage(ActivityManager.RunningTaskInfo task,
                int sideStagePosition) {
            return mMainExecutor.executeBlockingForResult(() -> {
                return SplitScreenController.this.moveToSideStage(task, sideStagePosition);
            }, Boolean.class);
        }

        @Override
        public boolean removeFromSideStage(int taskId) {
            return mMainExecutor.executeBlockingForResult(() -> {
                return SplitScreenController.this.removeFromSideStage(taskId);
            }, Boolean.class);
        }

        @Override
        public void setSideStagePosition(int sideStagePosition) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.setSideStagePosition(sideStagePosition);
            });
        }

        @Override
        public void setSideStageVisibility(boolean visible) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.setSideStageVisibility(visible);
            });
        }

        @Override
        public void enterSplitScreen(int taskId, boolean leftOrTop) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.enterSplitScreen(taskId, leftOrTop);
            });
        }

        @Override
        public void exitSplitScreen() {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.exitSplitScreen();
            });
        }

        @Override
        public void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.exitSplitScreenOnHide(exitSplitScreenOnHide);
            });
        }

        @Override
        public void getStageBounds(Rect outTopOrLeftBounds, Rect outBottomOrRightBounds) {
            try {
                mMainExecutor.executeBlocking(() -> {
                    SplitScreenController.this.getStageBounds(outTopOrLeftBounds,
                            outBottomOrRightBounds);
                });
            } catch (InterruptedException e) {
                Slog.e(TAG, "Failed to get stage bounds in 2s");
            }
        }

        @Override
        public void registerSplitScreenListener(SplitScreenListener listener) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.registerSplitScreenListener(listener);
            });
        }

        @Override
        public void unregisterSplitScreenListener(SplitScreenListener listener) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.unregisterSplitScreenListener(listener);
            });
        }

        @Override
        public void startTask(int taskId, int stage, int position, @Nullable Bundle options) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.startTask(taskId, stage, position, options);
            });
        }

        @Override
        public void startShortcut(String packageName, String shortcutId, int stage, int position,
                @Nullable Bundle options, UserHandle user) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.startShortcut(packageName, shortcutId, stage, position,
                        options, user);
            });
        }

        @Override
        public void startIntent(PendingIntent intent, Context context, Intent fillInIntent,
                int stage, int position, @Nullable Bundle options) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.startIntent(intent, context, fillInIntent, stage,
                        position, options);
            });
        }
    }

}
