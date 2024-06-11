/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.settingslib.media.MediaDevice;
import com.android.systemui.res.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Optional;

/**
 * MediaItem represents an item in OutputSwitcher list (could be a MediaDevice, group divider or
 * connect new device item).
 */
public class MediaItem {
    private final Optional<MediaDevice> mMediaDeviceOptional;
    private final String mTitle;
    @MediaItemType
    private final int mMediaItemType;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MediaItemType.TYPE_DEVICE,
            MediaItemType.TYPE_GROUP_DIVIDER,
            MediaItemType.TYPE_PAIR_NEW_DEVICE})
    public @interface MediaItemType {
        int TYPE_DEVICE = 0;
        int TYPE_GROUP_DIVIDER = 1;
        int TYPE_PAIR_NEW_DEVICE = 2;
    }

    /**
     * Returns a new {@link MediaItemType#TYPE_DEVICE} {@link MediaItem} with its {@link
     * #getMediaDevice() media device} set to {@code device} and its title set to {@code device}'s
     * name.
     */
    public static MediaItem createDeviceMediaItem(@NonNull MediaDevice device) {
        return new MediaItem(device, device.getName(), MediaItemType.TYPE_DEVICE);
    }

    /**
     * Returns a new {@link MediaItemType#TYPE_PAIR_NEW_DEVICE} {@link MediaItem} with both {@link
     * #getMediaDevice() media device} and title set to {@code null}.
     */
    public static MediaItem createPairNewDeviceMediaItem() {
        return new MediaItem(
                /* device */ null, /* title */ null, MediaItemType.TYPE_PAIR_NEW_DEVICE);
    }

    /**
     * Returns a new {@link MediaItemType#TYPE_GROUP_DIVIDER} {@link MediaItem} with the specified
     * title and a {@code null} {@link #getMediaDevice() media device}.
     */
    public static MediaItem createGroupDividerMediaItem(@Nullable String title) {
        return new MediaItem(/* device */ null, title, MediaItemType.TYPE_GROUP_DIVIDER);
    }

    private MediaItem(
            @Nullable MediaDevice device, @Nullable String title, @MediaItemType int type) {
        this.mMediaDeviceOptional = Optional.ofNullable(device);
        this.mTitle = title;
        this.mMediaItemType = type;
    }

    public Optional<MediaDevice> getMediaDevice() {
        return mMediaDeviceOptional;
    }

    /** Get layout id based on media item Type. */
    public static int getMediaLayoutId(@MediaItemType int mediaItemType) {
        return switch (mediaItemType) {
            case MediaItemType.TYPE_DEVICE, MediaItemType.TYPE_PAIR_NEW_DEVICE ->
                    R.layout.media_output_list_item_advanced;
            default -> R.layout.media_output_list_group_divider;
        };
    }

    public String getTitle() {
        return mTitle;
    }

    public boolean isMutingExpectedDevice() {
        return mMediaDeviceOptional.isPresent()
                && mMediaDeviceOptional.get().isMutingExpectedDevice();
    }

    public int getMediaItemType() {
        return mMediaItemType;
    }
}
