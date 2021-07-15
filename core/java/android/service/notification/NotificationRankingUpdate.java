/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.service.notification;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class NotificationRankingUpdate implements Parcelable {
    private final NotificationListenerService.RankingMap mRankingMap;

    public NotificationRankingUpdate(NotificationListenerService.Ranking[] rankings) {
        mRankingMap = new NotificationListenerService.RankingMap(rankings);
    }

    public NotificationRankingUpdate(Parcel in) {
        mRankingMap = in.readParcelable(getClass().getClassLoader());
    }

    public NotificationListenerService.RankingMap getRankingMap() {
        return mRankingMap;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NotificationRankingUpdate other = (NotificationRankingUpdate) o;
        return mRankingMap.equals(other.mRankingMap);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mRankingMap, flags);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<NotificationRankingUpdate> CREATOR
            = new Parcelable.Creator<NotificationRankingUpdate>() {
        public NotificationRankingUpdate createFromParcel(Parcel parcel) {
            return new NotificationRankingUpdate(parcel);
        }

        public NotificationRankingUpdate[] newArray(int size) {
            return new NotificationRankingUpdate[size];
        }
    };
}
