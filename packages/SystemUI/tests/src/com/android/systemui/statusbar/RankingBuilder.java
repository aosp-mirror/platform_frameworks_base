/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;

import java.util.ArrayList;

/**
 * Standard builder class for Ranking objects. For use in tests that need to craft the underlying
 * Ranking object of a NotificationEntry.
 */
public class RankingBuilder {
    private String mKey = "test_key";
    private int mRank = 0;
    private boolean mMatchesInterruptionFilter = false;
    private int mVisibilityOverride = 0;
    private int mSuppressedVisualEffects = 0;
    private int mImportance = 0;
    private CharSequence mExplanation = "test_explanation";
    private String mOverrideGroupKey = null;
    private NotificationChannel mChannel = null;
    private ArrayList<String> mOverridePeople = null;
    private ArrayList<SnoozeCriterion> mSnoozeCriteria = null;
    private boolean mShowBadge = false;
    private int mUserSentiment = 0;
    private boolean mHidden = false;
    private long mLastAudiblyAlertedMs = 0;
    private boolean mNoisy = false;
    private ArrayList<Notification.Action> mSmartActions = null;
    private ArrayList<CharSequence> mSmartReplies = null;
    private boolean mCanBubble = false;

    public RankingBuilder setKey(String key) {
        mKey = key;
        return this;
    }

    public RankingBuilder setImportance(int importance) {
        mImportance = importance;
        return this;
    }

    public RankingBuilder setUserSentiment(int userSentiment) {
        mUserSentiment = userSentiment;
        return this;
    }

    public RankingBuilder setChannel(NotificationChannel channel) {
        mChannel = channel;
        return this;
    }

    public RankingBuilder setSmartActions(ArrayList<Notification.Action> smartActions) {
        mSmartActions = smartActions;
        return this;
    }

    public RankingBuilder setSmartReplies(ArrayList<CharSequence> smartReplies) {
        mSmartReplies = smartReplies;
        return this;
    }

    public Ranking build() {
        final Ranking ranking = new Ranking();
        ranking.populate(
                mKey,
                mRank,
                mMatchesInterruptionFilter,
                mVisibilityOverride,
                mSuppressedVisualEffects,
                mImportance,
                mExplanation,
                mOverrideGroupKey,
                mChannel,
                mOverridePeople,
                mSnoozeCriteria,
                mShowBadge,
                mUserSentiment,
                mHidden,
                mLastAudiblyAlertedMs,
                mNoisy,
                mSmartActions,
                mSmartReplies,
                mCanBubble);
        return ranking;
    }
}
