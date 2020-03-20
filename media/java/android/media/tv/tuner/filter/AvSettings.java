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
import android.media.tv.tuner.TunerUtils;

/**
 * Filter Settings for a Video and Audio.
 *
 * @hide
 */
@SystemApi
public class AvSettings extends Settings {
    private final boolean mIsPassthrough;

    private AvSettings(int mainType, boolean isAudio, boolean isPassthrough) {
        super(TunerUtils.getFilterSubtype(
                mainType,
                isAudio
                        ? Filter.SUBTYPE_AUDIO
                        : Filter.SUBTYPE_VIDEO));
        mIsPassthrough = isPassthrough;
    }

    /**
     * Checks whether it's passthrough.
     */
    public boolean isPassthrough() {
        return mIsPassthrough;
    }

    /**
     * Creates a builder for {@link AvSettings}.
     *
     * @param mainType the filter main type.
     * @param isAudio {@code true} if it's audio settings; {@code false} if it's video settings.
     */
    @NonNull
    public static Builder builder(@Filter.Type int mainType, boolean isAudio) {
        return new Builder(mainType, isAudio);
    }

    /**
     * Builder for {@link AvSettings}.
     */
    public static class Builder {
        private final int mMainType;
        private final boolean mIsAudio;
        private boolean mIsPassthrough;

        private Builder(int mainType, boolean isAudio) {
            mMainType = mainType;
            mIsAudio = isAudio;
        }

        /**
         * Sets whether it's passthrough.
         */
        @NonNull
        public Builder setPassthrough(boolean isPassthrough) {
            mIsPassthrough = isPassthrough;
            return this;
        }

        /**
         * Builds a {@link AvSettings} object.
         */
        @NonNull
        public AvSettings build() {
            return new AvSettings(mMainType, mIsAudio, mIsPassthrough);
        }
    }
}
