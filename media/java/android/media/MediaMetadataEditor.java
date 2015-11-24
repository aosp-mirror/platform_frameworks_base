/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.util.SparseIntArray;

/**
 * An abstract class for editing and storing metadata that can be published by
 * {@link RemoteControlClient}. See the {@link RemoteControlClient#editMetadata(boolean)}
 * method to instantiate a {@link RemoteControlClient.MetadataEditor} object.
 *
 * @deprecated Use {@link MediaMetadata} instead together with {@link MediaSession}.
 */
@Deprecated public abstract class MediaMetadataEditor {

    private final static String TAG = "MediaMetadataEditor";
    /**
     * @hide
     */
    protected MediaMetadataEditor() {
    }

    // Public keys for metadata used by RemoteControlClient and RemoteController.
    // Note that these keys are defined here, and not in MediaMetadataRetriever
    // because they are not supported by the MediaMetadataRetriever features.
    /**
     * The metadata key for the content artwork / album art.
     */
    public final static int BITMAP_KEY_ARTWORK =
            RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK;

    /**
     * The metadata key for the content's average rating, not the user's rating.
     * The value associated with this key is a {@link Rating} instance.
     * @see #RATING_KEY_BY_USER
     */
    public final static int RATING_KEY_BY_OTHERS = 101;

    /**
     * The metadata key for the content's user rating.
     * The value associated with this key is a {@link Rating} instance.
     * This key can be flagged as "editable" (with {@link #addEditableKey(int)}) to enable
     * receiving user rating values through the
     * {@link android.media.RemoteControlClient.OnMetadataUpdateListener} interface.
     */
    public final static int RATING_KEY_BY_USER = 0x10000001;

    /**
     * @hide
     * Editable key mask
     */
    public final static int KEY_EDITABLE_MASK = 0x1FFFFFFF;


    /**
     * Applies all of the metadata changes that have been set since the MediaMetadataEditor instance
     * was created or since {@link #clear()} was called. Subclasses should synchronize on
     * {@code this} for thread safety.
     */
    public abstract void apply();


    /**
     * @hide
     * Mask of editable keys.
     */
    protected long mEditableKeys;

    /**
     * @hide
     */
    protected boolean mMetadataChanged = false;

    /**
     * @hide
     */
    protected boolean mApplied = false;

    /**
     * @hide
     */
    protected boolean mArtworkChanged = false;

    /**
     * @hide
     */
    protected Bitmap mEditorArtwork;

    /**
     * @hide
     */
    protected Bundle mEditorMetadata;

    /**
     * @hide
     */
    protected MediaMetadata.Builder mMetadataBuilder;

    /**
     * Clears all the pending metadata changes set since the MediaMetadataEditor instance was
     * created or since this method was last called.
     * Note that clearing the metadata doesn't reset the editable keys
     * (use {@link #removeEditableKeys()} instead).
     */
    public synchronized void clear() {
        if (mApplied) {
            Log.e(TAG, "Can't clear a previously applied MediaMetadataEditor");
            return;
        }
        mEditorMetadata.clear();
        mEditorArtwork = null;
        mMetadataBuilder = new MediaMetadata.Builder();
    }

    /**
     * Flags the given key as being editable.
     * This should only be used by metadata publishers, such as {@link RemoteControlClient},
     * which will declare the metadata field as eligible to be updated, with new values
     * received through the {@link RemoteControlClient.OnMetadataUpdateListener} interface.
     * @param key the type of metadata that can be edited. The supported key is
     *     {@link #RATING_KEY_BY_USER}.
     */
    public synchronized void addEditableKey(int key) {
        if (mApplied) {
            Log.e(TAG, "Can't change editable keys of a previously applied MetadataEditor");
            return;
        }
        // only one editable key at the moment, so we're not wasting memory on an array
        // of editable keys to check the validity of the key, just hardcode the supported key.
        if (key == RATING_KEY_BY_USER) {
            mEditableKeys |= (KEY_EDITABLE_MASK & key);
            mMetadataChanged = true;
        } else {
            Log.e(TAG, "Metadata key " + key + " cannot be edited");
        }
    }

    /**
     * Causes all metadata fields to be read-only.
     */
    public synchronized void removeEditableKeys() {
        if (mApplied) {
            Log.e(TAG, "Can't remove all editable keys of a previously applied MetadataEditor");
            return;
        }
        if (mEditableKeys != 0) {
            mEditableKeys = 0;
            mMetadataChanged = true;
        }
    }

    /**
     * Retrieves the keys flagged as editable.
     * @return null if there are no editable keys, or an array containing the keys.
     */
    public synchronized int[] getEditableKeys() {
        // only one editable key supported here
        if (mEditableKeys == RATING_KEY_BY_USER) {
            int[] keys = { RATING_KEY_BY_USER };
            return keys;
        } else {
            return null;
        }
    }

    /**
     * Adds textual information.
     * Note that none of the information added after {@link #apply()} has been called,
     * will be available to consumers of metadata stored by the MediaMetadataEditor.
     * @param key The identifier of a the metadata field to set. Valid values are
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUM},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUMARTIST},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_TITLE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ARTIST},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_AUTHOR},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPILATION},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPOSER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DATE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_GENRE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_WRITER}.
     * @param value The text for the given key, or {@code null} to signify there is no valid
     *      information for the field.
     * @return Returns a reference to the same MediaMetadataEditor object, so you can chain put
     *      calls together.
     */
    public synchronized MediaMetadataEditor putString(int key, String value)
            throws IllegalArgumentException {
        if (mApplied) {
            Log.e(TAG, "Can't edit a previously applied MediaMetadataEditor");
            return this;
        }
        if (METADATA_KEYS_TYPE.get(key, METADATA_TYPE_INVALID) != METADATA_TYPE_STRING) {
            throw(new IllegalArgumentException("Invalid type 'String' for key "+ key));
        }
        mEditorMetadata.putString(String.valueOf(key), value);
        mMetadataChanged = true;
        return this;
    }

    /**
     * Adds numerical information.
     * Note that none of the information added after {@link #apply()} has been called
     * will be available to consumers of metadata stored by the MediaMetadataEditor.
     * @param key the identifier of a the metadata field to set. Valid values are
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_CD_TRACK_NUMBER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DISC_NUMBER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DURATION} (with a value
     *      expressed in milliseconds),
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_YEAR}.
     * @param value The long value for the given key
     * @return Returns a reference to the same MediaMetadataEditor object, so you can chain put
     *      calls together.
     * @throws IllegalArgumentException
     */
    public synchronized MediaMetadataEditor putLong(int key, long value)
            throws IllegalArgumentException {
        if (mApplied) {
            Log.e(TAG, "Can't edit a previously applied MediaMetadataEditor");
            return this;
        }
        if (METADATA_KEYS_TYPE.get(key, METADATA_TYPE_INVALID) != METADATA_TYPE_LONG) {
            throw(new IllegalArgumentException("Invalid type 'long' for key "+ key));
        }
        mEditorMetadata.putLong(String.valueOf(key), value);
        mMetadataChanged = true;
        return this;
    }

    /**
     * Adds image.
     * @param key the identifier of the bitmap to set. The only valid value is
     *      {@link #BITMAP_KEY_ARTWORK}
     * @param bitmap The bitmap for the artwork, or null if there isn't any.
     * @return Returns a reference to the same MediaMetadataEditor object, so you can chain put
     *      calls together.
     * @throws IllegalArgumentException
     * @see android.graphics.Bitmap
     */
    public synchronized MediaMetadataEditor putBitmap(int key, Bitmap bitmap)
            throws IllegalArgumentException {
        if (mApplied) {
            Log.e(TAG, "Can't edit a previously applied MediaMetadataEditor");
            return this;
        }
        if (key != BITMAP_KEY_ARTWORK) {
            throw(new IllegalArgumentException("Invalid type 'Bitmap' for key "+ key));
        }
        mEditorArtwork = bitmap;
        mArtworkChanged = true;
        return this;
    }

    /**
     * Adds information stored as an instance.
     * Note that none of the information added after {@link #apply()} has been called
     * will be available to consumers of metadata stored by the MediaMetadataEditor.
     * @param key the identifier of a the metadata field to set. Valid keys for a:
     *     <ul>
     *     <li>{@link Bitmap} object are {@link #BITMAP_KEY_ARTWORK},</li>
     *     <li>{@link String} object are the same as for {@link #putString(int, String)}</li>
     *     <li>{@link Long} object are the same as for {@link #putLong(int, long)}</li>
     *     <li>{@link Rating} object are {@link #RATING_KEY_BY_OTHERS}
     *         and {@link #RATING_KEY_BY_USER}.</li>
     *     </ul>
     * @param value the metadata to add.
     * @return Returns a reference to the same MediaMetadataEditor object, so you can chain put
     *      calls together.
     * @throws IllegalArgumentException
     */
    public synchronized MediaMetadataEditor putObject(int key, Object value)
            throws IllegalArgumentException {
        if (mApplied) {
            Log.e(TAG, "Can't edit a previously applied MediaMetadataEditor");
            return this;
        }
        switch(METADATA_KEYS_TYPE.get(key, METADATA_TYPE_INVALID)) {
            case METADATA_TYPE_LONG:
                if (value instanceof Long) {
                    return putLong(key, ((Long)value).longValue());
                } else {
                    throw(new IllegalArgumentException("Not a non-null Long for key "+ key));
                }
            case METADATA_TYPE_STRING:
                if ((value == null) || (value instanceof String)) {
                    return putString(key, (String) value);
                } else {
                    throw(new IllegalArgumentException("Not a String for key "+ key));
                }
            case METADATA_TYPE_RATING:
                mEditorMetadata.putParcelable(String.valueOf(key), (Parcelable)value);
                mMetadataChanged = true;
                break;
            case METADATA_TYPE_BITMAP:
                if ((value == null) || (value instanceof Bitmap))  {
                    return putBitmap(key, (Bitmap) value);
                } else {
                    throw(new IllegalArgumentException("Not a Bitmap for key "+ key));
                }
            default:
                throw(new IllegalArgumentException("Invalid key "+ key));
        }
        return this;
    }


    /**
     * Returns the long value for the key.
     * @param key one of the keys supported in {@link #putLong(int, long)}
     * @param defaultValue the value returned if the key is not present
     * @return the long value for the key, or the supplied default value if the key is not present
     * @throws IllegalArgumentException
     */
    public synchronized long getLong(int key, long defaultValue)
            throws IllegalArgumentException {
        if (METADATA_KEYS_TYPE.get(key, METADATA_TYPE_INVALID) != METADATA_TYPE_LONG) {
            throw(new IllegalArgumentException("Invalid type 'long' for key "+ key));
        }
        return mEditorMetadata.getLong(String.valueOf(key), defaultValue);
    }

    /**
     * Returns the {@link String} value for the key.
     * @param key one of the keys supported in {@link #putString(int, String)}
     * @param defaultValue the value returned if the key is not present
     * @return the {@link String} value for the key, or the supplied default value if the key is
     *     not present
     * @throws IllegalArgumentException
     */
    public synchronized String getString(int key, String defaultValue)
            throws IllegalArgumentException {
        if (METADATA_KEYS_TYPE.get(key, METADATA_TYPE_INVALID) != METADATA_TYPE_STRING) {
            throw(new IllegalArgumentException("Invalid type 'String' for key "+ key));
        }
        return mEditorMetadata.getString(String.valueOf(key), defaultValue);
    }

    /**
     * Returns the {@link Bitmap} value for the key.
     * @param key the {@link #BITMAP_KEY_ARTWORK} key
     * @param defaultValue the value returned if the key is not present
     * @return the {@link Bitmap} value for the key, or the supplied default value if the key is
     *     not present
     * @throws IllegalArgumentException
     */
    public synchronized Bitmap getBitmap(int key, Bitmap defaultValue)
            throws IllegalArgumentException {
        if (key != BITMAP_KEY_ARTWORK) {
            throw(new IllegalArgumentException("Invalid type 'Bitmap' for key "+ key));
        }
        return (mEditorArtwork != null ? mEditorArtwork : defaultValue);
    }

    /**
     * Returns an object representation of the value for the key
     * @param key one of the keys supported in {@link #putObject(int, Object)}
     * @param defaultValue the value returned if the key is not present
     * @return the object for the key, as a {@link Long}, {@link Bitmap}, {@link String}, or
     *     {@link Rating} depending on the key value, or the supplied default value if the key is
     *     not present
     * @throws IllegalArgumentException
     */
    public synchronized Object getObject(int key, Object defaultValue)
            throws IllegalArgumentException {
        switch (METADATA_KEYS_TYPE.get(key, METADATA_TYPE_INVALID)) {
            case METADATA_TYPE_LONG:
                if (mEditorMetadata.containsKey(String.valueOf(key))) {
                    return mEditorMetadata.getLong(String.valueOf(key));
                } else {
                    return defaultValue;
                }
            case METADATA_TYPE_STRING:
                if (mEditorMetadata.containsKey(String.valueOf(key))) {
                    return mEditorMetadata.getString(String.valueOf(key));
                } else {
                    return defaultValue;
                }
            case METADATA_TYPE_RATING:
                if (mEditorMetadata.containsKey(String.valueOf(key))) {
                    return mEditorMetadata.getParcelable(String.valueOf(key));
                } else {
                    return defaultValue;
                }
            case METADATA_TYPE_BITMAP:
                // only one key for Bitmap supported, value is not stored in mEditorMetadata Bundle
                if (key == BITMAP_KEY_ARTWORK) {
                    return (mEditorArtwork != null ? mEditorArtwork : defaultValue);
                } // else: fall through to invalid key handling
            default:
                throw(new IllegalArgumentException("Invalid key "+ key));
        }
    }


    /**
     * @hide
     */
    protected static final int METADATA_TYPE_INVALID = -1;
    /**
     * @hide
     */
    protected static final int METADATA_TYPE_LONG = 0;

    /**
     * @hide
     */
    protected static final int METADATA_TYPE_STRING = 1;

    /**
     * @hide
     */
    protected static final int METADATA_TYPE_BITMAP = 2;

    /**
     * @hide
     */
    protected static final int METADATA_TYPE_RATING = 3;

    /**
     * @hide
     */
    protected static final SparseIntArray METADATA_KEYS_TYPE;

    static {
        METADATA_KEYS_TYPE = new SparseIntArray(17);
        // NOTE: if adding to the list below, make sure you increment the array initialization size
        // keys with long values
        METADATA_KEYS_TYPE.put(
                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_DURATION, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_YEAR, METADATA_TYPE_LONG);
        // keys with String values
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_ALBUM, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_TITLE, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_ARTIST, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_AUTHOR, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(
                MediaMetadataRetriever.METADATA_KEY_COMPILATION, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_COMPOSER, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_DATE, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_GENRE, METADATA_TYPE_STRING);
        METADATA_KEYS_TYPE.put(MediaMetadataRetriever.METADATA_KEY_WRITER, METADATA_TYPE_STRING);
        // keys with Bitmap values
        METADATA_KEYS_TYPE.put(BITMAP_KEY_ARTWORK, METADATA_TYPE_BITMAP);
        // keys with Rating values
        METADATA_KEYS_TYPE.put(RATING_KEY_BY_OTHERS, METADATA_TYPE_RATING);
        METADATA_KEYS_TYPE.put(RATING_KEY_BY_USER, METADATA_TYPE_RATING);
    }
}
