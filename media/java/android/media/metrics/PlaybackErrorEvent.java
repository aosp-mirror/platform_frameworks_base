/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.metrics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.util.Objects;

/**
 * Playback error event.
 * @hide
 */
public final class PlaybackErrorEvent implements Parcelable {
    public static final int ERROR_CODE_UNKNOWN = 0;
    public static final int ERROR_CODE_OTHER = 1;
    public static final int ERROR_CODE_RUNTIME = 2;

    private final @Nullable String mExceptionStack;
    private final int mErrorCode;
    private final int mSubErrorCode;
    private final long mTimeSincePlaybackCreatedMillis;


    /** @hide */
    // TODO: more error types
    @IntDef(prefix = "ERROR_CODE_", value = {
        ERROR_CODE_UNKNOWN,
        ERROR_CODE_OTHER,
        ERROR_CODE_RUNTIME
    })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /**
     * Creates a new PlaybackErrorEvent.
     *
     * @hide
     */
    public PlaybackErrorEvent(
            @Nullable String exceptionStack,
            int errorCode,
            int subErrorCode,
            long timeSincePlaybackCreatedMillis) {
        this.mExceptionStack = exceptionStack;
        this.mErrorCode = errorCode;
        this.mSubErrorCode = subErrorCode;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
    }

    /** @hide */
    @Nullable
    public String getExceptionStack() {
        return mExceptionStack;
    }

    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }

    public int getSubErrorCode() {
        return mSubErrorCode;
    }

    public long getTimeSincePlaybackCreatedMillis() {
        return mTimeSincePlaybackCreatedMillis;
    }

    @Override
    public String toString() {
        return "PlaybackErrorEvent { "
                + "exceptionStack = " + mExceptionStack + ", "
                + "errorCode = " + mErrorCode + ", "
                + "subErrorCode = " + mSubErrorCode + ", "
                + "timeSincePlaybackCreatedMillis = " + mTimeSincePlaybackCreatedMillis
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackErrorEvent that = (PlaybackErrorEvent) o;
        return Objects.equals(mExceptionStack, that.mExceptionStack)
                && mErrorCode == that.mErrorCode
                && mSubErrorCode == that.mSubErrorCode
                && mTimeSincePlaybackCreatedMillis == that.mTimeSincePlaybackCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mExceptionStack, mErrorCode, mSubErrorCode,
                mTimeSincePlaybackCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        byte flg = 0;
        if (mExceptionStack != null) flg |= 0x1;
        dest.writeByte(flg);
        if (mExceptionStack != null) dest.writeString(mExceptionStack);
        dest.writeInt(mErrorCode);
        dest.writeInt(mSubErrorCode);
        dest.writeLong(mTimeSincePlaybackCreatedMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ PlaybackErrorEvent(@NonNull Parcel in) {
        byte flg = in.readByte();
        String exceptionStack = (flg & 0x1) == 0 ? null : in.readString();
        int errorCode = in.readInt();
        int subErrorCode = in.readInt();
        long timeSincePlaybackCreatedMillis = in.readLong();

        this.mExceptionStack = exceptionStack;
        this.mErrorCode = errorCode;
        this.mSubErrorCode = subErrorCode;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
    }

    public static final @NonNull Parcelable.Creator<PlaybackErrorEvent> CREATOR =
            new Parcelable.Creator<PlaybackErrorEvent>() {
        @Override
        public PlaybackErrorEvent[] newArray(int size) {
            return new PlaybackErrorEvent[size];
        }

        @Override
        public PlaybackErrorEvent createFromParcel(@NonNull Parcel in) {
            return new PlaybackErrorEvent(in);
        }
    };

    /**
     * A builder for {@link PlaybackErrorEvent}
     */
    public static final class Builder {
        private @Nullable Exception mException;
        private int mErrorCode;
        private int mSubErrorCode;
        private long mTimeSincePlaybackCreatedMillis;

        /**
         * Creates a new Builder.
         *
         * @hide
         */
        public Builder(
                @Nullable Exception exception,
                int errorCode,
                int subErrorCode,
                long timeSincePlaybackCreatedMillis) {
            mException = exception;
            mErrorCode = errorCode;
            mSubErrorCode = subErrorCode;
            mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
        }

        /**
         * Sets the {@link Exception} object.
         */
        public @NonNull Builder setException(@NonNull Exception value) {
            mException = value;
            return this;
        }

        /**
         * Sets error code.
         */
        public @NonNull Builder setErrorCode(@ErrorCode int value) {
            mErrorCode = value;
            return this;
        }

        /**
         * Sets sub error code.
         */
        public @NonNull Builder setSubErrorCode(int value) {
            mSubErrorCode = value;
            return this;
        }

        /**
         * Set the timestamp in milliseconds.
         */
        public @NonNull Builder setTimeSincePlaybackCreatedMillis(long value) {
            mTimeSincePlaybackCreatedMillis = value;
            return this;
        }

        /** Builds the instance. */
        public @NonNull PlaybackErrorEvent build() {

            String stack;
            if (mException.getStackTrace() != null && mException.getStackTrace().length > 0) {
                // TODO: a better definition of the stack trace
                stack = mException.getStackTrace()[0].toString();
            } else {
                stack = null;
            }

            PlaybackErrorEvent o = new PlaybackErrorEvent(
                    stack,
                    mErrorCode,
                    mSubErrorCode,
                    mTimeSincePlaybackCreatedMillis);
            return o;
        }
    }
}
