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
package com.android.server.notification;

import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_MIN;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_DEFAULT;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_HIGH;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_LOW;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_MAX;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;

/**
 * Holds data about notifications that should not be shared with the
 * {@link android.service.notification.NotificationListenerService}s.
 *
 * <p>These objects should not be mutated unless the code is synchronized
 * on {@link NotificationManagerService#mNotificationList}, and any
 * modification should be followed by a sorting of that list.</p>
 *
 * <p>Is sortable by {@link NotificationComparator}.</p>
 *
 * {@hide}
 */
public final class NotificationRecord {
    static final String TAG = "NotificationRecord";
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    final StatusBarNotification sbn;
    final int mOriginalFlags;
    private final Context mContext;

    NotificationUsageStats.SingleNotificationStats stats;
    boolean isCanceled;
    /** Whether the notification was seen by the user via one of the notification listeners. */
    boolean mIsSeen;

    // These members are used by NotificationSignalExtractors
    // to communicate with the ranking module.
    private float mContactAffinity;
    private boolean mRecentlyIntrusive;

    // is this notification currently being intercepted by Zen Mode?
    private boolean mIntercept;

    // The timestamp used for ranking.
    private long mRankingTimeMs;

    // The first post time, stable across updates.
    private long mCreationTimeMs;

    // The most recent visibility event.
    private long mVisibleSinceMs;

    // The most recent update time, or the creation time if no updates.
    private long mUpdateTimeMs;

    // Is this record an update of an old record?
    public boolean isUpdate;
    private int mPackagePriority;

    private int mAuthoritativeRank;
    private String mGlobalSortKey;
    private int mPackageVisibility;
    private int mUserImportance = IMPORTANCE_UNSPECIFIED;
    private int mImportance = IMPORTANCE_UNSPECIFIED;
    private CharSequence mImportanceExplanation = null;

    private int mSuppressedVisualEffects = 0;
    private String mUserExplanation;
    private String mPeopleExplanation;

    @VisibleForTesting
    public NotificationRecord(Context context, StatusBarNotification sbn)
    {
        this.sbn = sbn;
        mOriginalFlags = sbn.getNotification().flags;
        mRankingTimeMs = calculateRankingTimeMs(0L);
        mCreationTimeMs = sbn.getPostTime();
        mUpdateTimeMs = mCreationTimeMs;
        mContext = context;
        stats = new NotificationUsageStats.SingleNotificationStats();
        mImportance = defaultImportance();
    }

    private int defaultImportance() {
        final Notification n = sbn.getNotification();
        int importance = IMPORTANCE_DEFAULT;

        // Migrate notification flags to scores
        if (0 != (n.flags & Notification.FLAG_HIGH_PRIORITY)) {
            n.priority = Notification.PRIORITY_MAX;
        }

        switch (n.priority) {
            case Notification.PRIORITY_MIN:
                importance = IMPORTANCE_MIN;
                break;
            case Notification.PRIORITY_LOW:
                importance = IMPORTANCE_LOW;
                break;
            case Notification.PRIORITY_DEFAULT:
                importance = IMPORTANCE_DEFAULT;
                break;
            case Notification.PRIORITY_HIGH:
                importance = IMPORTANCE_HIGH;
                break;
            case Notification.PRIORITY_MAX:
                importance = IMPORTANCE_MAX;
                break;
        }
        stats.requestedImportance = importance;

        boolean isNoisy = (n.defaults & Notification.DEFAULT_SOUND) != 0
                || (n.defaults & Notification.DEFAULT_VIBRATE) != 0
                || n.sound != null
                || n.vibrate != null;
        stats.isNoisy = isNoisy;

        if (!isNoisy && importance > IMPORTANCE_LOW) {
            importance = IMPORTANCE_LOW;
        }

        if (isNoisy) {
            if (importance < IMPORTANCE_DEFAULT) {
                importance = IMPORTANCE_DEFAULT;
            }
        }

        if (n.fullScreenIntent != null) {
            importance = IMPORTANCE_MAX;
        }

        stats.naturalImportance = importance;
        return importance;
    }

    // copy any notes that the ranking system may have made before the update
    public void copyRankingInformation(NotificationRecord previous) {
        mContactAffinity = previous.mContactAffinity;
        mRecentlyIntrusive = previous.mRecentlyIntrusive;
        mPackagePriority = previous.mPackagePriority;
        mPackageVisibility = previous.mPackageVisibility;
        mIntercept = previous.mIntercept;
        mRankingTimeMs = calculateRankingTimeMs(previous.getRankingTimeMs());
        mCreationTimeMs = previous.mCreationTimeMs;
        mVisibleSinceMs = previous.mVisibleSinceMs;
        if (previous.sbn.getOverrideGroupKey() != null && !sbn.isAppGroup()) {
            sbn.setOverrideGroupKey(previous.sbn.getOverrideGroupKey());
        }
        // Don't copy importance information or mGlobalSortKey, recompute them.
    }

    public Notification getNotification() { return sbn.getNotification(); }
    public int getFlags() { return sbn.getNotification().flags; }
    public UserHandle getUser() { return sbn.getUser(); }
    public String getKey() { return sbn.getKey(); }
    /** @deprecated Use {@link #getUser()} instead. */
    public int getUserId() { return sbn.getUserId(); }

    void dump(PrintWriter pw, String prefix, Context baseContext, boolean redact) {
        final Notification notification = sbn.getNotification();
        final Icon icon = notification.getSmallIcon();
        String iconStr = String.valueOf(icon);
        if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
            iconStr += " / " + idDebugString(baseContext, icon.getResPackage(), icon.getResId());
        }
        pw.println(prefix + this);
        pw.println(prefix + "  uid=" + sbn.getUid() + " userId=" + sbn.getUserId());
        pw.println(prefix + "  icon=" + iconStr);
        pw.println(prefix + "  pri=" + notification.priority);
        pw.println(prefix + "  key=" + sbn.getKey());
        pw.println(prefix + "  seen=" + mIsSeen);
        pw.println(prefix + "  groupKey=" + getGroupKey());
        pw.println(prefix + "  contentIntent=" + notification.contentIntent);
        pw.println(prefix + "  deleteIntent=" + notification.deleteIntent);
        pw.println(prefix + "  tickerText=" + notification.tickerText);
        pw.println(prefix + "  contentView=" + notification.contentView);
        pw.println(prefix + String.format("  defaults=0x%08x flags=0x%08x",
                notification.defaults, notification.flags));
        pw.println(prefix + "  sound=" + notification.sound);
        pw.println(prefix + "  audioStreamType=" + notification.audioStreamType);
        pw.println(prefix + "  audioAttributes=" + notification.audioAttributes);
        pw.println(prefix + String.format("  color=0x%08x", notification.color));
        pw.println(prefix + "  vibrate=" + Arrays.toString(notification.vibrate));
        pw.println(prefix + String.format("  led=0x%08x onMs=%d offMs=%d",
                notification.ledARGB, notification.ledOnMS, notification.ledOffMS));
        if (notification.actions != null && notification.actions.length > 0) {
            pw.println(prefix + "  actions={");
            final int N = notification.actions.length;
            for (int i=0; i<N; i++) {
                final Notification.Action action = notification.actions[i];
                if (action != null) {
                    pw.println(String.format("%s    [%d] \"%s\" -> %s",
                            prefix,
                            i,
                            action.title,
                            action.actionIntent == null ? "null" : action.actionIntent.toString()
                    ));
                }
            }
            pw.println(prefix + "  }");
        }
        if (notification.extras != null && notification.extras.size() > 0) {
            pw.println(prefix + "  extras={");
            for (String key : notification.extras.keySet()) {
                pw.print(prefix + "    " + key + "=");
                Object val = notification.extras.get(key);
                if (val == null) {
                    pw.println("null");
                } else {
                    pw.print(val.getClass().getSimpleName());
                    if (redact && (val instanceof CharSequence || val instanceof String)) {
                        // redact contents from bugreports
                    } else if (val instanceof Bitmap) {
                        pw.print(String.format(" (%dx%d)",
                                ((Bitmap) val).getWidth(),
                                ((Bitmap) val).getHeight()));
                    } else if (val.getClass().isArray()) {
                        final int N = Array.getLength(val);
                        pw.print(" (" + N + ")");
                        if (!redact) {
                            for (int j=0; j<N; j++) {
                                pw.println();
                                pw.print(String.format("%s      [%d] %s",
                                        prefix, j, String.valueOf(Array.get(val, j))));
                            }
                        }
                    } else {
                        pw.print(" (" + String.valueOf(val) + ")");
                    }
                    pw.println();
                }
            }
            pw.println(prefix + "  }");
        }
        pw.println(prefix + "  stats=" + stats.toString());
        pw.println(prefix + "  mContactAffinity=" + mContactAffinity);
        pw.println(prefix + "  mRecentlyIntrusive=" + mRecentlyIntrusive);
        pw.println(prefix + "  mPackagePriority=" + mPackagePriority);
        pw.println(prefix + "  mPackageVisibility=" + mPackageVisibility);
        pw.println(prefix + "  mUserImportance="
                + NotificationListenerService.Ranking.importanceToString(mUserImportance));
        pw.println(prefix + "  mImportance="
                + NotificationListenerService.Ranking.importanceToString(mImportance));
        pw.println(prefix + "  mImportanceExplanation=" + mImportanceExplanation);
        pw.println(prefix + "  mIntercept=" + mIntercept);
        pw.println(prefix + "  mGlobalSortKey=" + mGlobalSortKey);
        pw.println(prefix + "  mRankingTimeMs=" + mRankingTimeMs);
        pw.println(prefix + "  mCreationTimeMs=" + mCreationTimeMs);
        pw.println(prefix + "  mVisibleSinceMs=" + mVisibleSinceMs);
        pw.println(prefix + "  mUpdateTimeMs=" + mUpdateTimeMs);
        pw.println(prefix + "  mSuppressedVisualEffects= " + mSuppressedVisualEffects);
    }


    static String idDebugString(Context baseContext, String packageName, int id) {
        Context c;

        if (packageName != null) {
            try {
                c = baseContext.createPackageContext(packageName, 0);
            } catch (NameNotFoundException e) {
                c = baseContext;
            }
        } else {
            c = baseContext;
        }

        Resources r = c.getResources();
        try {
            return r.getResourceName(id);
        } catch (Resources.NotFoundException e) {
            return "<name unknown>";
        }
    }

    @Override
    public final String toString() {
        return String.format(
                "NotificationRecord(0x%08x: pkg=%s user=%s id=%d tag=%s importance=%d key=%s: %s)",
                System.identityHashCode(this),
                this.sbn.getPackageName(), this.sbn.getUser(), this.sbn.getId(),
                this.sbn.getTag(), this.mImportance, this.sbn.getKey(),
                this.sbn.getNotification());
    }

    public void setContactAffinity(float contactAffinity) {
        mContactAffinity = contactAffinity;
        if (mImportance < IMPORTANCE_DEFAULT &&
                mContactAffinity > ValidateNotificationPeople.VALID_CONTACT) {
            setImportance(IMPORTANCE_DEFAULT, getPeopleExplanation());
        }
    }

    public float getContactAffinity() {
        return mContactAffinity;
    }

    public void setRecentlyIntrusive(boolean recentlyIntrusive) {
        mRecentlyIntrusive = recentlyIntrusive;
    }

    public boolean isRecentlyIntrusive() {
        return mRecentlyIntrusive;
    }

    public void setPackagePriority(int packagePriority) {
        mPackagePriority = packagePriority;
    }

    public int getPackagePriority() {
        return mPackagePriority;
    }

    public void setPackageVisibilityOverride(int packageVisibility) {
        mPackageVisibility = packageVisibility;
    }

    public int getPackageVisibilityOverride() {
        return mPackageVisibility;
    }

    public void setUserImportance(int importance) {
        mUserImportance = importance;
        applyUserImportance();
    }

    private String getUserExplanation() {
        if (mUserExplanation == null) {
            mUserExplanation =
                    mContext.getString(com.android.internal.R.string.importance_from_user);
        }
        return mUserExplanation;
    }

    private String getPeopleExplanation() {
        if (mPeopleExplanation == null) {
            mPeopleExplanation =
                    mContext.getString(com.android.internal.R.string.importance_from_person);
        }
        return mPeopleExplanation;
    }

    private void applyUserImportance() {
        if (mUserImportance != NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED) {
            mImportance = mUserImportance;
            mImportanceExplanation = getUserExplanation();
        }
    }

    public int getUserImportance() {
        return mUserImportance;
    }

    public void setImportance(int importance, CharSequence explanation) {
        if (importance != NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED) {
            mImportance = importance;
            mImportanceExplanation = explanation;
        }
        applyUserImportance();
    }

    public int getImportance() {
        return mImportance;
    }

    public CharSequence getImportanceExplanation() {
        return mImportanceExplanation;
    }

    public boolean setIntercepted(boolean intercept) {
        mIntercept = intercept;
        return mIntercept;
    }

    public boolean isIntercepted() {
        return mIntercept;
    }

    public void setSuppressedVisualEffects(int effects) {
        mSuppressedVisualEffects = effects;
    }

    public int getSuppressedVisualEffects() {
        return mSuppressedVisualEffects;
    }

    public boolean isCategory(String category) {
        return Objects.equals(getNotification().category, category);
    }

    public boolean isAudioStream(int stream) {
        return getNotification().audioStreamType == stream;
    }

    public boolean isAudioAttributesUsage(int usage) {
        final AudioAttributes attributes = getNotification().audioAttributes;
        return attributes != null && attributes.getUsage() == usage;
    }

    /**
     * Returns the timestamp to use for time-based sorting in the ranker.
     */
    public long getRankingTimeMs() {
        return mRankingTimeMs;
    }

    /**
     * @param now this current time in milliseconds.
     * @returns the number of milliseconds since the most recent update, or the post time if none.
     */
    public int getFreshnessMs(long now) {
        return (int) (now - mUpdateTimeMs);
    }

    /**
     * @param now this current time in milliseconds.
     * @returns the number of milliseconds since the the first post, ignoring updates.
     */
    public int getLifespanMs(long now) {
        return (int) (now - mCreationTimeMs);
    }

    /**
     * @param now this current time in milliseconds.
     * @returns the number of milliseconds since the most recent visibility event, or 0 if never.
     */
    public int getExposureMs(long now) {
        return mVisibleSinceMs == 0 ? 0 : (int) (now - mVisibleSinceMs);
    }

    /**
     * Set the visibility of the notification.
     */
    public void setVisibility(boolean visible, int rank) {
        final long now = System.currentTimeMillis();
        mVisibleSinceMs = visible ? now : mVisibleSinceMs;
        stats.onVisibilityChanged(visible);
        EventLogTags.writeNotificationVisibility(getKey(), visible ? 1 : 0,
                (int) (now - mCreationTimeMs),
                (int) (now - mUpdateTimeMs),
                0, // exposure time
                rank);
    }

    /**
     * @param previousRankingTimeMs for updated notifications, {@link #getRankingTimeMs()}
     *     of the previous notification record, 0 otherwise
     */
    private long calculateRankingTimeMs(long previousRankingTimeMs) {
        Notification n = getNotification();
        // Take developer provided 'when', unless it's in the future.
        if (n.when != 0 && n.when <= sbn.getPostTime()) {
            return n.when;
        }
        // If we've ranked a previous instance with a timestamp, inherit it. This case is
        // important in order to have ranking stability for updating notifications.
        if (previousRankingTimeMs > 0) {
            return previousRankingTimeMs;
        }
        return sbn.getPostTime();
    }

    public void setGlobalSortKey(String globalSortKey) {
        mGlobalSortKey = globalSortKey;
    }

    public String getGlobalSortKey() {
        return mGlobalSortKey;
    }

    /** Check if any of the listeners have marked this notification as seen by the user. */
    public boolean isSeen() {
        return mIsSeen;
    }

    /** Mark the notification as seen by the user. */
    public void setSeen() {
        mIsSeen = true;
    }

    public void setAuthoritativeRank(int authoritativeRank) {
        mAuthoritativeRank = authoritativeRank;
    }

    public int getAuthoritativeRank() {
        return mAuthoritativeRank;
    }

    public String getGroupKey() {
        return sbn.getGroupKey();
    }

    public boolean isImportanceFromUser() {
        return mImportance == mUserImportance;
    }
}
