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
 * limitations under the License
 */

package android.nfc;

import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public class TechListParcel implements Parcelable {

    private String[][] mTechLists;

    public TechListParcel(String[]... strings) {
        mTechLists = strings;
    }

    public String[][] getTechLists() {
        return mTechLists;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        int count = mTechLists.length;
        dest.writeInt(count);
        for (int i = 0; i < count; i++) {
            String[] techList = mTechLists[i];
            dest.writeStringArray(techList);
        }
    }

    public static final Creator<TechListParcel> CREATOR = new Creator<TechListParcel>() {
        @Override
        public TechListParcel createFromParcel(Parcel source) {
            int count = source.readInt();
            String[][] techLists = new String[count][];
            for (int i = 0; i < count; i++) {
                techLists[i] = source.readStringArray();
            }
            return new TechListParcel(techLists);
        }

        @Override
        public TechListParcel[] newArray(int size) {
            return new TechListParcel[size];
        }
    };
}
