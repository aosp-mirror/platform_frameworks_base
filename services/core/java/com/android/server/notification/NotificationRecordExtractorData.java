/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.service.notification.SnoozeCriterion;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Class that stores any field in a NotificationRecord that can change via an extractor.
 * Used to cache previous data used in a sort.
 */
public final class NotificationRecordExtractorData {
    private final int mPosition;
    private final int mVisibility;
    private final boolean mShowBadge;
    private final boolean mAllowBubble;
    private final boolean mIsBubble;
    private final NotificationChannel mChannel;
    private final String mGroupKey;
    private final ArrayList<String> mOverridePeople;
    private final ArrayList<SnoozeCriterion> mSnoozeCriteria;
    private final Integer mUserSentiment;
    private final Integer mSuppressVisually;
    private final ArrayList<Notification.Action> mSystemSmartActions;
    private final ArrayList<CharSequence> mSmartReplies;
    private final int mImportance;

    // These fields may not trigger a reranking but diffs here may be logged.
    private final float mRankingScore;
    private final boolean mIsConversation;
    private final int mProposedImportance;

    NotificationRecordExtractorData(int position, int visibility, boolean showBadge,
            boolean allowBubble, boolean isBubble, NotificationChannel channel, String groupKey,
            ArrayList<String> overridePeople, ArrayList<SnoozeCriterion> snoozeCriteria,
            Integer userSentiment, Integer suppressVisually,
            ArrayList<Notification.Action> systemSmartActions,
            ArrayList<CharSequence> smartReplies, int importance, float rankingScore,
            boolean isConversation, int proposedImportance) {
        mPosition = position;
        mVisibility = visibility;
        mShowBadge = showBadge;
        mAllowBubble = allowBubble;
        mIsBubble = isBubble;
        mChannel = channel;
        mGroupKey = groupKey;
        mOverridePeople = overridePeople;
        mSnoozeCriteria = snoozeCriteria;
        mUserSentiment = userSentiment;
        mSuppressVisually = suppressVisually;
        mSystemSmartActions = systemSmartActions;
        mSmartReplies = smartReplies;
        mImportance = importance;
        mRankingScore = rankingScore;
        mIsConversation = isConversation;
        mProposedImportance = proposedImportance;
    }

    // Returns whether the provided NotificationRecord differs from the cached data in any way.
    // Should be guarded by mNotificationLock; not annotated here as this class is static.
    boolean hasDiffForRankingLocked(NotificationRecord r, int newPosition) {
        return mPosition != newPosition
                || mVisibility != r.getPackageVisibilityOverride()
                || mShowBadge != r.canShowBadge()
                || mAllowBubble != r.canBubble()
                || mIsBubble != r.getNotification().isBubbleNotification()
                || !Objects.equals(mChannel, r.getChannel())
                || !Objects.equals(mGroupKey, r.getGroupKey())
                || !Objects.equals(mOverridePeople, r.getPeopleOverride())
                || !Objects.equals(mSnoozeCriteria, r.getSnoozeCriteria())
                || !Objects.equals(mUserSentiment, r.getUserSentiment())
                || !Objects.equals(mSuppressVisually, r.getSuppressedVisualEffects())
                || !Objects.equals(mSystemSmartActions, r.getSystemGeneratedSmartActions())
                || !Objects.equals(mSmartReplies, r.getSmartReplies())
                || mImportance != r.getImportance()
                || mProposedImportance != r.getProposedImportance();
    }

    // Returns whether the NotificationRecord has a change from this data for which we should
    // log an update. This method specifically targets fields that may be changed via
    // adjustments from the assistant.
    //
    // Fields here are the union of things in NotificationRecordLogger.shouldLogReported
    // and NotificationRecord.applyAdjustments.
    //
    // Should be guarded by mNotificationLock; not annotated here as this class is static.
    boolean hasDiffForLoggingLocked(NotificationRecord r, int newPosition) {
        return mPosition != newPosition
                || !Objects.equals(mChannel, r.getChannel())
                || !Objects.equals(mGroupKey, r.getGroupKey())
                || !Objects.equals(mOverridePeople, r.getPeopleOverride())
                || !Objects.equals(mSnoozeCriteria, r.getSnoozeCriteria())
                || !Objects.equals(mUserSentiment, r.getUserSentiment())
                || !Objects.equals(mSystemSmartActions, r.getSystemGeneratedSmartActions())
                || !Objects.equals(mSmartReplies, r.getSmartReplies())
                || mImportance != r.getImportance()
                || !r.rankingScoreMatches(mRankingScore)
                || mIsConversation != r.isConversation()
                || mProposedImportance != r.getProposedImportance();
    }
}
