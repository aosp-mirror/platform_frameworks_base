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

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager.Importance;
import android.content.pm.ShortcutInfo;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;

import java.util.ArrayList;
import java.util.Arrays;
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
    private ArrayList<Notification.Action> mSmartActions = new ArrayList<>();
    private ArrayList<CharSequence> mSmartReplies = new ArrayList<>();
    private boolean mCanBubble = false;
    private boolean mIsVisuallyInterruptive = false;
    private boolean mIsConversation = false;
    private ShortcutInfo mShortcutInfo = null;
    private int mRankingAdjustment = 0;
    private boolean mIsBubble = false;

    public RankingBuilder() {
    }

    public RankingBuilder(Ranking ranking) {
        mKey = ranking.getKey();
        mRank = ranking.getRank();
        mMatchesInterruptionFilter = ranking.matchesInterruptionFilter();
        mVisibilityOverride = ranking.getLockscreenVisibilityOverride();
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
        mIsVisuallyInterruptive = ranking.visuallyInterruptive();
        mIsConversation = ranking.isConversation();
        mShortcutInfo = ranking.getConversationShortcutInfo();
        mRankingAdjustment = ranking.getRankingAdjustment();
        mIsBubble = ranking.isBubble();
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
                mCanBubble,
                mIsVisuallyInterruptive,
                mIsConversation,
                mShortcutInfo,
                mRankingAdjustment,
                mIsBubble);
        return ranking;
    }

    public RankingBuilder setKey(String key) {
        mKey = key;
        return this;
    }

    public RankingBuilder setRank(int rank) {
        mRank = rank;
        return this;
    }

    public RankingBuilder setMatchesInterruptionFilter(boolean matchesInterruptionFilter) {
        mMatchesInterruptionFilter = matchesInterruptionFilter;
        return this;
    }

    public RankingBuilder setVisibilityOverride(int visibilityOverride) {
        mVisibilityOverride = visibilityOverride;
        return this;
    }

    public RankingBuilder setSuppressedVisualEffects(int suppressedVisualEffects) {
        mSuppressedVisualEffects = suppressedVisualEffects;
        return this;
    }

    public RankingBuilder setExplanation(CharSequence explanation) {
        mExplanation = explanation;
        return this;
    }

    public RankingBuilder setOverrideGroupKey(String overrideGroupKey) {
        mOverrideGroupKey = overrideGroupKey;
        return this;
    }

    public RankingBuilder setAdditionalPeople(ArrayList<String> additionalPeople) {
        mAdditionalPeople = additionalPeople;
        return this;
    }

    public RankingBuilder setSnoozeCriteria(
            ArrayList<SnoozeCriterion> snoozeCriteria) {
        mSnoozeCriteria = snoozeCriteria;
        return this;
    }

    public RankingBuilder setCanShowBadge(boolean canShowBadge) {
        mCanShowBadge = canShowBadge;
        return this;
    }

    public RankingBuilder setSuspended(boolean suspended) {
        mIsSuspended = suspended;
        return this;
    }

    public RankingBuilder setLastAudiblyAlertedMs(long lastAudiblyAlertedMs) {
        mLastAudiblyAlertedMs = lastAudiblyAlertedMs;
        return this;
    }

    public RankingBuilder setNoisy(boolean noisy) {
        mNoisy = noisy;
        return this;
    }

    public RankingBuilder setCanBubble(boolean canBubble) {
        mCanBubble = canBubble;
        return this;
    }

    public RankingBuilder setVisuallyInterruptive(boolean interruptive) {
        mIsVisuallyInterruptive = interruptive;
        return this;
    }

    public RankingBuilder setIsConversation(boolean isConversation) {
        mIsConversation = isConversation;
        return this;
    }

    public RankingBuilder setShortcutInfo(ShortcutInfo shortcutInfo) {
        mShortcutInfo = shortcutInfo;
        return this;
    }

    public RankingBuilder setRankingAdjustment(int rankingAdjustment) {
        mRankingAdjustment = rankingAdjustment;
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

    public RankingBuilder setSmartActions(@NonNull ArrayList<Notification.Action> smartActions) {
        mSmartActions = smartActions;
        return this;
    }

    public RankingBuilder setSmartActions(Notification.Action... smartActions) {
        mSmartActions = new ArrayList<>(Arrays.asList(smartActions));
        return this;
    }

    public RankingBuilder setSmartReplies(@NonNull ArrayList<CharSequence> smartReplies) {
        mSmartReplies = smartReplies;
        return this;
    }

    public RankingBuilder setSmartReplies(CharSequence... smartReplies) {
        mSmartReplies = new ArrayList<>(Arrays.asList(smartReplies));
        return this;
    }

    private static <E> ArrayList<E> copyList(List<E> list) {
        if (list == null) {
            return null;
        } else {
            return new ArrayList<>(list);
        }
    }
}
