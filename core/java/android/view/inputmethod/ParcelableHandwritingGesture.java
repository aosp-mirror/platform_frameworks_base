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

package android.view.inputmethod;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A generic container of parcelable {@link HandwritingGesture}.
 *
 * @hide
 */
public final class ParcelableHandwritingGesture implements Parcelable {
    @NonNull
    private final HandwritingGesture mGesture;
    @NonNull
    private final Parcelable mGestureAsParcelable;

    private ParcelableHandwritingGesture(@NonNull HandwritingGesture gesture) {
        mGesture = gesture;
        // For fail-fast.
        mGestureAsParcelable = (Parcelable) gesture;
    }

    /**
     * Creates {@link ParcelableHandwritingGesture} from {@link HandwritingGesture}, which also
     * implements {@link Parcelable}.
     *
     * @param gesture {@link HandwritingGesture} object to be stored.
     * @return {@link ParcelableHandwritingGesture} to be stored in {@link Parcel}.
     */
    @NonNull
    public static ParcelableHandwritingGesture of(@NonNull HandwritingGesture gesture) {
        return new ParcelableHandwritingGesture(Objects.requireNonNull(gesture));
    }

    /**
     * @return {@link HandwritingGesture} object stored in this container.
     */
    @NonNull
    public HandwritingGesture get() {
        return mGesture;
    }

    private static HandwritingGesture createFromParcelInternal(
            @HandwritingGesture.GestureType int gestureType, @NonNull Parcel parcel) {
        switch (gestureType) {
            case HandwritingGesture.GESTURE_TYPE_NONE:
                throw new UnsupportedOperationException("GESTURE_TYPE_NONE is not supported");
            case HandwritingGesture.GESTURE_TYPE_SELECT:
                return SelectGesture.CREATOR.createFromParcel(parcel);
            case HandwritingGesture.GESTURE_TYPE_SELECT_RANGE:
                return SelectRangeGesture.CREATOR.createFromParcel(parcel);
            case HandwritingGesture.GESTURE_TYPE_INSERT:
                return InsertGesture.CREATOR.createFromParcel(parcel);
            case HandwritingGesture.GESTURE_TYPE_INSERT_MODE:
                return InsertModeGesture.CREATOR.createFromParcel(parcel);
            case HandwritingGesture.GESTURE_TYPE_DELETE:
                return DeleteGesture.CREATOR.createFromParcel(parcel);
            case HandwritingGesture.GESTURE_TYPE_DELETE_RANGE:
                return DeleteRangeGesture.CREATOR.createFromParcel(parcel);
            case HandwritingGesture.GESTURE_TYPE_JOIN_OR_SPLIT:
                return JoinOrSplitGesture.CREATOR.createFromParcel(parcel);
            case HandwritingGesture.GESTURE_TYPE_REMOVE_SPACE:
                return RemoveSpaceGesture.CREATOR.createFromParcel(parcel);
            default:
                throw new UnsupportedOperationException("Unknown type=" + gestureType);
        }
    }

    public static final Creator<ParcelableHandwritingGesture> CREATOR = new Parcelable.Creator<>() {
        @Override
        public ParcelableHandwritingGesture createFromParcel(Parcel in) {
            final int gestureType = in.readInt();
            return new ParcelableHandwritingGesture(createFromParcelInternal(gestureType, in));
        }

        @Override
        public ParcelableHandwritingGesture[] newArray(int size) {
            return new ParcelableHandwritingGesture[size];
        }
    };

    @Override
    public int describeContents() {
        return mGestureAsParcelable.describeContents();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mGesture.getGestureType());
        mGestureAsParcelable.writeToParcel(dest, flags);
    }
}
