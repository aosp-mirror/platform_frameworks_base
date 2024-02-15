/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.hardware.radio;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Contains meta data about a radio program such as station name, song title, artist etc...
 * @hide
 */
@SystemApi
public final class RadioMetadata implements Parcelable {
    private static final String TAG = "BroadcastRadio.metadata";

    /**
     * The RDS Program Information.
     */
    public static final String METADATA_KEY_RDS_PI = "android.hardware.radio.metadata.RDS_PI";

    /**
     * The RDS Program Service.
     */
    public static final String METADATA_KEY_RDS_PS = "android.hardware.radio.metadata.RDS_PS";

    /**
     * The RDS PTY.
     */
    public static final String METADATA_KEY_RDS_PTY = "android.hardware.radio.metadata.RDS_PTY";

    /**
     * The RBDS PTY.
     */
    public static final String METADATA_KEY_RBDS_PTY = "android.hardware.radio.metadata.RBDS_PTY";

    /**
     * The RBDS Radio Text.
     */
    public static final String METADATA_KEY_RDS_RT = "android.hardware.radio.metadata.RDS_RT";

    /**
     * The song title.
     */
    public static final String METADATA_KEY_TITLE = "android.hardware.radio.metadata.TITLE";

    /**
     * The artist name.
     */
    public static final String METADATA_KEY_ARTIST = "android.hardware.radio.metadata.ARTIST";

    /**
     * The album name.
     */
    public static final String METADATA_KEY_ALBUM = "android.hardware.radio.metadata.ALBUM";

    /**
     * The music genre.
     */
    public static final String METADATA_KEY_GENRE = "android.hardware.radio.metadata.GENRE";

    /**
     * The radio station icon {@link Bitmap}.
     */
    public static final String METADATA_KEY_ICON = "android.hardware.radio.metadata.ICON";

    /**
     * The artwork for the song/album {@link Bitmap}.
     */
    public static final String METADATA_KEY_ART = "android.hardware.radio.metadata.ART";

    /**
     * The clock.
     */
    public static final String METADATA_KEY_CLOCK = "android.hardware.radio.metadata.CLOCK";

    /**
     * Technology-independent program name (station name).
     */
    public static final String METADATA_KEY_PROGRAM_NAME =
            "android.hardware.radio.metadata.PROGRAM_NAME";

    /**
     * DAB ensemble name.
     */
    public static final String METADATA_KEY_DAB_ENSEMBLE_NAME =
            "android.hardware.radio.metadata.DAB_ENSEMBLE_NAME";

    /**
     * DAB ensemble name - short version (up to 8 characters).
     */
    public static final String METADATA_KEY_DAB_ENSEMBLE_NAME_SHORT =
            "android.hardware.radio.metadata.DAB_ENSEMBLE_NAME_SHORT";

    /**
     * DAB service name.
     */
    public static final String METADATA_KEY_DAB_SERVICE_NAME =
            "android.hardware.radio.metadata.DAB_SERVICE_NAME";

    /**
     * DAB service name - short version (up to 8 characters).
     */
    public static final String METADATA_KEY_DAB_SERVICE_NAME_SHORT =
            "android.hardware.radio.metadata.DAB_SERVICE_NAME_SHORT";

    /**
     * DAB component name.
     */
    public static final String METADATA_KEY_DAB_COMPONENT_NAME =
            "android.hardware.radio.metadata.DAB_COMPONENT_NAME";

    /**
     * DAB component name.
     */
    public static final String METADATA_KEY_DAB_COMPONENT_NAME_SHORT =
            "android.hardware.radio.metadata.DAB_COMPONENT_NAME_SHORT";

    /**
     * Short context description of comment
     *
     * <p>Comment could relate to the current audio program content, or it might
     * be unrelated information that the station chooses to send. It is composed
     * of short content description and actual text (see NRSC-G200-A and id3v2.3.0
     * for more info).
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final String METADATA_KEY_COMMENT_SHORT_DESCRIPTION =
            "android.hardware.radio.metadata.COMMENT_SHORT_DESCRIPTION";

    /**
     * Actual text of comment
     *
     * @see #METADATA_KEY_COMMENT_SHORT_DESCRIPTION
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final String METADATA_KEY_COMMENT_ACTUAL_TEXT =
            "android.hardware.radio.metadata.COMMENT_ACTUAL_TEXT";

    /**
     * Commercial
     *
     * <p>Commercial is application specific and generally used to facilitate the
     * sale of products and services (see NRSC-G200-A and id3v2.3.0 for more info).
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final String METADATA_KEY_COMMERCIAL =
            "android.hardware.radio.metadata.COMMERCIAL";

    /**
     * Array of Unique File Identifiers
     *
     * <p>Unique File Identifier (UFID) can be used to transmit an alphanumeric
     * identifier of the current content, or of an advertised product or
     * service (see NRSC-G200-A and id3v2.3.0 for more info).
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final String METADATA_KEY_UFIDS = "android.hardware.radio.metadata.UFIDS";

    /**
     * HD short station name or HD universal short station name
     *
     * <p>It can be up to 12 characters (see SY_IDD_1020s for more info).
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final String METADATA_KEY_HD_STATION_NAME_SHORT =
            "android.hardware.radio.metadata.HD_STATION_NAME_SHORT";

    /**
     * HD long station name, HD station slogan or HD station message
     *
     * <p>(see SY_IDD_1020s for more info)
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final String METADATA_KEY_HD_STATION_NAME_LONG =
            "android.hardware.radio.metadata.HD_STATION_NAME_LONG";

    /**
     * Bit mask of all HD Radio subchannels available
     *
     * <p>Bit {@link ProgramSelector#SUB_CHANNEL_HD_1} from LSB represents the
     * availability of HD-1 subchannel (main program service, MPS). Bits
     * {@link ProgramSelector#SUB_CHANNEL_HD_2} to {@link ProgramSelector#SUB_CHANNEL_HD_8}
     * from LSB represent HD-2 to HD-8 subchannel (supplemental program services, SPS)
     * respectively.
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final String METADATA_KEY_HD_SUBCHANNELS_AVAILABLE =
            "android.hardware.radio.metadata.HD_SUBCHANNELS_AVAILABLE";

    private static final int METADATA_TYPE_INVALID = -1;
    private static final int METADATA_TYPE_INT = 0;
    private static final int METADATA_TYPE_TEXT = 1;
    private static final int METADATA_TYPE_BITMAP = 2;
    private static final int METADATA_TYPE_CLOCK = 3;
    private static final int METADATA_TYPE_TEXT_ARRAY = 4;

    private static final ArrayMap<String, Integer> METADATA_KEYS_TYPE;

    static {
        METADATA_KEYS_TYPE = new ArrayMap<String, Integer>();
        METADATA_KEYS_TYPE.put(METADATA_KEY_RDS_PI, METADATA_TYPE_INT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_RDS_PS, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_RDS_PTY, METADATA_TYPE_INT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_RBDS_PTY, METADATA_TYPE_INT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_RDS_RT, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_TITLE, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ARTIST, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_GENRE, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ICON, METADATA_TYPE_BITMAP);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ART, METADATA_TYPE_BITMAP);
        METADATA_KEYS_TYPE.put(METADATA_KEY_CLOCK, METADATA_TYPE_CLOCK);
        METADATA_KEYS_TYPE.put(METADATA_KEY_PROGRAM_NAME, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DAB_ENSEMBLE_NAME, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DAB_ENSEMBLE_NAME_SHORT, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DAB_SERVICE_NAME, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DAB_SERVICE_NAME_SHORT, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DAB_COMPONENT_NAME, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DAB_COMPONENT_NAME_SHORT, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_COMMENT_SHORT_DESCRIPTION, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_COMMENT_ACTUAL_TEXT, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_COMMERCIAL, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_UFIDS, METADATA_TYPE_TEXT_ARRAY);
        METADATA_KEYS_TYPE.put(METADATA_KEY_HD_STATION_NAME_SHORT, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_HD_STATION_NAME_LONG, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_HD_SUBCHANNELS_AVAILABLE, METADATA_TYPE_INT);
    }

    // keep in sync with: system/media/radio/include/system/radio_metadata.h
    private static final int NATIVE_KEY_INVALID     = -1;
    private static final int NATIVE_KEY_RDS_PI      = 0;
    private static final int NATIVE_KEY_RDS_PS      = 1;
    private static final int NATIVE_KEY_RDS_PTY     = 2;
    private static final int NATIVE_KEY_RBDS_PTY    = 3;
    private static final int NATIVE_KEY_RDS_RT      = 4;
    private static final int NATIVE_KEY_TITLE       = 5;
    private static final int NATIVE_KEY_ARTIST      = 6;
    private static final int NATIVE_KEY_ALBUM       = 7;
    private static final int NATIVE_KEY_GENRE       = 8;
    private static final int NATIVE_KEY_ICON        = 9;
    private static final int NATIVE_KEY_ART         = 10;
    private static final int NATIVE_KEY_CLOCK       = 11;

    private static final SparseArray<String> NATIVE_KEY_MAPPING;

    static {
        NATIVE_KEY_MAPPING = new SparseArray<String>();
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_RDS_PI, METADATA_KEY_RDS_PI);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_RDS_PS, METADATA_KEY_RDS_PS);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_RDS_PTY, METADATA_KEY_RDS_PTY);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_RBDS_PTY, METADATA_KEY_RBDS_PTY);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_RDS_RT, METADATA_KEY_RDS_RT);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_TITLE, METADATA_KEY_TITLE);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_ARTIST, METADATA_KEY_ARTIST);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_ALBUM, METADATA_KEY_ALBUM);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_GENRE, METADATA_KEY_GENRE);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_ICON, METADATA_KEY_ICON);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_ART, METADATA_KEY_ART);
        NATIVE_KEY_MAPPING.put(NATIVE_KEY_CLOCK, METADATA_KEY_CLOCK);
    }

    /**
     * Provides a Clock that can be used to describe time as provided by the Radio.
     *
     * The clock is defined by the seconds since epoch at the UTC + 0 timezone
     * and timezone offset from UTC + 0 represented in number of minutes.
     *
     * @hide
     */
    @SystemApi
    public static final class Clock implements Parcelable {
        private final long mUtcEpochSeconds;
        private final int mTimezoneOffsetMinutes;

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeLong(mUtcEpochSeconds);
            out.writeInt(mTimezoneOffsetMinutes);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Clock> CREATOR
                = new Parcelable.Creator<Clock>() {
            public Clock createFromParcel(Parcel in) {
                return new Clock(in);
            }

            public Clock[] newArray(int size) {
                return new Clock[size];
            }
        };

        public Clock(long utcEpochSeconds, int timezoneOffsetMinutes) {
            mUtcEpochSeconds = utcEpochSeconds;
            mTimezoneOffsetMinutes = timezoneOffsetMinutes;
        }

        private Clock(Parcel in) {
            mUtcEpochSeconds = in.readLong();
            mTimezoneOffsetMinutes = in.readInt();
        }

        public long getUtcEpochSeconds() {
            return mUtcEpochSeconds;
        }

        public int getTimezoneOffsetMinutes() {
            return mTimezoneOffsetMinutes;
        }
    }

    private final Bundle mBundle;

    // Lazily computed hash code based upon the contents of mBundle.
    private Integer mHashCode;

    @Override
    public int hashCode() {
        if (mHashCode == null) {
            List<String> keys = new ArrayList<String>(mBundle.keySet());
            keys.sort(null);
            Object[] objs = new Object[2 * keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                objs[2 * i] = keys.get(i);
                objs[2 * i + 1] = mBundle.get(keys.get(i));
            }
            mHashCode = Arrays.hashCode(objs);
        }
        return mHashCode;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RadioMetadata)) return false;
        Bundle otherBundle = ((RadioMetadata) obj).mBundle;
        if (!mBundle.keySet().equals(otherBundle.keySet())) {
            return false;
        }
        for (String key : mBundle.keySet()) {
            if (Flags.hdRadioImproved() && Objects.equals(METADATA_KEYS_TYPE.get(key),
                    METADATA_TYPE_TEXT_ARRAY)) {
                if (!Arrays.equals(mBundle.getStringArray(key), otherBundle.getStringArray(key))) {
                    return false;
                }
            } else if (!Objects.equals(mBundle.get(key), otherBundle.get(key))) {
                return false;
            }
        }
        return true;
    }

    RadioMetadata() {
        mBundle = new Bundle();
    }

    private RadioMetadata(Bundle bundle) {
        mBundle = new Bundle(bundle);
    }

    private RadioMetadata(Parcel in) {
        mBundle = in.readBundle();
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RadioMetadata[");

        final String removePrefix = "android.hardware.radio.metadata";

        boolean first = true;
        for (String key : mBundle.keySet()) {
            if (first) first = false;
            else sb.append(", ");

            String keyDisp = key;
            if (key.startsWith(removePrefix)) keyDisp = key.substring(removePrefix.length());

            sb.append(keyDisp);
            sb.append('=');
            if (Flags.hdRadioImproved() && Objects.equals(METADATA_KEYS_TYPE.get(key),
                    METADATA_TYPE_TEXT_ARRAY)) {
                String[] stringArrayValue = mBundle.getStringArray(key);
                sb.append('[');
                for (int i = 0; i < stringArrayValue.length; i++) {
                    if (i != 0) {
                        sb.append(',');
                    }
                    sb.append(stringArrayValue[i]);
                }
                sb.append(']');
            } else {
                sb.append(mBundle.get(key));
            }

        }

        sb.append("]");
        return sb.toString();
    }

    /**
     * Returns {@code true} if the given key is contained in the meta data
     *
     * @param key a String key
     * @return {@code true} if the key exists in this meta data, {@code false} otherwise
     */
    public boolean containsKey(String key) {
        return mBundle.containsKey(key);
    }

    /**
     * Returns the text value associated with the given key as a String, or null
     * if the key is not found in the meta data.
     *
     * @param key The key the value is stored under
     * @return a String value, or null
     */
    public String getString(String key) {
        return mBundle.getString(key);
    }

    private static void putInt(Bundle bundle, String key, int value) {
        int type = METADATA_KEYS_TYPE.getOrDefault(key, METADATA_TYPE_INVALID);
        if (type != METADATA_TYPE_INT && type != METADATA_TYPE_BITMAP) {
            throw new IllegalArgumentException("The " + key + " key cannot be used to put an int");
        }
        bundle.putInt(key, value);
    }

    /**
     * Returns the value associated with the given key,
     * or 0 if the key is not found in the meta data.
     *
     * @param key The key the value is stored under
     * @return an int value
     */
    public int getInt(String key) {
        return mBundle.getInt(key, 0);
    }

    /**
     * Returns a {@link Bitmap} for the given key or null if the key is not found in the meta data.
     *
     * @param key The key the value is stored under
     * @return a {@link Bitmap} or null
     * @deprecated Use getBitmapId(String) instead
     */
    @Deprecated
    public Bitmap getBitmap(String key) {
        Bitmap bmp = null;
        try {
            bmp = mBundle.getParcelable(key, android.graphics.Bitmap.class);
        } catch (Exception e) {
            // ignore, value was not a bitmap
            Log.w(TAG, "Failed to retrieve a key as Bitmap.", e);
        }
        return bmp;
    }

    /**
     * Retrieves an identifier for a bitmap.
     *
     * The format of an identifier is opaque to the application,
     * with a special case of value 0 being invalid.
     * An identifier for a given image-tuner pair is unique, so an application
     * may cache images and determine if there is a necessity to fetch them
     * again - if identifier changes, it means the image has changed.
     *
     * Only bitmap keys may be used with this method:
     * <ul>
     * <li>{@link #METADATA_KEY_ICON}</li>
     * <li>{@link #METADATA_KEY_ART}</li>
     * </ul>
     *
     * @param key The key the value is stored under.
     * @return a bitmap identifier or 0 if it's missing.
     * @throws NullPointerException if metadata key is {@code null}
     * @throws IllegalArgumentException if the metadata with the key is not found in
     * metadata or the key is not of bitmap-key type
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public int getBitmapId(@NonNull String key) {
        Objects.requireNonNull(key, "Metadata key can not be null");
        if (!METADATA_KEY_ICON.equals(key) && !METADATA_KEY_ART.equals(key)) {
            throw new IllegalArgumentException("Failed to retrieve key " + key + " as bitmap key");
        }
        return getInt(key);
    }

    public Clock getClock(String key) {
        Clock clock = null;
        try {
            clock = mBundle.getParcelable(key, android.hardware.radio.RadioMetadata.Clock.class);
        } catch (Exception e) {
            // ignore, value was not a clock.
            Log.w(TAG, "Failed to retrieve a key as Clock.", e);
        }
        return clock;
    }

    /**
     * Gets the string array value associated with the given key as a string
     * array.
     *
     * <p>Only string array keys may be used with this method:
     * <ul>
     * <li>{@link #METADATA_KEY_UFIDS}</li>
     * </ul>
     *
     * @param key The key the value is stored under
     * @return String array of the given string-array-type key
     * @throws NullPointerException if metadata key is {@code null}
     * @throws IllegalArgumentException if the metadata with the key is not found in
     * metadata or the key is not of string-array type
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    @NonNull
    public String[] getStringArray(@NonNull String key) {
        Objects.requireNonNull(key, "Metadata key can not be null");
        if (!Objects.equals(METADATA_KEYS_TYPE.get(key), METADATA_TYPE_TEXT_ARRAY)) {
            throw new IllegalArgumentException("Failed to retrieve key " + key
                    + " as string array");
        }
        String[] stringArrayValue = mBundle.getStringArray(key);
        if (stringArrayValue == null) {
            throw new IllegalArgumentException("Key " + key + " is not found in metadata");
        }
        return stringArrayValue;
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
     * Returns the number of fields in this meta data.
     *
     * @return the number of fields in the meta data.
     */
    public int size() {
        return mBundle.size();
    }

    /**
     * Returns a Set containing the Strings used as keys in this meta data.
     *
     * @return a Set of String keys
     */
    public Set<String> keySet() {
        return mBundle.keySet();
    }

    /**
     * Helper for getting the String key used by {@link RadioMetadata} from the
     * corrsponding native integer key.
     *
     * @param nativeKey The key used by the editor
     * @return the key used by this class or null if no mapping exists
     * @hide
     */
    public static String getKeyFromNativeKey(int nativeKey) {
        return NATIVE_KEY_MAPPING.get(nativeKey, null);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<RadioMetadata> CREATOR =
            new Parcelable.Creator<RadioMetadata>() {
                @Override
                public RadioMetadata createFromParcel(Parcel in) {
                    return new RadioMetadata(in);
                }

                @Override
                public RadioMetadata[] newArray(int size) {
                    return new RadioMetadata[size];
                }
            };

    /**
     * Use to build RadioMetadata objects.
     */
    public static final class Builder {
        private final Bundle mBundle;

        /**
         * Create an empty Builder. Any field that should be included in the
         * {@link RadioMetadata} must be added.
         */
        public Builder() {
            mBundle = new Bundle();
        }

        /**
         * Create a Builder using a {@link RadioMetadata} instance to set the
         * initial values. All fields in the source meta data will be included in
         * the new meta data. Fields can be overwritten by adding the same key.
         *
         * @param source
         */
        public Builder(RadioMetadata source) {
            mBundle = new Bundle(source.mBundle);
        }

        /**
         * Create a Builder using a {@link RadioMetadata} instance to set
         * initial values, but replace bitmaps with a scaled down copy if they
         * are larger than maxBitmapSize.
         *
         * @param source The original meta data to copy.
         * @param maxBitmapSize The maximum height/width for bitmaps contained
         *            in the meta data.
         * @hide
         */
        public Builder(RadioMetadata source, int maxBitmapSize) {
            this(source);
            for (String key : mBundle.keySet()) {
                Object value = mBundle.get(key);
                if (value != null && value instanceof Bitmap) {
                    Bitmap bmp = (Bitmap) value;
                    if (bmp.getHeight() > maxBitmapSize || bmp.getWidth() > maxBitmapSize) {
                        putBitmap(key, scaleBitmap(bmp, maxBitmapSize));
                    }
                }
            }
        }

        /**
         * Put a String value into the meta data. Custom keys may be used, but if
         * the METADATA_KEYs defined in this class are used they may only be one
         * of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_RDS_PS}</li>
         * <li>{@link #METADATA_KEY_RDS_RT}</li>
         * <li>{@link #METADATA_KEY_TITLE}</li>
         * <li>{@link #METADATA_KEY_ARTIST}</li>
         * <li>{@link #METADATA_KEY_ALBUM}</li>
         * <li>{@link #METADATA_KEY_GENRE}</li>
         * <li>{@link #METADATA_KEY_COMMENT_SHORT_DESCRIPTION}</li>
         * <li>{@link #METADATA_KEY_COMMENT_ACTUAL_TEXT}</li>
         * <li>{@link #METADATA_KEY_COMMERCIAL}</li>
         * <li>{@link #METADATA_KEY_HD_STATION_NAME_SHORT}</li>
         * <li>{@link #METADATA_KEY_HD_STATION_NAME_LONG}</li>
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return the same Builder instance
         */
        public Builder putString(String key, String value) {
            if (!METADATA_KEYS_TYPE.containsKey(key) ||
                    METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_TEXT) {
                throw new IllegalArgumentException("The " + key
                        + " key cannot be used to put a String");
            }
            mBundle.putString(key, value);
            return this;
        }

        /**
         * Put an int value into the meta data. Custom keys may be used, but if
         * the METADATA_KEYs defined in this class are used they may only be one
         * of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_RDS_PI}</li>
         * <li>{@link #METADATA_KEY_RDS_PTY}</li>
         * <li>{@link #METADATA_KEY_RBDS_PTY}</li>
         * <li>{@link #METADATA_KEY_HD_SUBCHANNELS_AVAILABLE}</li>
         * </ul>
         * or any bitmap represented by its identifier.
         *
         * @param key The key for referencing this value
         * @param value The int value to store
         * @return the same Builder instance
         */
        public Builder putInt(String key, int value) {
            RadioMetadata.putInt(mBundle, key, value);
            return this;
        }

        /**
         * Put a {@link Bitmap} into the meta data. Custom keys may be used, but
         * if the METADATA_KEYs defined in this class are used they may only be
         * one of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_ICON}</li>
         * <li>{@link #METADATA_KEY_ART}</li>
         * </ul>
         * <p>
         *
         * @param key The key for referencing this value
         * @param value The Bitmap to store
         * @return the same Builder instance
         */
        public Builder putBitmap(String key, Bitmap value) {
            if (!METADATA_KEYS_TYPE.containsKey(key) ||
                    METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_BITMAP) {
                throw new IllegalArgumentException("The " + key
                        + " key cannot be used to put a Bitmap");
            }
            mBundle.putParcelable(key, value);
            return this;
        }

        /**
         * Put a {@link RadioMetadata.Clock} into the meta data. Custom keys may be used, but if the
         * METADATA_KEYs defined in this class are used they may only be one of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_CLOCK}</li>
         * </ul>
         *
         * @param utcSecondsSinceEpoch Number of seconds since epoch for UTC + 0 timezone.
         * @param timezoneOffsetMinutes Offset of timezone from UTC + 0 in minutes.
         * @return the same Builder instance.
         */
        public Builder putClock(String key, long utcSecondsSinceEpoch, int timezoneOffsetMinutes) {
            if (!METADATA_KEYS_TYPE.containsKey(key) ||
                    METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_CLOCK) {
                throw new IllegalArgumentException("The " + key
                    + " key cannot be used to put a RadioMetadata.Clock.");
            }
            mBundle.putParcelable(key, new Clock(utcSecondsSinceEpoch, timezoneOffsetMinutes));
            return this;
        }

        /**
         * Put a String array into the meta data. Custom keys may be used, but if
         * the METADATA_KEYs defined in this class are used they may only be one
         * of the following:
         * <ul>
         * <li>{@link #METADATA_KEY_UFIDS}</li>
         * </ul>
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @return the same Builder instance
         * @throws NullPointerException if key or value is null
         * @throws IllegalArgumentException if the key is not string-array-type key
         */
        @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
        @NonNull
        public Builder putStringArray(@NonNull String key, @NonNull String[] value) {
            Objects.requireNonNull(key, "Key can not be null");
            Objects.requireNonNull(value, "Value can not be null");
            if (!METADATA_KEYS_TYPE.containsKey(key)
                    || !Objects.equals(METADATA_KEYS_TYPE.get(key), METADATA_TYPE_TEXT_ARRAY)) {
                throw new IllegalArgumentException("The " + key
                        + " key cannot be used to put a RadioMetadata String Array.");
            }
            mBundle.putStringArray(key, value);
            return this;
        }


        /**
         * Creates a {@link RadioMetadata} instance with the specified fields.
         *
         * @return a new {@link RadioMetadata} object
         */
        public RadioMetadata build() {
            return new RadioMetadata(mBundle);
        }

        private Bitmap scaleBitmap(Bitmap bmp, int maxSize) {
            float maxSizeF = maxSize;
            float widthScale = maxSizeF / bmp.getWidth();
            float heightScale = maxSizeF / bmp.getHeight();
            float scale = Math.min(widthScale, heightScale);
            int height = (int) (bmp.getHeight() * scale);
            int width = (int) (bmp.getWidth() * scale);
            return Bitmap.createScaledBitmap(bmp, width, height, true);
        }
    }

    int putIntFromNative(int nativeKey, int value) {
        String key = getKeyFromNativeKey(nativeKey);
        try {
            putInt(mBundle, key, value);
            // Invalidate mHashCode to force it to be recomputed.
            mHashCode = null;
            return 0;
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }

    int putStringFromNative(int nativeKey, String value) {
        String key = getKeyFromNativeKey(nativeKey);
        if (!METADATA_KEYS_TYPE.containsKey(key) ||
                METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_TEXT) {
            return -1;
        }
        mBundle.putString(key, value);
        // Invalidate mHashCode to force it to be recomputed.
        mHashCode = null;
        return 0;
    }

    int putBitmapFromNative(int nativeKey, byte[] value) {
        String key = getKeyFromNativeKey(nativeKey);
        if (!METADATA_KEYS_TYPE.containsKey(key) ||
                METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_BITMAP) {
            return -1;
        }
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeByteArray(value, 0, value.length);
            if (bmp != null) {
                mBundle.putParcelable(key, bmp);
                // Invalidate mHashCode to force it to be recomputed.
                mHashCode = null;
                return 0;
            }
        } catch (Exception e) {
        }
        return -1;
    }

    int putClockFromNative(int nativeKey, long utcEpochSeconds, int timezoneOffsetInMinutes) {
        String key = getKeyFromNativeKey(nativeKey);
        if (!METADATA_KEYS_TYPE.containsKey(key) ||
                METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_CLOCK) {
              return -1;
        }
        mBundle.putParcelable(key, new RadioMetadata.Clock(
            utcEpochSeconds, timezoneOffsetInMinutes));
        // Invalidate mHashCode to force it to be recomputed.
        mHashCode = null;
        return 0;
    }
}
