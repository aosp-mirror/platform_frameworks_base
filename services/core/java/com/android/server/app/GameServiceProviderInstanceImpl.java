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

import android.annotation.NonNull;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.games.CreateGameSessionRequest;
import android.service.games.GameStartedEvent;
import android.service.games.IGameService;
import android.service.games.IGameServiceController;
import android.service.games.IGameSession;
import android.service.games.IGameSessionService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

final class GameServiceProviderInstanceImpl implements GameServiceProviderInstance {
    private static final String TAG = "GameServiceProviderInstance";
    private static final int CREATE_GAME_SESSION_TIMEOUT_MS = 10_000;
    private static final boolean DEBUG = false;

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
    };

    private final IGameServiceController mGameServiceController =
            new IGameServiceController.Stub() {
                @Override
                public void createGameSession(int taskId) {
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.createGameSession(taskId);
                    });
                }
            };

    private final Object mLock = new Object();
    private final UserHandle mUserHandle;
    private final Executor mBackgroundExecutor;
    private final GameClassifier mGameClassifier;
    private final IActivityTaskManager mActivityTaskManager;
    private final ServiceConnector<IGameService> mGameServiceConnector;
    private final ServiceConnector<IGameSessionService> mGameSessionServiceConnector;

    @GuardedBy("mLock")
    private final ConcurrentHashMap<Integer, GameSessionRecord> mGameSessions =
            new ConcurrentHashMap<>();
    @GuardedBy("mLock")
    private volatile boolean mIsRunning;

    GameServiceProviderInstanceImpl(
            UserHandle userHandle,
            @NonNull Executor backgroundExecutor,
            @NonNull GameClassifier gameClassifier,
            @NonNull IActivityTaskManager activityTaskManager,
            @NonNull ServiceConnector<IGameService> gameServiceConnector,
            @NonNull ServiceConnector<IGameSessionService> gameSessionServiceConnector) {
        mUserHandle = userHandle;
        mBackgroundExecutor = backgroundExecutor;
        mGameClassifier = gameClassifier;
        mActivityTaskManager = activityTaskManager;
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

        // TODO(b/204503192): In cases where the connection to the game service fails retry with
        //  back off mechanism.
        AndroidFuture<Void> unusedPostConnectedFuture = mGameServiceConnector.post(gameService -> {
            gameService.connected(mGameServiceController);
        });

        try {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to register task stack listener", e);
        }
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

        for (GameSessionRecord gameSessionRecord : mGameSessions.values()) {
            IGameSession gameSession = gameSessionRecord.getGameSession();
            if (gameSession == null) {
                continue;
            }

            try {
                gameSession.destroy();
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to destroy session: " + gameSessionRecord, ex);
            }
        }
        mGameSessions.clear();

        // TODO(b/204503192): It is possible that the game service is disconnected. In this
        //  case we should avoid rebinding just to shut it down again.
        AndroidFuture<Void> unusedPostDisconnectedFuture =
                mGameServiceConnector.post(gameService -> {
                    gameService.disconnected();
                });
        mGameServiceConnector.unbind();
        mGameSessionServiceConnector.unbind();
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

    @GuardedBy("mLock")
    private void gameTaskStartedLocked(int sessionId, @NonNull ComponentName componentName) {
        if (DEBUG) {
            Slog.i(TAG, "gameStartedLocked() id: " + sessionId + " component: " + componentName);
        }

        if (!mIsRunning) {
            return;
        }

        GameSessionRecord existingGameSessionRecord = mGameSessions.get(sessionId);
        if (existingGameSessionRecord != null) {
            Slog.w(TAG, "Existing game session found for task (id: " + sessionId
                    + ") creation. Ignoring.");
            return;
        }

        GameSessionRecord gameSessionRecord = GameSessionRecord.awaitingGameSessionRequest(
                sessionId, componentName);
        mGameSessions.put(sessionId, gameSessionRecord);

        AndroidFuture<Void> unusedPostGameStartedFuture = mGameServiceConnector.post(
                gameService -> {
                    gameService.gameStarted(
                            new GameStartedEvent(sessionId, componentName.getPackageName()));
                });
    }

    private void onTaskRemoved(int taskId) {
        synchronized (mLock) {
            boolean isTaskAssociatedWithGameSession = mGameSessions.containsKey(taskId);
            if (!isTaskAssociatedWithGameSession) {
                return;
            }

            destroyGameSessionIfNecessaryLocked(taskId);
        }
    }

    private void createGameSession(int taskId) {
        synchronized (mLock) {
            createGameSessionLocked(taskId);
        }
    }

    @GuardedBy("mLock")
    private void createGameSessionLocked(int sessionId) {
        if (DEBUG) {
            Slog.i(TAG, "createGameSessionLocked() id: " + sessionId);
        }

        if (!mIsRunning) {
            return;
        }

        GameSessionRecord existingGameSessionRecord = mGameSessions.get(sessionId);
        if (existingGameSessionRecord == null) {
            Slog.w(TAG, "No existing game session record found for task (id: " + sessionId
                    + ") creation. Ignoring.");
            return;
        }
        if (!existingGameSessionRecord.isAwaitingGameSessionRequest()) {
            Slog.w(TAG, "Existing game session for task (id: " + sessionId
                    + ") is not awaiting game session request. Ignoring.");
            return;
        }
        mGameSessions.put(sessionId, existingGameSessionRecord.withGameSessionRequested());

        ComponentName componentName = existingGameSessionRecord.getComponentName();

        // TODO(b/207035150): Allow the game service provider to determine if a game session
        //  should be created. For now we will assume all games should have a session.
        AndroidFuture<IBinder> gameSessionFuture = new AndroidFuture<IBinder>()
                .orTimeout(CREATE_GAME_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenCompleteAsync((gameSessionIBinder, exception) -> {
                    IGameSession gameSession = IGameSession.Stub.asInterface(gameSessionIBinder);
                    if (exception != null || gameSession == null) {
                        Slog.w(TAG, "Failed to create GameSession: " + existingGameSessionRecord,
                                exception);
                        synchronized (mLock) {
                            destroyGameSessionIfNecessaryLocked(sessionId);
                        }
                        return;
                    }

                    synchronized (mLock) {
                        attachGameSessionLocked(sessionId, gameSession);
                    }
                }, mBackgroundExecutor);

        AndroidFuture<Void> unusedPostCreateGameSessionFuture =
                mGameSessionServiceConnector.post(gameService -> {
                    CreateGameSessionRequest createGameSessionRequest =
                            new CreateGameSessionRequest(sessionId, componentName.getPackageName());
                    gameService.create(createGameSessionRequest, gameSessionFuture);
                });
    }

    @GuardedBy("mLock")
    private void attachGameSessionLocked(int sessionId, @NonNull IGameSession gameSession) {
        if (DEBUG) {
            Slog.i(TAG, "attachGameSession() id: " + sessionId);
        }

        GameSessionRecord gameSessionRecord = mGameSessions.get(sessionId);
        boolean isValidAttachRequest = true;
        if (gameSessionRecord == null) {
            Slog.w(TAG, "No associated game session record. Destroying id: " + sessionId);
            isValidAttachRequest = false;
        }
        if (gameSessionRecord != null && !gameSessionRecord.isGameSessionRequested()) {
            Slog.w(TAG,
                    "Game session not requested for existing game session record. Destroying id: "
                            + sessionId);
            isValidAttachRequest = false;
        }

        if (!isValidAttachRequest) {
            try {
                gameSession.destroy();
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to destroy session: " + gameSessionRecord, ex);
            }
            return;
        }

        mGameSessions.put(sessionId, gameSessionRecord.withGameSession(gameSession));
    }

    @GuardedBy("mLock")
    private void destroyGameSessionIfNecessaryLocked(int sessionId) {
        // TODO(b/204503192): Limit the lifespan of the game session in the Game Service provider
        // to only when the associated task is running. Right now it is possible for a task to
        // move into the background and for all associated processes to die and for the Game Session
        // provider's GameSessionService to continue to be running. Ideally we could unbind the
        // service when this happens.
        if (DEBUG) {
            Slog.i(TAG, "destroyGameSession() id: " + sessionId);
        }

        GameSessionRecord gameSessionRecord = mGameSessions.remove(sessionId);
        if (gameSessionRecord == null) {
            if (DEBUG) {
                Slog.w(TAG, "No game session found for id: " + sessionId);
            }
            return;
        }

        IGameSession gameSession = gameSessionRecord.getGameSession();
        if (gameSession != null) {
            try {
                gameSession.destroy();
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to destroy session: " + gameSessionRecord, ex);
            }
        }

        if (mGameSessions.isEmpty()) {
            if (DEBUG) {
                Slog.i(TAG, "No active game sessions. Disconnecting GameSessionService");
            }

            if (mGameSessionServiceConnector != null) {
                mGameSessionServiceConnector.unbind();
            }
        }
    }
}
