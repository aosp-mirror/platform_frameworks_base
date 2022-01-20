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

    private enum State {
        // Game task is running, but GameSession not created.
        NO_GAME_SESSION_REQUESTED,
        // Game Service provider requested a Game Session and we are in the
        // process of creating it. GameSessionRecord.getGameSession() == null;
        GAME_SESSION_REQUESTED,
        // A Game Session is created and attached.
        // GameSessionRecord.getGameSession() != null.
        GAME_SESSION_ATTACHED,
    }

    private final int mTaskId;
    private final ComponentName mRootComponentName;
    @Nullable
    private final IGameSession mIGameSession;
    private final State mState;

    static GameSessionRecord awaitingGameSessionRequest(int taskId,
            ComponentName rootComponentName) {
        return new GameSessionRecord(taskId, rootComponentName, /* gameSession= */ null,
                State.NO_GAME_SESSION_REQUESTED);
    }

    private GameSessionRecord(
            int taskId,
            @NonNull ComponentName rootComponentName,
            @Nullable IGameSession gameSession,
            @NonNull State state) {
        this.mTaskId = taskId;
        this.mRootComponentName = rootComponentName;
        this.mIGameSession = gameSession;
        this.mState = state;
    }

    public boolean isAwaitingGameSessionRequest() {
        return mState == State.NO_GAME_SESSION_REQUESTED;
    }

    @NonNull
    public GameSessionRecord withGameSessionRequested() {
        return new GameSessionRecord(mTaskId, mRootComponentName, /* gameSession=*/ null,
                State.GAME_SESSION_REQUESTED);
    }

    public boolean isGameSessionRequested() {
        return mState == State.GAME_SESSION_REQUESTED;
    }

    @NonNull
    public GameSessionRecord withGameSession(@NonNull IGameSession gameSession) {
        Objects.requireNonNull(gameSession);
        return new GameSessionRecord(mTaskId, mRootComponentName, gameSession,
                State.GAME_SESSION_ATTACHED);
    }

    @Nullable
    public IGameSession getGameSession() {
        return mIGameSession;
    }

    @NonNull
    public ComponentName getComponentName() {
        return mRootComponentName;
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
                + ", mState="
                + mState
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
                && Objects.equals(mIGameSession, that.mIGameSession) && mState == that.mState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTaskId, mRootComponentName, mIGameSession, mState);
    }
}
