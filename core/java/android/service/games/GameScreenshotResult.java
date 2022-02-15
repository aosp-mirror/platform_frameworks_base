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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Result object for calls to {@link IGameSessionController#takeScreenshot}.
 *
 * It includes a status (see {@link #getStatus}) and, if the status is
 * {@link #GAME_SCREENSHOT_SUCCESS} an {@link android.graphics.Bitmap} result (see {@link
 * #getBitmap}).
 *
 * @hide
 */
public final class GameScreenshotResult implements Parcelable {

    /**
     * The status of a call to {@link IGameSessionController#takeScreenshot} will be represented by
     * one of these values.
     *
     * @hide
     */
    @IntDef(flag = false, prefix = {"GAME_SCREENSHOT_"}, value = {
            GAME_SCREENSHOT_SUCCESS, // 0
            GAME_SCREENSHOT_ERROR_INTERNAL_ERROR, // 1
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GameScreenshotStatus {
    }

    /**
     * Indicates that the result of a call to {@link IGameSessionController#takeScreenshot} was
     * successful and an {@link android.graphics.Bitmap} result should be available by calling
     * {@link #getBitmap}.
     *
     * @hide
     */
    public static final int GAME_SCREENSHOT_SUCCESS = 0;

    /**
     * Indicates that the result of a call to {@link IGameSessionController#takeScreenshot} failed
     * due to an internal error.
     *
     * This error may occur if the device is not in a suitable state for a screenshot to be taken
     * (e.g., the screen is off) or if the game task is not in a suitable state for a screenshot
     * to be taken (e.g., the task is not visible). To make sure that the device and game are
     * in a suitable state, the caller can monitor the lifecycle methods for the {@link
     * GameSession} to make sure that the game task is focused. If the conditions are met, then the
     * caller may try again immediately.
     *
     * @hide
     */
    public static final int GAME_SCREENSHOT_ERROR_INTERNAL_ERROR = 1;

    @NonNull
    public static final Parcelable.Creator<GameScreenshotResult> CREATOR =
            new Parcelable.Creator<GameScreenshotResult>() {
                @Override
                public GameScreenshotResult createFromParcel(Parcel source) {
                    return new GameScreenshotResult(
                            source.readInt(),
                            source.readParcelable(null, Bitmap.class));
                }

                @Override
                public GameScreenshotResult[] newArray(int size) {
                    return new GameScreenshotResult[0];
                }
            };

    @GameScreenshotStatus
    private final int mStatus;

    @Nullable
    private final Bitmap mBitmap;

    /**
     * Creates a successful {@link GameScreenshotResult} with the provided bitmap.
     */
    public static GameScreenshotResult createSuccessResult(@NonNull Bitmap bitmap) {
        return new GameScreenshotResult(GAME_SCREENSHOT_SUCCESS, bitmap);
    }

    /**
     * Creates a failed {@link GameScreenshotResult} with an
     * {@link #GAME_SCREENSHOT_ERROR_INTERNAL_ERROR} status.
     */
    public static GameScreenshotResult createInternalErrorResult() {
        return new GameScreenshotResult(GAME_SCREENSHOT_ERROR_INTERNAL_ERROR, null);
    }

    private GameScreenshotResult(@GameScreenshotStatus int status, @Nullable Bitmap bitmap) {
        this.mStatus = status;
        this.mBitmap = bitmap;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeParcelable(mBitmap, flags);
    }

    @GameScreenshotStatus
    public int getStatus() {
        return mStatus;
    }

    /**
     * Gets the {@link Bitmap} result from a successful screenshot attempt.
     *
     * @return The bitmap.
     * @throws IllegalStateException if this method is called when {@link #getStatus} does not
     *                               return {@link #GAME_SCREENSHOT_SUCCESS}.
     */
    @NonNull
    public Bitmap getBitmap() {
        if (mBitmap == null) {
            throw new IllegalStateException("Bitmap not available for failed screenshot result");
        }
        return mBitmap;
    }

    @Override
    public String toString() {
        return "GameScreenshotResult{"
                + "mStatus="
                + mStatus
                + ", has bitmap='"
                + mBitmap != null ? "yes" : "no"
                + "\'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof GameScreenshotResult)) {
            return false;
        }

        GameScreenshotResult that = (GameScreenshotResult) o;
        return mStatus == that.mStatus
                && Objects.equals(mBitmap, that.mBitmap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, mBitmap);
    }
}
