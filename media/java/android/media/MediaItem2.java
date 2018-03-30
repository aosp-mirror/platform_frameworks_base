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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.update.ApiLoader;
import android.media.update.MediaItem2Provider;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 * A class with information on a single media item with the metadata information.
 * Media item are application dependent so we cannot guarantee that they contain the right values.
 * <p>
 * When it's sent to a controller or browser, it's anonymized and data descriptor wouldn't be sent.
 * <p>
 * This object isn't a thread safe.
 */
public class MediaItem2 {
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
     * The id of this item may be passed to
     * {@link MediaController2#playFromMediaId(String, Bundle)}
     */
    public static final int FLAG_PLAYABLE = 1 << 1;

    private final MediaItem2Provider mProvider;

    /**
     * Create a new media item
     * @hide
     */
    public MediaItem2(MediaItem2Provider provider) {
        mProvider = provider;
    }

    /**
     * @hide
     */
    public MediaItem2Provider getProvider() {
        return mProvider;
    }

    /**
     * Return this object as a bundle to share between processes.
     *
     * @return a new bundle instance
     */
    public Bundle toBundle() {
        return mProvider.toBundle_impl();
    }

    public static MediaItem2 fromBundle(Bundle bundle) {
        return ApiLoader.getProvider().fromBundle_MediaItem2(bundle);
    }

    public String toString() {
        return mProvider.toString_impl();
    }

    /**
     * Gets the flags of the item.
     */
    public @Flags int getFlags() {
        return mProvider.getFlags_impl();
    }

    /**
     * Returns whether this item is browsable.
     * @see #FLAG_BROWSABLE
     */
    public boolean isBrowsable() {
        return mProvider.isBrowsable_impl();
    }

    /**
     * Returns whether this item is playable.
     * @see #FLAG_PLAYABLE
     */
    public boolean isPlayable() {
        return mProvider.isPlayable_impl();
    }

    /**
     * Set a metadata. If the metadata is not null, its id should be matched with this instance's
     * media id.
     *
     * @param metadata metadata to update
     */
    public void setMetadata(@Nullable MediaMetadata2 metadata) {
        mProvider.setMetadata_impl(metadata);
    }

    /**
     * Returns the metadata of the media.
     */
    public @Nullable MediaMetadata2 getMetadata() {
        return mProvider.getMetadata_impl();
    }

    /**
     * Returns the media id for this item.
     */
    public @NonNull String getMediaId() {
        return mProvider.getMediaId_impl();
    }

    /**
     * Return the {@link DataSourceDesc}
     * <p>
     * Can be {@code null} if the MediaItem2 came from another process and anonymized
     *
     * @return data source descriptor
     */
    public @Nullable DataSourceDesc getDataSourceDesc() {
        return mProvider.getDataSourceDesc_impl();
    }

    @Override
    public boolean equals(Object obj) {
        return mProvider.equals_impl(obj);
    }

    /**
     * Build {@link MediaItem2}
     */
    public static final class Builder {
        private final MediaItem2Provider.BuilderProvider mProvider;

        /**
         * Constructor for {@link Builder}
         *
         * @param flags
         */
        public Builder(@Flags int flags) {
            mProvider = ApiLoader.getProvider().createMediaItem2Builder(this, flags);
        }

        /**
         * Set the media id of this instance. {@code null} for unset.
         * <p>
         * Media id is used to identify a media contents between session and controller.
         * <p>
         * If the metadata is set with the {@link #setMetadata(MediaMetadata2)} and it has
         * media id, id from {@link #setMediaId(String)} will be ignored and metadata's id will be
         * used instead. If the id isn't set neither by {@link #setMediaId(String)} nor
         * {@link #setMetadata(MediaMetadata2)}, id will be automatically generated.
         *
         * @param mediaId media id
         * @return this instance for chaining
         */
        public Builder setMediaId(@Nullable String mediaId) {
            return mProvider.setMediaId_impl(mediaId);
        }

        /**
         * Set the metadata of this instance. {@code null} for unset.
         * <p>
         * If the metadata is set with the {@link #setMetadata(MediaMetadata2)} and it has
         * media id, id from {@link #setMediaId(String)} will be ignored and metadata's id will be
         * used instead. If the id isn't set neither by {@link #setMediaId(String)} nor
         * {@link #setMetadata(MediaMetadata2)}, id will be automatically generated.
         *
         * @param metadata metadata
         * @return this instance for chaining
         */
        public Builder setMetadata(@Nullable MediaMetadata2 metadata) {
            return mProvider.setMetadata_impl(metadata);
        }

        /**
         * Set the data source descriptor for this instance. {@code null} for unset.
         *
         * @param dataSourceDesc data source descriptor
         * @return this instance for chaining
         */
        public Builder setDataSourceDesc(@Nullable DataSourceDesc dataSourceDesc) {
            return mProvider.setDataSourceDesc_impl(dataSourceDesc);
        }

        /**
         * Build {@link MediaItem2}.
         *
         * @return a new {@link MediaItem2}.
         */
        public MediaItem2 build() {
            return mProvider.build_impl();
        }
    }
}
