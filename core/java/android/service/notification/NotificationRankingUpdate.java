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
    private final Bundle mChannels;
    private final Bundle mOverridePeople;
    private final Bundle mSnoozeCriteria;
    private final Bundle mShowBadge;
    private final Bundle mUserSentiment;
    private final Bundle mHidden;
    private final Bundle mSmartActions;
    private final Bundle mSmartReplies;
    private final Bundle mLastAudiblyAlerted;
    private final Bundle mNoisy;
    private final boolean[] mCanBubble;

    public NotificationRankingUpdate(String[] keys, String[] interceptedKeys,
            Bundle visibilityOverrides, Bundle suppressedVisualEffects,
            int[] importance, Bundle explanation, Bundle overrideGroupKeys,
            Bundle channels, Bundle overridePeople, Bundle snoozeCriteria,
            Bundle showBadge, Bundle userSentiment, Bundle hidden, Bundle smartActions,
            Bundle smartReplies, Bundle lastAudiblyAlerted, Bundle noisy, boolean[] canBubble) {
        mKeys = keys;
        mInterceptedKeys = interceptedKeys;
        mVisibilityOverrides = visibilityOverrides;
        mSuppressedVisualEffects = suppressedVisualEffects;
        mImportance = importance;
        mImportanceExplanation = explanation;
        mOverrideGroupKeys = overrideGroupKeys;
        mChannels = channels;
        mOverridePeople = overridePeople;
        mSnoozeCriteria = snoozeCriteria;
        mShowBadge = showBadge;
        mUserSentiment = userSentiment;
        mHidden = hidden;
        mSmartActions = smartActions;
        mSmartReplies = smartReplies;
        mLastAudiblyAlerted = lastAudiblyAlerted;
        mNoisy = noisy;
        mCanBubble = canBubble;
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
        mChannels = in.readBundle();
        mOverridePeople = in.readBundle();
        mSnoozeCriteria = in.readBundle();
        mShowBadge = in.readBundle();
        mUserSentiment = in.readBundle();
        mHidden = in.readBundle();
        mSmartActions = in.readBundle();
        mSmartReplies = in.readBundle();
        mLastAudiblyAlerted = in.readBundle();
        mNoisy = in.readBundle();
        mCanBubble = new boolean[mKeys.length];
        in.readBooleanArray(mCanBubble);
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
        out.writeBundle(mChannels);
        out.writeBundle(mOverridePeople);
        out.writeBundle(mSnoozeCriteria);
        out.writeBundle(mShowBadge);
        out.writeBundle(mUserSentiment);
        out.writeBundle(mHidden);
        out.writeBundle(mSmartActions);
        out.writeBundle(mSmartReplies);
        out.writeBundle(mLastAudiblyAlerted);
        out.writeBundle(mNoisy);
        out.writeBooleanArray(mCanBubble);
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

    public Bundle getChannels() {
        return mChannels;
    }

    public Bundle getOverridePeople() {
        return mOverridePeople;
    }

    public Bundle getSnoozeCriteria() {
        return mSnoozeCriteria;
    }

    public Bundle getShowBadge() {
        return mShowBadge;
    }

    public Bundle getUserSentiment() {
        return mUserSentiment;
    }

    public Bundle getHidden() {
        return mHidden;
    }

    public Bundle getSmartActions() {
        return mSmartActions;
    }

    public Bundle getSmartReplies() {
        return mSmartReplies;
    }

    public Bundle getLastAudiblyAlerted() {
        return mLastAudiblyAlerted;
    }

    public Bundle getNoisy() {
        return mNoisy;
    }

    public boolean[] getCanBubble() {
        return mCanBubble;
    }
}
