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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Used by {@link Activity#onPictureInPictureUiStateChanged(PictureInPictureUiState)}.
 */
public final class PictureInPictureUiState implements Parcelable {

    private final boolean mIsStashed;
    private final boolean mIsTransitioningToPip;

    /** {@hide} */
    PictureInPictureUiState(Parcel in) {
        mIsStashed = in.readBoolean();
        mIsTransitioningToPip = in.readBoolean();
    }

    /** {@hide} */
    @TestApi
    public PictureInPictureUiState(boolean isStashed) {
        this(isStashed, false /* isEnteringPip */);
    }

    private PictureInPictureUiState(boolean isStashed, boolean isTransitioningToPip) {
        mIsStashed = isStashed;
        mIsTransitioningToPip = isTransitioningToPip;
    }

    /**
     * Returns whether Picture-in-Picture is stashed or not. A stashed PiP means it is only
     * partially visible to the user, with some parts of it being off-screen. This is usually a
     * UI state that is triggered by the user, such as flinging the PiP to the edge or letting go
     * of PiP while dragging partially off-screen.
     *
     * Developers can use this in conjunction with
     * {@link Activity#onPictureInPictureUiStateChanged(PictureInPictureUiState)} to get a signal
     * when the PiP stash state has changed. For example, if the state changed from {@code false} to
     * {@code true}, developers can choose to temporarily pause video playback if PiP is of video
     * content. Vice versa, if changing from {@code true} to {@code false} and video content is
     * paused, developers can resume video playback.
     *
     * @see <a href="http://developer.android.com/about/versions/12/features/pip-improvements">
     *     Picture in Picture (PiP) improvements</a>
     */
    public boolean isStashed() {
        return mIsStashed;
    }

    /**
     * Returns {@code true} if the app is going to enter Picture-in-Picture (PiP) mode.
     *
     * This state is associated with the entering PiP animation. When that animation starts,
     * whether via auto enter PiP or calling
     * {@link Activity#enterPictureInPictureMode(PictureInPictureParams)} explicitly, app can expect
     * {@link Activity#onPictureInPictureUiStateChanged(PictureInPictureUiState)} callback with
     * {@link #isTransitioningToPip()} to be {@code true} first,
     * followed by {@link Activity#onPictureInPictureModeChanged(boolean, Configuration)} when it
     * fully settles in PiP mode.
     *
     * When app receives the
     * {@link Activity#onPictureInPictureUiStateChanged(PictureInPictureUiState)} callback with
     * {@link #isTransitioningToPip()} being {@code true}, it's recommended to hide certain UI
     * elements, such as video controls, to archive a clean entering PiP animation.
     *
     * In case an application wants to restore the previously hidden UI elements when exiting
     * PiP, it is recommended to do that in
     * {@code onPictureInPictureModeChanged(isInPictureInPictureMode=false)} callback rather
     * than the beginning of exit PiP animation.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PIP_UI_STATE_CALLBACK_ON_ENTERING)
    public boolean isTransitioningToPip() {
        return mIsTransitioningToPip;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PictureInPictureUiState)) return false;
        PictureInPictureUiState that = (PictureInPictureUiState) o;
        return mIsStashed == that.mIsStashed
                && mIsTransitioningToPip == that.mIsTransitioningToPip;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsStashed, mIsTransitioningToPip);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeBoolean(mIsStashed);
        out.writeBoolean(mIsTransitioningToPip);
    }

    public static final @android.annotation.NonNull Creator<PictureInPictureUiState> CREATOR =
            new Creator<PictureInPictureUiState>() {
                public PictureInPictureUiState createFromParcel(Parcel in) {
                    return new PictureInPictureUiState(in);
                }
                public PictureInPictureUiState[] newArray(int size) {
                    return new PictureInPictureUiState[size];
                }
            };

    /**
     * Builder class for {@link PictureInPictureUiState}.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PIP_UI_STATE_CALLBACK_ON_ENTERING)
    public static final class Builder {
        private boolean mIsStashed;
        private boolean mIsTransitioningToPip;

        /** Empty constructor. */
        public Builder() {
        }

        /**
         * Sets the {@link #mIsStashed} state.
         * @return The same {@link Builder} instance.
         */
        public Builder setStashed(boolean isStashed) {
            mIsStashed = isStashed;
            return this;
        }

        /**
         * Sets the {@link #mIsTransitioningToPip} state.
         * @return The same {@link Builder} instance.
         */
        public Builder setTransitioningToPip(boolean isEnteringPip) {
            mIsTransitioningToPip = isEnteringPip;
            return this;
        }

        /**
         * @return The constructed {@link PictureInPictureUiState} instance.
         */
        public PictureInPictureUiState build() {
            return new PictureInPictureUiState(mIsStashed, mIsTransitioningToPip);
        }
    }
}
