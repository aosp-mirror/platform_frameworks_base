/**
 * Copyright (C) 2017 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.app.Flags;
import android.app.RemoteInput;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Information about how the user has interacted with a given notification.
 * @hide
 */
@SystemApi
public final class NotificationStats implements Parcelable {

    private boolean mSeen;
    private boolean mExpanded;
    private boolean mDirectReplied;
    private boolean mSmartReplied;
    private boolean mSnoozed;
    private boolean mViewedSettings;
    private boolean mInteracted;

    /** @hide */
    @IntDef(prefix = { "DISMISSAL_SURFACE_" }, value = {
            DISMISSAL_NOT_DISMISSED, DISMISSAL_OTHER, DISMISSAL_PEEK, DISMISSAL_AOD,
            DISMISSAL_SHADE, DISMISSAL_BUBBLE, DISMISSAL_LOCKSCREEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DismissalSurface {}


    private @DismissalSurface int mDismissalSurface = DISMISSAL_NOT_DISMISSED;

    /**
     * Notification has not been dismissed yet.
     */
    public static final int DISMISSAL_NOT_DISMISSED = -1;
    /**
     * Notification has been dismissed from a {@link NotificationListenerService} or the app
     * itself.
     */
    public static final int DISMISSAL_OTHER = 0;
    /**
     * Notification has been dismissed while peeking.
     */
    public static final int DISMISSAL_PEEK = 1;
    /**
     * Notification has been dismissed from always on display.
     */
    public static final int DISMISSAL_AOD = 2;
    /**
     * Notification has been dismissed from the notification shade.
     */
    public static final int DISMISSAL_SHADE = 3;
    /**
     * Notification has been dismissed as a bubble.
     * @hide
     */
    public static final int DISMISSAL_BUBBLE = 4;
    /**
     * Notification has been dismissed from the lock screen.
     * @hide
     */
    public static final int DISMISSAL_LOCKSCREEN = 5;

    /** @hide */
    @IntDef(prefix = { "DISMISS_SENTIMENT_" }, value = {
            DISMISS_SENTIMENT_UNKNOWN, DISMISS_SENTIMENT_NEGATIVE, DISMISS_SENTIMENT_NEUTRAL,
            DISMISS_SENTIMENT_POSITIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DismissalSentiment {}

    /**
     * No information is available about why this notification was dismissed, or the notification
     * isn't dismissed yet.
     */
    public static final int DISMISS_SENTIMENT_UNKNOWN = -1000;
    /**
     * The user indicated while dismissing that they did not like the notification.
     */
    public static final int DISMISS_SENTIMENT_NEGATIVE = 0;
    /**
     * The user didn't indicate one way or another how they felt about the notification while
     * dismissing it.
     */
    public static final int DISMISS_SENTIMENT_NEUTRAL = 1;
    /**
     * The user indicated while dismissing that they did like the notification.
     */
    public static final int DISMISS_SENTIMENT_POSITIVE = 2;


    private @DismissalSentiment
    int mDismissalSentiment = DISMISS_SENTIMENT_UNKNOWN;

    public NotificationStats() {
    }

    /**
     * @hide
     */
    @SystemApi
    protected NotificationStats(Parcel in) {
        mSeen = in.readByte() != 0;
        mExpanded = in.readByte() != 0;
        mDirectReplied = in.readByte() != 0;
        if (Flags.lifetimeExtensionRefactor()) {
            mSmartReplied = in.readByte() != 0;
        }
        mSnoozed = in.readByte() != 0;
        mViewedSettings = in.readByte() != 0;
        mInteracted = in.readByte() != 0;
        mDismissalSurface = in.readInt();
        mDismissalSentiment = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (mSeen ? 1 : 0));
        dest.writeByte((byte) (mExpanded ? 1 : 0));
        dest.writeByte((byte) (mDirectReplied ? 1 : 0));
        if (Flags.lifetimeExtensionRefactor()) {
            dest.writeByte((byte) (mSmartReplied ? 1 : 0));
        }
        dest.writeByte((byte) (mSnoozed ? 1 : 0));
        dest.writeByte((byte) (mViewedSettings ? 1 : 0));
        dest.writeByte((byte) (mInteracted ? 1 : 0));
        dest.writeInt(mDismissalSurface);
        dest.writeInt(mDismissalSentiment);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<NotificationStats> CREATOR = new Creator<NotificationStats>() {
        @Override
        public NotificationStats createFromParcel(Parcel in) {
            return new NotificationStats(in);
        }

        @Override
        public NotificationStats[] newArray(int size) {
            return new NotificationStats[size];
        }
    };

    /**
     * Returns whether the user has seen this notification at least once.
     */
    public boolean hasSeen() {
        return mSeen;
    }

    /**
     * Records that the user as seen this notification at least once.
     */
    public void setSeen() {
        mSeen = true;
    }

    /**
     * Returns whether the user has expanded this notification at least once.
     */
    public boolean hasExpanded() {
        return mExpanded;
    }

    /**
     * Records that the user has expanded this notification at least once.
     */
    public void setExpanded() {
        mExpanded = true;
        mInteracted = true;
    }

    /**
     * Returns whether the user has replied to a notification that has a
     * {@link android.app.Notification.Action.Builder#addRemoteInput(RemoteInput) direct reply} at
     * least once.
     */
    public boolean hasDirectReplied() {
        return mDirectReplied;
    }

    /**
     * Records that the user has replied to a notification that has a
     * {@link android.app.Notification.Action.Builder#addRemoteInput(RemoteInput) direct reply}
     * at least once.
     */
    public void setDirectReplied() {
        mDirectReplied = true;
        mInteracted = true;
    }

    /**
     * Returns whether the user has replied to a notification that has a smart reply at least once.
     */
    @FlaggedApi(Flags.FLAG_LIFETIME_EXTENSION_REFACTOR)
    public boolean hasSmartReplied() {
        return mSmartReplied;
    }

    /**
     * Records that the user has replied to a notification that has a smart reply at least once.
     */
    @FlaggedApi(Flags.FLAG_LIFETIME_EXTENSION_REFACTOR)
    public void setSmartReplied() {
        mSmartReplied = true;
        mInteracted = true;
    }

    /**
     * Returns whether the user has snoozed this notification at least once.
     */
    public boolean hasSnoozed() {
        return mSnoozed;
    }

    /**
     * Records that the user has snoozed this notification at least once.
     */
    public void setSnoozed() {
        mSnoozed = true;
        mInteracted = true;
    }

    /**
     * Returns whether the user has viewed the in-shade settings for this notification at least
     * once.
     */
    public boolean hasViewedSettings() {
        return mViewedSettings;
    }

    /**
     * Records that the user has viewed the in-shade settings for this notification at least once.
     */
    public void setViewedSettings() {
        mViewedSettings = true;
        mInteracted = true;
    }

    /**
     * Returns whether the user has interacted with this notification beyond having viewed it.
     */
    public boolean hasInteracted() {
        return mInteracted;
    }

    /**
     * Returns from which surface the notification was dismissed.
     */
    public @DismissalSurface int getDismissalSurface() {
        return mDismissalSurface;
    }

    /**
     * Returns from which surface the notification was dismissed.
     */
    public void setDismissalSurface(@DismissalSurface int dismissalSurface) {
        mDismissalSurface = dismissalSurface;
    }

    /**
     * Records whether the user indicated how they felt about a notification before or
     * during dismissal.
     */
    public void setDismissalSentiment(@DismissalSentiment int dismissalSentiment) {
        mDismissalSentiment = dismissalSentiment;
    }

    /**
     * Returns how the user indicated they felt about a notification before or during dismissal.
     */
    public @DismissalSentiment int getDismissalSentiment() {
        return mDismissalSentiment;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NotificationStats that = (NotificationStats) o;

        if (mSeen != that.mSeen) return false;
        if (mExpanded != that.mExpanded) return false;
        if (mDirectReplied != that.mDirectReplied) return false;
        if (Flags.lifetimeExtensionRefactor()) {
            if (mSmartReplied != that.mSmartReplied) return false;
        }
        if (mSnoozed != that.mSnoozed) return false;
        if (mViewedSettings != that.mViewedSettings) return false;
        if (mInteracted != that.mInteracted) return false;
        return mDismissalSurface == that.mDismissalSurface;
    }

    @Override
    public int hashCode() {
        int result = (mSeen ? 1 : 0);
        result = 31 * result + (mExpanded ? 1 : 0);
        result = 31 * result + (mDirectReplied ? 1 : 0);
        if (Flags.lifetimeExtensionRefactor()) {
            result = 31 * result + (mSmartReplied ? 1 : 0);
        }
        result = 31 * result + (mSnoozed ? 1 : 0);
        result = 31 * result + (mViewedSettings ? 1 : 0);
        result = 31 * result + (mInteracted ? 1 : 0);
        result = 31 * result + mDismissalSurface;
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("NotificationStats{");
        sb.append("mSeen=").append(mSeen);
        sb.append(", mExpanded=").append(mExpanded);
        sb.append(", mDirectReplied=").append(mDirectReplied);
        if (Flags.lifetimeExtensionRefactor()) {
            sb.append(", mSmartReplied=").append(mSmartReplied);
        }
        sb.append(", mSnoozed=").append(mSnoozed);
        sb.append(", mViewedSettings=").append(mViewedSettings);
        sb.append(", mInteracted=").append(mInteracted);
        sb.append(", mDismissalSurface=").append(mDismissalSurface);
        sb.append('}');
        return sb.toString();
    }
}
