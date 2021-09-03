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
import static android.view.RemoteAnimationTarget.MODE_OPENING;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_TOP_OR_LEFT;

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
import android.util.ArrayMap;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.window.IRemoteTransition;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.logging.InstanceId;
import com.android.internal.util.FrameworkStatsLog;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.split.SplitLayout.SplitPosition;
import com.android.wm.shell.draganddrop.DragAndDropPolicy;
import com.android.wm.shell.transition.LegacyTransitions;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * Class manages split-screen multitasking mode and implements the main interface
 * {@link SplitScreen}.
 * @see StageCoordinator
 */
// TODO(b/198577848): Implement split screen flicker test to consolidate CUJ of split screen.
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
    private final DisplayInsetsController mDisplayInsetsController;
    private final Transitions mTransitions;
    private final TransactionPool mTransactionPool;
    private final SplitscreenEventLogger mLogger;

    private StageCoordinator mStageCoordinator;

    public SplitScreenController(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer,
            ShellExecutor mainExecutor, DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            Transitions transitions, TransactionPool transactionPool) {
        mTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mContext = context;
        mRootTDAOrganizer = rootTDAOrganizer;
        mMainExecutor = mainExecutor;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mTransitions = transitions;
        mTransactionPool = transactionPool;
        mLogger = new SplitscreenEventLogger();
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
                    mRootTDAOrganizer, mTaskOrganizer, mDisplayImeController,
                    mDisplayInsetsController, mTransitions, mTransactionPool, mLogger);
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

    public void setSideStageOutline(boolean enable) {
        mStageCoordinator.setSideStageOutline(enable);
    }

    public void setSideStagePosition(@SplitPosition int sideStagePosition) {
        mStageCoordinator.setSideStagePosition(sideStagePosition, null /* wct */);
    }

    public void setSideStageVisibility(boolean visible) {
        mStageCoordinator.setSideStageVisibility(visible);
    }

    public void enterSplitScreen(int taskId, boolean leftOrTop) {
        moveToSideStage(taskId,
                leftOrTop ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT);
    }

    public void exitSplitScreen(int toTopTaskId, int exitReason) {
        mStageCoordinator.exitSplitScreen(toTopTaskId, exitReason);
    }

    public void onKeyguardOccludedChanged(boolean occluded) {
        mStageCoordinator.onKeyguardOccludedChanged(occluded);
    }

    public void onKeyguardVisibilityChanged(boolean showing) {
        mStageCoordinator.onKeyguardVisibilityChanged(showing);
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
        options = mStageCoordinator.resolveStartStage(stage, position, options, null /* wct */);

        try {
            ActivityTaskManager.getService().startActivityFromRecents(taskId, options);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to launch task", e);
        }
    }

    public void startShortcut(String packageName, String shortcutId,
            @SplitScreen.StageType int stage, @SplitPosition int position,
            @Nullable Bundle options, UserHandle user) {
        options = mStageCoordinator.resolveStartStage(stage, position, options, null /* wct */);

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
        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            startIntentLegacy(intent, fillInIntent, stage, position, options);
            return;
        }
        mStageCoordinator.startIntent(intent, fillInIntent, stage, position, options,
                null /* remote */);
    }

    private void startIntentLegacy(PendingIntent intent, Intent fillInIntent,
            @SplitScreen.StageType int stage, @SplitPosition int position,
            @Nullable Bundle options) {
        LegacyTransitions.ILegacyTransition transition = new LegacyTransitions.ILegacyTransition() {
            @Override
            public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                    IRemoteAnimationFinishedCallback finishedCallback,
                    SurfaceControl.Transaction t) {
                mStageCoordinator.updateSurfaceBounds(null /* layout */, t);

                if (apps != null) {
                    for (int i = 0; i < apps.length; ++i) {
                        if (apps[i].mode == MODE_OPENING) {
                            t.show(apps[i].leash);
                        }
                    }
                }

                final RemoteAnimationTarget divider = mStageCoordinator.getDividerBarLegacyTarget();
                if (divider.leash != null) {
                    t.show(divider.leash);
                }

                t.apply();
                if (finishedCallback != null) {
                    try {
                        finishedCallback.onAnimationFinished();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error finishing legacy transition: ", e);
                    }
                }
            }
        };
        WindowContainerTransaction wct = new WindowContainerTransaction();
        options = mStageCoordinator.resolveStartStage(stage, position, options, wct);
        wct.sendPendingIntent(intent, fillInIntent, options);
        mSyncQueue.queue(transition, WindowManager.TRANSIT_OPEN, wct);
    }

    RemoteAnimationTarget[] onGoingToRecentsLegacy(boolean cancel, RemoteAnimationTarget[] apps) {
        if (!isSplitScreenVisible()) return null;
        final SurfaceControl.Builder builder = new SurfaceControl.Builder(new SurfaceSession())
                .setContainerLayer()
                .setName("RecentsAnimationSplitTasks")
                .setHidden(false)
                .setCallsite("SplitScreenController#onGoingtoRecentsLegacy");
        mRootTDAOrganizer.attachToDisplayArea(DEFAULT_DISPLAY, builder);
        SurfaceControl sc = builder.build();
        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();

        // Ensure that we order these in the parent in the right z-order as their previous order
        Arrays.sort(apps, (a1, a2) -> a1.prefixOrderIndex - a2.prefixOrderIndex);
        int layer = 1;
        for (RemoteAnimationTarget appTarget : apps) {
            transaction.reparent(appTarget.leash, sc);
            transaction.setPosition(appTarget.leash, appTarget.screenSpaceBounds.left,
                    appTarget.screenSpaceBounds.top);
            transaction.setLayer(appTarget.leash, layer++);
        }
        transaction.apply();
        transaction.close();
        return new RemoteAnimationTarget[]{mStageCoordinator.getDividerBarLegacyTarget()};
    }

    /**
     * Sets drag info to be logged when splitscreen is entered.
     */
    public void logOnDroppedToSplit(@SplitPosition int position, InstanceId dragSessionId) {
        mStageCoordinator.logOnDroppedToSplit(position, dragSessionId);
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
        private final ArrayMap<SplitScreenListener, Executor> mExecutors = new ArrayMap<>();
        private final SplitScreen.SplitScreenListener mListener = new SplitScreenListener() {
            @Override
            public void onStagePositionChanged(int stage, int position) {
                for (int i = 0; i < mExecutors.size(); i++) {
                    final int index = i;
                    mExecutors.valueAt(index).execute(() -> {
                        mExecutors.keyAt(index).onStagePositionChanged(stage, position);
                    });
                }
            }

            @Override
            public void onTaskStageChanged(int taskId, int stage, boolean visible) {
                for (int i = 0; i < mExecutors.size(); i++) {
                    final int index = i;
                    mExecutors.valueAt(index).execute(() -> {
                        mExecutors.keyAt(index).onTaskStageChanged(taskId, stage, visible);
                    });
                }
            }

            @Override
            public void onSplitVisibilityChanged(boolean visible) {
                for (int i = 0; i < mExecutors.size(); i++) {
                    final int index = i;
                    mExecutors.valueAt(index).execute(() -> {
                        mExecutors.keyAt(index).onSplitVisibilityChanged(visible);
                    });
                }
            }
        };

        @Override
        public ISplitScreen createExternalInterface() {
            if (mISplitScreen != null) {
                mISplitScreen.invalidate();
            }
            mISplitScreen = new ISplitScreenImpl(SplitScreenController.this);
            return mISplitScreen;
        }

        @Override
        public void onKeyguardOccludedChanged(boolean occluded) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.onKeyguardOccludedChanged(occluded);
            });
        }

        @Override
        public void registerSplitScreenListener(SplitScreenListener listener, Executor executor) {
            if (mExecutors.containsKey(listener)) return;

            mMainExecutor.execute(() -> {
                if (mExecutors.size() == 0) {
                    SplitScreenController.this.registerSplitScreenListener(mListener);
                }

                mExecutors.put(listener, executor);
            });

            executor.execute(() -> {
                mStageCoordinator.sendStatusToListener(listener);
            });
        }

        @Override
        public void unregisterSplitScreenListener(SplitScreenListener listener) {
            mMainExecutor.execute(() -> {
                mExecutors.remove(listener);

                if (mExecutors.size() == 0) {
                    SplitScreenController.this.unregisterSplitScreenListener(mListener);
                }
            });
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.onKeyguardVisibilityChanged(showing);
            });
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
        public void exitSplitScreen(int toTopTaskId) {
            executeRemoteCallWithTaskPermission(mController, "exitSplitScreen",
                    (controller) -> {
                        controller.exitSplitScreen(toTopTaskId,
                                FrameworkStatsLog.SPLITSCREEN_UICHANGED__EXIT_REASON__UNKNOWN_EXIT);
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
        public void startTasksWithLegacyTransition(int mainTaskId, @Nullable Bundle mainOptions,
                int sideTaskId, @Nullable Bundle sideOptions, @SplitPosition int sidePosition,
                RemoteAnimationAdapter adapter) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasksWithLegacyTransition(
                            mainTaskId, mainOptions, sideTaskId, sideOptions, sidePosition,
                            adapter));
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

        @Override
        public RemoteAnimationTarget[] onGoingToRecentsLegacy(boolean cancel,
                RemoteAnimationTarget[] apps) {
            final RemoteAnimationTarget[][] out = new RemoteAnimationTarget[][]{null};
            executeRemoteCallWithTaskPermission(mController, "onGoingToRecentsLegacy",
                    (controller) -> out[0] = controller.onGoingToRecentsLegacy(cancel, apps),
                    true /* blocking */);
            return out[0];
        }
    }
}
