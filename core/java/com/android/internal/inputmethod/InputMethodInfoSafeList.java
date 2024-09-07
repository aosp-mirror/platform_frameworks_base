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

package com.android.internal.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.inputmethod.InputMethodInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link Parcelable} container that can holds an arbitrary number of {@link InputMethodInfo}
 * without worrying about {@link android.os.TransactionTooLargeException} when passing across
 * process boundary.
 *
 * @see Parcel#readBlob()
 * @see Parcel#writeBlob(byte[])
 */
public final class InputMethodInfoSafeList implements Parcelable {
    @Nullable
    private byte[] mBuffer;

    /**
     * Instantiates a list of {@link InputMethodInfo} from the given {@link InputMethodInfoSafeList}
     * then clears the internal buffer of {@link InputMethodInfoSafeList}.
     *
     * <p>Note that each {@link InputMethodInfo} item is guaranteed to be a copy of the original
     * {@link InputMethodInfo} object.</p>
     *
     * <p>Any subsequent call will return an empty list.</p>
     *
     * @param from {@link InputMethodInfoSafeList} from which the list of {@link InputMethodInfo}
     *             will be extracted
     * @return list of {@link InputMethodInfo} stored in the given {@link InputMethodInfoSafeList}
     */
    @NonNull
    public static List<InputMethodInfo> extractFrom(@Nullable InputMethodInfoSafeList from) {
        final byte[] buf = from.mBuffer;
        from.mBuffer = null;
        if (buf != null) {
            final InputMethodInfo[] array = unmarshall(buf);
            if (array != null) {
                return new ArrayList<>(Arrays.asList(array));
            }
        }
        return new ArrayList<>();
    }

    @NonNull
    private static InputMethodInfo[] toArray(@Nullable List<InputMethodInfo> original) {
        if (original == null) {
            return new InputMethodInfo[0];
        }
        return original.toArray(new InputMethodInfo[0]);
    }

    @Nullable
    private static byte[] marshall(@NonNull InputMethodInfo[] array) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeTypedArray(array, 0);
            return parcel.marshall();
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    @Nullable
    private static InputMethodInfo[] unmarshall(byte[] data) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return parcel.createTypedArray(InputMethodInfo.CREATOR);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    private InputMethodInfoSafeList(@Nullable byte[] blob) {
        mBuffer = blob;
    }

    /**
     * Instantiates {@link InputMethodInfoSafeList} from the given list of {@link InputMethodInfo}.
     *
     * @param list list of {@link InputMethodInfo} from which {@link InputMethodInfoSafeList} will
     *             be created
     * @return {@link InputMethodInfoSafeList} that stores the given list of {@link InputMethodInfo}
     */
    @NonNull
    public static InputMethodInfoSafeList create(@Nullable List<InputMethodInfo> list) {
        if (list == null || list.isEmpty()) {
            return empty();
        }
        return new InputMethodInfoSafeList(marshall(toArray(list)));
    }

    /**
     * Creates an empty {@link InputMethodInfoSafeList}.
     *
     * @return {@link InputMethodInfoSafeList} that is empty
     */
    @NonNull
    public static InputMethodInfoSafeList empty() {
        return new InputMethodInfoSafeList(null);
    }

    public static final Creator<InputMethodInfoSafeList> CREATOR = new Creator<>() {
        @Override
        public InputMethodInfoSafeList createFromParcel(Parcel in) {
            return new InputMethodInfoSafeList(in.readBlob());
        }

        @Override
        public InputMethodInfoSafeList[] newArray(int size) {
            return new InputMethodInfoSafeList[size];
        }
    };

    @Override
    public int describeContents() {
        // As long as InputMethodInfo#describeContents() is guaranteed to return 0, we can always
        // return 0 here.
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBlob(mBuffer);
    }
}
