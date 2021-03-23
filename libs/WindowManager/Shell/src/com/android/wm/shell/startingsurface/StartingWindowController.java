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
package com.android.wm.shell.startingsurface;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_NONE;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SNAPSHOT;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_CREATED;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_NEW_TASK;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_PROCESS_RUNNING;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_SAME_PACKAGE;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_TASK_SWITCH;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.StartingWindowInfo;
import android.window.TaskOrganizer;
import android.window.TaskSnapshot;

import androidx.annotation.BinderThread;

import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

import java.util.function.BiConsumer;

/**
 * Implementation to draw the starting window to an application, and remove the starting window
 * until the application displays its own window.
 *
 * When receive {@link TaskOrganizer#addStartingWindow} callback, use this class to create a
 * starting window and attached to the Task, then when the Task want to remove the starting window,
 * the TaskOrganizer will receive {@link TaskOrganizer#removeStartingWindow} callback then use this
 * class to remove the starting window of the Task.
 * Besides add/remove starting window, There is an API #setStartingWindowListener to register
 * a callback when starting window is about to create which let the registerer knows the next
 * starting window's type.
 * So far all classes in this package is an enclose system so there is no interact with other shell
 * component, all the methods must be executed in splash screen thread or the thread used in
 * constructor to keep everything synchronized.
 * @hide
 */
public class StartingWindowController implements RemoteCallable<StartingWindowController> {
    private static final String TAG = StartingWindowController.class.getSimpleName();
    // TODO b/183150443 Keep this flag open for a while, several things might need to adjust.
    static final boolean DEBUG_SPLASH_SCREEN = true;
    static final boolean DEBUG_TASK_SNAPSHOT = false;

    private final StartingSurfaceDrawer mStartingSurfaceDrawer;
    private final StartingTypeChecker mStartingTypeChecker = new StartingTypeChecker();

    private BiConsumer<Integer, Integer> mTaskLaunchingCallback;
    private final StartingSurfaceImpl mImpl = new StartingSurfaceImpl();
    private final Context mContext;
    private final ShellExecutor mSplashScreenExecutor;

    // For Car Launcher
    public StartingWindowController(Context context, ShellExecutor splashScreenExecutor) {
        this(context, splashScreenExecutor, new TransactionPool());
    }

    public StartingWindowController(Context context, ShellExecutor splashScreenExecutor,
            TransactionPool pool) {
        mContext = context;
        mStartingSurfaceDrawer = new StartingSurfaceDrawer(context, splashScreenExecutor, pool);
        mSplashScreenExecutor = splashScreenExecutor;
    }

    /**
     * Provide the implementation for Shell Module.
     */
    public StartingSurface asStartingSurface() {
        return mImpl;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mSplashScreenExecutor;
    }

    private static class StartingTypeChecker {
        TaskSnapshot mSnapshot;

        StartingTypeChecker() { }

        private void reset() {
            mSnapshot = null;
        }

        private @StartingWindowInfo.StartingWindowType int
                estimateStartingWindowType(StartingWindowInfo windowInfo) {
            reset();
            final int parameter = windowInfo.startingWindowTypeParameter;
            final boolean newTask = (parameter & TYPE_PARAMETER_NEW_TASK) != 0;
            final boolean taskSwitch = (parameter & TYPE_PARAMETER_TASK_SWITCH) != 0;
            final boolean processRunning = (parameter & TYPE_PARAMETER_PROCESS_RUNNING) != 0;
            final boolean allowTaskSnapshot = (parameter & TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT) != 0;
            final boolean activityCreated = (parameter & TYPE_PARAMETER_ACTIVITY_CREATED) != 0;
            final boolean samePackage = (parameter & TYPE_PARAMETER_SAME_PACKAGE) != 0;
            return estimateStartingWindowType(windowInfo, newTask, taskSwitch,
                    processRunning, allowTaskSnapshot, activityCreated, samePackage);
        }

        // reference from ActivityRecord#getStartingWindowType
        private int estimateStartingWindowType(StartingWindowInfo windowInfo,
                boolean newTask, boolean taskSwitch, boolean processRunning,
                boolean allowTaskSnapshot, boolean activityCreated, boolean samePackage) {
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "preferredStartingWindowType newTask " + newTask
                        + " taskSwitch " + taskSwitch
                        + " processRunning " + processRunning
                        + " allowTaskSnapshot " + allowTaskSnapshot
                        + " activityCreated " + activityCreated
                        + " samePackage " + samePackage);
            }
            if (windowInfo.taskInfo.topActivityType != ACTIVITY_TYPE_HOME) {
                if (!processRunning) {
                    return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
                }
                if (newTask) {
                    if (samePackage) {
                        return STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN;
                    } else {
                        return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
                    }
                }
                if (taskSwitch && !activityCreated) {
                    return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
                }
            }
            if (taskSwitch && allowTaskSnapshot) {
                final TaskSnapshot snapshot = getTaskSnapshot(windowInfo.taskInfo.taskId);
                if (isSnapshotCompatible(windowInfo, snapshot)) {
                    return STARTING_WINDOW_TYPE_SNAPSHOT;
                }
                if (windowInfo.taskInfo.topActivityType != ACTIVITY_TYPE_HOME) {
                    return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
                }
            }
            return STARTING_WINDOW_TYPE_NONE;
        }

        /**
         * Returns {@code true} if the task snapshot is compatible with this activity (at least the
         * rotation must be the same).
         */
        private boolean isSnapshotCompatible(StartingWindowInfo windowInfo, TaskSnapshot snapshot) {
            if (snapshot == null) {
                if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                    Slog.d(TAG, "isSnapshotCompatible no snapshot " + windowInfo.taskInfo.taskId);
                }
                return false;
            }
            if (!snapshot.getTopActivityComponent().equals(windowInfo.taskInfo.topActivity)) {
                if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                    Slog.d(TAG, "isSnapshotCompatible obsoleted snapshot "
                            + windowInfo.taskInfo.topActivity);
                }
                return false;
            }

            final int taskRotation = windowInfo.taskInfo.configuration
                    .windowConfiguration.getRotation();
            final int snapshotRotation = snapshot.getRotation();
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "isSnapshotCompatible rotation " + taskRotation
                        + " snapshot " + snapshotRotation);
            }
            return taskRotation == snapshotRotation;
        }

        private TaskSnapshot getTaskSnapshot(int taskId) {
            if (mSnapshot != null) {
                return mSnapshot;
            }
            try {
                mSnapshot = ActivityTaskManager.getService().getTaskSnapshot(taskId,
                        false/* isLowResolution */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to get snapshot for task: " + taskId + ", from: " + e);
                return null;
            }
            return mSnapshot;
        }
    }

    /*
     * Registers the starting window listener.
     *
     * @param listener The callback when need a starting window.
     */
    void setStartingWindowListener(BiConsumer<Integer, Integer> listener) {
        mTaskLaunchingCallback = listener;
    }

    private boolean shouldSendToListener(int suggestionType) {
        return suggestionType != STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN;
    }

    /**
     * Called when a task need a starting window.
     */
    public void addStartingWindow(StartingWindowInfo windowInfo, IBinder appToken) {
        mSplashScreenExecutor.execute(() -> {
            final int suggestionType = mStartingTypeChecker.estimateStartingWindowType(windowInfo);
            final RunningTaskInfo runningTaskInfo = windowInfo.taskInfo;
            if (mTaskLaunchingCallback != null && shouldSendToListener(suggestionType)) {
                mTaskLaunchingCallback.accept(runningTaskInfo.taskId, suggestionType);
            }
            if (suggestionType == STARTING_WINDOW_TYPE_SPLASH_SCREEN) {
                mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, appToken,
                        false /* emptyView */);
            } else if (suggestionType == STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN) {
                mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, appToken,
                        true /* emptyView */);
            } else if (suggestionType == STARTING_WINDOW_TYPE_SNAPSHOT) {
                final TaskSnapshot snapshot = mStartingTypeChecker.mSnapshot;
                mStartingSurfaceDrawer.makeTaskSnapshotWindow(windowInfo, appToken, snapshot);
            }
            // If prefer don't show, then don't show!
        });
    }

    public void copySplashScreenView(int taskId) {
        mSplashScreenExecutor.execute(() -> {
            mStartingSurfaceDrawer.copySplashScreenView(taskId);
        });
    }

    /**
     * Called when the content of a task is ready to show, starting window can be removed.
     */
    public void removeStartingWindow(int taskId, SurfaceControl leash, Rect frame,
            boolean playRevealAnimation) {
        mSplashScreenExecutor.execute(() -> {
            mStartingSurfaceDrawer.removeStartingWindow(taskId, leash, frame, playRevealAnimation);
        });
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    private class StartingSurfaceImpl implements StartingSurface {
        private IStartingWindowImpl mIStartingWindow;

        @Override
        public IStartingWindowImpl createExternalInterface() {
            if (mIStartingWindow != null) {
                mIStartingWindow.invalidate();
            }
            mIStartingWindow = new IStartingWindowImpl(StartingWindowController.this);
            return mIStartingWindow;
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IStartingWindowImpl extends IStartingWindow.Stub {
        private StartingWindowController mController;
        private IStartingWindowListener mListener;
        private final BiConsumer<Integer, Integer> mStartingWindowListener =
                this::notifyIStartingWindowListener;
        private final IBinder.DeathRecipient mListenerDeathRecipient =
                new IBinder.DeathRecipient() {
                    @Override
                    @BinderThread
                    public void binderDied() {
                        final StartingWindowController controller = mController;
                        controller.getRemoteCallExecutor().execute(() -> {
                            mListener = null;
                            controller.setStartingWindowListener(null);
                        });
                    }
                };

        public IStartingWindowImpl(StartingWindowController controller) {
            mController = controller;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        void invalidate() {
            mController = null;
        }

        @Override
        public void setStartingWindowListener(IStartingWindowListener listener) {
            executeRemoteCallWithTaskPermission(mController, "setStartingWindowListener",
                    (controller) -> {
                        if (mListener != null) {
                            // Reset the old death recipient
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
                        controller.setStartingWindowListener(mStartingWindowListener);
                    });
        }

        private void notifyIStartingWindowListener(int taskId, int supportedType) {
            if (mListener == null) {
                return;
            }

            try {
                mListener.onTaskLaunching(taskId, supportedType);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify task launching", e);
            }
        }
    }
}
