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

package com.android.settingslib.wifi;

import android.net.ScoredNetwork;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data encapsulation object to associate a time with a {@link ScoredNetwork}
 */
class TimestampedScoredNetwork implements Parcelable {
    private ScoredNetwork mScore;
    private long mUpdatedTimestampMillis;

    TimestampedScoredNetwork(ScoredNetwork score, long updatedTimestampMillis) {
        mScore = score;
        mUpdatedTimestampMillis = updatedTimestampMillis;
    }

    protected TimestampedScoredNetwork(Parcel in) {
        mScore = in.readParcelable(ScoredNetwork.class.getClassLoader());
        mUpdatedTimestampMillis = in.readLong();
    }

    public void update(ScoredNetwork score, long updatedTimestampMillis) {
        mScore = score;
        mUpdatedTimestampMillis = updatedTimestampMillis;
    }

    public ScoredNetwork getScore() {
        return mScore;
    }

    public long getUpdatedTimestampMillis() {
        return mUpdatedTimestampMillis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mScore, flags);
        dest.writeLong(mUpdatedTimestampMillis);
    }

    public static final Creator<TimestampedScoredNetwork> CREATOR =
            new Creator<TimestampedScoredNetwork>() {
                @Override
                public TimestampedScoredNetwork createFromParcel(Parcel in) {
                    return new TimestampedScoredNetwork(in);
                }

                @Override
                public TimestampedScoredNetwork[] newArray(int size) {
                    return new TimestampedScoredNetwork[size];
                }
            };
}
