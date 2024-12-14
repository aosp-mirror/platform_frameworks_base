/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.appfunctions;

import android.app.appsearch.GenericDocument;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * The Parcelable object contains a {@link GenericDocument} to allow the parcelization of it
 * exceeding the binder limit.
 *
 * <p>{#link {@link Parcel#writeBlob(byte[])}} could take care of whether to pass data via binder
 * directly or Android shared memory if the data is large.
 *
 * @hide
 * @see Parcel#writeBlob(byte[])
 */
public final class GenericDocumentWrapper implements Parcelable {
    public static final Creator<GenericDocumentWrapper> CREATOR =
            new Creator<>() {
                @Override
                public GenericDocumentWrapper createFromParcel(Parcel in) {
                    byte[] dataBlob = Objects.requireNonNull(in.readBlob());
                    Parcel unmarshallParcel = Parcel.obtain();
                    try {
                        unmarshallParcel.unmarshall(dataBlob, 0, dataBlob.length);
                        unmarshallParcel.setDataPosition(0);
                        return new GenericDocumentWrapper(
                                GenericDocument.createFromParcel(unmarshallParcel));
                    } finally {
                        unmarshallParcel.recycle();
                    }
                }

                @Override
                public GenericDocumentWrapper[] newArray(int size) {
                    return new GenericDocumentWrapper[size];
                }
            };
    @NonNull private final GenericDocument mGenericDocument;

    public GenericDocumentWrapper(@NonNull GenericDocument genericDocument) {
        mGenericDocument = Objects.requireNonNull(genericDocument);
    }

    /** Returns the wrapped {@link android.app.appsearch.GenericDocument} */
    @NonNull
    public GenericDocument getValue() {
        return mGenericDocument;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Parcel parcel = Parcel.obtain();
        try {
            mGenericDocument.writeToParcel(parcel, flags);
            byte[] bytes = parcel.marshall();
            dest.writeBlob(bytes);
        } finally {
            parcel.recycle();
        }
    }
}
