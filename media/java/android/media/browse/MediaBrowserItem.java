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

package android.media.browse;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.net.Uri;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes a media item in the list of items offered by a {@link MediaBrowserService}.
 */
public final class MediaBrowserItem implements Parcelable {
    private final Uri mUri;
    private final int mFlags;
    private final CharSequence mTitle;
    private final CharSequence mSummary;
    private final Bundle mExtras;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag=true, value = { FLAG_BROWSABLE, FLAG_PLAYABLE })
    public @interface Flags { }

    /**
     * Flag: Indicates that the item has children of its own.
     */
    public static final int FLAG_BROWSABLE = 1 << 0;

    /**
     * Flag: Indicates that the item is playable.
     * <p>
     * The Uri of this item may be passed to link android.media.session.MediaController#play(Uri)
     * to start playing it.
     * </p>
     */
    public static final int FLAG_PLAYABLE = 1 << 1;

    /**
     * Initialize a MediaBrowserItem object.
     */
    private MediaBrowserItem(@NonNull Uri uri, int flags, @NonNull CharSequence title,
            CharSequence summary, Bundle extras) {
        if (uri == null) {
            throw new IllegalArgumentException("uri can not be null");
        }
        if (title == null) {
            throw new IllegalArgumentException("title can not be null");
        }
        mUri = uri;
        mFlags = flags;
        mTitle = title;
        mSummary = summary;
        mExtras = extras;
    }

    /**
     * Private constructor.
     */
    private MediaBrowserItem(Parcel in) {
        mUri = Uri.CREATOR.createFromParcel(in);
        mFlags = in.readInt();
        mTitle = in.readCharSequence();
        if (in.readInt() != 0) {
            mSummary = in.readCharSequence();
        } else {
            mSummary = null;
        }
        if (in.readInt() != 0) {
            mExtras = Bundle.CREATOR.createFromParcel(in);
        } else {
            mExtras = null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mUri.writeToParcel(out, flags);
        out.writeInt(mFlags);
        out.writeCharSequence(mTitle);
        if (mSummary != null) {
            out.writeInt(1);
            out.writeCharSequence(mSummary);
        } else {
            out.writeInt(0);
        }
        if (mExtras != null) {
            out.writeInt(1);
            mExtras.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
    }

    public static final Parcelable.Creator<MediaBrowserItem> CREATOR =
            new Parcelable.Creator<MediaBrowserItem>() {
        @Override
        public MediaBrowserItem createFromParcel(Parcel in) {
            return new MediaBrowserItem(in);
        }

        @Override
        public MediaBrowserItem[] newArray(int size) {
            return new MediaBrowserItem[size];
        }
    };

    /**
     * Gets the Uri of the item.
     */
    public @NonNull Uri getUri() {
        return mUri;
    }

    /**
     * Gets the flags of the item.
     */
    public @Flags int getFlags() {
        return mFlags;
    }

    /**
     * Returns whether this item is browsable.
     * @see #FLAG_BROWSABLE
     */
    public boolean isBrowsable() {
        return (mFlags & FLAG_BROWSABLE) != 0;
    }

    /**
     * Returns whether this item is playable.
     * @see #FLAG_PLAYABLE
     */
    public boolean isPlayable() {
        return (mFlags & FLAG_PLAYABLE) != 0;
    }

    /**
     * Gets the title of the item.
     * @more
     * The title will be shown as the first line of text when
     * describing each item to the user.
     */
    public @NonNull CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Gets summary of the item, or null if none.
     * @more
     * The summary will be shown as the second line of text when
     * describing each item to the user.
     */
    public @Nullable CharSequence getSummary() {
        return mSummary;
    }

    /**
     * Gets additional service-specified extras about the
     * item or its content, or null if none.
     */
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    /**
     * Builder for {@link MediaBrowserItem} objects.
     */
    public static final class Builder {
        private final Uri mUri;
        private final int mFlags;
        private final CharSequence mTitle;
        private CharSequence mSummary;
        private Bundle mExtras;

        /**
         * Creates an item builder.
         */
        public Builder(@NonNull Uri uri, @Flags int flags, @NonNull CharSequence title) {
            if (uri == null) {
                throw new IllegalArgumentException("uri can not be null");
            }
            if (title == null) {
                throw new IllegalArgumentException("title can not be null");
            }
            mUri = uri;
            mFlags = flags;
            mTitle = title;
        }

        /**
         * Sets summary of the item, or null if none.
         */
        public @NonNull Builder setSummary(@Nullable CharSequence summary) {
            mSummary = summary;
            return this;
        }

        /**
        * Sets additional service-specified extras about the
        * item or its content, or null if none.
        */
        public @NonNull Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
        * Builds the item.
        */
        public @NonNull MediaBrowserItem build() {
            return new MediaBrowserItem(mUri, mFlags, mTitle, mSummary, mExtras);
        }
    }
}

