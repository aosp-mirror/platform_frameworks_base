/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
 * Library</a> for consistent behavior across all devices.
 * <p>
 * Define a command that a {@link MediaController2} can send to a {@link MediaSession2}.
 * <p>
 * If {@link #getCommandCode()} isn't {@link #COMMAND_CODE_CUSTOM}), it's predefined command.
 * If {@link #getCommandCode()} is {@link #COMMAND_CODE_CUSTOM}), it's custom command and
 * {@link #getCustomAction()} shouldn't be {@code null}.
 * <p>
 * Refer to the
 * <a href="{@docRoot}reference/androidx/media2/SessionCommand2.html">AndroidX SessionCommand</a>
 * class for the list of valid commands.
 */
public final class Session2Command implements Parcelable {
    /**
     * Command code for the custom command which can be defined by string action in the
     * {@link Session2Command}.
     */
    public static final int COMMAND_CODE_CUSTOM = 0;

    public static final @android.annotation.NonNull Parcelable.Creator<Session2Command> CREATOR =
            new Parcelable.Creator<Session2Command>() {
                @Override
                public Session2Command createFromParcel(Parcel in) {
                    return new Session2Command(in);
                }

                @Override
                public Session2Command[] newArray(int size) {
                    return new Session2Command[size];
                }
            };

    private final int mCommandCode;
    // Nonnull if it's custom command
    private final String mCustomAction;
    private final Bundle mCustomExtras;

    /**
     * Constructor for creating a command predefined in AndroidX media2.
     *
     * @param commandCode A command code for a command predefined in AndroidX media2.
     */
    public Session2Command(int commandCode) {
        if (commandCode == COMMAND_CODE_CUSTOM) {
            throw new IllegalArgumentException("commandCode shouldn't be COMMAND_CODE_CUSTOM");
        }
        mCommandCode = commandCode;
        mCustomAction = null;
        mCustomExtras = null;
    }

    /**
     * Constructor for creating a custom command.
     *
     * @param action The action of this custom command.
     * @param extras An extra bundle for this custom command.
     */
    public Session2Command(@NonNull String action, @Nullable Bundle extras) {
        if (action == null) {
            throw new IllegalArgumentException("action shouldn't be null");
        }
        mCommandCode = COMMAND_CODE_CUSTOM;
        mCustomAction = action;
        mCustomExtras = extras;
    }

    /**
     * Used by parcelable creator.
     */
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    Session2Command(Parcel in) {
        mCommandCode = in.readInt();
        mCustomAction = in.readString();
        mCustomExtras = in.readBundle();
    }

    /**
     * Gets the command code of a predefined command.
     * This will return {@link #COMMAND_CODE_CUSTOM} for a custom command.
     */
    public int getCommandCode() {
        return mCommandCode;
    }

    /**
     * Gets the action of a custom command.
     * This will return {@code null} for a predefined command.
     */
    @Nullable
    public String getCustomAction() {
        return mCustomAction;
    }

    /**
     * Gets the extra bundle of a custom command.
     * This will return {@code null} for a predefined command.
     */
    @Nullable
    public Bundle getCustomExtras() {
        return mCustomExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("parcel shouldn't be null");
        }
        dest.writeInt(mCommandCode);
        dest.writeString(mCustomAction);
        dest.writeBundle(mCustomExtras);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof Session2Command)) {
            return false;
        }
        Session2Command other = (Session2Command) obj;
        return mCommandCode == other.mCommandCode
                && TextUtils.equals(mCustomAction, other.mCustomAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCustomAction, mCommandCode);
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Contains the result of {@link Session2Command}.
     */
    public static final class Result {
        private final int mResultCode;
        private final Bundle mResultData;

        /**
         * Result code representing that the command is skipped or canceled. For an example, a seek
         * command can be skipped if it is followed by another seek command.
         */
        public static final int RESULT_INFO_SKIPPED = 1;

        /**
         * Result code representing that the command is successfully completed.
         */
        public static final int RESULT_SUCCESS = 0;

        /**
         * Result code represents that call is ended with an unknown error.
         */
        public static final int RESULT_ERROR_UNKNOWN_ERROR = -1;

        /**
         * Constructor of {@link Result}.
         *
         * @param resultCode result code
         * @param resultData result data
         */
        public Result(int resultCode, @Nullable Bundle resultData) {
            mResultCode = resultCode;
            mResultData = resultData;
        }

        /**
         * Returns the result code.
         */
        public int getResultCode() {
            return mResultCode;
        }

        /**
         * Returns the result data.
         */
        @Nullable
        public Bundle getResultData() {
            return mResultData;
        }
    }
}
