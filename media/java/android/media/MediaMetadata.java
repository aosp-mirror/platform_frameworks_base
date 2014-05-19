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
package android.media;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

/**
 * Contains metadata about an item, such as the title, artist, etc.
 */
public final class MediaMetadata implements Parcelable {
    private static final String TAG = "MediaMetadata";

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
     * The date the media was created or published as TODO determine format.
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
     */
    public static final String METADATA_KEY_ART = "android.media.metadata.ART";

    /**
     * The artwork for the media as a Uri style String.
     */
    public static final String METADATA_KEY_ART_URI = "android.media.metadata.ART_URI";

    /**
     * The artwork for the album of the media's original source as a
     * {@link Bitmap}.
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

    private static final int METADATA_TYPE_INVALID = -1;
    private static final int METADATA_TYPE_LONG = 0;
    private static final int METADATA_TYPE_STRING = 1;
    private static final int METADATA_TYPE_BITMAP = 2;
    private static final int METADATA_TYPE_RATING = 3;
    private static final ArrayMap<String, Integer> METADATA_KEYS_TYPE;

    static {
        METADATA_KEYS_TYPE = new ArrayMap<String, Integer>();
        METADATA_KEYS_TYPE.put(METADATA_KEY_TITLE, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ARTIST, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DURATION, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_AUTHOR, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_WRITER, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_COMPOSER, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_COMPILATION, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DATE, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_YEAR, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_GENRE, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_TRACK_NUMBER, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_NUM_TRACKS, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DISC_NUMBER, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ARTIST, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ART, METADATA_TYPE_BITMAP);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ART_URI, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ART, METADATA_TYPE_BITMAP);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ART_URI, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_USER_RATING, METADATA_TYPE_RATING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_RATING, METADATA_TYPE_RATING);
    }

    private static final SparseArray<String> EDITOR_KEY_MAPPING;

    static {
        EDITOR_KEY_MAPPING = new SparseArray<String>();
        EDITOR_KEY_MAPPING.put(MediaMetadataEditor.BITMAP_KEY_ARTWORK, METADATA_KEY_ART);
        EDITOR_KEY_MAPPING.put(MediaMetadataEditor.RATING_KEY_BY_OTHERS, METADATA_KEY_RATING);
        EDITOR_KEY_MAPPING.put(MediaMetadataEditor.RATING_KEY_BY_USER, METADATA_KEY_USER_RATING);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_ALBUM, METADATA_KEY_ALBUM);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                METADATA_KEY_ALBUM_ARTIST);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_ARTIST, METADATA_KEY_ARTIST);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_AUTHOR, METADATA_KEY_AUTHOR);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER,
                METADATA_KEY_TRACK_NUMBER);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_COMPOSER, METADATA_KEY_COMPOSER);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_COMPILATION,
                METADATA_KEY_COMPILATION);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DATE, METADATA_KEY_DATE);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER,
                METADATA_KEY_DISC_NUMBER);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DURATION, METADATA_KEY_DURATION);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_GENRE, METADATA_KEY_GENRE);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS,
                METADATA_KEY_NUM_TRACKS);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_TITLE, METADATA_KEY_TITLE);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_WRITER, METADATA_KEY_WRITER);
        EDITOR_KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_YEAR, METADATA_KEY_YEAR);
    }

    private final Bundle mBundle;

    private MediaMetadata(Bundle bundle) {
        mBundle = new Bundle(bundle);
    }

    private MediaMetadata(Parcel in) {
        mBundle = in.readBundle();
    }

    /**
     * Returns true if the given key is contained in the metadata
     *
     * @param key a String key
     * @return true if the key exists in this metadata, false otherwise
     */
    public boolean containsKey(String key) {
        return mBundle.containsKey(key);
    }

    /**
     * Returns the value associated with the given key, or null if no mapping of
     * the desired type exists for the given key or a null value is explicitly
     * associated with the key.
     *
     * @param key The key the value is stored under
     * @return a String value, or null
     */
    public String getString(String key) {
        return mBundle.getString(key);
    }

    /**
     * Returns the value associated with the given key, or 0L if no long exists
     * for the given key.
     *
     * @param key The key the value is stored under
     * @return a long value
     */
    public long getLong(String key) {
        return mBundle.getLong(key, 0);
    }

    /**
     * Return a {@link Rating} for the given key or null if no rating exists for
     * the given key.
     *
     * @param key The key the value is stored under
     * @return A {@link Rating} or null
     */
    public Rating getRating(String key) {
        Rating rating = null;
        try {
            rating = mBundle.getParcelable(key);
        } catch (Exception e) {
            // ignore, value was not a bitmap
            Log.d(TAG, "Failed to retrieve a key as Rating.", e);
        }
        return rating;
    }

    /**
     * Return a {@link Bitmap} for the given key or null if no bitmap exists for
     * the given key.
     *
     * @param key The key the value is stored under
     * @return A {@link Bitmap} or null
     */
    public Bitmap getBitmap(String key) {
        Bitmap bmp = null;
        try {
            bmp = mBundle.getParcelable(key);
        } catch (Exception e) {
            // ignore, value was not a bitmap
            Log.d(TAG, "Failed to retrieve a key as Bitmap.", e);
        }
        return bmp;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(mBundle);
    }

    /**
     * Get the number of fields in this metadata.
     *
     * @return The number of fields in the metadata.
     */
    public int size() {
        return mBundle.size();
    }

    /**
     * Helper for getting the String key used by {@link MediaMetadata} from the
     * integer key that {@link MediaMetadataEditor} uses.
     *
     * @param editorKey The key used by the editor
     * @return The key used by this class or null if no mapping exists
     * @hide
     */
    public static String getKeyFromMetadataEditorKey(int editorKey) {
        return EDITOR_KEY_MAPPING.get(editorKey, null);
    }

    public static final Parcelable.Creator<MediaMetadata> CREATOR
            = new Parcelable.Creator<MediaMetadata>() {
                @Override
                public MediaMetadata createFromParcel(Parcel in) {
                    return new MediaMetadata(in);
                }

                @Override
                public MediaMetadata[] newArray(int size) {
                    return new MediaMetadata[size];
                }
            };

    /**
     * Use to build MediaMetadata objects. The system defined metadata keys must
     * use the appropriate data type.
     */
    public static final class Builder {
        private final Bundle mBundle;

        /**
         * Create an empty Builder. Any field that should be included in the
         * {@link MediaMetadata} must be added.
         */
        public Builder() {
            mBundle = new Bundle();
        }

        /**
         * Create a Builder using a {@link MediaMetadata} instance to set the
         * initial values. All fields in the source metadata will be included in
         * the new metadata. Fields can be overwritten by adding the same key.
         *
         * @param source
         */
        public Builder(MediaMetadata source) {
            mBundle = new Bundle(source.mBundle);
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
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return The Builder to allow chaining
         */
        public Builder putString(String key, String value) {
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_STRING) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a String");
                }
            }
            mBundle.putString(key, value);
            return this;
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
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return The Builder to allow chaining
         */
        public Builder putLong(String key, long value) {
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_LONG) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a long");
                }
            }
            mBundle.putLong(key, value);
            return this;
        }

        /**
         * Put a {@link Rating} into the metadata. Custom keys may be used, but
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
        public Builder putRating(String key, Rating value) {
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_RATING) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a Rating");
                }
            }
            mBundle.putParcelable(key, value);
            return this;
        }

        /**
         * Put a {@link Bitmap} into the metadata. Custom keys may be used, but
         * if the METADATA_KEYs defined in this class are used they may only be
         * one of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_ART}</li>
         * <li>{@link #METADATA_KEY_ALBUM_ART}</li>
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The Bitmap to store
         * @return The Builder to allow chaining
         */
        public Builder putBitmap(String key, Bitmap value) {
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_BITMAP) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a Bitmap");
                }
            }
            mBundle.putParcelable(key, value);
            return this;
        }

        /**
         * Creates a {@link MediaMetadata} instance with the specified fields.
         *
         * @return The new MediaMetadata instance
         */
        public MediaMetadata build() {
            return new MediaMetadata(mBundle);
        }
    }

}
