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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SNAPSHOT;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.StartingWindowInfo;
import android.window.TaskOrganizer;
import android.window.TaskSnapshot;

import androidx.annotation.BinderThread;

import com.android.internal.util.function.TriConsumer;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

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
    public static final boolean DEBUG_SPLASH_SCREEN = true;
    public static final boolean DEBUG_TASK_SNAPSHOT = false;

    private final StartingSurfaceDrawer mStartingSurfaceDrawer;
    private final StartingWindowTypeAlgorithm mStartingWindowTypeAlgorithm;

    private TriConsumer<Integer, Integer, Integer> mTaskLaunchingCallback;
    private final StartingSurfaceImpl mImpl = new StartingSurfaceImpl();
    private final Context mContext;
    private final ShellExecutor mSplashScreenExecutor;

    public StartingWindowController(Context context, ShellExecutor splashScreenExecutor,
            StartingWindowTypeAlgorithm startingWindowTypeAlgorithm, TransactionPool pool) {
        mContext = context;
        mStartingSurfaceDrawer = new StartingSurfaceDrawer(context, splashScreenExecutor, pool);
        mStartingWindowTypeAlgorithm = startingWindowTypeAlgorithm;
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

    /*
     * Registers the starting window listener.
     *
     * @param listener The callback when need a starting window.
     */
    void setStartingWindowListener(TriConsumer<Integer, Integer, Integer> listener) {
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
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "addStartingWindow");

            final int suggestionType = mStartingWindowTypeAlgorithm.getSuggestedWindowType(
                    windowInfo);
            final RunningTaskInfo runningTaskInfo = windowInfo.taskInfo;
            if (suggestionType == STARTING_WINDOW_TYPE_SPLASH_SCREEN) {
                mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, appToken,
                        false /* emptyView */);
            } else if (suggestionType == STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN) {
                mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, appToken,
                        true /* emptyView */);
            } else if (suggestionType == STARTING_WINDOW_TYPE_SNAPSHOT) {
                final TaskSnapshot snapshot = windowInfo.mTaskSnapshot;
                mStartingSurfaceDrawer.makeTaskSnapshotWindow(windowInfo, appToken,
                        snapshot);
            } else /* suggestionType == STARTING_WINDOW_TYPE_NONE */ {
                // Don't add a staring window.
            }
            if (mTaskLaunchingCallback != null && shouldSendToListener(suggestionType)) {
                int taskId = runningTaskInfo.taskId;
                int color = mStartingSurfaceDrawer.getStartingWindowBackgroundColorForTask(taskId);
                mTaskLaunchingCallback.accept(taskId, suggestionType, color);
            }

            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        });
    }

    public void copySplashScreenView(int taskId) {
        mSplashScreenExecutor.execute(() -> {
            mStartingSurfaceDrawer.copySplashScreenView(taskId);
        });
    }

    /**
     * @see StartingSurfaceDrawer#onAppSplashScreenViewRemoved(int)
     */
    public void onAppSplashScreenViewRemoved(int taskId) {
        mSplashScreenExecutor.execute(
                () -> mStartingSurfaceDrawer.onAppSplashScreenViewRemoved(taskId));
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
        private final TriConsumer<Integer, Integer, Integer> mStartingWindowListener =
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

        private void notifyIStartingWindowListener(int taskId, int supportedType,
                int startingWindowBackgroundColor) {
            if (mListener == null) {
                return;
            }

            try {
                mListener.onTaskLaunching(taskId, supportedType, startingWindowBackgroundColor);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify task launching", e);
            }
        }
    }
}
