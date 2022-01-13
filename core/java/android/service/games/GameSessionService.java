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

package android.service.games;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.Objects;

/**
 * Service that hosts active game sessions.
 *
 * This service should be in a separate process from the {@link GameService}. This
 * allows it to perform the heavyweight operations associated with rendering a game
 * session overlay while games are running and release these resources (by allowing
 * the process to be killed) when games are not running.
 *
 * Game Service providers must extend {@link GameSessionService} and declare the service in their
 * Manifest. The service must require the {@link android.Manifest.permission#BIND_GAME_SERVICE} so
 * that other application can not abuse it. This service is used to create instances of
 * {@link GameSession} via {@link #onNewSession(CreateGameSessionRequest)} and will remain bound to
 * so long as at least one {@link GameSession} is running.
 *
 * @hide
 */
@SystemApi
public abstract class GameSessionService extends Service {
    private static final String TAG = "GameSessionService";

    /**
     * The {@link Intent} action used when binding to the service.
     * To be supported, the service must require the
     * {@link android.Manifest.permission#BIND_GAME_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_GAME_SESSION_SERVICE =
            "android.service.games.action.GAME_SESSION_SERVICE";

    private final IGameSessionService mInterface = new IGameSessionService.Stub() {
        @Override
        public void create(CreateGameSessionRequest createGameSessionRequest,
                AndroidFuture gameSessionFuture) {
            Handler.getMain().post(PooledLambda.obtainRunnable(
                    GameSessionService::doCreate, GameSessionService.this,
                    createGameSessionRequest,
                    gameSessionFuture));
        }
    };

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }

        if (!ACTION_GAME_SESSION_SERVICE.equals(intent.getAction())) {
            return null;
        }

        return mInterface.asBinder();
    }

    private void doCreate(CreateGameSessionRequest createGameSessionRequest,
            AndroidFuture<IBinder> gameSessionFuture) {
        GameSession gameSession = onNewSession(createGameSessionRequest);
        Objects.requireNonNull(gameSession);

        gameSessionFuture.complete(gameSession.mInterface.asBinder());

        gameSession.doCreate();
    }

    /**
     * Request to create a new {@link GameSession}.
     */
    @NonNull
    public abstract GameSession onNewSession(
            @NonNull CreateGameSessionRequest createGameSessionRequest);
}
