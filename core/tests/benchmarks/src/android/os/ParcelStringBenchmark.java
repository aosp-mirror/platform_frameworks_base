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

package android.os;

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Param;

public class ParcelStringBenchmark {

    @Param({"com.example.typical_package_name", "從不喜歡孤單一個 - 蘇永康／吳雨霏"})
    String mValue;

    private Parcel mParcel;

    @BeforeExperiment
    protected void setUp() {
        mParcel = Parcel.obtain();
    }

    @AfterExperiment
    protected void tearDown() {
        mParcel.recycle();
        mParcel = null;
    }

    public void timeWriteString8(int reps) {
        for (int i = 0; i < reps; i++) {
            mParcel.setDataPosition(0);
            mParcel.writeString8(mValue);
        }
    }

    public void timeReadString8(int reps) {
        mParcel.writeString8(mValue);

        for (int i = 0; i < reps; i++) {
            mParcel.setDataPosition(0);
            mParcel.readString8();
        }
    }

    public void timeWriteString16(int reps) {
        for (int i = 0; i < reps; i++) {
            mParcel.setDataPosition(0);
            mParcel.writeString16(mValue);
        }
    }

    public void timeReadString16(int reps) {
        mParcel.writeString16(mValue);

        for (int i = 0; i < reps; i++) {
            mParcel.setDataPosition(0);
            mParcel.readString16();
        }
    }
}
