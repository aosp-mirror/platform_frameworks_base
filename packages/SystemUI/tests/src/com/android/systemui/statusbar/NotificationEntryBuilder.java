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

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager.Importance;
import android.os.UserHandle;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;

/**
 * Combined builder for constructing a NotificationEntry and its associated StatusBarNotification
 * and Ranking. Is largely a proxy for the SBN and Ranking builders, but does a little extra magic
 * to make sure the keys match between the two, etc.
 *
 * Only for use in tests.
 */
public class NotificationEntryBuilder {
    private final SbnBuilder mSbnBuilder = new SbnBuilder();
    private final RankingBuilder mRankingBuilder = new RankingBuilder();
    private StatusBarNotification mSbn = null;

    public NotificationEntry build() {
        StatusBarNotification sbn = mSbn != null ? mSbn : mSbnBuilder.build();
        mRankingBuilder.setKey(sbn.getKey());
        return new NotificationEntry(sbn, mRankingBuilder.build());
    }

    /**
     * Sets the SBN directly. If set, causes all calls to delegated SbnBuilder methods to be
     * ignored.
     */
    public NotificationEntryBuilder setSbn(@Nullable StatusBarNotification sbn) {
        mSbn = sbn;
        return this;
    }

    /* Delegated to SbnBuilder */

    public NotificationEntryBuilder setPkg(String pkg) {
        mSbnBuilder.setPkg(pkg);
        return this;
    }

    public NotificationEntryBuilder setOpPkg(String opPkg) {
        mSbnBuilder.setOpPkg(opPkg);
        return this;
    }

    public NotificationEntryBuilder setId(int id) {
        mSbnBuilder.setId(id);
        return this;
    }

    public NotificationEntryBuilder setTag(String tag) {
        mSbnBuilder.setTag(tag);
        return this;
    }

    public NotificationEntryBuilder setUid(int uid) {
        mSbnBuilder.setUid(uid);
        return this;
    }

    public NotificationEntryBuilder setInitialPid(int initialPid) {
        mSbnBuilder.setInitialPid(initialPid);
        return this;
    }

    public NotificationEntryBuilder setNotification(Notification notification) {
        mSbnBuilder.setNotification(notification);
        return this;
    }

    public NotificationEntryBuilder setUser(UserHandle user) {
        mSbnBuilder.setUser(user);
        return this;
    }

    public NotificationEntryBuilder setOverrideGroupKey(String overrideGroupKey) {
        mSbnBuilder.setOverrideGroupKey(overrideGroupKey);
        return this;
    }

    public NotificationEntryBuilder setPostTime(long postTime) {
        mSbnBuilder.setPostTime(postTime);
        return this;
    }

    /* Delegated to RankingBuilder */

    public NotificationEntryBuilder setRank(int rank) {
        mRankingBuilder.setRank(rank);
        return this;
    }

    public NotificationEntryBuilder setMatchesInterruptionFilter(
            boolean matchesInterruptionFilter) {
        mRankingBuilder.setMatchesInterruptionFilter(matchesInterruptionFilter);
        return this;
    }

    public NotificationEntryBuilder setVisibilityOverride(int visibilityOverride) {
        mRankingBuilder.setVisibilityOverride(visibilityOverride);
        return this;
    }

    public NotificationEntryBuilder setSuppressedVisualEffects(int suppressedVisualEffects) {
        mRankingBuilder.setSuppressedVisualEffects(suppressedVisualEffects);
        return this;
    }

    public NotificationEntryBuilder setExplanation(CharSequence explanation) {
        mRankingBuilder.setExplanation(explanation);
        return this;
    }

    public NotificationEntryBuilder setAdditionalPeople(ArrayList<String> additionalPeople) {
        mRankingBuilder.setAdditionalPeople(additionalPeople);
        return this;
    }

    public NotificationEntryBuilder setSnoozeCriteria(
            ArrayList<SnoozeCriterion> snoozeCriteria) {
        mRankingBuilder.setSnoozeCriteria(snoozeCriteria);
        return this;
    }

    public NotificationEntryBuilder setCanShowBadge(boolean canShowBadge) {
        mRankingBuilder.setCanShowBadge(canShowBadge);
        return this;
    }

    public NotificationEntryBuilder setSuspended(boolean suspended) {
        mRankingBuilder.setSuspended(suspended);
        return this;
    }

    public NotificationEntryBuilder setLastAudiblyAlertedMs(long lastAudiblyAlertedMs) {
        mRankingBuilder.setLastAudiblyAlertedMs(lastAudiblyAlertedMs);
        return this;
    }

    public NotificationEntryBuilder setNoisy(boolean noisy) {
        mRankingBuilder.setNoisy(noisy);
        return this;
    }

    public NotificationEntryBuilder setCanBubble(boolean canBubble) {
        mRankingBuilder.setCanBubble(canBubble);
        return this;
    }

    public NotificationEntryBuilder setImportance(@Importance int importance) {
        mRankingBuilder.setImportance(importance);
        return this;
    }

    public NotificationEntryBuilder setUserSentiment(int userSentiment) {
        mRankingBuilder.setUserSentiment(userSentiment);
        return this;
    }

    public NotificationEntryBuilder setChannel(NotificationChannel channel) {
        mRankingBuilder.setChannel(channel);
        return this;
    }

    public NotificationEntryBuilder setSmartActions(
            ArrayList<Notification.Action> smartActions) {
        mRankingBuilder.setSmartActions(smartActions);
        return this;
    }

    public NotificationEntryBuilder setSmartActions(Notification.Action... smartActions) {
        mRankingBuilder.setSmartActions(smartActions);
        return this;
    }

    public NotificationEntryBuilder setSmartReplies(ArrayList<CharSequence> smartReplies) {
        mRankingBuilder.setSmartReplies(smartReplies);
        return this;
    }

    public NotificationEntryBuilder setSmartReplies(CharSequence... smartReplies) {
        mRankingBuilder.setSmartReplies(smartReplies);
        return this;
    }
}
