/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os.health;

import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class to allow sending the HealthStats through aidl generated glue.
 *
 * The alternative would be to send a HealthStats object, which would
 * require constructing one, and then immediately flattening it. This
 * saves that step at the cost of doing the extra flattening when
 * accessed in the same process as the writer.
 *
 * The HealthStatsWriter passed in the constructor is retained, so don't
 * reuse them.
 * @hide
 */
@TestApi
public class HealthStatsParceler implements Parcelable {
    private HealthStatsWriter mWriter;
    private HealthStats mHealthStats;

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<HealthStatsParceler> CREATOR
            = new Parcelable.Creator<HealthStatsParceler>() {
        public HealthStatsParceler createFromParcel(Parcel in) {
            return new HealthStatsParceler(in);
        }

        public HealthStatsParceler[] newArray(int size) {
            return new HealthStatsParceler[size];
        }
    };

    public HealthStatsParceler(HealthStatsWriter writer) {
        mWriter = writer;
    }

    public HealthStatsParceler(Parcel in) {
        mHealthStats = new HealthStats(in);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        // See comment on mWriter declaration above.
        if (mWriter != null) {
            mWriter.flattenToParcel(out);
        } else {
            throw new RuntimeException("Can not re-parcel HealthStatsParceler that was"
                    + " constructed from a Parcel");
        }
    }

    public HealthStats getHealthStats() {
        if (mWriter != null) {
            final Parcel parcel = Parcel.obtain();
            mWriter.flattenToParcel(parcel);
            parcel.setDataPosition(0);
            mHealthStats = new HealthStats(parcel);
            parcel.recycle();
        }

        return mHealthStats;
    }
}

