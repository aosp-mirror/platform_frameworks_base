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
import android.content.ComponentName;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Representation of a {@link android.service.games.GameService} provider configuration.
 */
final class GameServiceProviderConfiguration {
    private final UserHandle mUserHandle;
    private final ComponentName mGameServiceComponentName;
    private final ComponentName mGameSessionServiceComponentName;

    GameServiceProviderConfiguration(
            @NonNull UserHandle userHandle,
            @NonNull ComponentName gameServiceComponentName,
            @NonNull ComponentName gameSessionServiceComponentName) {
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(gameServiceComponentName);
        Objects.requireNonNull(gameSessionServiceComponentName);

        this.mUserHandle = userHandle;
        this.mGameServiceComponentName = gameServiceComponentName;
        this.mGameSessionServiceComponentName = gameSessionServiceComponentName;
    }

    @NonNull
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    @NonNull
    public ComponentName getGameServiceComponentName() {
        return mGameServiceComponentName;
    }

    @NonNull
    public ComponentName getGameSessionServiceComponentName() {
        return mGameSessionServiceComponentName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof GameServiceProviderConfiguration)) {
            return false;
        }

        GameServiceProviderConfiguration that = (GameServiceProviderConfiguration) o;
        return mUserHandle.equals(that.mUserHandle)
                && mGameServiceComponentName.equals(that.mGameServiceComponentName)
                && mGameSessionServiceComponentName.equals(that.mGameSessionServiceComponentName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserHandle, mGameServiceComponentName,
                mGameSessionServiceComponentName);
    }

    @Override
    public String toString() {
        return "GameServiceProviderConfiguration{"
                + "mUserHandle="
                + mUserHandle
                + ", gameServiceComponentName="
                + mGameServiceComponentName
                + ", gameSessionServiceComponentName="
                + mGameSessionServiceComponentName
                + '}';
    }
}
