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

package android.media.tv;

import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Encapsulates the format of tracks played in {@link TvInputService}.
 */
public final class TvTrackInfo implements Parcelable {
    /**
     * A key describing the type of this track. The associated value is an integer and it should be
     * one of {@link #VALUE_TYPE_AUDIO}, {@link #VALUE_TYPE_VIDEO}, and {@link #VALUE_TYPE_SUBTITLE}.
     * <p>
     * This is a required key.
     * </p>
     */
    public static final String KEY_TYPE = "type";

    /**
     * A key describing the language of the track, using either ISO 639-1 or 639-2/T codes.
     * If the language is unknown or could not be determined, the corresponding value will be "und".
     * The associated value is a string.
     * <p>
     * This is a required key.
     * </p>
     */
    public static final String KEY_LANGUAGE = MediaFormat.KEY_LANGUAGE;

    /**
     * A key describing whether this track is selected for the playback.
     * The associated value is a boolean.
     * <p>
     * This is a required key.
     * </p>
     */
    public static final String KEY_IS_SELECTED = "is-selected";

    /**
     * A key describing the sample rate of an audio track.
     * The associated value is an integer.
     */
    public static final String KEY_SAMPLE_RATE = MediaFormat.KEY_SAMPLE_RATE;

    /**
     * A key describing the number of channels in an audio track.
     * The associated value is an integer.
     */
    public static final String KEY_CHANNEL_COUNT = MediaFormat.KEY_CHANNEL_COUNT;

    /**
     * A key describing the width of the content in a video track.
     * The associated value is an integer.
     */
    public static final String KEY_WIDTH = MediaFormat.KEY_WIDTH;

    /**
     * A key describing the height of the content in a video track.
     * The associated value is an integer.
     */
    public static final String KEY_HEIGHT = MediaFormat.KEY_HEIGHT;

    /**
     * A key describing a tag associated with this track. Expected to be used as an identifier with
     * in a session. The associated value is a string.
     */
    public static final String KEY_TAG = "tag";

    /**
     * The type value for audio track.
     */
    public static final int VALUE_TYPE_AUDIO = 0;

    /**
     * The type value for video track.
     */
    public static final int VALUE_TYPE_VIDEO = 1;

    /**
     * The type value for subtitle track.
     */
    public static final int VALUE_TYPE_SUBTITLE = 2;

    private final Bundle mBundle;

    private TvTrackInfo(Bundle bundle) {
        mBundle = new Bundle(bundle);
    }

    private TvTrackInfo(Parcel in) {
        mBundle = in.readBundle();
    }

    /**
     * Checks if there is only one or zero selected track per track type.
     *
     * @param tracks a list including tracks which will be checked.
     * @return true if there is only one or zero selected track per track type, false otherwise
     * @hide
     */
    public static boolean checkSanity(List<TvTrackInfo> tracks) {
        int selectedAudioTracks = 0;
        int selectedVideoTracks = 0;
        int selectedSubtitleTracks = 0;
        for (TvTrackInfo track : tracks) {
            if (track.getBoolean(KEY_IS_SELECTED)) {
                int type = track.getInt(KEY_TYPE);
                if (type == VALUE_TYPE_AUDIO) {
                    selectedAudioTracks++;
                } else if (type == VALUE_TYPE_VIDEO) {
                    selectedVideoTracks++;
                } else if (type == VALUE_TYPE_SUBTITLE) {
                    selectedSubtitleTracks++;
                }
            }
        }
        if (selectedAudioTracks > 1 || selectedVideoTracks > 1 || selectedSubtitleTracks > 1) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if the given key is contained in the metadata
     *
     * @param key A String key
     * @return true If the key exists in this metadata, false otherwise
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
     * @return A String value, or null
     */
    public String getString(String key) {
        return mBundle.getString(key);
    }

    /**
     * Returns the value associated with the given key, or 0L if no integer exists
     * for the given key.
     *
     * @param key The key the value is stored under
     * @return An integer value
     */
    public int getInt(String key) {
        return mBundle.getInt(key, 0);
    }

    /**
     * Returns the value associated with the given key, or false if no integer exists
     * for the given key.
     *
     * @param key The key the value is stored under
     * @return A boolean value
     */
    public boolean getBoolean(String key) {
        return mBundle.getBoolean(key, false);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(mBundle);
    }

    public static final Parcelable.Creator<TvTrackInfo> CREATOR
            = new Parcelable.Creator<TvTrackInfo>() {
                @Override
                public TvTrackInfo createFromParcel(Parcel in) {
                    return new TvTrackInfo(in);
                }

                @Override
                public TvTrackInfo[] newArray(int size) {
                    return new TvTrackInfo[size];
                }
            };

    /**
     * A builder class for creating {@link TvTrackInfo} objects.
     */
    public static final class Builder {
        private final Bundle mBundle;

        /**
         * Create a {@link Builder}. Any field that should be included in the
         * {@link TvTrackInfo} must be added.
         *
         * @param type The type of the track.
         * @param language The language of the track, using either ISO 639-1 or 639-2/T codes.
         *         "und" if the language is unknown.
         * @param isSelected Whether this track is selected for the playback or not.
         */
        public Builder(int type, String language, boolean isSelected) {
            if (type != VALUE_TYPE_AUDIO
                    && type != VALUE_TYPE_VIDEO
                    && type != VALUE_TYPE_SUBTITLE) {
                throw new IllegalArgumentException("Unknown type: " + type);
            }
            mBundle = new Bundle();
            putInt(KEY_TYPE, type);
            putString(KEY_LANGUAGE, language);
            putBoolean(KEY_IS_SELECTED, isSelected);
        }

        /**
         * Create a Builder using a {@link TvTrackInfo} instance to set the
         * initial values. All fields in the source metadata will be included in
         * the new metadata. Fields can be overwritten by adding the same key.
         *
         * @param source The source {@link TvTrackInfo} instance
         */
        public Builder(TvTrackInfo source) {
            mBundle = new Bundle(source.mBundle);
        }

        /**
         * Put a String value into the track.
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return The Builder to allow chaining
         */
        public Builder putString(String key, String value) {
            mBundle.putString(key, value);
            return this;
        }

        /**
         * Put an integer value into the track.
         *
         * @param key The key for referencing this value
         * @param value The integer value to store
         * @return The Builder to allow chaining
         */
        public Builder putInt(String key, int value) {
            mBundle.putInt(key, value);
            return this;
        }

        /**
         * Put a boolean value into the track.
         *
         * @param key The key for referencing this value
         * @param value The boolean value to store
         * @return The Builder to allow chaining
         */
        public Builder putBoolean(String key, boolean value) {
            mBundle.putBoolean(key, value);
            return this;
        }

        /**
         * Creates a {@link TvTrackInfo} instance with the specified fields.
         *
         * @return The new {@link TvTrackInfo} instance
         */
        public TvTrackInfo build() {
            return new TvTrackInfo(mBundle);
        }
    }
}