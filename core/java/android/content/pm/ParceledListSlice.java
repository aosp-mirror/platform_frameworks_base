/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Builds up a parcel that is discarded when written to another parcel or
 * written to a list. This is useful for API that sends huge lists across a
 * Binder that may be larger than the IPC limit.
 *
 * @hide
 */
public class ParceledListSlice<T extends Parcelable> implements Parcelable {
    /*
     * TODO get this number from somewhere else. For now set it to a quarter of
     * the 1MB limit.
     */
    private static final int MAX_IPC_SIZE = 256 * 1024;

    private Parcel mParcel;

    private int mNumItems;

    private boolean mIsLastSlice;

    public ParceledListSlice() {
        mParcel = Parcel.obtain();
    }

    private ParceledListSlice(Parcel p, int numItems, boolean lastSlice) {
        mParcel = p;
        mNumItems = numItems;
        mIsLastSlice = lastSlice;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Write this to another Parcel. Note that this discards the internal Parcel
     * and should not be used anymore. This is so we can pass this to a Binder
     * where we won't have a chance to call recycle on this.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mNumItems);
        dest.writeInt(mIsLastSlice ? 1 : 0);

        if (mNumItems > 0) {
            final int parcelSize = mParcel.dataSize();
            dest.writeInt(parcelSize);
            dest.appendFrom(mParcel, 0, parcelSize);
        }

        mNumItems = 0;
        mParcel.recycle();
        mParcel = null;
    }

    /**
     * Appends a parcel to this list slice.
     *
     * @param item Parcelable item to append to this list slice
     * @return true when the list slice is full and should not be appended to
     *         anymore
     */
    public boolean append(T item) {
        if (mParcel == null) {
            throw new IllegalStateException("ParceledListSlice has already been recycled");
        }

        item.writeToParcel(mParcel, PARCELABLE_WRITE_RETURN_VALUE);
        mNumItems++;

        return mParcel.dataSize() > MAX_IPC_SIZE;
    }

    /**
     * Populates a list and discards the internal state of the
     * ParceledListSlice in the process. The instance should
     * not be used anymore.
     *
     * @param list list to insert items from this slice.
     * @param creator creator that knows how to unparcel the
     *        target object type.
     * @return the last item inserted into the list or null if none.
     */
    public T populateList(List<T> list, Creator<T> creator) {
        mParcel.setDataPosition(0);

        T item = null;
        for (int i = 0; i < mNumItems; i++) {
            item = creator.createFromParcel(mParcel);
            list.add(item);
        }

        mParcel.recycle();
        mParcel = null;

        return item;
    }

    /**
     * Sets whether this is the last list slice in the series.
     *
     * @param lastSlice
     */
    public void setLastSlice(boolean lastSlice) {
        mIsLastSlice = lastSlice;
    }

    /**
     * Returns whether this is the last slice in a series of slices.
     *
     * @return true if this is the last slice in the series.
     */
    public boolean isLastSlice() {
        return mIsLastSlice;
    }

    @SuppressWarnings("unchecked")
    public static final Parcelable.Creator<ParceledListSlice> CREATOR =
            new Parcelable.Creator<ParceledListSlice>() {
        public ParceledListSlice createFromParcel(Parcel in) {
            final int numItems = in.readInt();
            final boolean lastSlice = in.readInt() == 1;

            if (numItems > 0) {
                final int parcelSize = in.readInt();

                // Advance within this Parcel
                int offset = in.dataPosition();
                in.setDataPosition(offset + parcelSize);

                Parcel p = Parcel.obtain();
                p.setDataPosition(0);
                p.appendFrom(in, offset, parcelSize);
                p.setDataPosition(0);

                return new ParceledListSlice(p, numItems, lastSlice);
            } else {
                return new ParceledListSlice();
            }
        }

        public ParceledListSlice[] newArray(int size) {
            return new ParceledListSlice[size];
        }
    };
}
