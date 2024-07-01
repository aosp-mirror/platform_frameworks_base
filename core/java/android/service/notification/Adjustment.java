/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Ranking updates from the Assistant.
 *
 * The updates are provides as a {@link Bundle} of signals, using the keys provided in this
 * class.
 * Each {@code KEY} specifies what type of data it supports and what kind of Adjustment it
 * realizes on the notification rankings.
 *
 * Notifications affected by the Adjustment will be re-ranked if necessary.
 *
 * @hide
 */
@SystemApi
public final class Adjustment implements Parcelable {
    private final String mPackage;
    private final String mKey;
    private final CharSequence mExplanation;
    private final Bundle mSignals;
    private final int mUser;
    @Nullable private String mIssuer;

    /** @hide */
    @StringDef (prefix = { "KEY_" }, value = {
            KEY_PEOPLE,
            KEY_SNOOZE_CRITERIA,
            KEY_GROUP_KEY,
            KEY_USER_SENTIMENT,
            KEY_CONTEXTUAL_ACTIONS,
            KEY_TEXT_REPLIES,
            KEY_IMPORTANCE,
            KEY_IMPORTANCE_PROPOSAL,
            KEY_SENSITIVE_CONTENT,
            KEY_RANKING_SCORE,
            KEY_NOT_CONVERSATION,
            KEY_TYPE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Keys {}

    /**
     * Data type: ArrayList of {@code String}, where each is a representation of a
     * {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}.
     * See {@link android.app.Notification.Builder#addPerson(String)}.
     * @hide
     */
    @SystemApi
    public static final String KEY_PEOPLE = "key_people";

    /**
     * Parcelable {@code ArrayList} of {@link SnoozeCriterion}. These criteria may be visible to
     * users. If a user chooses to snooze a notification until one of these criterion, the
     * assistant will be notified via
     * {@link NotificationAssistantService#onNotificationSnoozedUntilContext}.
     */
    public static final String KEY_SNOOZE_CRITERIA = "key_snooze_criteria";

    /**
     * Data type: String. Used to change what {@link Notification#getGroup() group} a notification
     * belongs to.
     * @hide
     */
    public static final String KEY_GROUP_KEY = "key_group_key";

    /**
     * Data type: int, one of {@link NotificationListenerService.Ranking#USER_SENTIMENT_POSITIVE},
     * {@link NotificationListenerService.Ranking#USER_SENTIMENT_NEUTRAL},
     * {@link NotificationListenerService.Ranking#USER_SENTIMENT_NEGATIVE}. Used to express how
     * a user feels about notifications in the same {@link android.app.NotificationChannel} as
     * the notification represented by {@link #getKey()}.
     */
    public static final String KEY_USER_SENTIMENT = "key_user_sentiment";

    /**
     * Data type: ArrayList of {@link android.app.Notification.Action}.
     * Used to suggest contextual actions for a notification.
     *
     * @see Notification.Action.Builder#setContextual(boolean)
     */
    public static final String KEY_CONTEXTUAL_ACTIONS = "key_contextual_actions";

    /**
     * Data type: ArrayList of {@link CharSequence}.
     * Used to suggest smart replies for a notification.
     */
    public static final String KEY_TEXT_REPLIES = "key_text_replies";

    /**
     * Data type: int, one of importance values e.g.
     * {@link android.app.NotificationManager#IMPORTANCE_MIN}.
     *
     * <p> If used from
     * {@link NotificationAssistantService#onNotificationEnqueued(StatusBarNotification)}, and
     * received before the notification is posted, it can block a notification from appearing or
     * silence it. Importance adjustments received too late from
     * {@link NotificationAssistantService#onNotificationEnqueued(StatusBarNotification)} will be
     * ignored.
     * </p>
     * <p>If used from
     * {@link NotificationAssistantService#adjustNotification(Adjustment)}, it can
     * visually demote or cancel a notification, but use this with care if they notification was
     * recently posted because the notification may already have made noise.
     * </p>
     */
    public static final String KEY_IMPORTANCE = "key_importance";

    /**
     * Weaker than {@link #KEY_IMPORTANCE}, this adjustment suggests an importance rather than
     * mandates an importance change.
     *
     * A notification listener can interpet this suggestion to show the user a prompt to change
     * notification importance for the notification (or type, or app) moving forward.
     *
     * Data type: int, one of importance values e.g.
     * {@link android.app.NotificationManager#IMPORTANCE_MIN}.
     */
    public static final String KEY_IMPORTANCE_PROPOSAL = "key_importance_proposal";

    /**
     * Data type: boolean, when true it suggests that the content text of this notification is
     * sensitive. The system uses this information to improve privacy around the notification
     * content. In {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, sensitive notification content is
     * redacted from updates to most {@link NotificationListenerService
     * NotificationListenerServices}. Also if an app posts a sensitive notification while
     * {@link android.media.projection.MediaProjection screen-sharing} is active, that app's windows
     * are blocked from screen-sharing and a {@link android.widget.Toast Toast} is shown to inform
     * the user about this.
     */
    public static final String KEY_SENSITIVE_CONTENT = "key_sensitive_content";

    /**
     * Data type: float, a ranking score from 0 (lowest) to 1 (highest).
     * Used to rank notifications inside that fall under the same classification (i.e. alerting,
     * silenced).
     */
    public static final String KEY_RANKING_SCORE = "key_ranking_score";

    /**
     * Data type: boolean, when true it suggests this is NOT a conversation notification.
     * @hide
     */
    @SystemApi
    public static final String KEY_NOT_CONVERSATION = "key_not_conversation";

    /**
     * Data type: int, the classification type of this notification. The OS may display
     * notifications differently depending on the type, and may change the alerting level of the
     * notification.
     */
    @FlaggedApi(Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public static final String KEY_TYPE = "key_type";

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_OTHER,
            TYPE_PROMOTION,
            TYPE_SOCIAL_MEDIA,
            TYPE_NEWS,
            TYPE_CONTENT_RECOMMENDATION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Types {}

    /**
     * This notification can be categorized, but not into one of the other categories known to the
     * OS at a given version.
     */
    @FlaggedApi(Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public static final int TYPE_OTHER = 0;
    /**
     * The type of this notification is a promotion/deal.
     */
    @FlaggedApi(Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public static final int TYPE_PROMOTION = 1;
    /**
     * The type of this notification is social media content that isn't a
     * {@link Notification.Builder#setShortcutId(String) conversation}.
     */
    @FlaggedApi(Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public static final int TYPE_SOCIAL_MEDIA = 2;
    /**
     * The type of this notification is news.
     */
    @FlaggedApi(Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public static final int TYPE_NEWS = 3;
    /**
     * The type of this notification is content recommendation, for example new videos or books the
     * user may be interested in.
     */
    @FlaggedApi(Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public static final int TYPE_CONTENT_RECOMMENDATION = 4;

    /**
     * Create a notification adjustment.
     *
     * @param pkg The package of the notification.
     * @param key The notification key.
     * @param signals A bundle of signals that should inform notification display, ordering, and
     *                interruptiveness.
     * @param explanation A human-readable justification for the adjustment.
     * @hide
     */
    @SystemApi
    public Adjustment(String pkg, String key, Bundle signals, CharSequence explanation, int user) {
        mPackage = pkg;
        mKey = key;
        mSignals = signals;
        mExplanation = explanation;
        mUser = user;
    }

    /**
     * Create a notification adjustment.
     *
     * @param pkg The package of the notification.
     * @param key The notification key.
     * @param signals A bundle of signals that should inform notification display, ordering, and
     *                interruptiveness.
     * @param explanation A human-readable justification for the adjustment.
     * @param userHandle User handle for for whose the adjustments will be applied.
     */
    public Adjustment(@NonNull String pkg, @NonNull String key, @NonNull Bundle signals,
            @NonNull CharSequence explanation,
            @NonNull UserHandle userHandle) {
        mPackage = pkg;
        mKey = key;
        mSignals = signals;
        mExplanation = explanation;
        mUser = userHandle.getIdentifier();
    }

    /**
     * @hide
     */
    @SystemApi
    protected Adjustment(Parcel in) {
        if (in.readInt() == 1) {
            mPackage = in.readString();
        } else {
            mPackage = null;
        }
        if (in.readInt() == 1) {
            mKey = in.readString();
        } else {
            mKey = null;
        }
        if (in.readInt() == 1) {
            mExplanation = in.readCharSequence();
        } else {
            mExplanation = null;
        }
        mSignals = in.readBundle();
        mUser = in.readInt();
        mIssuer = in.readString();
    }

    public static final @android.annotation.NonNull Creator<Adjustment> CREATOR = new Creator<Adjustment>() {
        @Override
        public Adjustment createFromParcel(Parcel in) {
            return new Adjustment(in);
        }

        @Override
        public Adjustment[] newArray(int size) {
            return new Adjustment[size];
        }
    };

    public @NonNull String getPackage() {
        return mPackage;
    }

    public @NonNull String getKey() {
        return mKey;
    }

    public @NonNull CharSequence getExplanation() {
        return mExplanation;
    }

    public @NonNull Bundle getSignals() {
        return mSignals;
    }

    /** @hide */
    @SystemApi
    public int getUser() {
        return mUser;
    }

    public @NonNull UserHandle getUserHandle() {
        return UserHandle.of(mUser);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mPackage != null) {
            dest.writeInt(1);
            dest.writeString(mPackage);
        } else {
            dest.writeInt(0);
        }
        if (mKey != null) {
            dest.writeInt(1);
            dest.writeString(mKey);
        } else {
            dest.writeInt(0);
        }
        if (mExplanation != null) {
            dest.writeInt(1);
            dest.writeCharSequence(mExplanation);
        } else {
            dest.writeInt(0);
        }
        dest.writeBundle(mSignals);
        dest.writeInt(mUser);
        dest.writeString(mIssuer);
    }

    @NonNull
    @Override
    public String toString() {
        return "Adjustment{"
                + "mSignals=" + mSignals
                + '}';
    }

    /** @hide */
    public void setIssuer(@Nullable String issuer) {
        mIssuer = issuer;
    }

    /** @hide */
    public @Nullable String getIssuer() {
        return mIssuer;
    }
}
