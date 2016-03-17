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
    private final Bundle mVisibilityOverrides;
    private final Bundle mSuppressedVisualEffects;
    private final int[] mImportance;
    private final Bundle mImportanceExplanation;
    private final Bundle mOverrideGroupKeys;

    public NotificationRankingUpdate(String[] keys, String[] interceptedKeys,
            Bundle visibilityOverrides, Bundle suppressedVisualEffects,
            int[] importance, Bundle explanation, Bundle overrideGroupKeys) {
        mKeys = keys;
        mInterceptedKeys = interceptedKeys;
        mVisibilityOverrides = visibilityOverrides;
        mSuppressedVisualEffects = suppressedVisualEffects;
        mImportance = importance;
        mImportanceExplanation = explanation;
        mOverrideGroupKeys = overrideGroupKeys;
    }

    public NotificationRankingUpdate(Parcel in) {
        mKeys = in.readStringArray();
        mInterceptedKeys = in.readStringArray();
        mVisibilityOverrides = in.readBundle();
        mSuppressedVisualEffects = in.readBundle();
        mImportance = new int[mKeys.length];
        in.readIntArray(mImportance);
        mImportanceExplanation = in.readBundle();
        mOverrideGroupKeys = in.readBundle();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStringArray(mKeys);
        out.writeStringArray(mInterceptedKeys);
        out.writeBundle(mVisibilityOverrides);
        out.writeBundle(mSuppressedVisualEffects);
        out.writeIntArray(mImportance);
        out.writeBundle(mImportanceExplanation);
        out.writeBundle(mOverrideGroupKeys);
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

    public String[] getInterceptedKeys() {
        return mInterceptedKeys;
    }

    public Bundle getVisibilityOverrides() {
        return mVisibilityOverrides;
    }

    public Bundle getSuppressedVisualEffects() {
        return mSuppressedVisualEffects;
    }

    public int[] getImportance() {
        return mImportance;
    }

    public Bundle getImportanceExplanation() {
        return mImportanceExplanation;
    }

    public Bundle getOverrideGroupKeys() {
        return mOverrideGroupKeys;
    }
}
