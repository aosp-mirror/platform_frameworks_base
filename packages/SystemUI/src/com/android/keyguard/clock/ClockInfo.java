/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.graphics.Bitmap;

import java.util.function.Supplier;

/**
 * Metadata about an available clock face.
 */
final class ClockInfo {

    private final String mName;
    private final Supplier<String> mTitle;
    private final String mId;
    private final Supplier<Bitmap> mThumbnail;
    private final Supplier<Bitmap> mPreview;

    private ClockInfo(String name, Supplier<String> title, String id,
            Supplier<Bitmap> thumbnail, Supplier<Bitmap> preview) {
        mName = name;
        mTitle = title;
        mId = id;
        mThumbnail = thumbnail;
        mPreview = preview;
    }

    /**
     * Gets the non-internationalized name for the clock face.
     */
    String getName() {
        return mName;
    }

    /**
     * Gets the name (title) of the clock face to be shown in the picker app.
     */
    String getTitle() {
        return mTitle.get();
    }

    /**
     * Gets the ID of the clock face, used by the picker to set the current selection.
     */
    String getId() {
        return mId;
    }

    /**
     * Gets a thumbnail image of the clock.
     */
    Bitmap getThumbnail() {
        return mThumbnail.get();
    }

    /**
     * Gets a potentially realistic preview image of the clock face.
     */
    Bitmap getPreview() {
        return mPreview.get();
    }

    static Builder builder() {
        return new Builder();
    }

    static class Builder {
        private String mName;
        private Supplier<String> mTitle;
        private String mId;
        private Supplier<Bitmap> mThumbnail;
        private Supplier<Bitmap> mPreview;

        public ClockInfo build() {
            return new ClockInfo(mName, mTitle, mId, mThumbnail, mPreview);
        }

        public Builder setName(String name) {
            mName = name;
            return this;
        }

        public Builder setTitle(Supplier<String> title) {
            mTitle = title;
            return this;
        }

        public Builder setId(String id) {
            mId = id;
            return this;
        }

        public Builder setThumbnail(Supplier<Bitmap> thumbnail) {
            mThumbnail = thumbnail;
            return this;
        }

        public Builder setPreview(Supplier<Bitmap> preview) {
            mPreview = preview;
            return this;
        }
    }
}
