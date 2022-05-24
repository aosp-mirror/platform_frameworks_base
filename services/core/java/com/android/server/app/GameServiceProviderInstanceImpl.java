/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import static android.Manifest.permission.MANAGE_GAME_ACTIVITY;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IProcessObserver;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.games.CreateGameSessionRequest;
import android.service.games.CreateGameSessionResult;
import android.service.games.GameScreenshotResult;
import android.service.games.GameSessionViewHostConfiguration;
import android.service.games.GameStartedEvent;
import android.service.games.IGameService;
import android.service.games.IGameServiceController;
import android.service.games.IGameSession;
import android.service.games.IGameSessionController;
import android.service.games.IGameSessionService;
import android.text.TextUtils;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.WindowManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.infra.ServiceConnector.ServiceLifecycleCallbacks;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ScreenshotHelper;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.TaskSystemBarsListener;
import com.android.server.wm.WindowManagerService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class GameServiceProviderInstanceImpl implements GameServiceProviderInstance {
    private static final String TAG = "GameServiceProviderInstance";
    private static final int CREATE_GAME_SESSION_TIMEOUT_MS = 10_000;
    private static final boolean DEBUG = false;

    private final ServiceLifecycleCallbacks<IGameService> mGameServiceLifecycleCallbacks =
            new ServiceLifecycleCallbacks<IGameService>() {
                @Override
                public void onConnected(@NonNull IGameService service) {
                    try {
                        service.connected(mGameServiceController);
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to send connected event", ex);
                    }
                }
            };

    private final ServiceLifecycleCallbacks<IGameSessionService>
            mGameSessionServiceLifecycleCallbacks =
            new ServiceLifecycleCallbacks<IGameSessionService>() {
                @Override
                public void onBinderDied() {
                    mBackgroundExecutor.execute(() -> {
                        synchronized (mLock) {
                            if (DEBUG) {
                                Slog.d(TAG, "GameSessionService died. Destroying all sessions");
                            }
                            destroyAndClearAllGameSessionsLocked();
                        }
                    });
                }
            };

    private final TaskSystemBarsListener mTaskSystemBarsVisibilityListener =
            new TaskSystemBarsListener() {
                @Override
                public void onTransientSystemBarsVisibilityChanged(
                        int taskId,
                        boolean visible,
                        boolean wereRevealedFromSwipeOnSystemBar) {
                    GameServiceProviderInstanceImpl.this.onTransientSystemBarsVisibilityChanged(
                            taskId, visible, wereRevealedFromSwipeOnSystemBar);
                }
            };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
            if (componentName == null) {
                return;
            }

            mBackgroundExecutor.execute(() -> {
                GameServiceProviderInstanceImpl.this.onTaskCreated(taskId, componentName);
            });
        }

        @Override
        public void onTaskRemoved(int taskId) throws RemoteException {
            mBackgroundExecutor.execute(() -> {
                GameServiceProviderInstanceImpl.this.onTaskRemoved(taskId);
            });
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            mBackgroundExecutor.execute(() -> {
                GameServiceProviderInstanceImpl.this.onTaskFocusChanged(taskId, focused);
            });
        }
    };

    /**
     * The TaskStackListener declared above gives us good visibility into game task lifecycle.
     * However, it is possible for the Android system to kill all the processes associated with a
     * game task (e.g., when the system is under memory pressure or reaches a background process
     * limit). When this happens, the game task remains (and no TaskStackListener callbacks are
     * invoked), but we would nonetheless want to destroy a game session associated with the task
     * if this were to happen.
     *
     * This process observer gives us visibility into process lifecycles and lets us track all the
     * processes associated with each package so that any game sessions associated with the package
     * are destroyed if the process count for a given package reaches zero (most packages will
     * have at most one task). If processes for a given package are started up again, the destroyed
     * game sessions will be re-created.
     */
    private final IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
            // This callback is used to track how many processes are running for a given package.
            // Then, when a process dies, we will know if it was the only process running for that
            // package and the associated game sessions should be destroyed.
            mBackgroundExecutor.execute(() -> {
                GameServiceProviderInstanceImpl.this.onForegroundActivitiesChanged(pid);
            });
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            mBackgroundExecutor.execute(() -> {
                GameServiceProviderInstanceImpl.this.onProcessDied(pid);
            });
        }

        @Override
        public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) {
        }
    };

    private final IGameServiceController mGameServiceController =
            new IGameServiceController.Stub() {
                @Override
                @EnforcePermission(MANAGE_GAME_ACTIVITY)
                public void createGameSession(int taskId) {
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.createGameSession(taskId);
                    });
                }
            };

    private final IGameSessionController mGameSessionController =
            new IGameSessionController.Stub() {
                @Override
                @EnforcePermission(MANAGE_GAME_ACTIVITY)
                public void takeScreenshot(int taskId,
                        @NonNull AndroidFuture gameScreenshotResultFuture) {
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.takeScreenshot(taskId,
                                gameScreenshotResultFuture);
                    });
                }

                @Override
                @EnforcePermission(MANAGE_GAME_ACTIVITY)
                public void restartGame(int taskId) {
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.restartGame(taskId);
                    });
                }
            };

    private final Object mLock = new Object();
    private final UserHandle mUserHandle;
    private final Executor mBackgroundExecutor;
    private final Context mContext;
    private final GameTaskInfoProvider mGameTaskInfoProvider;
    private final IActivityManager mActivityManager;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final IActivityTaskManager mActivityTaskManager;
    private final WindowManagerService mWindowManagerService;
    private final WindowManagerInternal mWindowManagerInternal;
    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private final ScreenshotHelper mScreenshotHelper;
    private final ServiceConnector<IGameService> mGameServiceConnector;
    private final ServiceConnector<IGameSessionService> mGameSessionServiceConnector;

    @GuardedBy("mLock")
    private final ConcurrentHashMap<Integer, GameSessionRecord> mGameSessions =
            new ConcurrentHashMap<>();
    @GuardedBy("mLock")
    private final ConcurrentHashMap<Integer, String> mPidToPackageMap = new ConcurrentHashMap<>();
    @GuardedBy("mLock")
    private final ConcurrentHashMap<String, Integer> mPackageNameToProcessCountMap =
            new ConcurrentHashMap<>();

    @GuardedBy("mLock")
    private volatile boolean mIsRunning;

    GameServiceProviderInstanceImpl(
            @NonNull UserHandle userHandle,
            @NonNull Executor backgroundExecutor,
            @NonNull Context context,
            @NonNull GameTaskInfoProvider gameTaskInfoProvider,
            @NonNull IActivityManager activityManager,
            @NonNull ActivityManagerInternal activityManagerInternal,
            @NonNull IActivityTaskManager activityTaskManager,
            @NonNull WindowManagerService windowManagerService,
            @NonNull WindowManagerInternal windowManagerInternal,
            @NonNull ActivityTaskManagerInternal activityTaskManagerInternal,
            @NonNull ServiceConnector<IGameService> gameServiceConnector,
            @NonNull ServiceConnector<IGameSessionService> gameSessionServiceConnector,
            @NonNull ScreenshotHelper screenshotHelper) {
        mUserHandle = userHandle;
        mBackgroundExecutor = backgroundExecutor;
        mContext = context;
        mGameTaskInfoProvider = gameTaskInfoProvider;
        mActivityManager = activityManager;
        mActivityManagerInternal = activityManagerInternal;
        mActivityTaskManager = activityTaskManager;
        mWindowManagerService = windowManagerService;
        mWindowManagerInternal = windowManagerInternal;
        mActivityTaskManagerInternal = activityTaskManagerInternal;
        mGameServiceConnector = gameServiceConnector;
        mGameSessionServiceConnector = gameSessionServiceConnector;
        mScreenshotHelper = screenshotHelper;
    }

    @Override
    public void start() {
        synchronized (mLock) {
            startLocked();
        }
    }

    @Override
    public void stop() {
        synchronized (mLock) {
            stopLocked();
        }
    }

    @GuardedBy("mLock")
    private void startLocked() {
        if (mIsRunning) {
            return;
        }
        mIsRunning = true;

        mGameServiceConnector.setServiceLifecycleCallbacks(mGameServiceLifecycleCallbacks);
        mGameSessionServiceConnector.setServiceLifecycleCallbacks(
                mGameSessionServiceLifecycleCallbacks);

        AndroidFuture<?> unusedConnectFuture = mGameServiceConnector.connect();

        try {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to register task stack listener", e);
        }

        try {
            mActivityManager.registerProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to register process observer", e);
        }

        mWindowManagerInternal.registerTaskSystemBarsListener(mTaskSystemBarsVisibilityListener);
    }

    @GuardedBy("mLock")
    private void stopLocked() {
        if (!mIsRunning) {
            return;
        }
        mIsRunning = false;

        try {
            mActivityManager.unregisterProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to unregister process observer", e);
        }

        try {
            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to unregister task stack listener", e);
        }

        mWindowManagerInternal.unregisterTaskSystemBarsListener(
                mTaskSystemBarsVisibilityListener);

        destroyAndClearAllGameSessionsLocked();

        mGameServiceConnector.post(IGameService::disconnected).whenComplete((result, t) -> {
            mGameServiceConnector.unbind();
        });
        mGameSessionServiceConnector.unbind();

        mGameServiceConnector.setServiceLifecycleCallbacks(null);
        mGameSessionServiceConnector.setServiceLifecycleCallbacks(null);

    }

    private void onTaskCreated(int taskId, @NonNull ComponentName componentName) {
        final GameTaskInfo taskInfo = mGameTaskInfoProvider.get(taskId, componentName);

        if (!taskInfo.mIsGameTask) {
            return;
        }

        synchronized (mLock) {
            gameTaskStartedLocked(taskInfo);
        }
    }

    private void onTaskFocusChanged(int taskId, boolean focused) {
        synchronized (mLock) {
            onTaskFocusChangedLocked(taskId, focused);
        }
    }

    @GuardedBy("mLock")
    private void onTaskFocusChangedLocked(int taskId, boolean focused) {
        if (DEBUG) {
            Slog.d(TAG, "onTaskFocusChangedLocked() id: " + taskId + " focused: " + focused);
        }

        final GameSessionRecord gameSessionRecord = mGameSessions.get(taskId);
        if (gameSessionRecord == null) {
            if (focused) {
                // The game session for a game task may have been destroyed when the game task
                // was put into the background by pressing the back button. If the task is restored
                // via the Recents UI there will be no TaskStackListener#onCreated call for the
                // restoration, so this focus event is the first opportunity to re-create the game
                // session.
                maybeCreateGameSessionForFocusedTaskLocked(taskId);
            }
            return;
        } else if (gameSessionRecord.getGameSession() == null) {
            return;
        }

        try {
            gameSessionRecord.getGameSession().onTaskFocusChanged(focused);
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to notify session of task focus change: " + gameSessionRecord);
        }
    }

    @GuardedBy("mLock")
    private void maybeCreateGameSessionForFocusedTaskLocked(int taskId) {
        if (DEBUG) {
            Slog.d(TAG, "maybeRecreateGameSessionForFocusedTaskLocked() id: " + taskId);
        }

        final GameTaskInfo taskInfo = mGameTaskInfoProvider.get(taskId);
        if (taskInfo == null) {
            Slog.w(TAG, "No task info for focused task: " + taskId);
            return;
        }

        if (!taskInfo.mIsGameTask) {
            return;
        }

        gameTaskStartedLocked(taskInfo);
    }

    @GuardedBy("mLock")
    private void gameTaskStartedLocked(@NonNull GameTaskInfo gameTaskInfo) {
        if (DEBUG) {
            Slog.i(TAG, "gameStartedLocked(): " + gameTaskInfo);
        }

        if (!mIsRunning) {
            return;
        }

        GameSessionRecord existingGameSessionRecord = mGameSessions.get(gameTaskInfo.mTaskId);
        if (existingGameSessionRecord != null) {
            Slog.w(TAG, "Existing game session found for task (id: " + gameTaskInfo.mTaskId
                    + ") creation. Ignoring.");
            return;
        }

        GameSessionRecord gameSessionRecord = GameSessionRecord.awaitingGameSessionRequest(
                gameTaskInfo.mTaskId, gameTaskInfo.mComponentName);
        mGameSessions.put(gameTaskInfo.mTaskId, gameSessionRecord);

        AndroidFuture<Void> unusedPostGameStartedFuture = mGameServiceConnector.post(
                gameService -> {
                    gameService.gameStarted(
                            new GameStartedEvent(gameTaskInfo.mTaskId,
                                    gameTaskInfo.mComponentName.getPackageName()));
                });
    }

    private void onTaskRemoved(int taskId) {
        synchronized (mLock) {
            boolean isTaskAssociatedWithGameSession = mGameSessions.containsKey(taskId);
            if (!isTaskAssociatedWithGameSession) {
                return;
            }


            if (DEBUG) {
                Slog.i(TAG, "onTaskRemoved() id: " + taskId);
            }

            removeAndDestroyGameSessionIfNecessaryLocked(taskId);
        }
    }

    private void onTransientSystemBarsVisibilityChanged(
            int taskId,
            boolean visible,
            boolean wereRevealedFromSwipeOnSystemBar) {
        if (visible && !wereRevealedFromSwipeOnSystemBar) {
            return;
        }

        GameSessionRecord gameSessionRecord;
        synchronized (mLock) {
            gameSessionRecord = mGameSessions.get(taskId);
        }

        if (gameSessionRecord == null) {
            return;
        }

        IGameSession gameSession = gameSessionRecord.getGameSession();
        if (gameSession == null) {
            return;
        }

        try {
            gameSession.onTransientSystemBarVisibilityFromRevealGestureChanged(visible);
        } catch (RemoteException ex) {
            Slog.w(TAG,
                    "Failed to send transient system bars visibility from reveal gesture for task: "
                            + taskId);
        }
    }

    private void createGameSession(int taskId) {
        synchronized (mLock) {
            createGameSessionLocked(taskId);
        }
    }

    @GuardedBy("mLock")
    private void createGameSessionLocked(int taskId) {
        if (DEBUG) {
            Slog.i(TAG, "createGameSessionLocked() id: " + taskId);
        }

        if (!mIsRunning) {
            return;
        }

        GameSessionRecord existingGameSessionRecord = mGameSessions.get(taskId);
        if (existingGameSessionRecord == null) {
            Slog.w(TAG, "No existing game session record found for task (id: " + taskId
                    + ") creation. Ignoring.");
            return;
        }
        if (!existingGameSessionRecord.isAwaitingGameSessionRequest()) {
            Slog.w(TAG, "Existing game session for task (id: " + taskId
                    + ") is not awaiting game session request. Ignoring.");
            return;
        }

        GameSessionViewHostConfiguration gameSessionViewHostConfiguration =
                createViewHostConfigurationForTask(taskId);
        if (gameSessionViewHostConfiguration == null) {
            Slog.w(TAG, "Failed to create view host configuration for task (id" + taskId
                    + ") creation. Ignoring.");
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Determined initial view host configuration for task (id: " + taskId + "): "
                    + gameSessionViewHostConfiguration);
        }

        mGameSessions.put(taskId, existingGameSessionRecord.withGameSessionRequested());

        AndroidFuture<CreateGameSessionResult> createGameSessionResultFuture =
                new AndroidFuture<CreateGameSessionResult>()
                        .orTimeout(CREATE_GAME_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .whenCompleteAsync((createGameSessionResult, exception) -> {
                            if (exception != null || createGameSessionResult == null) {
                                Slog.w(TAG, "Failed to create GameSession: "
                                                + existingGameSessionRecord,
                                        exception);
                                synchronized (mLock) {
                                    removeAndDestroyGameSessionIfNecessaryLocked(taskId);
                                }
                                return;
                            }

                            synchronized (mLock) {
                                attachGameSessionLocked(taskId, createGameSessionResult);
                            }

                            // The TaskStackListener may have made its task focused call for the
                            // game session's task before the game session was created, so check if
                            // the task is already focused so that the game session can be notified.
                            setGameSessionFocusedIfNecessary(taskId,
                                    createGameSessionResult.getGameSession());
                        }, mBackgroundExecutor);

        AndroidFuture<Void> unusedPostCreateGameSessionFuture =
                mGameSessionServiceConnector.post(gameSessionService -> {
                    CreateGameSessionRequest createGameSessionRequest =
                            new CreateGameSessionRequest(
                                    taskId,
                                    existingGameSessionRecord.getComponentName().getPackageName());
                    gameSessionService.create(
                            mGameSessionController,
                            createGameSessionRequest,
                            gameSessionViewHostConfiguration,
                            createGameSessionResultFuture);
                });
    }

    private void setGameSessionFocusedIfNecessary(int taskId, IGameSession gameSession) {
        try {
            final ActivityTaskManager.RootTaskInfo rootTaskInfo =
                    mActivityTaskManager.getFocusedRootTaskInfo();
            if (rootTaskInfo != null && rootTaskInfo.taskId == taskId) {
                gameSession.onTaskFocusChanged(true);
            }
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to set task focused for ID: " + taskId);
        }
    }

    @GuardedBy("mLock")
    private void attachGameSessionLocked(
            int taskId,
            @NonNull CreateGameSessionResult createGameSessionResult) {
        if (DEBUG) {
            Slog.d(TAG, "attachGameSession() id: " + taskId);
        }

        GameSessionRecord gameSessionRecord = mGameSessions.get(taskId);

        if (gameSessionRecord == null) {
            Slog.w(TAG, "No associated game session record. Destroying id: " + taskId);
            destroyGameSessionDuringAttach(taskId, createGameSessionResult);
            return;
        }

        if (!gameSessionRecord.isGameSessionRequested()) {
            destroyGameSessionDuringAttach(taskId, createGameSessionResult);
            return;
        }

        try {
            mWindowManagerInternal.addTrustedTaskOverlay(
                    taskId,
                    createGameSessionResult.getSurfacePackage());
        } catch (IllegalArgumentException ex) {
            Slog.w(TAG, "Failed to add task overlay. Destroying id: " + taskId);
            destroyGameSessionDuringAttach(taskId, createGameSessionResult);
            return;
        }

        mGameSessions.put(taskId,
                gameSessionRecord.withGameSession(
                        createGameSessionResult.getGameSession(),
                        createGameSessionResult.getSurfacePackage()));
    }

    @GuardedBy("mLock")
    private void destroyAndClearAllGameSessionsLocked() {
        for (GameSessionRecord gameSessionRecord : mGameSessions.values()) {
            destroyGameSessionFromRecordLocked(gameSessionRecord);
        }
        mGameSessions.clear();
    }

    private void destroyGameSessionDuringAttach(
            int taskId,
            CreateGameSessionResult createGameSessionResult) {
        try {
            createGameSessionResult.getGameSession().onDestroyed();
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to destroy session: " + taskId);
        }
    }

    @GuardedBy("mLock")
    private void removeAndDestroyGameSessionIfNecessaryLocked(int taskId) {
        if (DEBUG) {
            Slog.d(TAG, "destroyGameSession() id: " + taskId);
        }

        GameSessionRecord gameSessionRecord = mGameSessions.remove(taskId);
        if (gameSessionRecord == null) {
            if (DEBUG) {
                Slog.w(TAG, "No game session found for id: " + taskId);
            }
            return;
        }
        destroyGameSessionFromRecordLocked(gameSessionRecord);
    }

    @GuardedBy("mLock")
    private void destroyGameSessionFromRecordLocked(@NonNull GameSessionRecord gameSessionRecord) {
        SurfacePackage surfacePackage = gameSessionRecord.getSurfacePackage();
        if (surfacePackage != null) {
            try {
                mWindowManagerInternal.removeTrustedTaskOverlay(
                        gameSessionRecord.getTaskId(),
                        surfacePackage);
            } catch (IllegalArgumentException ex) {
                Slog.i(TAG,
                        "Failed to remove task overlay. This is expected if the task is already "
                                + "destroyed: "
                                + gameSessionRecord);
            }
        }

        IGameSession gameSession = gameSessionRecord.getGameSession();
        if (gameSession != null) {
            try {
                gameSession.onDestroyed();
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to destroy session: " + gameSessionRecord, ex);
            }
        }

        if (mGameSessions.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "No active game sessions. Disconnecting GameSessionService");
            }

            mGameSessionServiceConnector.unbind();
        }
    }

    private void onForegroundActivitiesChanged(int pid) {
        synchronized (mLock) {
            onForegroundActivitiesChangedLocked(pid);
        }
    }

    @GuardedBy("mLock")
    private void onForegroundActivitiesChangedLocked(int pid) {
        if (mPidToPackageMap.containsKey(pid)) {
            // We are already tracking this pid, nothing to do.
            return;
        }

        final String packageName = mActivityManagerInternal.getPackageNameByPid(pid);
        if (TextUtils.isEmpty(packageName)) {
            // Game processes should always have a package name.
            return;
        }

        if (!gameSessionExistsForPackageNameLocked(packageName)) {
            // We only need to track processes for tasks with game session records.
            return;
        }

        mPidToPackageMap.put(pid, packageName);
        final int processCountForPackage = mPackageNameToProcessCountMap.getOrDefault(packageName,
                0) + 1;
        mPackageNameToProcessCountMap.put(packageName, processCountForPackage);

        if (DEBUG) {
            Slog.d(TAG, "onForegroundActivitiesChangedLocked: tracking pid " + pid + ", for "
                    + packageName + ". Process count for package: " + processCountForPackage);
        }

        // If there are processes for the package, we may need to re-create game sessions
        // that are associated with the package
        if (processCountForPackage > 0) {
            recreateEndedGameSessionsLocked(packageName);
        }
    }

    @GuardedBy("mLock")
    private void recreateEndedGameSessionsLocked(String packageName) {
        for (GameSessionRecord gameSessionRecord : mGameSessions.values()) {
            if (gameSessionRecord.isGameSessionEndedForProcessDeath() && packageName.equals(
                    gameSessionRecord.getComponentName().getPackageName())) {
                if (DEBUG) {
                    Slog.d(TAG,
                            "recreateGameSessionsLocked(): re-creating game session for: "
                                    + packageName + " with taskId: "
                                    + gameSessionRecord.getTaskId());
                }

                final int taskId = gameSessionRecord.getTaskId();
                mGameSessions.put(taskId, GameSessionRecord.awaitingGameSessionRequest(taskId,
                        gameSessionRecord.getComponentName()));
                createGameSessionLocked(gameSessionRecord.getTaskId());
            }
        }
    }

    private void onProcessDied(int pid) {
        synchronized (mLock) {
            onProcessDiedLocked(pid);
        }
    }

    @GuardedBy("mLock")
    private void onProcessDiedLocked(int pid) {
        final String packageName = mPidToPackageMap.remove(pid);
        if (packageName == null) {
            // We weren't tracking this process.
            return;
        }

        final Integer oldProcessCountForPackage = mPackageNameToProcessCountMap.get(packageName);
        if (oldProcessCountForPackage == null) {
            // This should never happen; we should have a process count for all tracked packages.
            Slog.w(TAG, "onProcessDiedLocked(): Missing process count for package");
            return;
        }

        final int processCountForPackage = oldProcessCountForPackage - 1;
        mPackageNameToProcessCountMap.put(packageName, processCountForPackage);

        // If there are no more processes for the game, then we will terminate any game sessions
        // running for the package.
        if (processCountForPackage <= 0) {
            endGameSessionsForPackageLocked(packageName);
        }
    }

    @GuardedBy("mLock")
    private void endGameSessionsForPackageLocked(String packageName) {
        for (GameSessionRecord gameSessionRecord : mGameSessions.values()) {
            if (gameSessionRecord.getGameSession() != null && packageName.equals(
                    gameSessionRecord.getComponentName().getPackageName())) {
                if (DEBUG) {
                    Slog.i(TAG, "endGameSessionsForPackageLocked(): No more processes for "
                            + packageName + ", ending game session with taskId: "
                            + gameSessionRecord.getTaskId());
                }

                RunningTaskInfo runningTaskInfo =
                        mGameTaskInfoProvider.getRunningTaskInfo(gameSessionRecord.getTaskId());
                if (runningTaskInfo != null && (runningTaskInfo.isVisible)) {
                    if (DEBUG) {
                        Slog.i(TAG, "Found visible task. Ignoring end game session. taskId:"
                                + gameSessionRecord.getTaskId());
                    }
                    continue;
                }
                mGameSessions.put(gameSessionRecord.getTaskId(),
                        gameSessionRecord.withGameSessionEndedOnProcessDeath());
                destroyGameSessionFromRecordLocked(gameSessionRecord);
            }
        }
    }

    @GuardedBy("mLock")
    private boolean gameSessionExistsForPackageNameLocked(String packageName) {
        for (GameSessionRecord gameSessionRecord : mGameSessions.values()) {
            if (packageName.equals(gameSessionRecord.getComponentName().getPackageName())) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private GameSessionViewHostConfiguration createViewHostConfigurationForTask(int taskId) {
        RunningTaskInfo runningTaskInfo = mGameTaskInfoProvider.getRunningTaskInfo(taskId);
        if (runningTaskInfo == null) {
            return null;
        }

        Rect bounds = runningTaskInfo.configuration.windowConfiguration.getBounds();
        return new GameSessionViewHostConfiguration(
                runningTaskInfo.displayId,
                bounds.width(),
                bounds.height());
    }

    @VisibleForTesting
    void takeScreenshot(int taskId, @NonNull AndroidFuture callback) {
        GameSessionRecord gameSessionRecord;
        synchronized (mLock) {
            gameSessionRecord = mGameSessions.get(taskId);
            if (gameSessionRecord == null) {
                Slog.w(TAG, "No game session found for id: " + taskId);
                callback.complete(GameScreenshotResult.createInternalErrorResult());
                return;
            }
        }

        final SurfacePackage overlaySurfacePackage = gameSessionRecord.getSurfacePackage();
        final SurfaceControl overlaySurfaceControl =
                overlaySurfacePackage != null ? overlaySurfacePackage.getSurfaceControl() : null;
        mBackgroundExecutor.execute(() -> {
            final SurfaceControl.LayerCaptureArgs.Builder layerCaptureArgsBuilder =
                    new SurfaceControl.LayerCaptureArgs.Builder(/* layer */ null);
            if (overlaySurfaceControl != null) {
                SurfaceControl[] excludeLayers = new SurfaceControl[1];
                excludeLayers[0] = overlaySurfaceControl;
                layerCaptureArgsBuilder.setExcludeLayers(excludeLayers);
            }
            final Bitmap bitmap = mWindowManagerService.captureTaskBitmap(taskId,
                    layerCaptureArgsBuilder);
            if (bitmap == null) {
                Slog.w(TAG, "Could not get bitmap for id: " + taskId);
                callback.complete(GameScreenshotResult.createInternalErrorResult());
            } else {
                final Bundle bundle = ScreenshotHelper.HardwareBitmapBundler.hardwareBitmapToBundle(
                        bitmap);
                final RunningTaskInfo runningTaskInfo =
                        mGameTaskInfoProvider.getRunningTaskInfo(taskId);
                if (runningTaskInfo == null) {
                    Slog.w(TAG, "Could not get running task info for id: " + taskId);
                    callback.complete(GameScreenshotResult.createInternalErrorResult());
                }
                final Rect crop = runningTaskInfo.configuration.windowConfiguration.getBounds();
                final Consumer<Uri> completionConsumer = (uri) -> {
                    if (uri == null) {
                        callback.complete(GameScreenshotResult.createInternalErrorResult());
                    } else {
                        callback.complete(GameScreenshotResult.createSuccessResult());
                    }
                };
                mScreenshotHelper.provideScreenshot(bundle, crop, Insets.NONE, taskId,
                        mUserHandle.getIdentifier(), gameSessionRecord.getComponentName(),
                        WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                        BackgroundThread.getHandler(),
                        completionConsumer);
            }
        });
    }

    private void restartGame(int taskId) {
        String packageName;
        synchronized (mLock) {
            GameSessionRecord gameSessionRecord = mGameSessions.get(taskId);
            if (gameSessionRecord == null) {
                return;
            }
            packageName = gameSessionRecord.getComponentName().getPackageName();
        }

        if (packageName == null) {
            return;
        }

        mActivityTaskManagerInternal.restartTaskActivityProcessIfVisible(taskId, packageName);
    }
}
