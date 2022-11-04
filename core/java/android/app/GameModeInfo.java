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

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * GameModeInfo returned from {@link GameManager#getGameModeInfo(String)}.
 *
 * Developers can enable game modes or interventions by adding
 * <pre>{@code
 * <meta-data android:name="android.game_mode_intervention"
 *   android:resource="@xml/GAME_MODE_CONFIG_FILE" />
 * }</pre>
 * to the <pre>{@code <application>}</pre>, where the GAME_MODE_CONFIG_FILE is an XML file that
 * specifies the game mode enablement and intervention configuration:
 * <pre>{@code
 * <game-mode-config xmlns:android="http://schemas.android.com/apk/res/android"
 *   android:gameModePerformance="true"
 *   android:gameModeBattery="false"
 *   android:allowGameDownscaling="true"
 *   android:allowGameFpsOverride="false"
 * />
 * }</pre>
 *
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

    /**
     * Builder for {@link GameModeInfo}.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        /** Constructs a new Builder for a game mode info. */
        public Builder() {
        }

        /**
         * Sets the available game modes.
         */
        @NonNull
        public GameModeInfo.Builder setAvailableGameModes(
                @NonNull @GameManager.GameMode int[] availableGameModes) {
            mAvailableGameModes = availableGameModes;
            return this;
        }

        /**
         * Sets the opted-in game modes.
         */
        @NonNull
        public GameModeInfo.Builder setOptedInGameModes(
                @NonNull @GameManager.GameMode int[] optedInGameModes) {
            mOptedInGameModes = optedInGameModes;
            return this;
        }

        /**
         * Sets the active game mode.
         */
        @NonNull
        public GameModeInfo.Builder setActiveGameMode(
                @NonNull @GameManager.GameMode int activeGameMode) {
            mActiveGameMode = activeGameMode;
            return this;
        }

        /**
         * Sets the downscaling intervention flag.
         */
        @NonNull
        public GameModeInfo.Builder setDownscalingAllowed(boolean allowed) {
            mIsDownscalingAllowed = allowed;
            return this;
        }

        /**
         * Sets the FPS override flag.
         */
        @NonNull
        public GameModeInfo.Builder setFpsOverrideAllowed(boolean allowed) {
            mIsFpsOverrideAllowed = allowed;
            return this;
        }

        /**
         * Builds a GameModeInfo.
         */
        @NonNull
        public GameModeInfo build() {
            return new GameModeInfo(mActiveGameMode, mAvailableGameModes, mOptedInGameModes,
                    mIsDownscalingAllowed, mIsFpsOverrideAllowed);
        }

        private @GameManager.GameMode int[] mAvailableGameModes = new int[]{};
        private @GameManager.GameMode int[] mOptedInGameModes = new int[]{};
        private @GameManager.GameMode int mActiveGameMode;
        private boolean mIsDownscalingAllowed;
        private boolean mIsFpsOverrideAllowed;
    }

    /**
     * Creates a game mode info.
     *
     * @deprecated Use the {@link Builder} instead.
     */
    public GameModeInfo(@GameManager.GameMode int activeGameMode,
            @NonNull @GameManager.GameMode int[] availableGameModes) {
        this(activeGameMode, availableGameModes, new int[]{}, true, true);
    }

    GameModeInfo(@GameManager.GameMode int activeGameMode,
            @NonNull @GameManager.GameMode int[] availableGameModes,
            @NonNull @GameManager.GameMode int[] optedInGameModes, boolean isDownscalingAllowed,
            boolean isFpsOverrideAllowed) {
        mActiveGameMode = activeGameMode;
        mAvailableGameModes = Arrays.copyOf(availableGameModes, availableGameModes.length);
        mOptedInGameModes = Arrays.copyOf(optedInGameModes, optedInGameModes.length);
        mIsDownscalingAllowed = isDownscalingAllowed;
        mIsFpsOverrideAllowed = isFpsOverrideAllowed;
    }

    /** @hide */
    @VisibleForTesting
    public GameModeInfo(Parcel in) {
        mActiveGameMode = in.readInt();
        mAvailableGameModes = in.createIntArray();
        mOptedInGameModes = in.createIntArray();
        mIsDownscalingAllowed = in.readBoolean();
        mIsFpsOverrideAllowed = in.readBoolean();
    }

    /**
     * Returns the {@link GameManager.GameMode} the application is currently using.
     */
    public @GameManager.GameMode int getActiveGameMode() {
        return mActiveGameMode;
    }

    /**
     * Gets the collection of {@link GameManager.GameMode} that can be applied to the game.
     * <p>
     * Available games include all game modes that are either supported by the OEM in device
     * config, or opted in by the game developers in game mode config XML, plus the default enabled
     * modes for any game including {@link GameManager#GAME_MODE_STANDARD} and
     * {@link GameManager#GAME_MODE_CUSTOM}.
     * <p>
     * Also see {@link GameModeInfo}.
     */
    @NonNull
    public @GameManager.GameMode int[] getAvailableGameModes() {
        return Arrays.copyOf(mAvailableGameModes, mAvailableGameModes.length);
    }

    /**
     * Gets the collection of {@link GameManager.GameMode} that are opted in by the game.
     * <p>
     * Also see {@link GameModeInfo}.
     */
    @NonNull
    public @GameManager.GameMode int[] getOptedInGameModes() {
        return Arrays.copyOf(mOptedInGameModes, mOptedInGameModes.length);
    }

    /**
     * Returns if downscaling is allowed (not opted out) by the game in their Game Mode config.
     * <p>
     * Also see {@link GameModeInfo}.
     */
    public boolean isDownscalingAllowed() {
        return mIsDownscalingAllowed;
    }

    /**
     * Returns if FPS override is allowed (not opted out) by the game in their Game Mode config.
     * <p>
     * Also see {@link GameModeInfo}.
     */
    public boolean isFpsOverrideAllowed() {
        return mIsFpsOverrideAllowed;
    }


    private final @GameManager.GameMode int[] mAvailableGameModes;
    private final @GameManager.GameMode int[] mOptedInGameModes;
    private final @GameManager.GameMode int mActiveGameMode;
    private final boolean mIsDownscalingAllowed;
    private final boolean mIsFpsOverrideAllowed;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mActiveGameMode);
        dest.writeIntArray(mAvailableGameModes);
        dest.writeIntArray(mOptedInGameModes);
        dest.writeBoolean(mIsDownscalingAllowed);
        dest.writeBoolean(mIsFpsOverrideAllowed);
    }
}
