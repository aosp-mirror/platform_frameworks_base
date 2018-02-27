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
import android.content.Context;
import android.media.update.ApiLoader;
import android.media.update.MediaItem2Provider;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
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
     * Create a new media item.
     *
     * @param mediaId id of this item. It must be unique whithin this app
     * @param metadata metadata with the media id.
     * @param flags The flags for this item.
     * @hide
     */
    // TODO(jaewan): Remove this
    public MediaItem2(@NonNull Context context, @NonNull String mediaId,
            @NonNull DataSourceDesc dsd, @Nullable MediaMetadata2 metadata,
            @Flags int flags) {
        mProvider = ApiLoader.getProvider(context).createMediaItem2(
                context, this, mediaId, dsd, metadata, flags);
    }

    /**
     * Create a new media item
     * @hide
     */
    public MediaItem2(MediaItem2Provider provider) {
        mProvider = provider;
    }

    /**
     * Return this object as a bundle to share between processes.
     *
     * @return a new bundle instance
     */
    public Bundle toBundle() {
        // TODO(jaewan): Fill here when we rebase.
        return mProvider.toBundle_impl();
    }

    public static MediaItem2 fromBundle(Context context, Bundle bundle) {
        return ApiLoader.getProvider(context).fromBundle_MediaItem2(context, bundle);
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

    /**
     * Build {@link MediaItem2}
     */
    // TODO(jaewan): Move it to updatable
    public static final class Builder {
        private Context mContext;
        private @Flags int mFlags;
        private String mMediaId;
        private MediaMetadata2 mMetadata;
        private DataSourceDesc mDataSourceDesc;

        /**
         * Constructor for {@link Builder}
         *
         * @param context
         * @param flags
         */
        public Builder(@NonNull Context context, @Flags int flags) {
            mContext = context;
            mFlags = flags;
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
            mMediaId = mediaId;
            return this;
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
            mMetadata = metadata;
            return this;
        }

        /**
         * Set the data source descriptor for this instance. {@code null} for unset.
         *
         * @param dataSourceDesc data source descriptor
         * @return this instance for chaining
         */
        public Builder setDataSourceDesc(@Nullable DataSourceDesc dataSourceDesc) {
            mDataSourceDesc = dataSourceDesc;
            return this;
        }

        /**
         * Build {@link MediaItem2}.
         *
         * @return a new {@link MediaItem2}.
         */
        public MediaItem2 build() {
            String id = (mMetadata != null)
                    ? mMetadata.getString(MediaMetadata2.METADATA_KEY_MEDIA_ID) : null;
            if (id == null) {
                //  TODO(jaewan): Double check if its sufficient (e.g. Use UUID instead?)
                id = (mMediaId != null) ? mMediaId : toString();
            }
            return new MediaItem2(mContext, id, mDataSourceDesc, mMetadata, mFlags);
        }
    }
}
