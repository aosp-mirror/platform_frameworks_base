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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A {@link Parcelable} helper class to encapsulate the exception information.
 */
public final class ThrowableHolder implements Parcelable {

    @Nullable
    private final String mMessage;

    ThrowableHolder(@NonNull Throwable throwable) {
        mMessage = throwable.getMessage();
    }

    ThrowableHolder(Parcel source) {
        mMessage = source.readString();
    }

    /**
     * Returns a {@link ThrowableHolder} with given {@link Throwable}.
     */
    @NonNull
    @AnyThread
    public static ThrowableHolder of(@NonNull Throwable throwable) {
        return new ThrowableHolder(throwable);
    }

    /**
     * Gets the message in this {@link ThrowableHolder}.
     */
    @Nullable
    @AnyThread
    String getMessage() {
        return mMessage;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mMessage);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<ThrowableHolder> CREATOR =
            new Parcelable.Creator<ThrowableHolder>() {

        @Override
        public ThrowableHolder createFromParcel(Parcel source) {
            return new ThrowableHolder(source);
        }

        @Override
        public ThrowableHolder[] newArray(int size) {
            return new ThrowableHolder[size];
        }
    };
}
