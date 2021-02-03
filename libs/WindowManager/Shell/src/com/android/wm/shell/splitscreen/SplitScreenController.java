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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
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
import com.android.wm.shell.common.SyncTransactionQueue;

import java.io.PrintWriter;

/**
 * Class manages split-screen multitasking mode and implements the main interface
 * {@link SplitScreen}.
 * @see StageCoordinator
 */
public class SplitScreenController implements SplitScreen {
    private static final String TAG = SplitScreenController.class.getSimpleName();

    private final ShellTaskOrganizer mTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Context mContext;
    private final RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    private StageCoordinator mStageCoordinator;

    public SplitScreenController(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer) {
        mTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mContext = context;
        mRootTDAOrganizer = rootTDAOrganizer;
    }

    @Override
    public void onOrganizerRegistered() {
        if (mStageCoordinator == null) {
            // TODO: Multi-display
            mStageCoordinator = new StageCoordinator(mContext, DEFAULT_DISPLAY, mSyncQueue,
                    mRootTDAOrganizer, mTaskOrganizer);
        }
    }

    @Override
    public boolean isSplitScreenVisible() {
        return mStageCoordinator.isSplitScreenVisible();
    }

    @Override
    public boolean moveToSideStage(int taskId, @StagePosition int sideStagePosition) {
        final ActivityManager.RunningTaskInfo task = mTaskOrganizer.getRunningTaskInfo(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown taskId" + taskId);
        }
        return moveToSideStage(task, sideStagePosition);
    }

    @Override
    public boolean moveToSideStage(ActivityManager.RunningTaskInfo task,
            @StagePosition int sideStagePosition) {
        return mStageCoordinator.moveToSideStage(task, sideStagePosition);
    }

    @Override
    public boolean removeFromSideStage(int taskId) {
        return mStageCoordinator.removeFromSideStage(taskId);
    }

    @Override
    public void setSideStagePosition(@StagePosition int sideStagePosition) {
        mStageCoordinator.setSideStagePosition(sideStagePosition);
    }

    @Override
    public void setSideStageVisibility(boolean visible) {
        mStageCoordinator.setSideStageVisibility(visible);
    }

    @Override
    public void exitSplitScreen() {
        mStageCoordinator.exitSplitScreen();
    }

    @Override
    public void getStageBounds(Rect outTopOrLeftBounds, Rect outBottomOrRightBounds) {
        mStageCoordinator.getStageBounds(outTopOrLeftBounds, outBottomOrRightBounds);
    }

    @Override
    public void registerSplitScreenListener(SplitScreenListener listener) {
        mStageCoordinator.registerSplitScreenListener(listener);
    }

    @Override
    public void unregisterSplitScreenListener(SplitScreenListener listener) {
        mStageCoordinator.unregisterSplitScreenListener(listener);
    }

    @Override
    public void startTask(int taskId,
            @StageType int stage, @StagePosition int position, @Nullable Bundle options) {
        options = resolveStartStage(stage, position, options);

        try {
            ActivityTaskManager.getService().startActivityFromRecents(taskId, options);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to launch task", e);
        }
    }

    @Override
    public void startShortcut(String packageName, String shortcutId, @StageType int stage,
            @StagePosition int position, @Nullable Bundle options, UserHandle user) {
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

    @Override
    public void startIntent(PendingIntent intent,
            @StageType int stage, @StagePosition int position, @Nullable Bundle options) {
        options = resolveStartStage(stage, position, options);

        try {
            intent.send(null, 0, null, null, null, null, options);
        } catch (PendingIntent.CanceledException e) {
            Slog.e(TAG, "Failed to launch activity", e);
        }
    }

    private Bundle resolveStartStage(@StageType int stage, @StagePosition int position,
            @Nullable Bundle options) {
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

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        pw.println(prefix + TAG);
        if (mStageCoordinator != null) {
            mStageCoordinator.dump(pw, prefix);
        }
    }

}
