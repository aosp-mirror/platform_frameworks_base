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

import android.annotation.Nullable;
import android.app.appsearch.GenericDocument;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.MathUtils;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * The Parcelable object contains a {@link GenericDocument} to allow the parcelization of it
 * exceeding the binder limit.
 *
 * <p>{#link {@link Parcel#writeBlob(byte[])}} could take care of whether to pass data via binder
 * directly or Android shared memory if the data is large.
 *
 * <p>This class performs lazy unparcelling. The `GenericDocument` is only unparcelled from the
 * underlying `Parcel` when {@link #getValue()} is called. This optimization allows the system
 * server to pass through the generic document, without unparcel and parcel it.
 *
 * @hide
 * @see Parcel#writeBlob(byte[])
 */
public final class GenericDocumentWrapper implements Parcelable {
    @Nullable
    @GuardedBy("mLock")
    private GenericDocument mGenericDocument;

    @GuardedBy("mLock")
    @Nullable
    private Parcel mParcel;

    @GuardedBy("mLock")
    @Nullable
    private Integer mDataSize;

    private final Object mLock = new Object();

    public static final Creator<GenericDocumentWrapper> CREATOR =
            new Creator<>() {
                @Override
                public GenericDocumentWrapper createFromParcel(Parcel in) {
                    int length = in.readInt();
                    int offset = in.dataPosition();
                    in.setDataPosition(MathUtils.addOrThrow(offset, length));

                    Parcel p = Parcel.obtain();
                    p.appendFrom(in, offset, length);
                    p.setDataPosition(0);
                    return new GenericDocumentWrapper(p);
                }

                @Override
                public GenericDocumentWrapper[] newArray(int size) {
                    return new GenericDocumentWrapper[size];
                }
            };

    public GenericDocumentWrapper(@NonNull GenericDocument genericDocument) {
        mGenericDocument = Objects.requireNonNull(genericDocument);
        mParcel = null;
        mDataSize = null;
    }

    public GenericDocumentWrapper(@NonNull Parcel parcel) {
        mGenericDocument = null;
        mParcel = Objects.requireNonNull(parcel);
        mDataSize = mParcel.dataSize();
    }

    /** Returns the wrapped {@link android.app.appsearch.GenericDocument} */
    @NonNull
    public GenericDocument getValue() {
        unparcel();
        synchronized (mLock) {
            return Objects.requireNonNull(mGenericDocument);
        }
    }

    private void unparcel() {
        synchronized (mLock) {
            if (mGenericDocument != null) {
                return;
            }
            byte[] dataBlob = Objects.requireNonNull(Objects.requireNonNull(mParcel).readBlob());
            Parcel unmarshallParcel = Parcel.obtain();
            try {
                unmarshallParcel.unmarshall(dataBlob, 0, dataBlob.length);
                unmarshallParcel.setDataPosition(0);
                mGenericDocument = GenericDocument.createFromParcel(unmarshallParcel);
                mParcel = null;
            } finally {
                unmarshallParcel.recycle();
            }
        }
    }

    /** Returns the size of the parcelled document. */

    int getDataSize() {
        synchronized (mLock) {
            if (mDataSize != null) {
                return mDataSize;
            }
            Parcel tempParcel = Parcel.obtain();
            writeToParcel(tempParcel, 0);
            mDataSize = tempParcel.dataSize();
            tempParcel.recycle();
            return mDataSize;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        synchronized (mLock) {
            if (mGenericDocument != null) {
                int lengthPos = dest.dataPosition();
                // write a placeholder for length
                dest.writeInt(-1);
                Parcel tempParcel = Parcel.obtain();
                byte[] bytes;
                try {
                    mGenericDocument.writeToParcel(tempParcel, flags);
                    bytes = tempParcel.marshall();
                } finally {
                    tempParcel.recycle();
                }
                int startPos = dest.dataPosition();
                dest.writeBlob(bytes);
                int endPos = dest.dataPosition();
                dest.setDataPosition(lengthPos);
                // Overwrite the length placeholder
                dest.writeInt(endPos - startPos);
                dest.setDataPosition(endPos);

            } else {
                Parcel originalParcel = Objects.requireNonNull(mParcel);
                dest.writeInt(originalParcel.dataSize());
                dest.appendFrom(originalParcel, 0, originalParcel.dataSize());
            }
        }
    }
}
