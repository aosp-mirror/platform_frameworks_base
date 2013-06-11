/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class encapsulates information about a {@link PrintAdapter} object.
 */
public final class PrintAdapterInfo implements Parcelable {

    /**
     * Constant for unknown page count.
     */
    public static final int PAGE_COUNT_UNKNOWN = -1;

    private int mPageCount;
    private int mFlags;

    /**
     * Creates a new instance.
     */
    private PrintAdapterInfo() {
        /* do nothing */
    }

    /**
     * Creates a new instance.
     *
     * @param parcel Data from which to initialize.
     */
    private PrintAdapterInfo(Parcel parcel) {
        mPageCount = parcel.readInt();
        mFlags = parcel.readInt();
    }

    /**
     * Gets the total number of pages.
     *
     * @return The number of pages.
     */
    public int getPageCount() {
        return mPageCount;
    }

    /**
     * @return The flags of this printable info.
     *
     * @see #FLAG_NOTIFY_FOR_ATTRIBUTES_CHANGE
     */
    public int getFlags() {
        return mFlags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mPageCount);
        parcel.writeInt(mFlags);
    }

    /**
     * Builder for creating an {@link PrintAdapterInfo}.
     */
    public static final class Builder {
        private final PrintAdapterInfo mPrintableInfo = new PrintAdapterInfo();

        /**
         * Sets the total number of pages.
         *
         * @param pageCount The number of pages. Must be
         * greater than zero.
         */
        public Builder setPageCount(int pageCount) {
            if (pageCount < 0) {
                throw new IllegalArgumentException("pageCount"
                        + " must be greater than or euqal to zero!");
            }
            mPrintableInfo.mPageCount = pageCount;
            return this;
        }

        /**
         * Sets the flags of this printable info.
         *
         * @param flags The flags.
         *
         * @see #FLAG_NOTIFY_FOR_ATTRIBUTES_CHANGE
         */
        public Builder setFlags(int flags) {
            mPrintableInfo.mFlags = flags;
            return this;
        }

        /**
         * Creates a new {@link PrintAdapterInfo} instance.
         *
         * @return The new instance.
         */
        public PrintAdapterInfo create() {
            return mPrintableInfo;
        }
    }

    public static final Parcelable.Creator<PrintAdapterInfo> CREATOR =
            new Creator<PrintAdapterInfo>() {
        @Override
        public PrintAdapterInfo createFromParcel(Parcel parcel) {
            return new PrintAdapterInfo(parcel);
        }

        @Override
        public PrintAdapterInfo[] newArray(int size) {
            return new PrintAdapterInfo[size];
        }
    };
}
