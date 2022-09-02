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

package com.android.internal.inputmethod;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A common IPC header used behind {@link RemoteInputConnectionImpl} and
 * {@link android.inputmethodservice.RemoteInputConnection}.
 */
public final class InputConnectionCommandHeader implements Parcelable {
    /**
     * An identifier that is to be used when multiplexing multiple sessions into a single
     * {@link com.android.internal.view.IInputContext}.
     *
     * <p>This ID is considered to belong to an implicit namespace defined for each
     * {@link com.android.internal.view.IInputContext} instance.  Uniqueness of the session ID
     * across multiple instances of {@link com.android.internal.view.IInputContext} is not
     * guaranteed unless explicitly noted in a higher layer.</p>
     */
    public final int mSessionId;

    public InputConnectionCommandHeader(int sessionId) {
        mSessionId = sessionId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<InputConnectionCommandHeader> CREATOR =
            new Parcelable.Creator<InputConnectionCommandHeader>() {
                @NonNull
                public InputConnectionCommandHeader createFromParcel(Parcel in) {
                    final int sessionId = in.readInt();
                    return new InputConnectionCommandHeader(sessionId);
                }

                @NonNull
                public InputConnectionCommandHeader[] newArray(int size) {
                    return new InputConnectionCommandHeader[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSessionId);
    }
}
