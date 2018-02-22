/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.update.ApiLoader;
import android.media.update.MediaMetadata2Provider;
import android.net.Uri;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * Contains metadata about an item, such as the title, artist, etc.
 *
 * @hide
 */
public final class MediaMetadata2 {
    // New version of MediaMetadata that no longer implements Parcelable but added from/toBundle()
    // for updatable.
    // MediaDescription is deprecated because it was insufficient for controller to display media
    // contents. Added getExtra() here to support all the features from the MediaDescription.

    /**
     * The title of the media.
     */
    public static final String METADATA_KEY_TITLE = "android.media.metadata.TITLE";

    /**
     * The artist of the media.
     */
    public static final String METADATA_KEY_ARTIST = "android.media.metadata.ARTIST";

    /**
     * The duration of the media in ms. A negative duration indicates that the
     * duration is unknown (or infinite).
     */
    public static final String METADATA_KEY_DURATION = "android.media.metadata.DURATION";

    /**
     * The album title for the media.
     */
    public static final String METADATA_KEY_ALBUM = "android.media.metadata.ALBUM";

    /**
     * The author of the media.
     */
    public static final String METADATA_KEY_AUTHOR = "android.media.metadata.AUTHOR";

    /**
     * The writer of the media.
     */
    public static final String METADATA_KEY_WRITER = "android.media.metadata.WRITER";

    /**
     * The composer of the media.
     */
    public static final String METADATA_KEY_COMPOSER = "android.media.metadata.COMPOSER";

    /**
     * The compilation status of the media.
     */
    public static final String METADATA_KEY_COMPILATION = "android.media.metadata.COMPILATION";

    /**
     * The date the media was created or published. The format is unspecified
     * but RFC 3339 is recommended.
     */
    public static final String METADATA_KEY_DATE = "android.media.metadata.DATE";

    /**
     * The year the media was created or published as a long.
     */
    public static final String METADATA_KEY_YEAR = "android.media.metadata.YEAR";

    /**
     * The genre of the media.
     */
    public static final String METADATA_KEY_GENRE = "android.media.metadata.GENRE";

    /**
     * The track number for the media.
     */
    public static final String METADATA_KEY_TRACK_NUMBER = "android.media.metadata.TRACK_NUMBER";

    /**
     * The number of tracks in the media's original source.
     */
    public static final String METADATA_KEY_NUM_TRACKS = "android.media.metadata.NUM_TRACKS";

    /**
     * The disc number for the media's original source.
     */
    public static final String METADATA_KEY_DISC_NUMBER = "android.media.metadata.DISC_NUMBER";

    /**
     * The artist for the album of the media's original source.
     */
    public static final String METADATA_KEY_ALBUM_ARTIST = "android.media.metadata.ALBUM_ARTIST";

    /**
     * The artwork for the media as a {@link Bitmap}.
     *
     * The artwork should be relatively small and may be scaled down
     * if it is too large. For higher resolution artwork
     * {@link #METADATA_KEY_ART_URI} should be used instead.
     */
    public static final String METADATA_KEY_ART = "android.media.metadata.ART";

    /**
     * The artwork for the media as a Uri style String.
     */
    public static final String METADATA_KEY_ART_URI = "android.media.metadata.ART_URI";

    /**
     * The artwork for the album of the media's original source as a
     * {@link Bitmap}.
     * The artwork should be relatively small and may be scaled down
     * if it is too large. For higher resolution artwork
     * {@link #METADATA_KEY_ALBUM_ART_URI} should be used instead.
     */
    public static final String METADATA_KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART";

    /**
     * The artwork for the album of the media's original source as a Uri style
     * String.
     */
    public static final String METADATA_KEY_ALBUM_ART_URI = "android.media.metadata.ALBUM_ART_URI";

    /**
     * The user's rating for the media.
     *
     * @see Rating
     */
    public static final String METADATA_KEY_USER_RATING = "android.media.metadata.USER_RATING";

    /**
     * The overall rating for the media.
     *
     * @see Rating
     */
    public static final String METADATA_KEY_RATING = "android.media.metadata.RATING";

    /**
     * A title that is suitable for display to the user. This will generally be
     * the same as {@link #METADATA_KEY_TITLE} but may differ for some formats.
     * When displaying media described by this metadata this should be preferred
     * if present.
     */
    public static final String METADATA_KEY_DISPLAY_TITLE = "android.media.metadata.DISPLAY_TITLE";

    /**
     * A subtitle that is suitable for display to the user. When displaying a
     * second line for media described by this metadata this should be preferred
     * to other fields if present.
     */
    public static final String METADATA_KEY_DISPLAY_SUBTITLE
            = "android.media.metadata.DISPLAY_SUBTITLE";

    /**
     * A description that is suitable for display to the user. When displaying
     * more information for media described by this metadata this should be
     * preferred to other fields if present.
     */
    public static final String METADATA_KEY_DISPLAY_DESCRIPTION
            = "android.media.metadata.DISPLAY_DESCRIPTION";

    /**
     * An icon or thumbnail that is suitable for display to the user. When
     * displaying an icon for media described by this metadata this should be
     * preferred to other fields if present. This must be a {@link Bitmap}.
     *
     * The icon should be relatively small and may be scaled down
     * if it is too large. For higher resolution artwork
     * {@link #METADATA_KEY_DISPLAY_ICON_URI} should be used instead.
     */
    public static final String METADATA_KEY_DISPLAY_ICON
            = "android.media.metadata.DISPLAY_ICON";

    /**
     * An icon or thumbnail that is suitable for display to the user. When
     * displaying more information for media described by this metadata the
     * display description should be preferred to other fields when present.
     * This must be a Uri style String.
     */
    public static final String METADATA_KEY_DISPLAY_ICON_URI
            = "android.media.metadata.DISPLAY_ICON_URI";

    /**
     * A String key for identifying the content. This value is specific to the
     * service providing the content. If used, this should be a persistent
     * unique key for the underlying content.  It may be used with
     * {@link MediaController2#playFromMediaId(String, Bundle)}
     * to initiate playback when provided by a {@link MediaBrowser2} connected to
     * the same app.
     */
    public static final String METADATA_KEY_MEDIA_ID = "android.media.metadata.MEDIA_ID";

    /**
     * A Uri formatted String representing the content. This value is specific to the
     * service providing the content. It may be used with
     * {@link MediaController2#playFromUri(String, Bundle)}
     * to initiate playback when provided by a {@link MediaBrowser2} connected to
     * the same app.
     */
    public static final String METADATA_KEY_MEDIA_URI = "android.media.metadata.MEDIA_URI";

    /**
     * The bluetooth folder type of the media specified in the section 6.10.2.2 of the Bluetooth
     * AVRCP 1.5. It should be one of the following:
     * <ul>
     * <li>{@link #BT_FOLDER_TYPE_MIXED}</li>
     * <li>{@link #BT_FOLDER_TYPE_TITLES}</li>
     * <li>{@link #BT_FOLDER_TYPE_ALBUMS}</li>
     * <li>{@link #BT_FOLDER_TYPE_ARTISTS}</li>
     * <li>{@link #BT_FOLDER_TYPE_GENRES}</li>
     * <li>{@link #BT_FOLDER_TYPE_PLAYLISTS}</li>
     * <li>{@link #BT_FOLDER_TYPE_YEARS}</li>
     * </ul>
     */
    public static final String METADATA_KEY_BT_FOLDER_TYPE
            = "android.media.metadata.BT_FOLDER_TYPE";

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

    /**
     * Whether the media is an advertisement. A value of 0 indicates it is not an advertisement. A
     * value of 1 or non-zero indicates it is an advertisement. If not specified, this value is set
     * to 0 by default.
     */
    public static final String METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT";

    /**
     * The download status of the media which will be used for later offline playback. It should be
     * one of the following:
     *
     * <ul>
     * <li>{@link #STATUS_NOT_DOWNLOADED}</li>
     * <li>{@link #STATUS_DOWNLOADING}</li>
     * <li>{@link #STATUS_DOWNLOADED}</li>
     * </ul>
     */
    public static final String METADATA_KEY_DOWNLOAD_STATUS =
            "android.media.metadata.DOWNLOAD_STATUS";

    /**
     * The status value to indicate the media item is not downloaded.
     *
     * @see #METADATA_KEY_DOWNLOAD_STATUS
     */
    public static final long STATUS_NOT_DOWNLOADED = 0;

    /**
     * The status value to indicate the media item is being downloaded.
     *
     * @see #METADATA_KEY_DOWNLOAD_STATUS
     */
    public static final long STATUS_DOWNLOADING = 1;

    /**
     * The status value to indicate the media item is downloaded for later offline playback.
     *
     * @see #METADATA_KEY_DOWNLOAD_STATUS
     */
    public static final long STATUS_DOWNLOADED = 2;

    /**
     * A {@link Bundle} extra.
     * @hide
     */
    public static final String METADATA_KEY_EXTRA = "android.media.metadata.EXTRA";

    /**
     * @hide
     */
    @StringDef({METADATA_KEY_TITLE, METADATA_KEY_ARTIST, METADATA_KEY_ALBUM, METADATA_KEY_AUTHOR,
            METADATA_KEY_WRITER, METADATA_KEY_COMPOSER, METADATA_KEY_COMPILATION,
            METADATA_KEY_DATE, METADATA_KEY_GENRE, METADATA_KEY_ALBUM_ARTIST, METADATA_KEY_ART_URI,
            METADATA_KEY_ALBUM_ART_URI, METADATA_KEY_DISPLAY_TITLE, METADATA_KEY_DISPLAY_SUBTITLE,
            METADATA_KEY_DISPLAY_DESCRIPTION, METADATA_KEY_DISPLAY_ICON_URI,
            METADATA_KEY_MEDIA_ID, METADATA_KEY_MEDIA_URI})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TextKey {}

    /**
     * @hide
     */
    @StringDef({METADATA_KEY_DURATION, METADATA_KEY_YEAR, METADATA_KEY_TRACK_NUMBER,
            METADATA_KEY_NUM_TRACKS, METADATA_KEY_DISC_NUMBER, METADATA_KEY_BT_FOLDER_TYPE,
            METADATA_KEY_ADVERTISEMENT, METADATA_KEY_DOWNLOAD_STATUS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface LongKey {}

    /**
     * @hide
     */
    @StringDef({METADATA_KEY_ART, METADATA_KEY_ALBUM_ART, METADATA_KEY_DISPLAY_ICON})
    @Retention(RetentionPolicy.SOURCE)
    public @interface BitmapKey {}

    /**
     * @hide
     */
    @StringDef({METADATA_KEY_USER_RATING, METADATA_KEY_RATING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RatingKey {}

    private final MediaMetadata2Provider mProvider;

    /**
     * @hide
     */
    @SystemApi
    public MediaMetadata2(MediaMetadata2Provider provider) {
        mProvider = provider;
    }

    /**
     * Returns true if the given key is contained in the metadata
     *
     * @param key a String key
     * @return true if the key exists in this metadata, false otherwise
     */
    public boolean containsKey(@NonNull String key) {
        return mProvider.containsKey_impl(key);
    }

    /**
     * Returns the value associated with the given key, or null if no mapping of
     * the desired type exists for the given key or a null value is explicitly
     * associated with the key.
     *
     * @param key The key the value is stored under
     * @return a CharSequence value, or null
     */
    public @Nullable CharSequence getText(@TextKey String key) {
        return mProvider.getText_impl(key);
    }

    /**
     * Returns the value associated with the given key, or null if no mapping of
     * the desired type exists for the given key or a null value is explicitly
     * associated with the key.
     *
     * @return media id. Can be {@code null}
     * @see #METADATA_KEY_MEDIA_ID
     */
    public @Nullable String getMediaId() {
        return mProvider.getMediaId_impl();
    }

    /**
     * Returns the value associated with the given key, or null if no mapping of
     * the desired type exists for the given key or a null value is explicitly
     * associated with the key.
     *
     * @param key The key the value is stored under
     * @return a String value, or null
     */
    public @Nullable String getString(@NonNull @TextKey String key) {
        return mProvider.getString_impl(key);
    }

    /**
     * Returns the value associated with the given key, or 0L if no long exists
     * for the given key.
     *
     * @param key The key the value is stored under
     * @return a long value
     */
    public long getLong(@NonNull @LongKey String key) {
        return mProvider.getLong_impl(key);
    }

    /**
     * Return a {@link Rating2} for the given key or null if no rating exists for
     * the given key.
     * <p>
     * For the {@link #METADATA_KEY_USER_RATING}, A {@code null} return value means that user rating
     * cannot be set by {@link MediaController2}.
     *
     * @param key The key the value is stored under
     * @return A {@link Rating2} or {@code null}
     */
    public @Nullable Rating2 getRating(@RatingKey String key) {
        return mProvider.getRating_impl(key);
    }

    /**
     * Return a {@link Bitmap} for the given key or null if no bitmap exists for
     * the given key.
     *
     * @param key The key the value is stored under
     * @return A {@link Bitmap} or null
     */
    public Bitmap getBitmap(@BitmapKey String key) {
        return mProvider.getBitmap_impl(key);
    }

    /**
     * Get the extra {@link Bundle} from the metadata object.
     *
     * @return A {@link Bundle} or {@code null}
     */
    public @Nullable Bundle getExtra() {
        return mProvider.getExtra_impl();
    }

    /**
     * Get the number of fields in this metadata.
     *
     * @return The number of fields in the metadata.
     */
    public int size() {
        return mProvider.size_impl();
    }

    /**
     * Returns a Set containing the Strings used as keys in this metadata.
     *
     * @return a Set of String keys
     */
    public @NonNull Set<String> keySet() {
        return mProvider.keySet_impl();
    }

    /**
     * Gets the bundle backing the metadata object. This is available to support
     * backwards compatibility. Apps should not modify the bundle directly.
     *
     * @return The Bundle backing this metadata.
     */
    public @NonNull Bundle toBundle() {
        return mProvider.toBundle_impl();
    }

    /**
     * Creates the {@link MediaMetadata2} from the bundle that previously returned by
     * {@link #toBundle()}.
     *
     * @param context context
     * @param bundle bundle for the metadata
     * @return a new MediaMetadata2
     */
    public static @NonNull MediaMetadata2 fromBundle(@NonNull Context context,
            @Nullable Bundle bundle) {
        return ApiLoader.getProvider(context).fromBundle_MediaMetadata2(context, bundle);
    }

    /**
     * Use to build MediaMetadata2 objects. The system defined metadata keys must
     * use the appropriate data type.
     */
    public static final class Builder {
        private final MediaMetadata2Provider.BuilderProvider mProvider;

        /**
         * Create an empty Builder. Any field that should be included in the
         * {@link MediaMetadata2} must be added.
         */
        public Builder(@NonNull Context context) {
            mProvider = ApiLoader.getProvider(context).createMediaMetadata2Builder(
                    context, this);
        }

        /**
         * Create a Builder using a {@link MediaMetadata2} instance to set the
         * initial values. All fields in the source metadata will be included in
         * the new metadata. Fields can be overwritten by adding the same key.
         *
         * @param source
         */
        public Builder(@NonNull Context context, @NonNull MediaMetadata2 source) {
            mProvider = ApiLoader.getProvider(context).createMediaMetadata2Builder(
                    context, this, source);
        }

        /**
         * @hide
         */
        @SystemApi
        public Builder(@NonNull MediaMetadata2Provider.BuilderProvider provider) {
            mProvider = provider;
        }

        /**
         * Put a CharSequence value into the metadata. Custom keys may be used,
         * but if the METADATA_KEYs defined in this class are used they may only
         * be one of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_TITLE}</li>
         * <li>{@link #METADATA_KEY_ARTIST}</li>
         * <li>{@link #METADATA_KEY_ALBUM}</li>
         * <li>{@link #METADATA_KEY_AUTHOR}</li>
         * <li>{@link #METADATA_KEY_WRITER}</li>
         * <li>{@link #METADATA_KEY_COMPOSER}</li>
         * <li>{@link #METADATA_KEY_DATE}</li>
         * <li>{@link #METADATA_KEY_GENRE}</li>
         * <li>{@link #METADATA_KEY_ALBUM_ARTIST}</li>
         * <li>{@link #METADATA_KEY_ART_URI}</li>
         * <li>{@link #METADATA_KEY_ALBUM_ART_URI}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_TITLE}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_SUBTITLE}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_DESCRIPTION}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_ICON_URI}</li>
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The CharSequence value to store
         * @return The Builder to allow chaining
         */
        public @NonNull Builder putText(@TextKey String key, @Nullable CharSequence value) {
            return mProvider.putText_impl(key, value);
        }

        /**
         * Put a String value into the metadata. Custom keys may be used, but if
         * the METADATA_KEYs defined in this class are used they may only be one
         * of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_TITLE}</li>
         * <li>{@link #METADATA_KEY_ARTIST}</li>
         * <li>{@link #METADATA_KEY_ALBUM}</li>
         * <li>{@link #METADATA_KEY_AUTHOR}</li>
         * <li>{@link #METADATA_KEY_WRITER}</li>
         * <li>{@link #METADATA_KEY_COMPOSER}</li>
         * <li>{@link #METADATA_KEY_DATE}</li>
         * <li>{@link #METADATA_KEY_GENRE}</li>
         * <li>{@link #METADATA_KEY_ALBUM_ARTIST}</li>
         * <li>{@link #METADATA_KEY_ART_URI}</li>
         * <li>{@link #METADATA_KEY_ALBUM_ART_URI}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_TITLE}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_SUBTITLE}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_DESCRIPTION}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_ICON_URI}</li>
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return The Builder to allow chaining
         */
        public @NonNull Builder putString(@TextKey String key, @Nullable String value) {
            return mProvider.putString_impl(key, value);
        }

        /**
         * Put a long value into the metadata. Custom keys may be used, but if
         * the METADATA_KEYs defined in this class are used they may only be one
         * of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_DURATION}</li>
         * <li>{@link #METADATA_KEY_TRACK_NUMBER}</li>
         * <li>{@link #METADATA_KEY_NUM_TRACKS}</li>
         * <li>{@link #METADATA_KEY_DISC_NUMBER}</li>
         * <li>{@link #METADATA_KEY_YEAR}</li>
         * <li>{@link #METADATA_KEY_BT_FOLDER_TYPE}</li>
         * <li>{@link #METADATA_KEY_ADVERTISEMENT}</li>
         * <li>{@link #METADATA_KEY_DOWNLOAD_STATUS}</li>
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return The Builder to allow chaining
         */
        public @NonNull Builder putLong(@NonNull @LongKey String key, long value) {
            return mProvider.putLong_impl(key, value);
        }

        /**
         * Put a {@link Rating2} into the metadata. Custom keys may be used, but
         * if the METADATA_KEYs defined in this class are used they may only be
         * one of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_RATING}</li>
         * <li>{@link #METADATA_KEY_USER_RATING}</li>
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return The Builder to allow chaining
         */
        public @NonNull Builder putRating(@NonNull @RatingKey String key, @Nullable Rating2 value) {
            return mProvider.putRating_impl(key, value);
        }

        /**
         * Put a {@link Bitmap} into the metadata. Custom keys may be used, but
         * if the METADATA_KEYs defined in this class are used they may only be
         * one of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_ART}</li>
         * <li>{@link #METADATA_KEY_ALBUM_ART}</li>
         * <li>{@link #METADATA_KEY_DISPLAY_ICON}</li>
         * </ul>
         * Large bitmaps may be scaled down by the system when
         * {@link android.media.session.MediaSession#setMetadata} is called.
         * To pass full resolution images {@link Uri Uris} should be used with
         * {@link #putString}.
         *
         * @param key The key for referencing this value
         * @param value The Bitmap to store
         * @return The Builder to allow chaining
         */
        public @NonNull Builder putBitmap(@NonNull @BitmapKey String key, @Nullable Bitmap value) {
            return mProvider.putBitmap_impl(key, value);
        }

        /**
         * Set an extra {@link Bundle} into the metadata.
         */
        public @NonNull Builder setExtra(@Nullable Bundle bundle) {
            return mProvider.setExtra_impl(bundle);
        }

        /**
         * Creates a {@link MediaMetadata2} instance with the specified fields.
         *
         * @return The new MediaMetadata2 instance
         */
        public @NonNull MediaMetadata2 build() {
            return mProvider.build_impl();
        }
    }
}

