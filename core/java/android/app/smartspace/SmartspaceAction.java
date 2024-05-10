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

package android.app.smartspace;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A {@link SmartspaceAction} represents an action which can be taken by a user by tapping on either
 * the title, the subtitle or on the icon. Supported instances are Intents, PendingIntents or a
 * ShortcutInfo (by putting the ShortcutInfoId in the bundle). These actions can be called from
 * another process or within the client process.
 *
 * Clients can also receive conditional Intents/PendingIntents in the extras bundle which are
 * supposed to be fired when the conditions are met. For example, a user can invoke a dismiss/block
 * action on a game score card but the intention is to only block the team and not the entire
 * feature.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceAction implements Parcelable {

    private static final String TAG = "SmartspaceAction";

    /** A unique Id of this {@link SmartspaceAction}. */
    @NonNull
    private final String mId;

    /** An Icon which can be displayed in the UI. */
    @Nullable
    private final Icon mIcon;

    /** Title associated with an action. */
    @NonNull
    private final CharSequence mTitle;

    /** Subtitle associated with an action. */
    @Nullable
    private final CharSequence mSubtitle;

    @Nullable
    private final CharSequence mContentDescription;

    @Nullable
    private final PendingIntent mPendingIntent;

    @Nullable
    private final Intent mIntent;

    @Nullable
    private final UserHandle mUserHandle;

    @Nullable
    private Bundle mExtras;

    SmartspaceAction(Parcel in) {
        mId = in.readString();
        mIcon = in.readTypedObject(Icon.CREATOR);
        mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mSubtitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
        mIntent = in.readTypedObject(Intent.CREATOR);
        mUserHandle = in.readTypedObject(UserHandle.CREATOR);
        mExtras = in.readBundle();
    }

    private SmartspaceAction(
            @NonNull String id,
            @Nullable Icon icon,
            @NonNull CharSequence title,
            @Nullable CharSequence subtitle,
            @Nullable CharSequence contentDescription,
            @Nullable PendingIntent pendingIntent,
            @Nullable Intent intent,
            @Nullable UserHandle userHandle,
            @Nullable Bundle extras) {
        mId = Objects.requireNonNull(id);
        mIcon = icon;
        mTitle = Objects.requireNonNull(title);
        mSubtitle = subtitle;
        mContentDescription = contentDescription;
        mPendingIntent = pendingIntent;
        mIntent = intent;
        mUserHandle = userHandle;
        mExtras = extras;
    }

    /**
     * Returns the unique id of this object.
     */
    public @NonNull String getId() {
        return mId;
    }

    /**
     * Returns an icon representing the action.
     */
    public @Nullable Icon getIcon() {
        return mIcon;
    }

    /**
     * Returns a title representing the action.
     */
    public @NonNull CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Returns a subtitle representing the action.
     */
    public @Nullable CharSequence getSubtitle() {
        return mSubtitle;
    }

    /**
     * Returns a content description representing the action.
     */
    public @Nullable CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Returns the action intent.
     */
    public @Nullable PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /**
     * Returns the intent.
     */
    public @Nullable Intent getIntent() {
        return mIntent;
    }

    /**
     * Returns the user handle.
     */
    public @Nullable UserHandle getUserHandle() {
        return mUserHandle;
    }

    /**
     * Returns the extra bundle for this object.
     */
    @SuppressLint("NullableCollection")
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmartspaceAction)) return false;
        SmartspaceAction that = (SmartspaceAction) o;
        return mId.equals(that.mId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mId);
        out.writeTypedObject(mIcon, flags);
        TextUtils.writeToParcel(mTitle, out, flags);
        TextUtils.writeToParcel(mSubtitle, out, flags);
        TextUtils.writeToParcel(mContentDescription, out, flags);
        out.writeTypedObject(mPendingIntent, flags);
        out.writeTypedObject(mIntent, flags);
        out.writeTypedObject(mUserHandle, flags);
        out.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        return "SmartspaceAction{"
                + "mId='" + mId + '\''
                + ", mIcon=" + mIcon
                + ", mTitle=" + mTitle
                + ", mSubtitle=" + mSubtitle
                + ", mContentDescription=" + mContentDescription
                + ", mPendingIntent=" + mPendingIntent
                + ", mIntent=" + mIntent
                + ", mUserHandle=" + mUserHandle
                + ", mExtras=" + mExtras
                + '}';
    }

    public static final @NonNull Creator<SmartspaceAction> CREATOR =
            new Creator<SmartspaceAction>() {
                public SmartspaceAction createFromParcel(Parcel in) {
                    return new SmartspaceAction(in);
                }
                public SmartspaceAction[] newArray(int size) {
                    return new SmartspaceAction[size];
                }
            };

    /**
     * A builder for Smartspace action object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        @NonNull
        private String mId;

        @Nullable
        private Icon mIcon;

        @NonNull
        private CharSequence mTitle;

        @Nullable
        private CharSequence mSubtitle;

        @Nullable
        private CharSequence mContentDescription;

        @Nullable
        private PendingIntent mPendingIntent;

        @Nullable
        private Intent mIntent;

        @Nullable
        private UserHandle mUserHandle;

        @Nullable
        private Bundle mExtras;

        /**
         * Id and title are required.
         */
        public Builder(@NonNull String id, @NonNull String title) {
            mId = Objects.requireNonNull(id);
            mTitle = Objects.requireNonNull(title);
        }

        /**
         * Sets the icon.
         */
        @NonNull
        public Builder setIcon(
                @Nullable Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the subtitle.
         */
        @NonNull
        public Builder setSubtitle(
                @Nullable CharSequence subtitle) {
            mSubtitle = subtitle;
            return this;
        }

        /**
         * Sets the content description.
         */
        @NonNull
        public Builder setContentDescription(
                @Nullable CharSequence contentDescription) {
            mContentDescription = contentDescription;
            return this;
        }

        /**
         * Sets the pending intent.
         */
        @NonNull
        public Builder setPendingIntent(@Nullable PendingIntent pendingIntent) {
            mPendingIntent = pendingIntent;
            return this;
        }

        /**
         * Sets the user handle.
         */
        @NonNull
        public Builder setUserHandle(@Nullable UserHandle userHandle) {
            mUserHandle = userHandle;
            return this;
        }

        /**
         * Sets the intent.
         */
        @NonNull
        public Builder setIntent(@Nullable Intent intent) {
            mIntent = intent;
            return this;
        }

        /**
         * Sets the extra.
         */
        @NonNull
        public Builder setExtras(@SuppressLint("NullableCollection") @Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds a new SmartspaceAction instance.
         *
         * @throws IllegalStateException if no target is set
         */
        @NonNull
        public SmartspaceAction build() {
            if (mIcon != null) {
                mIcon.convertToAshmem();
            }

            return new SmartspaceAction(mId, mIcon, mTitle, mSubtitle, mContentDescription,
                    mPendingIntent, mIntent, mUserHandle, mExtras);
        }
    }
}
