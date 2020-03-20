/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Bits Settings for Section Filters.
 *
 * @hide
 */
@SystemApi
public class SectionSettingsWithSectionBits extends SectionSettings {
    private final byte[] mFilter;
    private final byte[] mMask;
    private final byte[] mMode;


    private SectionSettingsWithSectionBits(int mainType, boolean isCheckCrc, boolean isRepeat,
            boolean isRaw, byte[] filter, byte[] mask, byte[] mode) {
        super(mainType, isCheckCrc, isRepeat, isRaw);
        mFilter = filter;
        mMask = mask;
        mMode = mode;
    }

    /**
     * Gets the bytes configured for Section Filter
     */
    @NonNull
    public byte[] getFilterBytes() {
        return mFilter;
    }
    /**
     * Gets bit mask.
     *
     * <p>The bits in the bytes are used for filtering.
     */
    @NonNull
    public byte[] getMask() {
        return mMask;
    }
    /**
     * Gets mode.
     *
     * <p>Do positive match at the bit position of the configured bytes when the bit at same
     * position of the mode is 0.
     * <p>Do negative match at the bit position of the configured bytes when the bit at same
     * position of the mode is 1.
     */
    @NonNull
    public byte[] getMode() {
        return mMode;
    }

    /**
     * Creates a builder for {@link SectionSettingsWithSectionBits}.
     *
     * @param mainType the filter main type.
     */
    @NonNull
    public static Builder builder(@Filter.Type int mainType) {
        return new Builder(mainType);
    }

    /**
     * Builder for {@link SectionSettingsWithSectionBits}.
     */
    public static class Builder extends SectionSettings.Builder<Builder> {
        private byte[] mFilter = {};
        private byte[] mMask = {};
        private byte[] mMode = {};

        private Builder(int mainType) {
            super(mainType);
        }

        /**
         * Sets filter bytes.
         *
         * <p>Default value is an empty byte array.
         */
        @NonNull
        public Builder setFilter(@NonNull byte[] filter) {
            mFilter = filter;
            return this;
        }
        /**
         * Sets bit mask.
         *
         * <p>Default value is an empty byte array.
         */
        @NonNull
        public Builder setMask(@NonNull byte[] mask) {
            mMask = mask;
            return this;
        }
        /**
         * Sets mode.
         *
         * <p>Default value is an empty byte array.
         */
        @NonNull
        public Builder setMode(@NonNull byte[] mode) {
            mMode = mode;
            return this;
        }

        /**
         * Builds a {@link SectionSettingsWithSectionBits} object.
         */
        @NonNull
        public SectionSettingsWithSectionBits build() {
            return new SectionSettingsWithSectionBits(
                    mMainType, mCrcEnabled, mIsRepeat, mIsRaw, mFilter, mMask, mMode);
        }

        @Override
        Builder self() {
            return this;
        }
    }
}
