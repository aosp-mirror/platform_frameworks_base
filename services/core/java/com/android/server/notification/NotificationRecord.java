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

import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRecordProto;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Holds data about notifications that should not be shared with the
 * {@link android.service.notification.NotificationListenerService}s.
 *
 * <p>These objects should not be mutated unless the code is synchronized
 * on {@link NotificationManagerService#mNotificationLock}, and any
 * modification should be followed by a sorting of that list.</p>
 *
 * <p>Is sortable by {@link NotificationComparator}.</p>
 *
 * {@hide}
 */
public final class NotificationRecord {
    static final String TAG = "NotificationRecord";
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int MAX_LOGTAG_LENGTH = 35;
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
    private long mLastIntrusive;

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
    private boolean mPreChannelsNotification = true;
    private Uri mSound;
    private long[] mVibration;
    private AudioAttributes mAttributes;
    private NotificationChannel mChannel;
    private ArrayList<String> mPeopleOverride;
    private ArrayList<SnoozeCriterion> mSnoozeCriteria;
    private boolean mShowBadge;
    private LogMaker mLogMaker;
    private Light mLight;
    private String mGroupLogTag;
    private String mChannelIdLogTag;

    private final List<Adjustment> mAdjustments;

    @VisibleForTesting
    public NotificationRecord(Context context, StatusBarNotification sbn,
            NotificationChannel channel)
    {
        this.sbn = sbn;
        mOriginalFlags = sbn.getNotification().flags;
        mRankingTimeMs = calculateRankingTimeMs(0L);
        mCreationTimeMs = sbn.getPostTime();
        mUpdateTimeMs = mCreationTimeMs;
        mContext = context;
        stats = new NotificationUsageStats.SingleNotificationStats();
        mChannel = channel;
        mPreChannelsNotification = isPreChannelsNotification();
        mSound = calculateSound();
        mVibration = calculateVibration();
        mAttributes = calculateAttributes();
        mImportance = calculateImportance();
        mLight = calculateLights();
        mAdjustments = new ArrayList<>();
    }

    private boolean isPreChannelsNotification() {
        try {
            if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(getChannel().getId())) {
                  final ApplicationInfo applicationInfo =
                        mContext.getPackageManager().getApplicationInfoAsUser(sbn.getPackageName(),
                                0, UserHandle.getUserId(sbn.getUid()));
                if (applicationInfo.targetSdkVersion < Build.VERSION_CODES.O) {
                    return true;
                }
            }
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Can't find package", e);
        }
        return false;
    }

    private Uri calculateSound() {
        final Notification n = sbn.getNotification();

        // No notification sounds on tv
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return null;
        }

        Uri sound = mChannel.getSound();
        if (mPreChannelsNotification && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_SOUND) == 0) {

            final boolean useDefaultSound = (n.defaults & Notification.DEFAULT_SOUND) != 0;
            if (useDefaultSound) {
                sound = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else {
                sound = n.sound;
            }
        }
        return sound;
    }

    private Light calculateLights() {
        int defaultLightColor = mContext.getResources().getColor(
                com.android.internal.R.color.config_defaultNotificationColor);
        int defaultLightOn = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOn);
        int defaultLightOff = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOff);

        int channelLightColor = getChannel().getLightColor() != 0 ? getChannel().getLightColor()
                : defaultLightColor;
        Light light = getChannel().shouldShowLights() ? new Light(channelLightColor,
                defaultLightOn, defaultLightOff) : null;
        if (mPreChannelsNotification
                && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_LIGHTS) == 0) {
            final Notification notification = sbn.getNotification();
            if ((notification.flags & Notification.FLAG_SHOW_LIGHTS) != 0) {
                light = new Light(notification.ledARGB, notification.ledOnMS,
                        notification.ledOffMS);
                if ((notification.defaults & Notification.DEFAULT_LIGHTS) != 0) {
                    light = new Light(defaultLightColor, defaultLightOn,
                            defaultLightOff);
                }
            } else {
                light = null;
            }
        }
        return light;
    }

    private long[] calculateVibration() {
        long[] vibration;
        final long[] defaultVibration =  NotificationManagerService.getLongArray(
                mContext.getResources(),
                com.android.internal.R.array.config_defaultNotificationVibePattern,
                NotificationManagerService.VIBRATE_PATTERN_MAXLEN,
                NotificationManagerService.DEFAULT_VIBRATE_PATTERN);
        if (getChannel().shouldVibrate()) {
            vibration = getChannel().getVibrationPattern() == null
                    ? defaultVibration : getChannel().getVibrationPattern();
        } else {
            vibration = null;
        }
        if (mPreChannelsNotification
                && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_VIBRATION) == 0) {
            final Notification notification = sbn.getNotification();
            final boolean useDefaultVibrate =
                    (notification.defaults & Notification.DEFAULT_VIBRATE) != 0;
            if (useDefaultVibrate) {
                vibration = defaultVibration;
            } else {
                vibration = notification.vibrate;
            }
        }
        return vibration;
    }

    private AudioAttributes calculateAttributes() {
        final Notification n = sbn.getNotification();
        AudioAttributes attributes = getChannel().getAudioAttributes();
        if (attributes == null) {
            attributes = Notification.AUDIO_ATTRIBUTES_DEFAULT;
        }

        if (mPreChannelsNotification
                && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_SOUND) == 0) {
            if (n.audioAttributes != null) {
                // prefer audio attributes to stream type
                attributes = n.audioAttributes;
            } else if (n.audioStreamType >= 0
                    && n.audioStreamType < AudioSystem.getNumStreamTypes()) {
                // the stream type is valid, use it
                attributes = new AudioAttributes.Builder()
                        .setInternalLegacyStreamType(n.audioStreamType)
                        .build();
            } else if (n.audioStreamType != AudioSystem.STREAM_DEFAULT) {
                Log.w(TAG, String.format("Invalid stream type: %d", n.audioStreamType));
            }
        }
        return attributes;
    }

    private int calculateImportance() {
        final Notification n = sbn.getNotification();
        int importance = getChannel().getImportance();
        int requestedImportance = IMPORTANCE_DEFAULT;

        // Migrate notification flags to scores
        if (0 != (n.flags & Notification.FLAG_HIGH_PRIORITY)) {
            n.priority = Notification.PRIORITY_MAX;
        }

        n.priority = NotificationManagerService.clamp(n.priority, Notification.PRIORITY_MIN,
                Notification.PRIORITY_MAX);
        switch (n.priority) {
            case Notification.PRIORITY_MIN:
                requestedImportance = IMPORTANCE_MIN;
                break;
            case Notification.PRIORITY_LOW:
                requestedImportance = IMPORTANCE_LOW;
                break;
            case Notification.PRIORITY_DEFAULT:
                requestedImportance = IMPORTANCE_DEFAULT;
                break;
            case Notification.PRIORITY_HIGH:
            case Notification.PRIORITY_MAX:
                requestedImportance = IMPORTANCE_HIGH;
                break;
        }
        stats.requestedImportance = requestedImportance;
        stats.isNoisy = mSound != null || mVibration != null;

        if (mPreChannelsNotification
                && (importance == IMPORTANCE_UNSPECIFIED
                || (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_IMPORTANCE) == 0)) {
            if (!stats.isNoisy && requestedImportance > IMPORTANCE_LOW) {
                requestedImportance = IMPORTANCE_LOW;
            }

            if (stats.isNoisy) {
                if (requestedImportance < IMPORTANCE_DEFAULT) {
                    requestedImportance = IMPORTANCE_DEFAULT;
                }
            }

            if (n.fullScreenIntent != null) {
                requestedImportance = IMPORTANCE_HIGH;
            }
            importance = requestedImportance;
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

    void dump(ProtoOutputStream proto, boolean redact) {
        proto.write(NotificationRecordProto.KEY, sbn.getKey());
        if (getChannel() != null) {
            proto.write(NotificationRecordProto.CHANNEL_ID, getChannel().getId());
        }
        proto.write(NotificationRecordProto.CAN_SHOW_LIGHT, getLight() != null);
        proto.write(NotificationRecordProto.CAN_VIBRATE, getVibration() != null);
        proto.write(NotificationRecordProto.FLAGS, sbn.getNotification().flags);
        proto.write(NotificationRecordProto.GROUP_KEY, getGroupKey());
        proto.write(NotificationRecordProto.IMPORTANCE, getImportance());
        if (getSound() != null) {
            proto.write(NotificationRecordProto.SOUND, getSound().toString());
        }
        if (getAudioAttributes() != null) {
            proto.write(NotificationRecordProto.SOUND_USAGE, getAudioAttributes().getUsage());
        }
    }

    String formatRemoteViews(RemoteViews rv) {
        if (rv == null) return "null";
        return String.format("%s/0x%08x (%d bytes): %s",
            rv.getPackage(), rv.getLayoutId(), rv.estimateMemoryUsage(), rv.toString());
    }

    void dump(PrintWriter pw, String prefix, Context baseContext, boolean redact) {
        final Notification notification = sbn.getNotification();
        final Icon icon = notification.getSmallIcon();
        String iconStr = String.valueOf(icon);
        if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
            iconStr += " / " + idDebugString(baseContext, icon.getResPackage(), icon.getResId());
        }
        pw.println(prefix + this);
        prefix = prefix + "  ";
        pw.println(prefix + "uid=" + sbn.getUid() + " userId=" + sbn.getUserId());
        pw.println(prefix + "icon=" + iconStr);
        pw.println(prefix + "flags=0x" + Integer.toHexString(notification.flags));
        pw.println(prefix + "pri=" + notification.priority);
        pw.println(prefix + "key=" + sbn.getKey());
        pw.println(prefix + "seen=" + mIsSeen);
        pw.println(prefix + "groupKey=" + getGroupKey());
        pw.println(prefix + "fullscreenIntent=" + notification.fullScreenIntent);
        pw.println(prefix + "contentIntent=" + notification.contentIntent);
        pw.println(prefix + "deleteIntent=" + notification.deleteIntent);

        pw.print(prefix + "tickerText=");
        if (!TextUtils.isEmpty(notification.tickerText)) {
            final String ticker = notification.tickerText.toString();
            if (redact) {
                // if the string is long enough, we allow ourselves a few bytes for debugging
                pw.print(ticker.length() > 16 ? ticker.substring(0,8) : "");
                pw.println("...");
            } else {
                pw.println(ticker);
            }
        } else {
            pw.println("null");
        }
        pw.println(prefix + "contentView=" + formatRemoteViews(notification.contentView));
        pw.println(prefix + "bigContentView=" + formatRemoteViews(notification.bigContentView));
        pw.println(prefix + "headsUpContentView="
                + formatRemoteViews(notification.headsUpContentView));
        pw.print(prefix + String.format("color=0x%08x", notification.color));
        pw.println(prefix + "timeout="
                + TimeUtils.formatForLogging(notification.getTimeoutAfter()));
        if (notification.actions != null && notification.actions.length > 0) {
            pw.println(prefix + "actions={");
            final int N = notification.actions.length;
            for (int i = 0; i < N; i++) {
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
            pw.println(prefix + "extras={");
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
                            for (int j = 0; j < N; j++) {
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
            pw.println(prefix + "}");
        }
        pw.println(prefix + "stats=" + stats.toString());
        pw.println(prefix + "mContactAffinity=" + mContactAffinity);
        pw.println(prefix + "mRecentlyIntrusive=" + mRecentlyIntrusive);
        pw.println(prefix + "mPackagePriority=" + mPackagePriority);
        pw.println(prefix + "mPackageVisibility=" + mPackageVisibility);
        pw.println(prefix + "mUserImportance="
                + NotificationListenerService.Ranking.importanceToString(mUserImportance));
        pw.println(prefix + "mImportance="
                + NotificationListenerService.Ranking.importanceToString(mImportance));
        pw.println(prefix + "mImportanceExplanation=" + mImportanceExplanation);
        pw.println(prefix + "mIntercept=" + mIntercept);
        pw.println(prefix + "mGlobalSortKey=" + mGlobalSortKey);
        pw.println(prefix + "mRankingTimeMs=" + mRankingTimeMs);
        pw.println(prefix + "mCreationTimeMs=" + mCreationTimeMs);
        pw.println(prefix + "mVisibleSinceMs=" + mVisibleSinceMs);
        pw.println(prefix + "mUpdateTimeMs=" + mUpdateTimeMs);
        pw.println(prefix + "mSuppressedVisualEffects= " + mSuppressedVisualEffects);
        if (mPreChannelsNotification) {
            pw.println(prefix + String.format("defaults=0x%08x flags=0x%08x",
                    notification.defaults, notification.flags));
            pw.println(prefix + "n.sound=" + notification.sound);
            pw.println(prefix + "n.audioStreamType=" + notification.audioStreamType);
            pw.println(prefix + "n.audioAttributes=" + notification.audioAttributes);
            pw.println(prefix + String.format("  led=0x%08x onMs=%d offMs=%d",
                    notification.ledARGB, notification.ledOnMS, notification.ledOffMS));
            pw.println(prefix + "vibrate=" + Arrays.toString(notification.vibrate));
        }
        pw.println(prefix + "mSound= " + mSound);
        pw.println(prefix + "mVibration= " + mVibration);
        pw.println(prefix + "mAttributes= " + mAttributes);
        pw.println(prefix + "mLight= " + mLight);
        pw.println(prefix + "mShowBadge=" + mShowBadge);
        pw.println(prefix + "mColorized=" + notification.isColorized());
        pw.println(prefix + "effectiveNotificationChannel=" + getChannel());
        if (getPeopleOverride() != null) {
            pw.println(prefix + "overridePeople= " + TextUtils.join(",", getPeopleOverride()));
        }
        if (getSnoozeCriteria() != null) {
            pw.println(prefix + "snoozeCriteria=" + TextUtils.join(",", getSnoozeCriteria()));
        }
        pw.println(prefix + "mAdjustments=" + mAdjustments);
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
                "NotificationRecord(0x%08x: pkg=%s user=%s id=%d tag=%s importance=%d key=%s" +
                        ": %s)",
                System.identityHashCode(this),
                this.sbn.getPackageName(), this.sbn.getUser(), this.sbn.getId(),
                this.sbn.getTag(), this.mImportance, this.sbn.getKey(),
                this.sbn.getNotification());
    }

    public void addAdjustment(Adjustment adjustment) {
        synchronized (mAdjustments) {
            mAdjustments.add(adjustment);
        }
    }

    public void applyAdjustments() {
        synchronized (mAdjustments) {
            for (Adjustment adjustment: mAdjustments) {
                Bundle signals = adjustment.getSignals();
                if (signals.containsKey(Adjustment.KEY_PEOPLE)) {
                    final ArrayList<String> people =
                            adjustment.getSignals().getStringArrayList(Adjustment.KEY_PEOPLE);
                    setPeopleOverride(people);
                }
                if (signals.containsKey(Adjustment.KEY_SNOOZE_CRITERIA)) {
                    final ArrayList<SnoozeCriterion> snoozeCriterionList =
                            adjustment.getSignals().getParcelableArrayList(
                                    Adjustment.KEY_SNOOZE_CRITERIA);
                    setSnoozeCriteria(snoozeCriterionList);
                }
                if (signals.containsKey(Adjustment.KEY_GROUP_KEY)) {
                    final String groupOverrideKey =
                            adjustment.getSignals().getString(Adjustment.KEY_GROUP_KEY);
                    setOverrideGroupKey(groupOverrideKey);
                }
            }
        }
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
        if (recentlyIntrusive) {
            mLastIntrusive = System.currentTimeMillis();
        }
    }

    public boolean isRecentlyIntrusive() {
        return mRecentlyIntrusive;
    }

    public long getLastIntrusive() {
        return mLastIntrusive;
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
            mUserExplanation = mContext.getResources().getString(
                    com.android.internal.R.string.importance_from_user);
        }
        return mUserExplanation;
    }

    private String getPeopleExplanation() {
        if (mPeopleExplanation == null) {
            mPeopleExplanation = mContext.getResources().getString(
                    com.android.internal.R.string.importance_from_person);
        }
        return mPeopleExplanation;
    }

    private void applyUserImportance() {
        if (mUserImportance != IMPORTANCE_UNSPECIFIED) {
            mImportance = mUserImportance;
            mImportanceExplanation = getUserExplanation();
        }
    }

    public int getUserImportance() {
        return mUserImportance;
    }

    public void setImportance(int importance, CharSequence explanation) {
        if (importance != IMPORTANCE_UNSPECIFIED) {
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
        MetricsLogger.action(getLogMaker(now)
                .setCategory(MetricsEvent.NOTIFICATION_ITEM)
                .setType(visible ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)
                .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, rank));
        if (visible) {
            MetricsLogger.histogram(mContext, "note_freshness", getFreshnessMs(now));
        }
        EventLogTags.writeNotificationVisibility(getKey(), visible ? 1 : 0,
                getLifespanMs(now),
                getFreshnessMs(now),
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

    public void setOverrideGroupKey(String overrideGroupKey) {
        sbn.setOverrideGroupKey(overrideGroupKey);
        mGroupLogTag = null;
    }

    private String getGroupLogTag() {
        if (mGroupLogTag == null) {
            mGroupLogTag = shortenTag(sbn.getGroup());
        }
        return mGroupLogTag;
    }

    private String getChannelIdLogTag() {
        if (mChannelIdLogTag == null) {
            mChannelIdLogTag = shortenTag(mChannel.getId());
        }
        return mChannelIdLogTag;
    }

    private String shortenTag(String longTag) {
        if (longTag == null) {
            return null;
        }
        if (longTag.length() < MAX_LOGTAG_LENGTH) {
            return longTag;
        } else {
            return longTag.substring(0, MAX_LOGTAG_LENGTH - 8) + "-" +
                    Integer.toHexString(longTag.hashCode());
        }
    }

    public boolean isImportanceFromUser() {
        return mImportance == mUserImportance;
    }

    public NotificationChannel getChannel() {
        return mChannel;
    }

    protected void updateNotificationChannel(NotificationChannel channel) {
        if (channel != null) {
            mChannel = channel;
            calculateImportance();
        }
    }

    public void setShowBadge(boolean showBadge) {
        mShowBadge = showBadge;
    }

    public boolean canShowBadge() {
        return mShowBadge;
    }

    public Light getLight() {
        return mLight;
    }

    public Uri getSound() {
        return mSound;
    }

    public long[] getVibration() {
        return mVibration;
    }

    public AudioAttributes getAudioAttributes() {
        return mAttributes;
    }

    public ArrayList<String> getPeopleOverride() {
        return mPeopleOverride;
    }

    protected void setPeopleOverride(ArrayList<String> people) {
        mPeopleOverride = people;
    }

    public ArrayList<SnoozeCriterion> getSnoozeCriteria() {
        return mSnoozeCriteria;
    }

    protected void setSnoozeCriteria(ArrayList<SnoozeCriterion> snoozeCriteria) {
        mSnoozeCriteria = snoozeCriteria;
    }

    public LogMaker getLogMaker(long now) {
        if (mLogMaker == null) {
            // initialize fields that only change on update (so a new record)
            mLogMaker = new LogMaker(MetricsEvent.VIEW_UNKNOWN)
                    .setPackageName(sbn.getPackageName())
                    .addTaggedData(MetricsEvent.NOTIFICATION_ID, sbn.getId())
                    .addTaggedData(MetricsEvent.NOTIFICATION_TAG, sbn.getTag())
                    .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID, getChannelIdLogTag());
        }
        // reset fields that can change between updates, or are used by multiple logs
        return mLogMaker
                .clearCategory()
                .clearType()
                .clearSubtype()
                .clearTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX)
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE, mImportance)
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID, getGroupLogTag())
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_SUMMARY,
                        sbn.getNotification().isGroupSummary() ? 1 : 0)
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_CREATE_MILLIS, getLifespanMs(now))
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_UPDATE_MILLIS, getFreshnessMs(now))
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS, getExposureMs(now));
    }

    public LogMaker getLogMaker() {
        return getLogMaker(System.currentTimeMillis());
    }

    @VisibleForTesting
    static final class Light {
        public final int color;
        public final int onMs;
        public final int offMs;

        public Light(int color, int onMs, int offMs) {
            this.color = color;
            this.onMs = onMs;
            this.offMs = offMs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Light light = (Light) o;

            if (color != light.color) return false;
            if (onMs != light.onMs) return false;
            return offMs == light.offMs;

        }

        @Override
        public int hashCode() {
            int result = color;
            result = 31 * result + onMs;
            result = 31 * result + offMs;
            return result;
        }

        @Override
        public String toString() {
            return "Light{" +
                    "color=" + color +
                    ", onMs=" + onMs +
                    ", offMs=" + offMs +
                    '}';
        }
    }
}
