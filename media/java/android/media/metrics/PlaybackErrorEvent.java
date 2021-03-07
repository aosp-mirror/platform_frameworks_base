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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.util.Objects;

/**
 * Playback error event.
 */
public final class PlaybackErrorEvent extends Event implements Parcelable {
    /** Unknown error code. */
    public static final int ERROR_CODE_UNKNOWN = 0;
    /** Error code for other errors */
    public static final int ERROR_CODE_OTHER = 1;
    /** Error code for runtime errors */
    public static final int ERROR_CODE_RUNTIME = 2;

    private final @Nullable String mExceptionStack;
    private final int mErrorCode;
    private final int mSubErrorCode;
    private final long mTimeSinceCreatedMillis;


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
            long timeSinceCreatedMillis,
            Bundle extras) {
        this.mExceptionStack = exceptionStack;
        this.mErrorCode = errorCode;
        this.mSubErrorCode = subErrorCode;
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mExtras = extras.deepCopy();
    }

    /** @hide */
    @Nullable
    public String getExceptionStack() {
        return mExceptionStack;
    }


    /**
     * Gets error code.
     */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }


    /**
     * Gets sub error code.
     */
    @IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE)
    public int getSubErrorCode() {
        return mSubErrorCode;
    }

    /**
     * Gets the timestamp since creation in milliseconds.
     * @return the timestamp since the playback is created, or -1 if unknown.
     */
    @Override
    @IntRange(from = -1)
    public long getTimeSinceCreatedMillis() {
        return mTimeSinceCreatedMillis;
    }

    @Override
    public String toString() {
        return "PlaybackErrorEvent { "
                + "exceptionStack = " + mExceptionStack + ", "
                + "errorCode = " + mErrorCode + ", "
                + "subErrorCode = " + mSubErrorCode + ", "
                + "timeSinceCreatedMillis = " + mTimeSinceCreatedMillis
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
                && mTimeSinceCreatedMillis == that.mTimeSinceCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mExceptionStack, mErrorCode, mSubErrorCode,
            mTimeSinceCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        byte flg = 0;
        if (mExceptionStack != null) flg |= 0x1;
        if (mExtras != null) flg |= 0x2;
        dest.writeByte(flg);
        if (mExceptionStack != null) dest.writeString(mExceptionStack);
        dest.writeInt(mErrorCode);
        dest.writeInt(mSubErrorCode);
        dest.writeLong(mTimeSinceCreatedMillis);
        if (mExtras != null) dest.writeBundle(mExtras);
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
        long timeSinceCreatedMillis = in.readLong();
        Bundle extras = (flg & 0x2) == 0 ? null : in.readBundle();

        this.mExceptionStack = exceptionStack;
        this.mErrorCode = errorCode;
        this.mSubErrorCode = subErrorCode;
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mExtras = extras;
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
        private long mTimeSinceCreatedMillis = -1;
        private Bundle mExtras;

        /**
         * Creates a new Builder.
         */
        public Builder() {
        }

        /**
         * Sets the {@link Exception} object.
         */
        @SuppressLint("MissingGetterMatchingBuilder") // Exception is not parcelable.
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
        public @NonNull Builder setSubErrorCode(
                @IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE) int value) {
            mSubErrorCode = value;
            return this;
        }

        /**
         * Set the timestamp since creation in milliseconds.
         * @param value the timestamp since the creation in milliseconds.
         *              -1 indicates the value is unknown.
         */
        public @NonNull Builder setTimeSinceCreatedMillis(@IntRange(from = -1) long value) {
            mTimeSinceCreatedMillis = value;
            return this;
        }

        /**
         * Set extras for compatibility.
         * <p>Should be used by support library only.
         * @hide
         */
        public @NonNull Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
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
                    mTimeSinceCreatedMillis,
                    mExtras);
            return o;
        }
    }
}
