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

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import com.android.internal.annotations.VisibleForTesting;

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
    final StatusBarNotification sbn;
    final int mOriginalFlags;

    NotificationUsageStats.SingleNotificationStats stats;
    boolean isCanceled;
    int score;

    // These members are used by NotificationSignalExtractors
    // to communicate with the ranking module.
    private float mContactAffinity;
    private boolean mRecentlyIntrusive;

    // is this notification currently being intercepted by Zen Mode?
    private boolean mIntercept;

    // The timestamp used for ranking.
    private long mRankingTimeMs;

    // Is this record an update of an old record?
    public boolean isUpdate;
    private int mPackagePriority;

    private int mAuthoritativeRank;
    private String mGlobalSortKey;
    private int mPackageVisibility;

    @VisibleForTesting
    public NotificationRecord(StatusBarNotification sbn, int score)
    {
        this.sbn = sbn;
        this.score = score;
        mOriginalFlags = sbn.getNotification().flags;
        mRankingTimeMs = calculateRankingTimeMs(0L);
    }

    // copy any notes that the ranking system may have made before the update
    public void copyRankingInformation(NotificationRecord previous) {
        mContactAffinity = previous.mContactAffinity;
        mRecentlyIntrusive = previous.mRecentlyIntrusive;
        mPackagePriority = previous.mPackagePriority;
        mPackageVisibility = previous.mPackageVisibility;
        mIntercept = previous.mIntercept;
        mRankingTimeMs = calculateRankingTimeMs(previous.getRankingTimeMs());
        // Don't copy mGlobalSortKey, recompute it.
    }

    public Notification getNotification() { return sbn.getNotification(); }
    public int getFlags() { return sbn.getNotification().flags; }
    public UserHandle getUser() { return sbn.getUser(); }
    public String getKey() { return sbn.getKey(); }
    /** @deprecated Use {@link #getUser()} instead. */
    public int getUserId() { return sbn.getUserId(); }

    void dump(PrintWriter pw, String prefix, Context baseContext) {
        final Notification notification = sbn.getNotification();
        pw.println(prefix + this);
        pw.println(prefix + "  uid=" + sbn.getUid() + " userId=" + sbn.getUserId());
        pw.println(prefix + "  icon=0x" + Integer.toHexString(notification.icon)
                + " / " + idDebugString(baseContext, sbn.getPackageName(), notification.icon));
        pw.println(prefix + "  pri=" + notification.priority + " score=" + sbn.getScore());
        pw.println(prefix + "  key=" + sbn.getKey());
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
                pw.println(String.format("%s    [%d] \"%s\" -> %s",
                        prefix,
                        i,
                        action.title,
                        action.actionIntent.toString()
                        ));
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
                    if (val instanceof CharSequence || val instanceof String) {
                        // redact contents from bugreports
                    } else if (val instanceof Bitmap) {
                        pw.print(String.format(" (%dx%d)",
                                ((Bitmap) val).getWidth(),
                                ((Bitmap) val).getHeight()));
                    } else if (val.getClass().isArray()) {
                        final int N = Array.getLength(val);
                        pw.println(" (" + N + ")");
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
        pw.println(prefix + "  mIntercept=" + mIntercept);
        pw.println(prefix + "  mGlobalSortKey=" + mGlobalSortKey);
        pw.println(prefix + "  mRankingTimeMs=" + mRankingTimeMs);
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
                "NotificationRecord(0x%08x: pkg=%s user=%s id=%d tag=%s score=%d key=%s: %s)",
                System.identityHashCode(this),
                this.sbn.getPackageName(), this.sbn.getUser(), this.sbn.getId(),
                this.sbn.getTag(), this.sbn.getScore(), this.sbn.getKey(),
                this.sbn.getNotification());
    }

    public void setContactAffinity(float contactAffinity) {
        mContactAffinity = contactAffinity;
    }

    public float getContactAffinity() {
        return mContactAffinity;
    }

    public void setRecentlyIntusive(boolean recentlyIntrusive) {
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

    public boolean setIntercepted(boolean intercept) {
        mIntercept = intercept;
        return mIntercept;
    }

    public boolean isIntercepted() {
        return mIntercept;
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

    public void setAuthoritativeRank(int authoritativeRank) {
        mAuthoritativeRank = authoritativeRank;
    }

    public int getAuthoritativeRank() {
        return mAuthoritativeRank;
    }

    public String getGroupKey() {
        return sbn.getGroupKey();
    }
}
