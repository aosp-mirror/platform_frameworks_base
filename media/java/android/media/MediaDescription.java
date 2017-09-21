package android.media;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

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
    /**
     * A Uri to identify this content.
     */
    private final Uri mMediaUri;

    /**
     * Used as a long extra field to indicate the bluetooth folder type of the media item as
     * specified in the section 6.10.2.2 of the Bluetooth AVRCP 1.5. This is valid only for
     * {@link MediaBrowser.MediaItem} with {@link MediaBrowser.MediaItem#FLAG_BROWSABLE}. The value
     * should be one of the following:
     * <ul>
     * <li>{@link #BT_FOLDER_TYPE_MIXED}</li>
     * <li>{@link #BT_FOLDER_TYPE_TITLES}</li>
     * <li>{@link #BT_FOLDER_TYPE_ALBUMS}</li>
     * <li>{@link #BT_FOLDER_TYPE_ARTISTS}</li>
     * <li>{@link #BT_FOLDER_TYPE_GENRES}</li>
     * <li>{@link #BT_FOLDER_TYPE_PLAYLISTS}</li>
     * <li>{@link #BT_FOLDER_TYPE_YEARS}</li>
     * </ul>
     *
     * @see #getExtras()
     */
    public static final String EXTRA_BT_FOLDER_TYPE = "android.media.extra.BT_FOLDER_TYPE";

    /**
     * The type of folder that is unknown or contains media elements of mixed types as specified in
     * the section 6.10.2.2 of the Bluetooth AVRCP 1.5.
     */
    public static final long BT_FOLDER_TYPE_MIXED = 0;

    /**
     * The type of folder that contains media elements only as specified in the section 6.10.2.2 of
     * the Bluetooth AVRCP 1.5.
     */
    public static final long BT_FOLDER_TYPE_TITLES = 1;

    /**
     * The type of folder that contains folders categorized by album as specified in the section
     * 6.10.2.2 of the Bluetooth AVRCP 1.5.
     */
    public static final long BT_FOLDER_TYPE_ALBUMS = 2;

    /**
     * The type of folder that contains folders categorized by artist as specified in the section
     * 6.10.2.2 of the Bluetooth AVRCP 1.5.
     */
    public static final long BT_FOLDER_TYPE_ARTISTS = 3;

    /**
     * The type of folder that contains folders categorized by genre as specified in the section
     * 6.10.2.2 of the Bluetooth AVRCP 1.5.
     */
    public static final long BT_FOLDER_TYPE_GENRES = 4;

    /**
     * The type of folder that contains folders categorized by playlist as specified in the section
     * 6.10.2.2 of the Bluetooth AVRCP 1.5.
     */
    public static final long BT_FOLDER_TYPE_PLAYLISTS = 5;

    /**
     * The type of folder that contains folders categorized by year as specified in the section
     * 6.10.2.2 of the Bluetooth AVRCP 1.5.
     */
    public static final long BT_FOLDER_TYPE_YEARS = 6;

    private MediaDescription(String mediaId, CharSequence title, CharSequence subtitle,
            CharSequence description, Bitmap icon, Uri iconUri, Bundle extras, Uri mediaUri) {
        mMediaId = mediaId;
        mTitle = title;
        mSubtitle = subtitle;
        mDescription = description;
        mIcon = icon;
        mIconUri = iconUri;
        mExtras = extras;
        mMediaUri = mediaUri;
    }

    private MediaDescription(Parcel in) {
        mMediaId = in.readString();
        mTitle = in.readCharSequence();
        mSubtitle = in.readCharSequence();
        mDescription = in.readCharSequence();
        mIcon = in.readParcelable(null);
        mIconUri = in.readParcelable(null);
        mExtras = in.readBundle();
        mMediaUri = in.readParcelable(null);
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

    /**
     * Returns a Uri representing this content or null.
     *
     * @return A media Uri or null.
     */
    public @Nullable Uri getMediaUri() {
        return mMediaUri;
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
        dest.writeParcelable(mMediaUri, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }

        if (!(o instanceof MediaDescription)){
            return false;
        }

        final MediaDescription d = (MediaDescription) o;

        if (!String.valueOf(mTitle).equals(String.valueOf(d.mTitle))) {
            return false;
        }

        if (!String.valueOf(mSubtitle).equals(String.valueOf(d.mSubtitle))) {
            return false;
        }

        if (!String.valueOf(mDescription).equals(String.valueOf(d.mDescription))) {
            return false;
        }

        return true;
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
        private Uri mMediaUri;

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

        /**
         * Sets the media uri.
         *
         * @param mediaUri The content's {@link Uri} for the item or null.
         * @return this
         */
        public Builder setMediaUri(@Nullable Uri mediaUri) {
            mMediaUri = mediaUri;
            return this;
        }

        public MediaDescription build() {
            return new MediaDescription(mMediaId, mTitle, mSubtitle, mDescription, mIcon, mIconUri,
                    mExtras, mMediaUri);
        }
    }
}
