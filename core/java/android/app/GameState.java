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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * State of the game passed to the GameManager.
 *
 * This includes a top-level state for the game (indicating if the game can be interrupted without
 * interfering with content that can't be paused). Since content can be loaded in any state, it
 * includes an independent boolean flag to indicate loading status.
 *
 * Free-form metadata (as a Bundle) and a string description can also be specified by the
 * application.
 */
public final class GameState implements Parcelable {
    /**
     * Default Game mode is unknown.
     */
    public static final int MODE_UNKNOWN = 0;

    /**
     * No mode means that the game is not in active play, for example the user is using the game
     * menu.
     */
    public static final int MODE_NONE = 1;

    /**
     * Indicates if the game is in active, but interruptible, game play.
     */
    public static final int MODE_GAMEPLAY_INTERRUPTIBLE = 2;

    /**
     * Indicates if the game is in active user play mode, which is real time and cannot be
     *  interrupted.
     */
    public static final int MODE_GAMEPLAY_UNINTERRUPTIBLE = 3;

    /**
     * Indicates that the current content shown is not gameplay related. For example it can be an
     * ad, a web page, a text, or a video.
     */
    public static final int MODE_CONTENT = 4;

    /**
     * Implement the parcelable interface.
     */
    public static final @NonNull Creator<GameState> CREATOR = new Creator<GameState>() {
        @Override
        public GameState createFromParcel(Parcel in) {
            return new GameState(in);
        }

        @Override
        public GameState[] newArray(int size) {
            return new GameState[size];
        }
    };

    // Indicates if the game is loading assets/resources/compiling/etc. This is independent of game
    // mode because there could be a loading UI displayed, or there could be loading in the
    // background.
    private final boolean mIsLoading;

    // One of the states listed above.
    private final @GameStateMode int mMode;

    // A developer-supplied enum, e.g. to indicate level or scene.
    private final int mLabel;

    // The developer-supplied enum, e.g. to indicate the current quality level.
    private final int mQuality;

    /**
     * Create a GameState with the specified loading status.
     * @param isLoading Whether the game is in the loading state.
     * @param mode The game state mode of type @GameStateMode.
     */
    public GameState(boolean isLoading, @GameStateMode int mode) {
        this(isLoading, mode, -1, -1);
    }

    /**
     * Create a GameState with the given state variables.
     * @param isLoading Whether the game is in the loading state.
     * @param mode The game state mode.
     * @param label An optional developer-supplied enum e.g. for the current level.
     * @param quality An optional developer-supplied enum, e.g. for the current quality level.
     */
    public GameState(boolean isLoading, @GameStateMode int mode, int label, int quality) {
        mIsLoading = isLoading;
        mMode = mode;
        mLabel = label;
        mQuality = quality;
    }

    private GameState(Parcel in) {
        mIsLoading = in.readBoolean();
        mMode = in.readInt();
        mLabel = in.readInt();
        mQuality = in.readInt();
    }

    /**
     * @return If the game is loading assets/resources/compiling/etc.
     */
    public boolean isLoading() {
        return mIsLoading;
    }

    /**
     * @return The game state mode.
     */
    public @GameStateMode int getMode() {
        return mMode;
    }

    /**
     * @return The developer-supplied enum, e.g. to indicate level or scene. The default value (if
     * not supplied) is -1.
     */
    public int getLabel() {
        return mLabel;
    }

    /**
     * @return The developer-supplied enum, e.g. to indicate the current quality level. The default
     * value (if not suplied) is -1.
     */
    public int getQuality() {
        return mQuality;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeBoolean(mIsLoading);
        parcel.writeInt(mMode);
        parcel.writeInt(mLabel);
        parcel.writeInt(mQuality);
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MODE_UNKNOWN, MODE_NONE, MODE_GAMEPLAY_INTERRUPTIBLE, MODE_GAMEPLAY_UNINTERRUPTIBLE,
            MODE_CONTENT

    })
    @interface GameStateMode {}
}
