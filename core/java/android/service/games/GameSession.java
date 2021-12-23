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

import android.annotation.SystemApi;
import android.os.Handler;

import com.android.internal.util.function.pooled.PooledLambda;

/**
 * An active game session, providing a facility for the implementation to interact with the game.
 *
 * A Game Service provider should extend the {@link GameSession} to provide their own implementation
 * which is then returned when a game session is created via
 * {@link GameSessionService#onNewSession(CreateGameSessionRequest)}.
 *
 * @hide
 */
@SystemApi
public abstract class GameSession {

    final IGameSession mInterface = new IGameSession.Stub() {
        @Override
        public void destroy() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    GameSession::doDestroy, GameSession.this));
        }
    };

    void doCreate() {
        onCreate();
    }

    void doDestroy() {
        onDestroy();
    }

    /**
     * Initializer called when the game session is starting.
     *
     * This should be used perform any setup required now that the game session is created.
     */
    public void onCreate() {}

    /**
     * Finalizer called when the game session is ending.
     *
     * This should be used to perform any cleanup before the game session is destroyed.
     */
    public void onDestroy() {}
}
