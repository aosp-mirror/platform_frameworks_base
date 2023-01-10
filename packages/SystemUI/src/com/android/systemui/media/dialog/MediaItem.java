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

import androidx.annotation.IntDef;

import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;

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

    public MediaItem() {
        this.mMediaDeviceOptional = Optional.empty();
        this.mTitle = null;
        this.mMediaItemType = MediaItemType.TYPE_PAIR_NEW_DEVICE;
    }

    public MediaItem(String title, int mediaItemType) {
        this.mMediaDeviceOptional = Optional.empty();
        this.mTitle = title;
        this.mMediaItemType = mediaItemType;
    }

    public MediaItem(MediaDevice mediaDevice) {
        this.mMediaDeviceOptional = Optional.of(mediaDevice);
        this.mTitle = mediaDevice.getName();
        this.mMediaItemType = MediaItemType.TYPE_DEVICE;
    }

    public Optional<MediaDevice> getMediaDevice() {
        return mMediaDeviceOptional;
    }

    /**
     * Get layout id based on media item Type.
     */
    public static int getMediaLayoutId(int mediaItemType) {
        switch (mediaItemType) {
            case MediaItemType.TYPE_DEVICE:
            case MediaItemType.TYPE_PAIR_NEW_DEVICE:
                return R.layout.media_output_list_item_advanced;
            case MediaItemType.TYPE_GROUP_DIVIDER:
            default:
                return R.layout.media_output_list_group_divider;
        }
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
