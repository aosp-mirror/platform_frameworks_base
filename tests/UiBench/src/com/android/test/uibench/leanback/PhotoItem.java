/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.test.uibench.leanback;

import android.os.Parcel;
import android.os.Parcelable;

public class PhotoItem implements Parcelable {

    private String mTitle;
    private long mId;

    public PhotoItem(String title, long id) {
        mTitle = title;
        mId = id;
    }

    public long getId() {
        return mId;
    }

    public String getTitle() {
        return mTitle;
    }

    @Override
    public String toString() {
        return mTitle;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mTitle);
        dest.writeLong(mId);
    }

    public static final Creator<PhotoItem> CREATOR
            = new Creator<PhotoItem>() {
        @Override
        public PhotoItem createFromParcel(Parcel in) {
            return new PhotoItem(in);
        }

        @Override
        public PhotoItem[] newArray(int size) {
            return new PhotoItem[size];
        }
    };

    private PhotoItem(Parcel in) {
        mTitle = in.readString();
        mId = in.readLong();
    }
}
