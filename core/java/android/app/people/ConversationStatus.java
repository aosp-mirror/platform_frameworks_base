/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.people;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

public final class ConversationStatus implements Parcelable {
    private static final String TAG = "ConversationStatus";

    /** @hide */
    @IntDef(prefix = { "ACTIVITY_" }, value = {
            ACTIVITY_OTHER,
            ACTIVITY_BIRTHDAY,
            ACTIVITY_ANNIVERSARY,
            ACTIVITY_NEW_STORY,
            ACTIVITY_AUDIO,
            ACTIVITY_VIDEO,
            ACTIVITY_GAME,
            ACTIVITY_LOCATION,
            ACTIVITY_UPCOMING_BIRTHDAY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActivityType {}

    /**
     * Constant representing that the conversation user is engaged in an activity that cannot be
     * more specifically represented by another type.
     */
    public static final int ACTIVITY_OTHER = 0;
    /**
     * Constant representing that today is the conversation user's birthday.
     */
    public static final int ACTIVITY_BIRTHDAY = 1;
    /**
     * Constant representing that the conversation user and the device user are celebrating
     * and anniversary today.
     */
    public static final int ACTIVITY_ANNIVERSARY = 2;
    /**
     * Constant representing that the conversation user has posted a new story.
     */
    public static final int ACTIVITY_NEW_STORY = 3;
    /**
     * Constant representing that the conversation user is listening to music or other audio
     * like a podcast.
     */
    public static final int ACTIVITY_AUDIO = 4;
    /**
     * Constant representing that the conversation user is watching video content.
     */
    public static final int ACTIVITY_VIDEO = 5;
    /**
     * Constant representing that the conversation user is playing a game.
     */
    public static final int ACTIVITY_GAME = 6;
    /**
     * Constant representing that the conversation user is sharing status with the device user.
     * Use this to represent a general 'this person is sharing their location with you' status or
     * a more specific 'this is the current location of this person' status.
     */
    public static final int ACTIVITY_LOCATION = 7;
    /**
     * Constant representing that the conversation user's birthday is approaching soon.
     */
    public static final int ACTIVITY_UPCOMING_BIRTHDAY = 8;

    /** @hide */
    @IntDef(prefix = { "AVAILABILITY_" }, value = {
            AVAILABILITY_UNKNOWN,
            AVAILABILITY_AVAILABLE,
            AVAILABILITY_BUSY,
            AVAILABILITY_OFFLINE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Availability {}

    public static final int AVAILABILITY_UNKNOWN = -1;
    public static final int AVAILABILITY_AVAILABLE = 0;
    public static final int AVAILABILITY_BUSY = 1;
    public static final int AVAILABILITY_OFFLINE = 2;

    private final String mId;
    private final int mActivity;

    private int mAvailability;
    private CharSequence mDescription;
    private Icon mIcon;
    private long mStartTimeMs;
    private long mEndTimeMs;

    private ConversationStatus(Builder b) {
        mId = b.mId;
        mActivity = b.mActivity;
        mAvailability = b.mAvailability;
        mDescription = b.mDescription;
        mIcon = b.mIcon;
        mStartTimeMs = b.mStartTimeMs;
        mEndTimeMs = b.mEndTimeMs;
    }

    private ConversationStatus(Parcel p) {
        mId = p.readString();
        mActivity = p.readInt();
        mAvailability = p.readInt();
        mDescription = p.readCharSequence();
        mIcon = p.readParcelable(Icon.class.getClassLoader(), android.graphics.drawable.Icon.class);
        mStartTimeMs = p.readLong();
        mEndTimeMs = p.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeInt(mActivity);
        dest.writeInt(mAvailability);
        dest.writeCharSequence(mDescription);
        dest.writeParcelable(mIcon, flags);
        dest.writeLong(mStartTimeMs);
        dest.writeLong(mEndTimeMs);
    }

    /**
     * Returns the unique identifier for the status.
     */
    public @NonNull String getId() {
        return mId;
    }

    /**
     * Returns the type of activity represented by this status
     */
    public @ActivityType int getActivity() {
        return mActivity;
    }

    /**
     * Returns the availability of the people behind this conversation while this activity is
     * happening.
     */
    public @Availability int getAvailability() {
        return mAvailability;
    }

    /**
     * Returns the description for this activity.
     */
    public @Nullable CharSequence getDescription() {
        return mDescription;
    }

    /**
     * Returns the image for this activity.
     */
    public @Nullable Icon getIcon() {
        return mIcon;
    }

    /**
     * Returns the time at which this status started
     */
    public long getStartTimeMillis() {
        return mStartTimeMs;
    }

    /**
     * Returns the time at which this status should be expired.
     */
    public long getEndTimeMillis() {
        return mEndTimeMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConversationStatus that = (ConversationStatus) o;
        return mActivity == that.mActivity &&
                mAvailability == that.mAvailability &&
                mStartTimeMs == that.mStartTimeMs &&
                mEndTimeMs == that.mEndTimeMs &&
                mId.equals(that.mId) &&
                Objects.equals(mDescription, that.mDescription) &&
                Objects.equals(mIcon, that.mIcon);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mActivity, mAvailability, mDescription, mIcon, mStartTimeMs,
                mEndTimeMs);
    }

    @Override
    public String toString() {
        return "ConversationStatus{" +
                "mId='" + mId + '\'' +
                ", mActivity=" + mActivity +
                ", mAvailability=" + mAvailability +
                ", mDescription=" + mDescription +
                ", mIcon=" + mIcon +
                ", mStartTimeMs=" + mStartTimeMs +
                ", mEndTimeMs=" + mEndTimeMs +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<ConversationStatus> CREATOR
            = new Creator<ConversationStatus>() {
        public ConversationStatus createFromParcel(Parcel source) {
            return new ConversationStatus(source);
        }

        public ConversationStatus[] newArray(int size) {
            return new ConversationStatus[size];
        }
    };

    public static final class Builder {
        final String mId;
        final int mActivity;
        int mAvailability = AVAILABILITY_UNKNOWN;
        CharSequence mDescription;
        Icon mIcon;
        long mStartTimeMs = -1;
        long mEndTimeMs = -1;

        /**
         * Creates a new builder.
         *
         * @param id The unique id for this status
         * @param activity The type of status
         */
        public Builder(@NonNull String id, @ActivityType @NonNull int activity) {
            mId = id;
            mActivity = activity;
        }


        /**
         * Sets the availability of the conversation to provide a hint about how likely
         * it is that the user would receive a timely response if they sent a message.
         */
        public @NonNull Builder setAvailability(@Availability int availability) {
            mAvailability = availability;
            return this;
        }

        /**
         * Sets a user visible description expanding on the conversation user(s)'s activity.
         *
         * <p>Examples include: what media someone is watching or listening to, their approximate
         * location, or what type of anniversary they are celebrating.</p>
         */
        public @NonNull Builder setDescription(@Nullable CharSequence description) {
            mDescription = description;
            return this;
        }

        /**
         * Sets an image representing the conversation user(s)'s activity.
         *
         * <p>Examples include: A still from a new story update, album art, or a map showing
         * approximate location.</p>
         */
        public @NonNull Builder setIcon(@Nullable Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the time at which this status became valid.
         */
        public @NonNull Builder setStartTimeMillis(long startTimeMs) {
            mStartTimeMs = startTimeMs;
            return this;
        }

        /**
         * Sets an expiration time for this status.
         *
         * <p>The system will remove the status at this time if it hasn't already been withdrawn.
         * </p>
         */
        public @NonNull Builder setEndTimeMillis(long endTimeMs) {
            mEndTimeMs = endTimeMs;
            return this;
        }

        public @NonNull ConversationStatus build() {
            return new ConversationStatus(this);
        }
    }
}
