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
import android.view.SurfaceControlViewHost.SurfacePackage;

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
        // A Game Session did exist for a given game task but was destroyed because the last process
        // for the game died.
        // GameSessionRecord.getGameSession() == null.
        GAME_SESSION_ENDED_PROCESS_DEATH,
    }

    private final int mTaskId;
    private final State mState;
    private final ComponentName mRootComponentName;
    @Nullable
    private final IGameSession mIGameSession;
    @Nullable
    private final SurfacePackage mSurfacePackage;

    static GameSessionRecord awaitingGameSessionRequest(int taskId,
            ComponentName rootComponentName) {
        return new GameSessionRecord(
                taskId,
                State.NO_GAME_SESSION_REQUESTED,
                rootComponentName,
                /* gameSession= */ null,
                /* surfacePackage= */ null);
    }

    private GameSessionRecord(
            int taskId,
            @NonNull State state,
            @NonNull ComponentName rootComponentName,
            @Nullable IGameSession gameSession,
            @Nullable SurfacePackage surfacePackage) {
        this.mTaskId = taskId;
        this.mState = state;
        this.mRootComponentName = rootComponentName;
        this.mIGameSession = gameSession;
        this.mSurfacePackage = surfacePackage;
    }

    public boolean isAwaitingGameSessionRequest() {
        return mState == State.NO_GAME_SESSION_REQUESTED;
    }

    @NonNull
    public GameSessionRecord withGameSessionRequested() {
        return new GameSessionRecord(
                mTaskId,
                State.GAME_SESSION_REQUESTED,
                mRootComponentName,
                /* gameSession=*/ null,
                /* surfacePackage=*/ null);
    }

    public boolean isGameSessionRequested() {
        return mState == State.GAME_SESSION_REQUESTED;
    }

    @NonNull
    public GameSessionRecord withGameSession(
            @NonNull IGameSession gameSession,
            @NonNull SurfacePackage surfacePackage) {
        Objects.requireNonNull(gameSession);
        return new GameSessionRecord(mTaskId,
                State.GAME_SESSION_ATTACHED,
                mRootComponentName,
                gameSession,
                surfacePackage);
    }

    @NonNull
    public GameSessionRecord withGameSessionEndedOnProcessDeath() {
        return new GameSessionRecord(
                mTaskId,
                State.GAME_SESSION_ENDED_PROCESS_DEATH,
                mRootComponentName,
                /* gameSession=*/ null,
                /* surfacePackage=*/ null);
    }

    public boolean isGameSessionEndedForProcessDeath() {
        return mState == State.GAME_SESSION_ENDED_PROCESS_DEATH;
    }

    @NonNull
    public int getTaskId() {
        return mTaskId;
    }

    @NonNull
    public ComponentName getComponentName() {
        return mRootComponentName;
    }

    @Nullable
    public IGameSession getGameSession() {
        return mIGameSession;
    }

    @Nullable
    public SurfacePackage getSurfacePackage() {
        return mSurfacePackage;
    }

    @Override
    public String toString() {
        return "GameSessionRecord{"
                + "mTaskId="
                + mTaskId
                + ", mState="
                + mState
                + ", mRootComponentName="
                + mRootComponentName
                + ", mIGameSession="
                + mIGameSession
                + ", mSurfacePackage="
                + mSurfacePackage
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
        return mTaskId == that.mTaskId
                && mState == that.mState
                && mRootComponentName.equals(that.mRootComponentName)
                && Objects.equals(mIGameSession, that.mIGameSession)
                && Objects.equals(mSurfacePackage, that.mSurfacePackage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mTaskId, mState, mRootComponentName, mIGameSession, mState, mSurfacePackage);
    }
}
