/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.metrics.LogMaker;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Class encapsulating a Notification. Sent by the NotificationManagerService to clients including
 * the status bar and any {@link android.service.notification.NotificationListenerService}s.
 */
public class StatusBarNotification implements Parcelable {
    static final int MAX_LOG_TAG_LENGTH = 36;

    @UnsupportedAppUsage
    private final String pkg;
    @UnsupportedAppUsage
    private final int id;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String tag;
    private final String key;
    private String groupKey;
    private String overrideGroupKey;

    @UnsupportedAppUsage
    private final int uid;
    private final String opPkg;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int initialPid;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final Notification notification;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final UserHandle user;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final long postTime;

    private Context mContext; // used for inflation & icon expansion

    // Contains the basic logging data of the notification.
    private LogMaker mLogMaker;

    /** @hide */
    public StatusBarNotification(String pkg, String opPkg, int id,
            String tag, int uid, int initialPid, Notification notification, UserHandle user,
            String overrideGroupKey, long postTime) {
        if (pkg == null) throw new NullPointerException();
        if (notification == null) throw new NullPointerException();

        this.pkg = pkg;
        this.opPkg = opPkg;
        this.id = id;
        this.tag = tag;
        this.uid = uid;
        this.initialPid = initialPid;
        this.notification = notification;
        this.user = user;
        this.postTime = postTime;
        this.overrideGroupKey = overrideGroupKey;
        this.key = key();
        this.groupKey = groupKey();
    }

    /**
     * @deprecated Non-system apps should not need to create StatusBarNotifications.
     */
    @Deprecated
    public StatusBarNotification(String pkg, String opPkg, int id, String tag, int uid,
            int initialPid, int score, Notification notification, UserHandle user,
            long postTime) {
        if (pkg == null) throw new NullPointerException();
        if (notification == null) throw new NullPointerException();

        this.pkg = pkg;
        this.opPkg = opPkg;
        this.id = id;
        this.tag = tag;
        this.uid = uid;
        this.initialPid = initialPid;
        this.notification = notification;
        this.user = user;
        this.postTime = postTime;
        this.key = key();
        this.groupKey = groupKey();
    }

    public StatusBarNotification(Parcel in) {
        this.pkg = in.readString();
        this.opPkg = in.readString();
        this.id = in.readInt();
        if (in.readInt() != 0) {
            this.tag = in.readString();
        } else {
            this.tag = null;
        }
        this.uid = in.readInt();
        this.initialPid = in.readInt();
        this.notification = new Notification(in);
        this.user = UserHandle.readFromParcel(in);
        this.postTime = in.readLong();
        if (in.readInt() != 0) {
            this.overrideGroupKey = in.readString();
        } else {
            this.overrideGroupKey = null;
        }
        this.key = key();
        this.groupKey = groupKey();
    }

    private String key() {
        String sbnKey = user.getIdentifier() + "|" + pkg + "|" + id + "|" + tag + "|" + uid;
        if (overrideGroupKey != null && getNotification().isGroupSummary()) {
            sbnKey = sbnKey + "|" + overrideGroupKey;
        }
        return sbnKey;
    }

    private String groupKey() {
        if (overrideGroupKey != null) {
            return user.getIdentifier() + "|" + pkg + "|" + "g:" + overrideGroupKey;
        }
        final String group = getNotification().getGroup();
        final String sortKey = getNotification().getSortKey();
        if (group == null && sortKey == null) {
            // a group of one
            return key;
        }
        return user.getIdentifier() + "|" + pkg + "|" +
                (group == null
                        ? "c:" + notification.getChannelId()
                        : "g:" + group);
    }

    /**
     * Returns true if this notification is part of a group.
     */
    public boolean isGroup() {
        if (overrideGroupKey != null || isAppGroup()) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if application asked that this notification be part of a group.
     * @hide
     */
    public boolean isAppGroup() {
        if (getNotification().getGroup() != null || getNotification().getSortKey() != null) {
            return true;
        }
        return false;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(this.pkg);
        out.writeString(this.opPkg);
        out.writeInt(this.id);
        if (this.tag != null) {
            out.writeInt(1);
            out.writeString(this.tag);
        } else {
            out.writeInt(0);
        }
        out.writeInt(this.uid);
        out.writeInt(this.initialPid);
        this.notification.writeToParcel(out, flags);
        user.writeToParcel(out, flags);

        out.writeLong(this.postTime);
        if (this.overrideGroupKey != null) {
            out.writeInt(1);
            out.writeString(this.overrideGroupKey);
        } else {
            out.writeInt(0);
        }
    }

    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<StatusBarNotification> CREATOR
            = new Parcelable.Creator<StatusBarNotification>()
    {
        public StatusBarNotification createFromParcel(Parcel parcel)
        {
            return new StatusBarNotification(parcel);
        }

        public StatusBarNotification[] newArray(int size)
        {
            return new StatusBarNotification[size];
        }
    };

    /**
     * @hide
     */
    public StatusBarNotification cloneLight() {
        final Notification no = new Notification();
        this.notification.cloneInto(no, false); // light copy
        return new StatusBarNotification(this.pkg, this.opPkg,
                this.id, this.tag, this.uid, this.initialPid,
                no, this.user, this.overrideGroupKey, this.postTime);
    }

    @Override
    public StatusBarNotification clone() {
        return new StatusBarNotification(this.pkg, this.opPkg,
                this.id, this.tag, this.uid, this.initialPid,
                this.notification.clone(), this.user, this.overrideGroupKey, this.postTime);
    }

    @Override
    public String toString() {
        return String.format(
                "StatusBarNotification(pkg=%s user=%s id=%d tag=%s key=%s: %s)",
                this.pkg, this.user, this.id, this.tag,
                this.key, this.notification);
    }

    /** Convenience method to check the notification's flags for
     * {@link Notification#FLAG_ONGOING_EVENT}.
     */
    public boolean isOngoing() {
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    /** Convenience method to check the notification's flags for
     * either {@link Notification#FLAG_ONGOING_EVENT} or
     * {@link Notification#FLAG_NO_CLEAR}.
     */
    public boolean isClearable() {
        return ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0)
                && ((notification.flags & Notification.FLAG_NO_CLEAR) == 0);
    }

    /**
     * Returns a userid for whom this notification is intended.
     *
     * @deprecated Use {@link #getUser()} instead.
     */
    @Deprecated
    public int getUserId() {
        return this.user.getIdentifier();
    }

    /** The package that the notification belongs to. */
    public String getPackageName() {
        return pkg;
    }

    /** The id supplied to {@link android.app.NotificationManager#notify(int,Notification)}. */
    public int getId() {
        return id;
    }

    /** The tag supplied to {@link android.app.NotificationManager#notify(int,Notification)},
     * or null if no tag was specified. */
    public String getTag() {
        return tag;
    }

    /**
     * The notifying app's ({@link #getPackageName()}'s) uid.
     */
    public int getUid() {
        return uid;
    }

    /** The package that posted the notification.
     *<p>
     * Might be different from {@link #getPackageName()} if the app owning the notification has
     * a {@link NotificationManager#setNotificationDelegate(String) notification delegate}.
     */
    public @NonNull String getOpPkg() {
        return opPkg;
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getInitialPid() {
        return initialPid;
    }

    /** The {@link android.app.Notification} supplied to
     * {@link android.app.NotificationManager#notify(int,Notification)}. */
    public Notification getNotification() {
        return notification;
    }

    /**
     * The {@link android.os.UserHandle} for whom this notification is intended.
     */
    public UserHandle getUser() {
        return user;
    }

    /** The time (in {@link System#currentTimeMillis} time) the notification was posted,
     * which may be different than {@link android.app.Notification#when}.
     */
    public long getPostTime() {
        return postTime;
    }

    /**
     * A unique instance key for this notification record.
     */
    public String getKey() {
        return key;
    }

    /**
     * A key that indicates the group with which this message ranks.
     */
    public String getGroupKey() {
        return groupKey;
    }

    /**
     * The ID passed to setGroup(), or the override, or null.
     * @hide
     */
    public String getGroup() {
        if (overrideGroupKey != null) {
            return overrideGroupKey;
        }
        return getNotification().getGroup();
    }

    /**
     * Sets the override group key.
     */
    public void setOverrideGroupKey(String overrideGroupKey) {
        this.overrideGroupKey = overrideGroupKey;
        groupKey = groupKey();
    }

    /**
     * Returns the override group key.
     */
    public String getOverrideGroupKey() {
        return overrideGroupKey;
    }

    /**
     * @hide
     */
    public void clearPackageContext() {
        mContext = null;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public Context getPackageContext(Context context) {
        if (mContext == null) {
            try {
                ApplicationInfo ai = context.getPackageManager()
                        .getApplicationInfoAsUser(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES,
                                getUserId());
                mContext = context.createApplicationContext(ai,
                        Context.CONTEXT_RESTRICTED);
            } catch (PackageManager.NameNotFoundException e) {
                mContext = null;
            }
        }
        if (mContext == null) {
            mContext = context;
        }
        return mContext;
    }

    /**
     * Returns a LogMaker that contains all basic information of the notification.
     * @hide
     */
    public LogMaker getLogMaker() {
        if (mLogMaker == null) {
            // Initialize fields that only change on update (so a new record).
            mLogMaker = new LogMaker(MetricsEvent.VIEW_UNKNOWN)
                .setPackageName(getPackageName())
                .addTaggedData(MetricsEvent.NOTIFICATION_ID, getId())
                .addTaggedData(MetricsEvent.NOTIFICATION_TAG, getTag())
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID, getChannelIdLogTag());
        }
        // Reset fields that can change between updates, or are used by multiple logs.
        return mLogMaker
            .clearCategory()
            .clearType()
            .clearSubtype()
            .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID, getGroupLogTag())
            .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_SUMMARY,
                getNotification().isGroupSummary() ? 1 : 0)
            .addTaggedData(MetricsProto.MetricsEvent.FIELD_NOTIFICATION_CATEGORY,
                    getNotification().category);
    }

    private String getGroupLogTag() {
        return shortenTag(getGroup());
    }

    private String getChannelIdLogTag() {
        if (notification.getChannelId() == null) {
            return null;
        }
        return shortenTag(notification.getChannelId());
    }

    // Make logTag with max size MAX_LOG_TAG_LENGTH.
    // For shorter or equal tags, returns the tag.
    // For longer tags, truncate the tag and append a hash of the full tag to
    // fill the maximum size.
    private String shortenTag(String logTag) {
        if (logTag == null || logTag.length() <= MAX_LOG_TAG_LENGTH) {
            return logTag;
        }
        String hash = Integer.toHexString(logTag.hashCode());
        return logTag.substring(0, MAX_LOG_TAG_LENGTH - hash.length() - 1) + "-"
            + hash;
    }
}
