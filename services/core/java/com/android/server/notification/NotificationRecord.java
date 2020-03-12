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

import static android.app.NotificationChannel.USER_LOCKED_IMPORTANCE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRecordProto;
import android.service.notification.NotificationStats;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.uri.UriGrantsManagerInternal;

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
    // the period after which a notification is updated where it can make sound
    private static final int MAX_SOUND_DELAY_MS = 2000;
    final StatusBarNotification sbn;
    IActivityManager mAm;
    UriGrantsManagerInternal mUgmInternal;
    final int mTargetSdkVersion;
    final int mOriginalFlags;
    private final Context mContext;

    NotificationUsageStats.SingleNotificationStats stats;
    boolean isCanceled;
    IBinder permissionOwner;

    // These members are used by NotificationSignalExtractors
    // to communicate with the ranking module.
    private float mContactAffinity;
    private boolean mRecentlyIntrusive;
    private long mLastIntrusive;

    // is this notification currently being intercepted by Zen Mode?
    private boolean mIntercept;

    // is this notification hidden since the app pkg is suspended?
    private boolean mHidden;

    // The timestamp used for ranking.
    private long mRankingTimeMs;

    // The first post time, stable across updates.
    private long mCreationTimeMs;

    // The most recent visibility event.
    private long mVisibleSinceMs;

    // The most recent update time, or the creation time if no updates.
    @VisibleForTesting
    final long mUpdateTimeMs;

    // The most recent interruption time, or the creation time if no updates. Differs from the
    // above value because updates are filtered based on whether they actually interrupted the
    // user
    private long mInterruptionTimeMs;

    // The most recent time the notification made noise or buzzed the device, or -1 if it did not.
    private long mLastAudiblyAlertedMs;

    // Is this record an update of an old record?
    public boolean isUpdate;
    private int mPackagePriority;

    private int mAuthoritativeRank;
    private String mGlobalSortKey;
    private int mPackageVisibility;
    private int mSystemImportance = IMPORTANCE_UNSPECIFIED;
    private int mAssistantImportance = IMPORTANCE_UNSPECIFIED;
    private int mImportance = IMPORTANCE_UNSPECIFIED;
    // Field used in global sort key to bypass normal notifications
    private int mCriticality = CriticalNotificationExtractor.NORMAL;
    // A MetricsEvent.NotificationImportanceExplanation, tracking source of mImportance.
    private int mImportanceExplanationCode = MetricsEvent.IMPORTANCE_EXPLANATION_UNKNOWN;
    // A MetricsEvent.NotificationImportanceExplanation for initial importance.
    private int mInitialImportanceExplanationCode = MetricsEvent.IMPORTANCE_EXPLANATION_UNKNOWN;

    private int mSuppressedVisualEffects = 0;
    private String mUserExplanation;
    private boolean mPreChannelsNotification = true;
    private Uri mSound;
    private long[] mVibration;
    private AudioAttributes mAttributes;
    private NotificationChannel mChannel;
    private ArrayList<String> mPeopleOverride;
    private ArrayList<SnoozeCriterion> mSnoozeCriteria;
    private boolean mShowBadge;
    private boolean mAllowBubble;
    private Light mLight;
    /**
     * This list contains system generated smart actions from NAS, app-generated smart actions are
     * stored in Notification.actions with isContextual() set to true.
     */
    private ArrayList<Notification.Action> mSystemGeneratedSmartActions;
    private ArrayList<CharSequence> mSmartReplies;

    private final List<Adjustment> mAdjustments;
    private String mAdjustmentIssuer;
    private final NotificationStats mStats;
    private int mUserSentiment;
    private boolean mIsInterruptive;
    private boolean mTextChanged;
    private boolean mRecordedInterruption;
    private int mNumberOfSmartRepliesAdded;
    private int mNumberOfSmartActionsAdded;
    private boolean mSuggestionsGeneratedByAssistant;
    private boolean mEditChoicesBeforeSending;
    private boolean mHasSeenSmartReplies;
    /**
     * Whether this notification (and its channels) should be considered user locked. Used in
     * conjunction with user sentiment calculation.
     */
    private boolean mIsAppImportanceLocked;
    private ArraySet<Uri> mGrantableUris;

    public NotificationRecord(Context context, StatusBarNotification sbn,
            NotificationChannel channel) {
        this.sbn = sbn;
        mTargetSdkVersion = LocalServices.getService(PackageManagerInternal.class)
                .getPackageTargetSdkVersion(sbn.getPackageName());
        mAm = ActivityManager.getService();
        mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);
        mOriginalFlags = sbn.getNotification().flags;
        mRankingTimeMs = calculateRankingTimeMs(0L);
        mCreationTimeMs = sbn.getPostTime();
        mUpdateTimeMs = mCreationTimeMs;
        mInterruptionTimeMs = mCreationTimeMs;
        mContext = context;
        stats = new NotificationUsageStats.SingleNotificationStats();
        mChannel = channel;
        mPreChannelsNotification = isPreChannelsNotification();
        mSound = calculateSound();
        mVibration = calculateVibration();
        mAttributes = calculateAttributes();
        mImportance = calculateInitialImportance();
        mLight = calculateLights();
        mAdjustments = new ArrayList<>();
        mStats = new NotificationStats();
        calculateUserSentiment();
        calculateGrantableUris();
    }

    private boolean isPreChannelsNotification() {
        if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(getChannel().getId())) {
            if (mTargetSdkVersion < Build.VERSION_CODES.O) {
                return true;
            }
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

    private int calculateInitialImportance() {
        final Notification n = sbn.getNotification();
        int importance = getChannel().getImportance();  // Post-channels notifications use this
        mInitialImportanceExplanationCode = getChannel().hasUserSetImportance()
                ? MetricsEvent.IMPORTANCE_EXPLANATION_USER
                : MetricsEvent.IMPORTANCE_EXPLANATION_APP;

        // Migrate notification priority flag to a priority value.
        if (0 != (n.flags & Notification.FLAG_HIGH_PRIORITY)) {
            n.priority = Notification.PRIORITY_MAX;
        }

        // Convert priority value to an importance value, used only for pre-channels notifications.
        int requestedImportance = IMPORTANCE_DEFAULT;
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

        // For pre-channels notifications, apply system overrides and then use requestedImportance
        // as importance.
        if (mPreChannelsNotification
                && (importance == IMPORTANCE_UNSPECIFIED
                || (!getChannel().hasUserSetImportance()))) {
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
            mInitialImportanceExplanationCode =
                    MetricsEvent.IMPORTANCE_EXPLANATION_APP_PRE_CHANNELS;
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
        mHidden = previous.mHidden;
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
    public int getUid() { return sbn.getUid(); }

    void dump(ProtoOutputStream proto, long fieldId, boolean redact, int state) {
        final long token = proto.start(fieldId);

        proto.write(NotificationRecordProto.KEY, sbn.getKey());
        proto.write(NotificationRecordProto.STATE, state);
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
            getAudioAttributes().writeToProto(proto, NotificationRecordProto.AUDIO_ATTRIBUTES);
        }
        proto.write(NotificationRecordProto.PACKAGE, sbn.getPackageName());
        proto.write(NotificationRecordProto.DELEGATE_PACKAGE, sbn.getOpPkg());

        proto.end(token);
    }

    String formatRemoteViews(RemoteViews rv) {
        if (rv == null) return "null";
        return String.format("%s/0x%08x (%d bytes): %s",
            rv.getPackage(), rv.getLayoutId(), rv.estimateMemoryUsage(), rv.toString());
    }

    void dump(PrintWriter pw, String prefix, Context baseContext, boolean redact) {
        final Notification notification = sbn.getNotification();
        pw.println(prefix + this);
        prefix = prefix + "  ";
        pw.println(prefix + "uid=" + sbn.getUid() + " userId=" + sbn.getUserId());
        pw.println(prefix + "opPkg=" + sbn.getOpPkg());
        pw.println(prefix + "icon=" + notification.getSmallIcon());
        pw.println(prefix + "flags=0x" + Integer.toHexString(notification.flags));
        pw.println(prefix + "pri=" + notification.priority);
        pw.println(prefix + "key=" + sbn.getKey());
        pw.println(prefix + "seen=" + mStats.hasSeen());
        pw.println(prefix + "groupKey=" + getGroupKey());
        pw.println(prefix + "fullscreenIntent=" + notification.fullScreenIntent);
        pw.println(prefix + "contentIntent=" + notification.contentIntent);
        pw.println(prefix + "deleteIntent=" + notification.deleteIntent);
        pw.println(prefix + "number=" + notification.number);
        pw.println(prefix + "groupAlertBehavior=" + notification.getGroupAlertBehavior());

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
        pw.println(prefix + "mSystemImportance="
                + NotificationListenerService.Ranking.importanceToString(mSystemImportance));
        pw.println(prefix + "mAsstImportance="
                + NotificationListenerService.Ranking.importanceToString(mAssistantImportance));
        pw.println(prefix + "mImportance="
                + NotificationListenerService.Ranking.importanceToString(mImportance));
        pw.println(prefix + "mImportanceExplanation=" + getImportanceExplanation());
        pw.println(prefix + "mIsAppImportanceLocked=" + mIsAppImportanceLocked);
        pw.println(prefix + "mIntercept=" + mIntercept);
        pw.println(prefix + "mHidden==" + mHidden);
        pw.println(prefix + "mGlobalSortKey=" + mGlobalSortKey);
        pw.println(prefix + "mRankingTimeMs=" + mRankingTimeMs);
        pw.println(prefix + "mCreationTimeMs=" + mCreationTimeMs);
        pw.println(prefix + "mVisibleSinceMs=" + mVisibleSinceMs);
        pw.println(prefix + "mUpdateTimeMs=" + mUpdateTimeMs);
        pw.println(prefix + "mInterruptionTimeMs=" + mInterruptionTimeMs);
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
        pw.println(prefix + "mIsInterruptive=" + mIsInterruptive);
        pw.println(prefix + "effectiveNotificationChannel=" + getChannel());
        if (getPeopleOverride() != null) {
            pw.println(prefix + "overridePeople= " + TextUtils.join(",", getPeopleOverride()));
        }
        if (getSnoozeCriteria() != null) {
            pw.println(prefix + "snoozeCriteria=" + TextUtils.join(",", getSnoozeCriteria()));
        }
        pw.println(prefix + "mAdjustments=" + mAdjustments);
    }

    @Override
    public final String toString() {
        return String.format(
                "NotificationRecord(0x%08x: pkg=%s user=%s id=%d tag=%s importance=%d key=%s" +
                        "appImportanceLocked=%s: %s)",
                System.identityHashCode(this),
                this.sbn.getPackageName(), this.sbn.getUser(), this.sbn.getId(),
                this.sbn.getTag(), this.mImportance, this.sbn.getKey(),
                mIsAppImportanceLocked, this.sbn.getNotification());
    }

    public boolean hasAdjustment(String key) {
        synchronized (mAdjustments) {
            for (Adjustment adjustment : mAdjustments) {
                if (adjustment.getSignals().containsKey(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void addAdjustment(Adjustment adjustment) {
        synchronized (mAdjustments) {
            mAdjustments.add(adjustment);
        }
    }

    public void applyAdjustments() {
        long now = System.currentTimeMillis();
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
                if (signals.containsKey(Adjustment.KEY_USER_SENTIMENT)) {
                    // Only allow user sentiment update from assistant if user hasn't already
                    // expressed a preference for this channel
                    if (!mIsAppImportanceLocked
                            && (getChannel().getUserLockedFields() & USER_LOCKED_IMPORTANCE) == 0) {
                        setUserSentiment(adjustment.getSignals().getInt(
                                Adjustment.KEY_USER_SENTIMENT, USER_SENTIMENT_NEUTRAL));
                    }
                }
                if (signals.containsKey(Adjustment.KEY_CONTEXTUAL_ACTIONS)) {
                    setSystemGeneratedSmartActions(
                            signals.getParcelableArrayList(Adjustment.KEY_CONTEXTUAL_ACTIONS));
                }
                if (signals.containsKey(Adjustment.KEY_TEXT_REPLIES)) {
                    setSmartReplies(signals.getCharSequenceArrayList(Adjustment.KEY_TEXT_REPLIES));
                }
                if (signals.containsKey(Adjustment.KEY_IMPORTANCE)) {
                    int importance = signals.getInt(Adjustment.KEY_IMPORTANCE);
                    importance = Math.max(IMPORTANCE_UNSPECIFIED, importance);
                    importance = Math.min(IMPORTANCE_HIGH, importance);
                    setAssistantImportance(importance);
                }
                if (!signals.isEmpty() && adjustment.getIssuer() != null) {
                    mAdjustmentIssuer = adjustment.getIssuer();
                }
            }
            // We have now gotten all the information out of the adjustments and can forget them.
            mAdjustments.clear();
        }
    }

    public void setIsAppImportanceLocked(boolean isAppImportanceLocked) {
        mIsAppImportanceLocked = isAppImportanceLocked;
        calculateUserSentiment();
    }

    public void setContactAffinity(float contactAffinity) {
        mContactAffinity = contactAffinity;
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

    private String getUserExplanation() {
        if (mUserExplanation == null) {
            mUserExplanation = mContext.getResources().getString(
                    com.android.internal.R.string.importance_from_user);
        }
        return mUserExplanation;
    }

    /**
     * Sets the importance value the system thinks the record should have.
     * e.g. bumping up foreground service notifications or people to people notifications.
     */
    public void setSystemImportance(int importance) {
        mSystemImportance = importance;
        // System importance is only changed in enqueue, so it's ok for us to calculate the
        // importance directly instead of waiting for signal extractor.
        calculateImportance();
    }

    /**
     * Sets the importance value the
     * {@link android.service.notification.NotificationAssistantService} thinks the record should
     * have.
     */
    public void setAssistantImportance(int importance) {
        mAssistantImportance = importance;
        // Unlike the system importance, the assistant importance can change on posted
        // notifications, so don't calculateImportance() here, but wait for the signal extractors.
    }

    /**
     * Returns the importance set by the assistant, or IMPORTANCE_UNSPECIFIED if the assistant
     * hasn't set it.
     */
    public int getAssistantImportance() {
        return mAssistantImportance;
    }

    /**
     * Recalculates the importance of the record after fields affecting importance have changed,
     * and records an explanation.
     */
    protected void calculateImportance() {
        mImportance = calculateInitialImportance();
        mImportanceExplanationCode = mInitialImportanceExplanationCode;

        // Consider Notification Assistant and system overrides to importance. If both, system wins.
        if (!getChannel().hasUserSetImportance()
                && mAssistantImportance != IMPORTANCE_UNSPECIFIED
                && !getChannel().isImportanceLockedByOEM()
                && !getChannel().isImportanceLockedByCriticalDeviceFunction()) {
            mImportance = mAssistantImportance;
            mImportanceExplanationCode = MetricsEvent.IMPORTANCE_EXPLANATION_ASST;
        }
        if (mSystemImportance != IMPORTANCE_UNSPECIFIED) {
            mImportance = mSystemImportance;
            mImportanceExplanationCode = MetricsEvent.IMPORTANCE_EXPLANATION_SYSTEM;
        }
    }

    public int getImportance() {
        return mImportance;
    }

    public CharSequence getImportanceExplanation() {
        switch (mImportanceExplanationCode) {
            case MetricsEvent.IMPORTANCE_EXPLANATION_UNKNOWN:
                return null;
            case MetricsEvent.IMPORTANCE_EXPLANATION_APP:
            case MetricsEvent.IMPORTANCE_EXPLANATION_APP_PRE_CHANNELS:
                return "app";
            case MetricsEvent.IMPORTANCE_EXPLANATION_USER:
                return "user";
            case MetricsEvent.IMPORTANCE_EXPLANATION_ASST:
                return "asst";
            case MetricsEvent.IMPORTANCE_EXPLANATION_SYSTEM:
                return "system";
        }
        return null;
    }

    public boolean setIntercepted(boolean intercept) {
        mIntercept = intercept;
        return mIntercept;
    }

    /**
     * Set to affect global sort key.
     *
     * @param criticality used in a string based sort thus 0 is the most critical
     */
    public void setCriticality(int criticality) {
        mCriticality = criticality;
    }

    public int getCriticality() {
        return mCriticality;
    }

    public boolean isIntercepted() {
        return mIntercept;
    }

    public boolean isNewEnoughForAlerting(long now) {
        return getFreshnessMs(now) <= MAX_SOUND_DELAY_MS;
    }

    public void setHidden(boolean hidden) {
        mHidden = hidden;
    }

    public boolean isHidden() {
        return mHidden;
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

    public boolean isAudioAttributesUsage(int usage) {
        return mAttributes != null && mAttributes.getUsage() == usage;
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

    public int getInterruptionMs(long now) {
        return (int) (now - mInterruptionTimeMs);
    }

    /**
     * Set the visibility of the notification.
     */
    public void setVisibility(boolean visible, int rank, int count) {
        final long now = System.currentTimeMillis();
        mVisibleSinceMs = visible ? now : mVisibleSinceMs;
        stats.onVisibilityChanged(visible);
        MetricsLogger.action(getLogMaker(now)
                .setCategory(MetricsEvent.NOTIFICATION_ITEM)
                .setType(visible ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)
                .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, rank)
                .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, count));
        if (visible) {
            setSeen();
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
        return mStats.hasSeen();
    }

    /** Mark the notification as seen by the user. */
    public void setSeen() {
        mStats.setSeen();
        if (mTextChanged) {
            setInterruptive(true);
        }
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
    }

    public NotificationChannel getChannel() {
        return mChannel;
    }

    /**
     * @see PreferencesHelper#getIsAppImportanceLocked(String, int)
     */
    public boolean getIsAppImportanceLocked() {
        return mIsAppImportanceLocked;
    }

    protected void updateNotificationChannel(NotificationChannel channel) {
        if (channel != null) {
            mChannel = channel;
            calculateImportance();
            calculateUserSentiment();
        }
    }

    public void setShowBadge(boolean showBadge) {
        mShowBadge = showBadge;
    }

    public boolean canBubble() {
        return mAllowBubble;
    }

    public void setAllowBubble(boolean allow) {
        mAllowBubble = allow;
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

    public void setInterruptive(boolean interruptive) {
        mIsInterruptive = interruptive;
        final long now = System.currentTimeMillis();
        mInterruptionTimeMs = interruptive ? now : mInterruptionTimeMs;

        if (interruptive) {
            MetricsLogger.action(getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_INTERRUPTION)
                    .setType(MetricsEvent.TYPE_OPEN)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_INTERRUPTION_MILLIS,
                            getInterruptionMs(now)));
            MetricsLogger.histogram(mContext, "note_interruptive", getInterruptionMs(now));
        }
    }

    public void setAudiblyAlerted(boolean audiblyAlerted) {
        mLastAudiblyAlertedMs = audiblyAlerted ? System.currentTimeMillis() : -1;
    }

    public void setTextChanged(boolean textChanged) {
        mTextChanged = textChanged;
    }

    public void setRecordedInterruption(boolean recorded) {
        mRecordedInterruption = recorded;
    }

    public boolean hasRecordedInterruption() {
        return mRecordedInterruption;
    }

    public boolean isInterruptive() {
        return mIsInterruptive;
    }

    /** Returns the time the notification audibly alerted the user. */
    public long getLastAudiblyAlertedMs() {
        return mLastAudiblyAlertedMs;
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

    private void calculateUserSentiment() {
        if ((getChannel().getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0
                || mIsAppImportanceLocked) {
            mUserSentiment = USER_SENTIMENT_POSITIVE;
        }
    }

    private void setUserSentiment(int userSentiment) {
        mUserSentiment = userSentiment;
    }

    public int getUserSentiment() {
        return mUserSentiment;
    }

    public NotificationStats getStats() {
        return mStats;
    }

    public void recordExpanded() {
        mStats.setExpanded();
    }

    public void recordDirectReplied() {
        mStats.setDirectReplied();
    }

    public void recordDismissalSurface(@NotificationStats.DismissalSurface int surface) {
        mStats.setDismissalSurface(surface);
    }

    public void recordDismissalSentiment(@NotificationStats.DismissalSentiment int sentiment) {
        mStats.setDismissalSentiment(sentiment);
    }

    public void recordSnoozed() {
        mStats.setSnoozed();
    }

    public void recordViewedSettings() {
        mStats.setViewedSettings();
    }

    public void setNumSmartRepliesAdded(int noReplies) {
        mNumberOfSmartRepliesAdded = noReplies;
    }

    public int getNumSmartRepliesAdded() {
        return mNumberOfSmartRepliesAdded;
    }

    public void setNumSmartActionsAdded(int noActions) {
        mNumberOfSmartActionsAdded = noActions;
    }

    public int getNumSmartActionsAdded() {
        return mNumberOfSmartActionsAdded;
    }

    public void setSuggestionsGeneratedByAssistant(boolean generatedByAssistant) {
        mSuggestionsGeneratedByAssistant = generatedByAssistant;
    }

    public boolean getSuggestionsGeneratedByAssistant() {
        return mSuggestionsGeneratedByAssistant;
    }

    public boolean getEditChoicesBeforeSending() {
        return mEditChoicesBeforeSending;
    }

    public void setEditChoicesBeforeSending(boolean editChoicesBeforeSending) {
        mEditChoicesBeforeSending = editChoicesBeforeSending;
    }

    public boolean hasSeenSmartReplies() {
        return mHasSeenSmartReplies;
    }

    public void setSeenSmartReplies(boolean hasSeenSmartReplies) {
        mHasSeenSmartReplies = hasSeenSmartReplies;
    }

    /**
     * Returns whether this notification has been visible and expanded at the same time.
     */
    public boolean hasBeenVisiblyExpanded() {
        return stats.hasBeenVisiblyExpanded();
    }

    public void setSystemGeneratedSmartActions(
            ArrayList<Notification.Action> systemGeneratedSmartActions) {
        mSystemGeneratedSmartActions = systemGeneratedSmartActions;
    }

    public ArrayList<Notification.Action> getSystemGeneratedSmartActions() {
        return mSystemGeneratedSmartActions;
    }

    public void setSmartReplies(ArrayList<CharSequence> smartReplies) {
        mSmartReplies = smartReplies;
    }

    public ArrayList<CharSequence> getSmartReplies() {
        return mSmartReplies;
    }

    /**
     * Returns whether this notification was posted by a secondary app
     */
    public boolean isProxied() {
        return !Objects.equals(sbn.getPackageName(), sbn.getOpPkg());
    }

    /**
     * @return all {@link Uri} that should have permission granted to whoever
     *         will be rendering it. This list has already been vetted to only
     *         include {@link Uri} that the enqueuing app can grant.
     */
    public @Nullable ArraySet<Uri> getGrantableUris() {
        return mGrantableUris;
    }

    /**
     * Collect all {@link Uri} that should have permission granted to whoever
     * will be rendering it.
     */
    protected void calculateGrantableUris() {
        final Notification notification = getNotification();
        notification.visitUris((uri) -> {
            visitGrantableUri(uri, false);
        });

        if (notification.getChannelId() != null) {
            NotificationChannel channel = getChannel();
            if (channel != null) {
                visitGrantableUri(channel.getSound(), (channel.getUserLockedFields()
                        & NotificationChannel.USER_LOCKED_SOUND) != 0);
            }
        }
    }

    /**
     * Note the presence of a {@link Uri} that should have permission granted to
     * whoever will be rendering it.
     * <p>
     * If the enqueuing app has the ability to grant access, it will be added to
     * {@link #mGrantableUris}. Otherwise, this will either log or throw
     * {@link SecurityException} depending on target SDK of enqueuing app.
     */
    private void visitGrantableUri(Uri uri, boolean userOverriddenUri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        // We can't grant Uri permissions from system
        final int sourceUid = sbn.getUid();
        if (sourceUid == android.os.Process.SYSTEM_UID) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            // This will throw SecurityException if caller can't grant
            mUgmInternal.checkGrantUriPermission(sourceUid, null,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));

            if (mGrantableUris == null) {
                mGrantableUris = new ArraySet<>();
            }
            mGrantableUris.add(uri);
        } catch (SecurityException e) {
            if (!userOverriddenUri) {
                if (mTargetSdkVersion >= Build.VERSION_CODES.P) {
                    throw e;
                } else {
                    Log.w(TAG, "Ignoring " + uri + " from " + sourceUid + ": " + e.getMessage());
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public LogMaker getLogMaker(long now) {
        LogMaker lm = sbn.getLogMaker()
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE, mImportance)
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_CREATE_MILLIS, getLifespanMs(now))
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_UPDATE_MILLIS, getFreshnessMs(now))
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS, getExposureMs(now))
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_INTERRUPTION_MILLIS,
                        getInterruptionMs(now));
        // Record results of the calculateImportance() calculation if available.
        if (mImportanceExplanationCode != MetricsEvent.IMPORTANCE_EXPLANATION_UNKNOWN) {
            lm.addTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_EXPLANATION,
                    mImportanceExplanationCode);
            // To avoid redundancy, we log the initial importance information only if it was
            // overridden.
            if (((mImportanceExplanationCode == MetricsEvent.IMPORTANCE_EXPLANATION_ASST)
                    || (mImportanceExplanationCode == MetricsEvent.IMPORTANCE_EXPLANATION_SYSTEM))
                    && (stats.naturalImportance != IMPORTANCE_UNSPECIFIED)) {
                // stats.naturalImportance is due to one of the 3 sources of initial importance.
                lm.addTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL_EXPLANATION,
                        mInitialImportanceExplanationCode);
                lm.addTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL,
                        stats.naturalImportance);
            }
        }
        // Log Assistant override if present, whether or not importance calculation is complete.
        if (mAssistantImportance != IMPORTANCE_UNSPECIFIED) {
            lm.addTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_ASST,
                        mAssistantImportance);
        }
        // Log the issuer of any adjustments that may have affected this notification. We only log
        // the hash here as NotificationItem events are frequent, and the number of NAS
        // implementations (and hence the chance of collisions) is low.
        if (mAdjustmentIssuer != null) {
            lm.addTaggedData(MetricsEvent.FIELD_NOTIFICATION_ASSISTANT_SERVICE_HASH,
                    mAdjustmentIssuer.hashCode());
        }
        return lm;
    }

    public LogMaker getLogMaker() {
        return getLogMaker(System.currentTimeMillis());
    }

    public LogMaker getItemLogMaker() {
        return getLogMaker().setCategory(MetricsEvent.NOTIFICATION_ITEM);
    }

    public boolean hasUndecoratedRemoteView() {
        Notification notification = getNotification();
        Class<? extends Notification.Style> style = notification.getNotificationStyle();
        boolean hasDecoratedStyle = style != null
                && (Notification.DecoratedCustomViewStyle.class.equals(style)
                || Notification.DecoratedMediaCustomViewStyle.class.equals(style));
        boolean hasCustomRemoteView = notification.contentView != null
                || notification.bigContentView != null
                || notification.headsUpContentView != null;
        return hasCustomRemoteView && !hasDecoratedStyle;
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
