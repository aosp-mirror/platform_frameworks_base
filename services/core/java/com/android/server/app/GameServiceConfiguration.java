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
import android.os.UserHandle;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Representation of a {@link android.service.games.GameService} provider configuration.
 */
final class GameServiceConfiguration {
    private final String mPackageName;
    @Nullable
    private final GameServiceComponentConfiguration mGameServiceComponentConfiguration;

    GameServiceConfiguration(
            @NonNull String packageName,
            @Nullable GameServiceComponentConfiguration gameServiceComponentConfiguration) {
        Objects.requireNonNull(packageName);

        mPackageName = packageName;
        mGameServiceComponentConfiguration = gameServiceComponentConfiguration;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @Nullable
    public GameServiceComponentConfiguration getGameServiceComponentConfiguration() {
        return mGameServiceComponentConfiguration;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof GameServiceConfiguration)) {
            return false;
        }

        GameServiceConfiguration that = (GameServiceConfiguration) o;
        return TextUtils.equals(mPackageName, that.mPackageName)
                && Objects.equals(mGameServiceComponentConfiguration,
                that.mGameServiceComponentConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, mGameServiceComponentConfiguration);
    }

    @Override
    public String toString() {
        return "GameServiceConfiguration{"
                + "packageName="
                + mPackageName
                + ", gameServiceComponentConfiguration="
                + mGameServiceComponentConfiguration
                + '}';
    }

    static final class GameServiceComponentConfiguration {
        private final UserHandle mUserHandle;
        private final ComponentName mGameServiceComponentName;
        private final ComponentName mGameSessionServiceComponentName;

        GameServiceComponentConfiguration(
                @NonNull UserHandle userHandle, @NonNull ComponentName gameServiceComponentName,
                @NonNull ComponentName gameSessionServiceComponentName) {
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(gameServiceComponentName);
            Objects.requireNonNull(gameSessionServiceComponentName);

            mUserHandle = userHandle;
            mGameServiceComponentName = gameServiceComponentName;
            mGameSessionServiceComponentName = gameSessionServiceComponentName;
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

            if (!(o instanceof GameServiceComponentConfiguration)) {
                return false;
            }

            GameServiceComponentConfiguration that =
                    (GameServiceComponentConfiguration) o;
            return mUserHandle.equals(that.mUserHandle) && mGameServiceComponentName.equals(
                    that.mGameServiceComponentName)
                    && mGameSessionServiceComponentName.equals(
                    that.mGameSessionServiceComponentName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUserHandle,
                    mGameServiceComponentName,
                    mGameSessionServiceComponentName);
        }

        @Override
        public String toString() {
            return "GameServiceComponentConfiguration{"
                    + "userHandle="
                    + mUserHandle
                    + ", gameServiceComponentName="
                    + mGameServiceComponentName
                    + ", gameSessionServiceComponentName="
                    + mGameSessionServiceComponentName
                    + "}";
        }
    }
}
