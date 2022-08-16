/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Event object provided when a game task is started.
 *
 * This is provided to the Game Service via
 * {@link GameService#onGameStarted(GameStartedEvent)}. It includes the game's taskId
 * (see {@link #getTaskId}) that the game's package name (see {@link #getPackageName}).
 *
 * @hide
 */
@SystemApi
public final class GameStartedEvent implements Parcelable {

    @NonNull
    public static final Parcelable.Creator<GameStartedEvent> CREATOR =
            new Parcelable.Creator<GameStartedEvent>() {
                @Override
                public GameStartedEvent createFromParcel(Parcel source) {
                    return new GameStartedEvent(
                            source.readInt(),
                            source.readString());
                }

                @Override
                public GameStartedEvent[] newArray(int size) {
                    return new GameStartedEvent[0];
                }
            };

    private final int mTaskId;
    private final String mPackageName;

    public GameStartedEvent(@IntRange(from = 0) int taskId, @NonNull String packageName) {
        this.mTaskId = taskId;
        this.mPackageName = packageName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mTaskId);
        dest.writeString(mPackageName);
    }

    /**
     * Unique identifier for the task associated with the game.
     */
    @IntRange(from = 0)
    public int getTaskId() {
        return mTaskId;
    }

    /**
     * The package name for the game.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public String toString() {
        return "GameStartedEvent{"
                + "mTaskId="
                + mTaskId
                + ", mPackageName='"
                + mPackageName
                + "\'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof GameStartedEvent)) {
            return false;
        }

        GameStartedEvent that = (GameStartedEvent) o;
        return mTaskId == that.mTaskId
                && Objects.equals(mPackageName, that.mPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTaskId, mPackageName);
    }
}
