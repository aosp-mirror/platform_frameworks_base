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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Request object providing the context in order to create a new {@link GameSession}.
 *
 * This is provided to the Game Service provider via
 * {@link GameSessionService#onNewSession(CreateGameSessionRequest)}. It includes game
 * (see {@link #getGamePackageName()}) that the session is associated with and a task
 * (see {@link #getTaskId()}.
 *
 * @hide
 */
@SystemApi
public final class CreateGameSessionRequest implements Parcelable {

    @NonNull
    public static final Parcelable.Creator<CreateGameSessionRequest> CREATOR =
            new Parcelable.Creator<CreateGameSessionRequest>() {
                @Override
                public CreateGameSessionRequest createFromParcel(Parcel source) {
                    return new CreateGameSessionRequest(
                            source.readInt(),
                            source.readString8());
                }

                @Override
                public CreateGameSessionRequest[] newArray(int size) {
                    return new CreateGameSessionRequest[0];
                }
            };

    private final int mTaskId;
    private final String mGamePackageName;

    public CreateGameSessionRequest(int taskId, @NonNull String gamePackageName) {
        this.mTaskId = taskId;
        this.mGamePackageName = gamePackageName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mTaskId);
        dest.writeString8(mGamePackageName);
    }

    /**
     * Unique identifier for the task.
     */
    public int getTaskId() {
        return mTaskId;
    }

    /**
     * The package name of the game associated with the session.
     */
    @NonNull
    public String getGamePackageName() {
        return mGamePackageName;
    }

    @Override
    public String toString() {
        return "GameSessionRequest{"
                + "mTaskId="
                + mTaskId
                + ", mGamePackageName='"
                + mGamePackageName
                + "\'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof CreateGameSessionRequest)) {
            return false;
        }

        CreateGameSessionRequest that = (CreateGameSessionRequest) o;
        return mTaskId == that.mTaskId
                && Objects.equals(mGamePackageName, that.mGamePackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTaskId, mGamePackageName);
    }
}
