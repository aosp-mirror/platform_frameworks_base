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

package com.android.systemui.statusbar.notification.collection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The list of currently displaying notifications.
 */
public class NotificationData {

    private final NotificationFilter mNotificationFilter = Dependency.get(NotificationFilter.class);

    /**
     * These dependencies are late init-ed
     */
    private KeyguardEnvironment mEnvironment;
    private NotificationMediaManager mMediaManager;

    private HeadsUpManager mHeadsUpManager;

    private final ArrayMap<String, NotificationEntry> mEntries = new ArrayMap<>();
    private final ArrayList<NotificationEntry> mSortedAndFiltered = new ArrayList<>();
    private final ArrayList<NotificationEntry> mFilteredForUser = new ArrayList<>();

    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);

    private RankingMap mRankingMap;
    private final Ranking mTmpRanking = new Ranking();

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    @VisibleForTesting
    protected final Comparator<NotificationEntry> mRankingComparator =
            new Comparator<NotificationEntry>() {
        private final Ranking mRankingA = new Ranking();
        private final Ranking mRankingB = new Ranking();

        @Override
        public int compare(NotificationEntry a, NotificationEntry b) {
            final StatusBarNotification na = a.notification;
            final StatusBarNotification nb = b.notification;
            int aImportance = NotificationManager.IMPORTANCE_DEFAULT;
            int bImportance = NotificationManager.IMPORTANCE_DEFAULT;
            int aRank = 0;
            int bRank = 0;

            if (mRankingMap != null) {
                // RankingMap as received from NoMan
                getRanking(a.key, mRankingA);
                getRanking(b.key, mRankingB);
                aImportance = mRankingA.getImportance();
                bImportance = mRankingB.getImportance();
                aRank = mRankingA.getRank();
                bRank = mRankingB.getRank();
            }

            String mediaNotification = getMediaManager().getMediaNotificationKey();

            // IMPORTANCE_MIN media streams are allowed to drift to the bottom
            final boolean aMedia = a.key.equals(mediaNotification)
                    && aImportance > NotificationManager.IMPORTANCE_MIN;
            final boolean bMedia = b.key.equals(mediaNotification)
                    && bImportance > NotificationManager.IMPORTANCE_MIN;

            boolean aSystemMax = aImportance >= NotificationManager.IMPORTANCE_HIGH
                    && isSystemNotification(na);
            boolean bSystemMax = bImportance >= NotificationManager.IMPORTANCE_HIGH
                    && isSystemNotification(nb);


            boolean aHeadsUp = a.getRow().isHeadsUp();
            boolean bHeadsUp = b.getRow().isHeadsUp();

            // HACK: This should really go elsewhere, but it's currently not straightforward to
            // extract the comparison code and we're guaranteed to touch every element, so this is
            // the best place to set the buckets for the moment.
            a.setIsTopBucket(aHeadsUp || aMedia || aSystemMax || a.isHighPriority());
            b.setIsTopBucket(bHeadsUp || bMedia || bSystemMax || b.isHighPriority());

            if (aHeadsUp != bHeadsUp) {
                return aHeadsUp ? -1 : 1;
            } else if (aHeadsUp) {
                // Provide consistent ranking with headsUpManager
                return mHeadsUpManager.compare(a, b);
            } else if (aMedia != bMedia) {
                // Upsort current media notification.
                return aMedia ? -1 : 1;
            } else if (aSystemMax != bSystemMax) {
                // Upsort PRIORITY_MAX system notifications
                return aSystemMax ? -1 : 1;
            } else if (a.isHighPriority() != b.isHighPriority()) {
                return -1 * Boolean.compare(a.isHighPriority(), b.isHighPriority());
            } else if (aRank != bRank) {
                return aRank - bRank;
            } else {
                return Long.compare(nb.getNotification().when, na.getNotification().when);
            }
        }
    };

    private KeyguardEnvironment getEnvironment() {
        if (mEnvironment == null) {
            mEnvironment = Dependency.get(KeyguardEnvironment.class);
        }
        return mEnvironment;
    }

    private NotificationMediaManager getMediaManager() {
        if (mMediaManager == null) {
            mMediaManager = Dependency.get(NotificationMediaManager.class);
        }
        return mMediaManager;
    }

    /**
     * Returns the sorted list of active notifications (depending on {@link KeyguardEnvironment}
     *
     * <p>
     * This call doesn't update the list of active notifications. Call {@link #filterAndSort()}
     * when the environment changes.
     * <p>
     * Don't hold on to or modify the returned list.
     */
    public ArrayList<NotificationEntry> getActiveNotifications() {
        return mSortedAndFiltered;
    }

    public ArrayList<NotificationEntry> getNotificationsForCurrentUser() {
        mFilteredForUser.clear();

        synchronized (mEntries) {
            final int len = mEntries.size();
            for (int i = 0; i < len; i++) {
                NotificationEntry entry = mEntries.valueAt(i);
                final StatusBarNotification sbn = entry.notification;
                if (!getEnvironment().isNotificationForCurrentProfiles(sbn)) {
                    continue;
                }
                mFilteredForUser.add(entry);
            }
        }
        return mFilteredForUser;
    }

    public NotificationEntry get(String key) {
        return mEntries.get(key);
    }

    public void add(NotificationEntry entry) {
        synchronized (mEntries) {
            mEntries.put(entry.notification.getKey(), entry);
        }
        mGroupManager.onEntryAdded(entry);

        updateRankingAndSort(mRankingMap);
    }

    public NotificationEntry remove(String key, RankingMap ranking) {
        NotificationEntry removed;
        synchronized (mEntries) {
            removed = mEntries.remove(key);
        }
        if (removed == null) return null;
        // NEM may pass us a null ranking map if removing a lifetime-extended notification,
        // so use the most recent ranking
        if (ranking == null) ranking = mRankingMap;
        mGroupManager.onEntryRemoved(removed);
        updateRankingAndSort(ranking);
        return removed;
    }

    /** Updates the given notification entry with the provided ranking. */
    public void update(
            NotificationEntry entry,
            RankingMap ranking,
            StatusBarNotification notification) {
        updateRanking(ranking);
        final StatusBarNotification oldNotification = entry.notification;
        entry.notification = notification;
        mGroupManager.onEntryUpdated(entry, oldNotification);
    }

    public void updateRanking(RankingMap ranking) {
        updateRankingAndSort(ranking);
    }

    public void updateAppOp(int appOp, int uid, String pkg, String key, boolean showIcon) {
        synchronized (mEntries) {
            final int len = mEntries.size();
            for (int i = 0; i < len; i++) {
                NotificationEntry entry = mEntries.valueAt(i);
                if (uid == entry.notification.getUid()
                        && pkg.equals(entry.notification.getPackageName())
                        && key.equals(entry.key)) {
                    if (showIcon) {
                        entry.mActiveAppOps.add(appOp);
                    } else {
                        entry.mActiveAppOps.remove(appOp);
                    }
                }
            }
        }
    }

    /**
     * Returns true if this notification should be displayed in the high-priority notifications
     * section
     */
    public boolean isHighPriority(StatusBarNotification statusBarNotification) {
        if (mRankingMap != null) {
            getRanking(statusBarNotification.getKey(), mTmpRanking);
            if (mTmpRanking.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                    || hasHighPriorityCharacteristics(
                            mTmpRanking.getChannel(), statusBarNotification)) {
                return true;
            }
            if (mGroupManager.isSummaryOfGroup(statusBarNotification)) {
                final ArrayList<NotificationEntry> logicalChildren =
                        mGroupManager.getLogicalChildren(statusBarNotification);
                for (NotificationEntry child : logicalChildren) {
                    if (isHighPriority(child.notification)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasHighPriorityCharacteristics(NotificationChannel channel,
            StatusBarNotification statusBarNotification) {

        if (isImportantOngoing(statusBarNotification.getNotification())
                || statusBarNotification.getNotification().hasMediaSession()
                || hasPerson(statusBarNotification.getNotification())
                || hasStyle(statusBarNotification.getNotification(),
                Notification.MessagingStyle.class)) {
            // Users who have long pressed and demoted to silent should not see the notification
            // in the top section
            if (channel != null && channel.hasUserSetImportance()) {
                return false;
            }
            return true;
        }

        return false;
    }

    private boolean isImportantOngoing(Notification notification) {
        return notification.isForegroundService()
                && mTmpRanking.getImportance() >= NotificationManager.IMPORTANCE_LOW;
    }

    private boolean hasStyle(Notification notification, Class targetStyle) {
        Class<? extends Notification.Style> style = notification.getNotificationStyle();
        return targetStyle.equals(style);
    }

    private boolean hasPerson(Notification notification) {
        // TODO: cache favorite and recent contacts to check contact affinity
        ArrayList<Person> people = notification.extras != null
                ? notification.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
                : new ArrayList<>();
        return people != null && !people.isEmpty();
    }

    public boolean isAmbient(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.isAmbient();
        }
        return false;
    }

    public int getVisibilityOverride(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getVisibilityOverride();
        }
        return Ranking.VISIBILITY_NO_OVERRIDE;
    }

    public int getImportance(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getImportance();
        }
        return NotificationManager.IMPORTANCE_UNSPECIFIED;
    }

    public String getOverrideGroupKey(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getOverrideGroupKey();
        }
        return null;
    }

    public List<SnoozeCriterion> getSnoozeCriteria(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getSnoozeCriteria();
        }
        return null;
    }

    public NotificationChannel getChannel(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getChannel();
        }
        return null;
    }

    public int getRank(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getRank();
        }
        return 0;
    }

    public boolean shouldHide(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.isSuspended();
        }
        return false;
    }

    private void updateRankingAndSort(RankingMap ranking) {
        if (ranking != null) {
            mRankingMap = ranking;
            synchronized (mEntries) {
                final int len = mEntries.size();
                for (int i = 0; i < len; i++) {
                    NotificationEntry entry = mEntries.valueAt(i);
                    if (!getRanking(entry.key, mTmpRanking)) {
                        continue;
                    }
                    final StatusBarNotification oldSbn = entry.notification.cloneLight();
                    final String overrideGroupKey = getOverrideGroupKey(entry.key);
                    if (!Objects.equals(oldSbn.getOverrideGroupKey(), overrideGroupKey)) {
                        entry.notification.setOverrideGroupKey(overrideGroupKey);
                        mGroupManager.onEntryUpdated(entry, oldSbn);
                    }
                    entry.populateFromRanking(mTmpRanking);
                    entry.setIsHighPriority(isHighPriority(entry.notification));
                }
            }
        }
        filterAndSort();
    }

    /**
     * Get the ranking from the current ranking map.
     *
     * @param key the key to look up
     * @param outRanking the ranking to populate
     *
     * @return {@code true} if the ranking was properly obtained.
     */
    @VisibleForTesting
    protected boolean getRanking(String key, Ranking outRanking) {
        return mRankingMap.getRanking(key, outRanking);
    }

    // TODO: This should not be public. Instead the Environment should notify this class when
    // anything changed, and this class should call back the UI so it updates itself.
    public void filterAndSort() {
        mSortedAndFiltered.clear();

        synchronized (mEntries) {
            final int len = mEntries.size();
            for (int i = 0; i < len; i++) {
                NotificationEntry entry = mEntries.valueAt(i);

                if (mNotificationFilter.shouldFilterOut(entry)) {
                    continue;
                }

                mSortedAndFiltered.add(entry);
            }
        }

        if (mSortedAndFiltered.size() == 1) {
            // HACK: We need the comparator to run on all children in order to set the
            // isHighPriority field. If there is only one child, then the comparison won't be run,
            // so we have to trigger it manually. Get rid of this code as soon as possible.
            mRankingComparator.compare(mSortedAndFiltered.get(0), mSortedAndFiltered.get(0));
        } else {
            Collections.sort(mSortedAndFiltered, mRankingComparator);
        }
    }

    public void dump(PrintWriter pw, String indent) {
        int filteredLen = mSortedAndFiltered.size();
        pw.print(indent);
        pw.println("active notifications: " + filteredLen);
        int active;
        for (active = 0; active < filteredLen; active++) {
            NotificationEntry e = mSortedAndFiltered.get(active);
            dumpEntry(pw, indent, active, e);
        }
        synchronized (mEntries) {
            int totalLen = mEntries.size();
            pw.print(indent);
            pw.println("inactive notifications: " + (totalLen - active));
            int inactiveCount = 0;
            for (int i = 0; i < totalLen; i++) {
                NotificationEntry entry = mEntries.valueAt(i);
                if (!mSortedAndFiltered.contains(entry)) {
                    dumpEntry(pw, indent, inactiveCount, entry);
                    inactiveCount++;
                }
            }
        }
    }

    private void dumpEntry(PrintWriter pw, String indent, int i, NotificationEntry e) {
        getRanking(e.key, mTmpRanking);
        pw.print(indent);
        pw.println("  [" + i + "] key=" + e.key + " icon=" + e.icon);
        StatusBarNotification n = e.notification;
        pw.print(indent);
        pw.println("      pkg=" + n.getPackageName() + " id=" + n.getId() + " importance="
                + mTmpRanking.getImportance());
        pw.print(indent);
        pw.println("      notification=" + n.getNotification());
    }

    private static boolean isSystemNotification(StatusBarNotification sbn) {
        String sbnPackage = sbn.getPackageName();
        return "android".equals(sbnPackage) || "com.android.systemui".equals(sbnPackage);
    }

    /**
     * Provides access to keyguard state and user settings dependent data.
     */
    public interface KeyguardEnvironment {
        boolean isDeviceProvisioned();
        boolean isNotificationForCurrentProfiles(StatusBarNotification sbn);
    }
}
