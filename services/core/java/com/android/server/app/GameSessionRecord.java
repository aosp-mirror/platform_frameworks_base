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
import android.annotation.Nullable;
import android.content.ComponentName;
import android.service.games.IGameSession;

import java.util.Objects;

final class GameSessionRecord {

    private final int mTaskId;
    private final ComponentName mRootComponentName;
    @Nullable
    private final IGameSession mIGameSession;

    static GameSessionRecord pendingGameSession(int taskId, ComponentName rootComponentName) {
        return new GameSessionRecord(taskId, rootComponentName, /* gameSession= */ null);
    }

    private GameSessionRecord(
            int taskId,
            @NonNull ComponentName rootComponentName,
            @Nullable IGameSession gameSession) {
        this.mTaskId = taskId;
        this.mRootComponentName = rootComponentName;
        this.mIGameSession = gameSession;
    }

    @NonNull
    public GameSessionRecord withGameSession(@NonNull IGameSession gameSession) {
        Objects.requireNonNull(gameSession);
        return new GameSessionRecord(mTaskId, mRootComponentName, gameSession);
    }

    @Nullable
    public IGameSession getGameSession() {
        return mIGameSession;
    }

    @Override
    public String toString() {
        return "GameSessionRecord{"
                + "mTaskId="
                + mTaskId
                + ", mRootComponentName="
                + mRootComponentName
                + ", mIGameSession="
                + mIGameSession
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof GameSessionRecord)) {
            return false;
        }

        GameSessionRecord that = (GameSessionRecord) o;
        return mTaskId == that.mTaskId && mRootComponentName.equals(that.mRootComponentName)
                && Objects.equals(mIGameSession, that.mIGameSession);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTaskId, mRootComponentName, mIGameSession);
    }
}
