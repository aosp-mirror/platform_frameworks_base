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
import android.annotation.SystemApi;
import android.os.Bundle;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class with information on a single media item with the metadata information.
 * Media item are application dependent so we cannot guarantee that they contain the right values.
 * <p>
 * When it's sent to a controller or browser, it's anonymized and data descriptor wouldn't be sent.
 * <p>
 * This object isn't a thread safe.
 * @hide
 */
public class MediaItem2 {
    // TODO(jaewan): Keep DataSourceDesc.
    private final int mFlags;
    private MediaMetadata2 mMetadata;

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

    /**
     * Create a new media item.
     *
     * @param metadata metadata with the media id.
     * @param flags The flags for this item.
     */
    public MediaItem2(@NonNull MediaMetadata2 metadata, @Flags int flags) {
        mFlags = flags;
        setMetadata(metadata);
    }

    /**
     * Return this object as a bundle to share between processes.
     *
     * @return a new bundle instance
     */
    public Bundle toBundle() {
        // TODO(jaewan): Fill here when we rebase.
        return new Bundle();
    }

    public String toString() {
        final StringBuilder sb = new StringBuilder("MediaItem2{");
        sb.append("mFlags=").append(mFlags);
        sb.append(", mMetadata=").append(mMetadata);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Gets the flags of the item.
     */
    public @Flags int getFlags() {
        return mFlags;
    }

    /**
     * Returns whether this item is browsable.
     * @see #FLAG_BROWSABLE
     */
    public boolean isBrowsable() {
        return (mFlags & FLAG_BROWSABLE) != 0;
    }

    /**
     * Returns whether this item is playable.
     * @see #FLAG_PLAYABLE
     */
    public boolean isPlayable() {
        return (mFlags & FLAG_PLAYABLE) != 0;
    }

    /**
     * Set a metadata. Metadata shouldn't be null and should have non-empty media id.
     *
     * @param metadata
     */
    public void setMetadata(@NonNull MediaMetadata2 metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata cannot be null");
        }
        if (TextUtils.isEmpty(metadata.getMediaId())) {
            throw new IllegalArgumentException("metadata must have a non-empty media id");
        }
        mMetadata = metadata;
    }

    /**
     * Returns the metadata of the media.
     */
    public @NonNull MediaMetadata2 getMetadata() {
        return mMetadata;
    }

    /**
     * Returns the media id in the {@link MediaMetadata2} for this item.
     * @see MediaMetadata2#METADATA_KEY_MEDIA_ID
     */
    public @Nullable String getMediaId() {
        return mMetadata.getMediaId();
    }
}
