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
import android.app.NotificationManager.Importance;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;

import java.util.ArrayList;
import java.util.List;

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
    @Importance private int mImportance = 0;
    private CharSequence mExplanation = "test_explanation";
    private String mOverrideGroupKey = null;
    private NotificationChannel mChannel = null;
    private ArrayList<String> mAdditionalPeople = null;
    private ArrayList<SnoozeCriterion> mSnoozeCriteria = null;
    private boolean mCanShowBadge = false;
    private int mUserSentiment = 0;
    private boolean mIsSuspended = false;
    private long mLastAudiblyAlertedMs = 0;
    private boolean mNoisy = false;
    private ArrayList<Notification.Action> mSmartActions = null;
    private ArrayList<CharSequence> mSmartReplies = null;
    private boolean mCanBubble = false;

    public RankingBuilder() {
    }

    public RankingBuilder(Ranking ranking) {
        mKey = ranking.getKey();
        mRank = ranking.getRank();
        mMatchesInterruptionFilter = ranking.matchesInterruptionFilter();
        mVisibilityOverride = ranking.getVisibilityOverride();
        mSuppressedVisualEffects = ranking.getSuppressedVisualEffects();
        mImportance = ranking.getImportance();
        mExplanation = ranking.getImportanceExplanation();
        mOverrideGroupKey = ranking.getOverrideGroupKey();
        mChannel = ranking.getChannel();
        mAdditionalPeople = copyList(ranking.getAdditionalPeople());
        mSnoozeCriteria = copyList(ranking.getSnoozeCriteria());
        mCanShowBadge = ranking.canShowBadge();
        mUserSentiment = ranking.getUserSentiment();
        mIsSuspended = ranking.isSuspended();
        mLastAudiblyAlertedMs = ranking.getLastAudiblyAlertedMillis();
        mNoisy = ranking.isNoisy();
        mSmartActions = copyList(ranking.getSmartActions());
        mSmartReplies = copyList(ranking.getSmartReplies());
        mCanBubble = ranking.canBubble();
    }

    public RankingBuilder setKey(String key) {
        mKey = key;
        return this;
    }

    public RankingBuilder setImportance(@Importance int importance) {
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
                mAdditionalPeople,
                mSnoozeCriteria,
                mCanShowBadge,
                mUserSentiment,
                mIsSuspended,
                mLastAudiblyAlertedMs,
                mNoisy,
                mSmartActions,
                mSmartReplies,
                mCanBubble);
        return ranking;
    }

    private static <E> ArrayList<E> copyList(List<E> list) {
        if (list == null) {
            return null;
        } else {
            return new ArrayList<>(list);
        }
    }
}
