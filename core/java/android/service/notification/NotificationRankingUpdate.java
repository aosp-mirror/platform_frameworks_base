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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class NotificationRankingUpdate implements Parcelable {
    // TODO: Support incremental updates.
    private final String[] mKeys;
    private final String[] mInterceptedKeys;
    private final int mFirstAmbientIndex;
    private final Bundle mVisibilityOverrides;

    public NotificationRankingUpdate(String[] keys, String[] interceptedKeys,
            Bundle visibilityOverrides, int firstAmbientIndex) {
        mKeys = keys;
        mFirstAmbientIndex = firstAmbientIndex;
        mInterceptedKeys = interceptedKeys;
        mVisibilityOverrides = visibilityOverrides;
    }

    public NotificationRankingUpdate(Parcel in) {
        mKeys = in.readStringArray();
        mFirstAmbientIndex = in.readInt();
        mInterceptedKeys = in.readStringArray();
        mVisibilityOverrides = in.readBundle();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStringArray(mKeys);
        out.writeInt(mFirstAmbientIndex);
        out.writeStringArray(mInterceptedKeys);
        out.writeBundle(mVisibilityOverrides);
    }

    public static final Parcelable.Creator<NotificationRankingUpdate> CREATOR
            = new Parcelable.Creator<NotificationRankingUpdate>() {
        public NotificationRankingUpdate createFromParcel(Parcel parcel) {
            return new NotificationRankingUpdate(parcel);
        }

        public NotificationRankingUpdate[] newArray(int size) {
            return new NotificationRankingUpdate[size];
        }
    };

    public String[] getOrderedKeys() {
        return mKeys;
    }

    public int getFirstAmbientIndex() {
        return mFirstAmbientIndex;
    }

    public String[] getInterceptedKeys() {
        return mInterceptedKeys;
    }

    public Bundle getVisibilityOverrides() {
        return mVisibilityOverrides;
    }
}
