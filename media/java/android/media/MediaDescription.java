package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Size;

/**
 * A simple set of metadata for a media item suitable for display. This can be
 * created using the Builder or retrieved from existing metadata using
 * {@link MediaMetadata#getDescription()}.
 */
public class MediaDescription implements Parcelable {
    /**
     * A unique persistent id for the content or null.
     */
    private final String mMediaId;
    /**
     * A primary title suitable for display or null.
     */
    private final CharSequence mTitle;
    /**
     * A subtitle suitable for display or null.
     */
    private final CharSequence mSubtitle;
    /**
     * A description suitable for display or null.
     */
    private final CharSequence mDescription;
    /**
     * A bitmap icon suitable for display or null.
     */
    private final Bitmap mIcon;
    /**
     * A Uri for an icon suitable for display or null.
     */
    private final Uri mIconUri;
    /**
     * Extras for opaque use by apps/system.
     */
    private final Bundle mExtras;

    private MediaDescription(String mediaId, CharSequence title, CharSequence subtitle,
            CharSequence description, Bitmap icon, Uri iconUri, Bundle extras) {
        mMediaId = mediaId;
        mTitle = title;
        mSubtitle = subtitle;
        mDescription = description;
        mIcon = icon;
        mIconUri = iconUri;
        mExtras = extras;
    }

    private MediaDescription(Parcel in) {
        mMediaId = in.readString();
        mTitle = in.readCharSequence();
        mSubtitle = in.readCharSequence();
        mDescription = in.readCharSequence();
        mIcon = in.readParcelable(null);
        mIconUri = in.readParcelable(null);
        mExtras = in.readBundle();
    }

    /**
     * Returns the media id or null. See
     * {@link MediaMetadata#METADATA_KEY_MEDIA_ID}.
     */
    public @Nullable String getMediaId() {
        return mMediaId;
    }

    /**
     * Returns a title suitable for display or null.
     *
     * @return A title or null.
     */
    public @Nullable CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Returns a subtitle suitable for display or null.
     *
     * @return A subtitle or null.
     */
    public @Nullable CharSequence getSubtitle() {
        return mSubtitle;
    }

    /**
     * Returns a description suitable for display or null.
     *
     * @return A description or null.
     */
    public @Nullable CharSequence getDescription() {
        return mDescription;
    }

    /**
     * Returns a bitmap icon suitable for display or null.
     *
     * @return An icon or null.
     */
    public @Nullable Bitmap getIconBitmap() {
        return mIcon;
    }

    /**
     * Returns a Uri for an icon suitable for display or null.
     *
     * @return An icon uri or null.
     */
    public @Nullable Uri getIconUri() {
        return mIconUri;
    }

    /**
     * Returns any extras that were added to the description.
     *
     * @return A bundle of extras or null.
     */
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mMediaId);
        dest.writeCharSequence(mTitle);
        dest.writeCharSequence(mSubtitle);
        dest.writeCharSequence(mDescription);
        dest.writeParcelable(mIcon, flags);
        dest.writeParcelable(mIconUri, flags);
        dest.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        return mTitle + ", " + mSubtitle + ", " + mDescription;
    }

    public static final Parcelable.Creator<MediaDescription> CREATOR =
            new Parcelable.Creator<MediaDescription>() {
                @Override
                public MediaDescription createFromParcel(Parcel in) {
                    return new MediaDescription(in);
                }

                @Override
                public MediaDescription[] newArray(int size) {
                    return new MediaDescription[size];
                }
            };

    /**
     * Builder for {@link MediaDescription} objects.
     */
    public static class Builder {
        private String mMediaId;
        private CharSequence mTitle;
        private CharSequence mSubtitle;
        private CharSequence mDescription;
        private Bitmap mIcon;
        private Uri mIconUri;
        private Bundle mExtras;

        /**
         * Creates an initially empty builder.
         */
        public Builder() {
        }

        /**
         * Sets the media id.
         *
         * @param mediaId The unique id for the item or null.
         * @return this
         */
        public Builder setMediaId(@Nullable String mediaId) {
            mMediaId = mediaId;
            return this;
        }

        /**
         * Sets the title.
         *
         * @param title A title suitable for display to the user or null.
         * @return this
         */
        public Builder setTitle(@Nullable CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets the subtitle.
         *
         * @param subtitle A subtitle suitable for display to the user or null.
         * @return this
         */
        public Builder setSubtitle(@Nullable CharSequence subtitle) {
            mSubtitle = subtitle;
            return this;
        }

        /**
         * Sets the description.
         *
         * @param description A description suitable for display to the user or
         *            null.
         * @return this
         */
        public Builder setDescription(@Nullable CharSequence description) {
            mDescription = description;
            return this;
        }

        /**
         * Sets the icon.
         *
         * @param icon A {@link Bitmap} icon suitable for display to the user or
         *            null.
         * @return this
         */
        public Builder setIconBitmap(@Nullable Bitmap icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the icon uri.
         *
         * @param iconUri A {@link Uri} for an icon suitable for display to the
         *            user or null.
         * @return this
         */
        public Builder setIconUri(@Nullable Uri iconUri) {
            mIconUri = iconUri;
            return this;
        }

        /**
         * Sets a bundle of extras.
         *
         * @param extras The extras to include with this description or null.
         * @return this
         */
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        public MediaDescription build() {
            return new MediaDescription(mMediaId, mTitle, mSubtitle, mDescription, mIcon, mIconUri,
                    mExtras);
        }
    }
}
