/*
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

package android.service.settings.suggestions;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Data object that has information about a device suggestion.
 *
 * @hide
 */
@SystemApi
public final class Suggestion implements Parcelable {

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_HAS_BUTTON,
            FLAG_ICON_TINTABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {
    }

    /**
     * Flag for suggestion type with a single button
     */
    public static final int FLAG_HAS_BUTTON = 1 << 0;
    /**
     * @hide
     */
    public static final int FLAG_ICON_TINTABLE = 1 << 1;

    private final String mId;
    private final CharSequence mTitle;
    private final CharSequence mSummary;
    private final Icon mIcon;
    @Flags
    private final int mFlags;
    private final PendingIntent mPendingIntent;

    /**
     * Gets the id for the suggestion object.
     */
    public String getId() {
        return mId;
    }

    /**
     * Title of the suggestion that is shown to the user.
     */
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Optional summary describing what this suggestion controls.
     */
    public CharSequence getSummary() {
        return mSummary;
    }

    /**
     * Optional icon for this suggestion.
     */
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Optional flags for this suggestion. This will influence UI when rendering suggestion in
     * different style.
     */
    @Flags
    public int getFlags() {
        return mFlags;
    }

    /**
     * The Intent to launch when the suggestion is activated.
     */
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    private Suggestion(Builder builder) {
        mId = builder.mId;
        mTitle = builder.mTitle;
        mSummary = builder.mSummary;
        mIcon = builder.mIcon;
        mFlags = builder.mFlags;
        mPendingIntent = builder.mPendingIntent;
    }

    private Suggestion(Parcel in) {
        mId = in.readString();
        mTitle = in.readCharSequence();
        mSummary = in.readCharSequence();
        mIcon = in.readParcelable(Icon.class.getClassLoader());
        mFlags = in.readInt();
        mPendingIntent = in.readParcelable(PendingIntent.class.getClassLoader());
    }

    public static final Creator<Suggestion> CREATOR = new Creator<Suggestion>() {
        @Override
        public Suggestion createFromParcel(Parcel in) {
            return new Suggestion(in);
        }

        @Override
        public Suggestion[] newArray(int size) {
            return new Suggestion[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeCharSequence(mTitle);
        dest.writeCharSequence(mSummary);
        dest.writeParcelable(mIcon, flags);
        dest.writeInt(mFlags);
        dest.writeParcelable(mPendingIntent, flags);
    }

    /**
     * Builder class for {@link Suggestion}.
     */
    public static class Builder {
        private final String mId;
        private CharSequence mTitle;
        private CharSequence mSummary;
        private Icon mIcon;
        @Flags
        private int mFlags;
        private PendingIntent mPendingIntent;

        public Builder(String id) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("Suggestion id cannot be empty");
            }
            mId = id;
        }

        /**
         * Sets suggestion title
         */
        public Builder setTitle(CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets suggestion summary
         */
        public Builder setSummary(CharSequence summary) {
            mSummary = summary;
            return this;
        }

        /**
         * Sets icon for the suggestion.
         */
        public Builder setIcon(Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets a UI type for this suggestion. This will influence UI when rendering suggestion in
         * different style.
         */
        public Builder setFlags(@Flags int flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Sets suggestion intent
         */
        public Builder setPendingIntent(PendingIntent pendingIntent) {
            mPendingIntent = pendingIntent;
            return this;
        }

        /**
         * Builds an immutable {@link Suggestion} object.
         */
        public Suggestion build() {
            return new Suggestion(this /* builder */);
        }
    }
}
