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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
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
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost.SurfacePackage;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.infra.ServiceConnector.ServiceLifecycleCallbacks;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.TaskSystemBarsListener;
import com.android.server.wm.WindowManagerService;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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

                @Override
                public void onDisconnected(@NonNull IGameService service) {
                    try {
                        service.disconnected();
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to send disconnected event", ex);
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

        // TODO(b/204503192): Limit the lifespan of the game session in the Game Service provider
        // to only when the associated task is running. Right now it is possible for a task to
        // move into the background and for all associated processes to die and for the Game Session
        // provider's GameSessionService to continue to be running. Ideally we could unbind the
        // service when this happens.
    };

    private final IGameServiceController mGameServiceController =
            new IGameServiceController.Stub() {
                @Override
                @RequiresPermission(Manifest.permission.MANAGE_GAME_ACTIVITY)
                public void createGameSession(int taskId) {
                    mContext.enforceCallingPermission(Manifest.permission.MANAGE_GAME_ACTIVITY,
                            "createGameSession()");
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.createGameSession(taskId);
                    });
                }
            };

    private final IGameSessionController mGameSessionController =
            new IGameSessionController.Stub() {
                @Override
                public void takeScreenshot(int taskId,
                        @NonNull AndroidFuture gameScreenshotResultFuture) {
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.takeScreenshot(taskId,
                                gameScreenshotResultFuture);
                    });
                }

                @Override
                @RequiresPermission(Manifest.permission.MANAGE_GAME_ACTIVITY)
                public void restartGame(int taskId) {
                    mContext.enforceCallingPermission(Manifest.permission.MANAGE_GAME_ACTIVITY,
                            "restartGame()");
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.restartGame(taskId);
                    });
                }
            };

    private final Object mLock = new Object();
    private final UserHandle mUserHandle;
    private final Executor mBackgroundExecutor;
    private final Context mContext;
    private final GameClassifier mGameClassifier;
    private final IActivityManager mActivityManager;
    private final IActivityTaskManager mActivityTaskManager;
    private final WindowManagerService mWindowManagerService;
    private final WindowManagerInternal mWindowManagerInternal;
    private final ServiceConnector<IGameService> mGameServiceConnector;
    private final ServiceConnector<IGameSessionService> mGameSessionServiceConnector;

    @GuardedBy("mLock")
    private final ConcurrentHashMap<Integer, GameSessionRecord> mGameSessions =
            new ConcurrentHashMap<>();
    @GuardedBy("mLock")
    private volatile boolean mIsRunning;

    GameServiceProviderInstanceImpl(
            @NonNull UserHandle userHandle,
            @NonNull Executor backgroundExecutor,
            @NonNull Context context,
            @NonNull GameClassifier gameClassifier,
            @NonNull IActivityManager activityManager,
            @NonNull IActivityTaskManager activityTaskManager,
            @NonNull WindowManagerService windowManagerService,
            @NonNull WindowManagerInternal windowManagerInternal,
            @NonNull ServiceConnector<IGameService> gameServiceConnector,
            @NonNull ServiceConnector<IGameSessionService> gameSessionServiceConnector) {
        mUserHandle = userHandle;
        mBackgroundExecutor = backgroundExecutor;
        mContext = context;
        mGameClassifier = gameClassifier;
        mActivityManager = activityManager;
        mActivityTaskManager = activityTaskManager;
        mWindowManagerService = windowManagerService;
        mWindowManagerInternal = windowManagerInternal;
        mGameServiceConnector = gameServiceConnector;
        mGameSessionServiceConnector = gameSessionServiceConnector;
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

        mWindowManagerInternal.registerTaskSystemBarsListener(mTaskSystemBarsVisibilityListener);
    }

    @GuardedBy("mLock")
    private void stopLocked() {
        if (!mIsRunning) {
            return;
        }
        mIsRunning = false;

        try {
            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to unregister task stack listener", e);
        }

        mWindowManagerInternal.unregisterTaskSystemBarsListener(
                mTaskSystemBarsVisibilityListener);

        destroyAndClearAllGameSessionsLocked();

        mGameServiceConnector.unbind();
        mGameSessionServiceConnector.unbind();

        mGameServiceConnector.setServiceLifecycleCallbacks(null);
        mGameSessionServiceConnector.setServiceLifecycleCallbacks(null);

    }

    private void onTaskCreated(int taskId, @NonNull ComponentName componentName) {
        String packageName = componentName.getPackageName();
        if (!mGameClassifier.isGame(packageName, mUserHandle)) {
            return;
        }

        synchronized (mLock) {
            gameTaskStartedLocked(taskId, componentName);
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
        if (gameSessionRecord == null || gameSessionRecord.getGameSession() == null) {
            return;
        }

        try {
            gameSessionRecord.getGameSession().onTaskFocusChanged(focused);
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to notify session of task focus change: " + gameSessionRecord);
        }
    }

    @GuardedBy("mLock")
    private void gameTaskStartedLocked(int taskId, @NonNull ComponentName componentName) {
        if (DEBUG) {
            Slog.i(TAG, "gameStartedLocked() id: " + taskId + " component: " + componentName);
        }

        if (!mIsRunning) {
            return;
        }

        GameSessionRecord existingGameSessionRecord = mGameSessions.get(taskId);
        if (existingGameSessionRecord != null) {
            Slog.w(TAG, "Existing game session found for task (id: " + taskId
                    + ") creation. Ignoring.");
            return;
        }

        GameSessionRecord gameSessionRecord = GameSessionRecord.awaitingGameSessionRequest(
                taskId, componentName);
        mGameSessions.put(taskId, gameSessionRecord);

        AndroidFuture<Void> unusedPostGameStartedFuture = mGameServiceConnector.post(
                gameService -> {
                    gameService.gameStarted(
                            new GameStartedEvent(taskId, componentName.getPackageName()));
                });
    }

    private void onTaskRemoved(int taskId) {
        synchronized (mLock) {
            boolean isTaskAssociatedWithGameSession = mGameSessions.containsKey(taskId);
            if (!isTaskAssociatedWithGameSession) {
                return;
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

    @Nullable
    private GameSessionViewHostConfiguration createViewHostConfigurationForTask(int taskId) {
        RunningTaskInfo runningTaskInfo = getRunningTaskInfoForTask(taskId);
        if (runningTaskInfo == null) {
            return null;
        }

        Rect bounds = runningTaskInfo.configuration.windowConfiguration.getBounds();
        return new GameSessionViewHostConfiguration(
                runningTaskInfo.displayId,
                bounds.width(),
                bounds.height());
    }

    @Nullable
    private RunningTaskInfo getRunningTaskInfoForTask(int taskId) {
        List<RunningTaskInfo> runningTaskInfos;
        try {
            runningTaskInfos = mActivityTaskManager.getTasks(
                    /* maxNum= */ Integer.MAX_VALUE,
                    /* filterOnlyVisibleRecents= */ true,
                    /* keepIntentExtra= */ false);
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to fetch running tasks");
            return null;
        }

        for (RunningTaskInfo taskInfo : runningTaskInfos) {
            if (taskInfo.taskId == taskId) {
                return taskInfo;
            }
        }

        return null;
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
                callback.complete(GameScreenshotResult.createSuccessResult(bitmap));
            }
        });
    }

    private void restartGame(int taskId) {
        String packageName;

        synchronized (mLock) {
            boolean isTaskAssociatedWithGameSession = mGameSessions.containsKey(taskId);
            if (!isTaskAssociatedWithGameSession) {
                return;
            }

            packageName = mGameSessions.get(taskId).getComponentName().getPackageName();
        }

        try {
            mActivityManager.forceStopPackage(packageName, UserHandle.USER_CURRENT);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        Intent launchIntent =
                mContext.getPackageManager().getLaunchIntentForPackage(packageName);
        mContext.startActivity(launchIntent);
    }
}
