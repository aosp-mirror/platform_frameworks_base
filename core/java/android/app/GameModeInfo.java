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

package android.app;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * GameModeInfo returned from {@link GameManager#getGameModeInfo(String)}.
 * @hide
 */
@SystemApi
public final class GameModeInfo implements Parcelable {

    public static final @NonNull Creator<GameModeInfo> CREATOR = new Creator<GameModeInfo>() {
        @Override
        public GameModeInfo createFromParcel(Parcel in) {
            return new GameModeInfo(in);
        }

        @Override
        public GameModeInfo[] newArray(int size) {
            return new GameModeInfo[size];
        }
    };

    public GameModeInfo(@GameManager.GameMode int activeGameMode,
            @NonNull @GameManager.GameMode int[] availableGameModes) {
        mActiveGameMode = activeGameMode;
        mAvailableGameModes = availableGameModes;
    }

    GameModeInfo(Parcel in) {
        mActiveGameMode = in.readInt();
        final int availableGameModesCount = in.readInt();
        mAvailableGameModes = new int[availableGameModesCount];
        in.readIntArray(mAvailableGameModes);
    }

    /**
     * Returns the {@link GameManager.GameMode} the application is currently using.
     * Developers can enable game modes by adding
     * <code>
     *     <meta-data android:name="android.game_mode_intervention"
     *             android:resource="@xml/GAME_MODE_CONFIG_FILE" />
     * </code>
     * to the {@link <application> tag}, where the GAME_MODE_CONFIG_FILE is an XML file that
     * specifies the game mode enablement and configuration:
     * <code>
     *     <game-mode-config xmlns:android="http://schemas.android.com/apk/res/android"
     *         android:gameModePerformance="true"
     *         android:gameModeBattery="false"
     *     />
     * </code>
     */
    public @GameManager.GameMode int getActiveGameMode() {
        return mActiveGameMode;
    }

    /**
     * The collection of {@link GameManager.GameMode GameModes} that can be applied to the game.
     */
    @NonNull
    public @GameManager.GameMode int[] getAvailableGameModes() {
        return mAvailableGameModes;
    }

    // Ideally there should be callback that the caller can register to know when the available
    // GameMode and/or the active GameMode is changed, however, there's no concrete use case
    // at the moment so there's no callback mechanism introduced    .
    private final @GameManager.GameMode int[] mAvailableGameModes;
    private final @GameManager.GameMode int mActiveGameMode;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mActiveGameMode);
        dest.writeInt(mAvailableGameModes.length);
        dest.writeIntArray(mAvailableGameModes);
    }
}
