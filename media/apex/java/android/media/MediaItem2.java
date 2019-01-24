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

import static android.media.MediaMetadata.METADATA_KEY_MEDIA_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A class with information on a single media item with the metadata information.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
 * for consistent behavior across all devices.
 * <p>
 */
public final class MediaItem2 implements Parcelable {
    private static final String TAG = "MediaItem2";

    // intentionally less than long.MAX_VALUE.
    // Declare this first to avoid 'illegal forward reference'.
    static final long LONG_MAX = 0x7ffffffffffffffL;

    /**
     * Used when a position is unknown.
     *
     * @see #getEndPosition()
     */
    public static final long POSITION_UNKNOWN = LONG_MAX;

    public static final Parcelable.Creator<MediaItem2> CREATOR =
            new Parcelable.Creator<MediaItem2>() {
                @Override
                public MediaItem2 createFromParcel(Parcel in) {
                    return new MediaItem2(in);
                }

                @Override
                public MediaItem2[] newArray(int size) {
                    return new MediaItem2[size];
                }
            };

    private static final long UNKNOWN_TIME = -1;

    private final long mStartPositionMs;
    private final long mEndPositionMs;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private MediaMetadata mMetadata;
    @GuardedBy("mLock")
    private final List<Pair<OnMetadataChangedListener, Executor>> mListeners = new ArrayList<>();

    /**
     * Used by {@link MediaItem2.Builder}.
     */
    // Note: Needs to be protected when we want to allow 3rd party player to define customized
    //       MediaItem2.
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    MediaItem2(Builder builder) {
        this(builder.mMetadata, builder.mStartPositionMs, builder.mEndPositionMs);
    }

    /**
     * Used by Parcelable.Creator.
     */
    // Note: Needs to be protected when we want to allow 3rd party player to define customized
    //       MediaItem2.
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    MediaItem2(Parcel in) {
        this(in.readParcelable(MediaItem2.class.getClassLoader()), in.readLong(), in.readLong());
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    MediaItem2(MediaItem2 item) {
        this(item.mMetadata, item.mStartPositionMs, item.mEndPositionMs);
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    MediaItem2(@Nullable MediaMetadata metadata, long startPositionMs, long endPositionMs) {
        if (startPositionMs > endPositionMs) {
            throw new IllegalArgumentException("Illegal start/end position: "
                    + startPositionMs + " : " + endPositionMs);
        }
        if (metadata != null && metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
            long durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (durationMs != UNKNOWN_TIME && endPositionMs != POSITION_UNKNOWN
                    && endPositionMs > durationMs) {
                throw new IllegalArgumentException("endPositionMs shouldn't be greater than"
                        + " duration in the metdata, endPositionMs=" + endPositionMs
                        + ", durationMs=" + durationMs);
            }
        }
        mMetadata = metadata;
        mStartPositionMs = startPositionMs;
        mEndPositionMs = endPositionMs;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        synchronized (mLock) {
            sb.append("{mMetadata=").append(mMetadata);
            sb.append(", mStartPositionMs=").append(mStartPositionMs);
            sb.append(", mEndPositionMs=").append(mEndPositionMs);
            sb.append('}');
        }
        return sb.toString();
    }

    /**
     * Sets metadata. If the metadata is not {@code null}, its id should be matched with this
     * instance's media id.
     *
     * @param metadata metadata to update
     * @see MediaMetadata#METADATA_KEY_MEDIA_ID
     */
    public void setMetadata(@Nullable MediaMetadata metadata) {
        List<Pair<OnMetadataChangedListener, Executor>> listeners = new ArrayList<>();
        synchronized (mLock) {
            if (mMetadata != null && metadata != null
                    && !TextUtils.equals(getMediaId(), metadata.getString(METADATA_KEY_MEDIA_ID))) {
                Log.d(TAG, "MediaItem2's media ID shouldn't be changed");
                return;
            }
            mMetadata = metadata;
            listeners.addAll(mListeners);
        }

        for (Pair<OnMetadataChangedListener, Executor> pair : listeners) {
            final OnMetadataChangedListener listener = pair.first;
            pair.second.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onMetadataChanged(MediaItem2.this);
                }
            });
        }
    }

    /**
     * Gets the metadata of the media.
     *
     * @return metadata from the session
     */
    public @Nullable MediaMetadata getMetadata() {
        synchronized (mLock) {
            return mMetadata;
        }
    }

    /**
     * Return the position in milliseconds at which the playback will start.
     * @return the position in milliseconds at which the playback will start
     */
    public long getStartPosition() {
        return mStartPositionMs;
    }

    /**
     * Return the position in milliseconds at which the playback will end.
     * {@link #POSITION_UNKNOWN} means ending at the end of source content.
     * @return the position in milliseconds at which the playback will end
     */
    public long getEndPosition() {
        return mEndPositionMs;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mMetadata, 0);
        dest.writeLong(mStartPositionMs);
        dest.writeLong(mEndPositionMs);
    }

    /**
     * Gets the media id for this item. If it's not {@code null}, it's a persistent unique key
     * for the underlying media content.
     *
     * @return media Id from the session
     */
    @Nullable String getMediaId() {
        synchronized (mLock) {
            return mMetadata != null
                    ? mMetadata.getString(METADATA_KEY_MEDIA_ID) : null;
        }
    }

    void addOnMetadataChangedListener(Executor executor, OnMetadataChangedListener listener) {
        synchronized (mLock) {
            for (Pair<OnMetadataChangedListener, Executor> pair : mListeners) {
                if (pair.first == listener) {
                    return;
                }
            }
            mListeners.add(new Pair<>(listener, executor));
        }
    }

    void removeOnMetadataChangedListener(OnMetadataChangedListener listener) {
        synchronized (mLock) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                if (mListeners.get(i).first == listener) {
                    mListeners.remove(i);
                    return;
                }
            }
        }
    }

    /**
     * Builder for {@link MediaItem2}.
     */
    public static class Builder {
        @SuppressWarnings("WeakerAccess") /* synthetic access */
        MediaMetadata mMetadata;
        @SuppressWarnings("WeakerAccess") /* synthetic access */
        long mStartPositionMs = 0;
        @SuppressWarnings("WeakerAccess") /* synthetic access */
        long mEndPositionMs = POSITION_UNKNOWN;

        /**
         * Set the metadata of this instance. {@code null} for unset.
         *
         * @param metadata metadata
         * @return this instance for chaining
         */
        public @NonNull Builder setMetadata(@Nullable MediaMetadata metadata) {
            mMetadata = metadata;
            return this;
        }

        /**
         * Sets the start position in milliseconds at which the playback will start.
         * Any negative number is treated as 0.
         *
         * @param position the start position in milliseconds at which the playback will start
         * @return the same Builder instance.
         */
        public @NonNull Builder setStartPosition(long position) {
            if (position < 0) {
                position = 0;
            }
            mStartPositionMs = position;
            return this;
        }

        /**
         * Sets the end position in milliseconds at which the playback will end.
         * Any negative number is treated as maximum length of the media item.
         *
         * @param position the end position in milliseconds at which the playback will end
         * @return the same Builder instance.
         */
        public @NonNull Builder setEndPosition(long position) {
            if (position < 0) {
                position = POSITION_UNKNOWN;
            }
            mEndPositionMs = position;
            return this;
        }

        /**
         * Build {@link MediaItem2}.
         *
         * @return a new {@link MediaItem2}.
         */
        public @NonNull MediaItem2 build() {
            return new MediaItem2(this);
        }
    }

    interface OnMetadataChangedListener {
        void onMetadataChanged(MediaItem2 item);
    }
}
