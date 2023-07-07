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

import android.service.games.IGameSessionController;
import android.service.games.IGameSession;
import android.service.games.CreateGameSessionRequest;
import android.service.games.GameSessionViewHostConfiguration;

import com.android.internal.infra.AndroidFuture;


/**
 * @hide
 */
oneway interface IGameSessionService {
    @RequiresNoPermission
    void create(
            in IGameSessionController gameSessionController,
            in CreateGameSessionRequest createGameSessionRequest,
            in GameSessionViewHostConfiguration gameSessionViewHostConfiguration,
            in AndroidFuture /* T=CreateGameSessionResult */ createGameSessionResultFuture);
}
