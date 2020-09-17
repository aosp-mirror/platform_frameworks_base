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
import android.annotation.Nullable;
import android.annotation.SystemApi;

/**
 * Filter configuration for a TS filter.
 *
 * @hide
 */
@SystemApi
public final class TsFilterConfiguration extends FilterConfiguration {
    private final int mTpid;

    private TsFilterConfiguration(Settings settings, int tpid) {
        super(settings);
        mTpid = tpid;
    }

    @Override
    public int getType() {
        return Filter.TYPE_TS;
    }

    /**
     * Gets Tag Protocol ID.
     */
    public int getTpid() {
        return mTpid;
    }

    /**
     * Creates a builder for {@link TsFilterConfiguration}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link TsFilterConfiguration}.
     */
    public static final class Builder {
        private int mTpid = 0;
        private Settings mSettings;

        private Builder() {
        }

        /**
         * Sets Tag Protocol ID.
         *
         * <p>Default value is 0.
         *
         * @param tpid the Tag Protocol ID.
         */
        @NonNull
        public Builder setTpid(int tpid) {
            mTpid = tpid;
            return this;
        }

        /**
         * Sets filter settings.
         */
        @NonNull
        public Builder setSettings(@Nullable Settings settings) {
            mSettings = settings;
            return this;
        }

        /**
         * Builds a {@link TsFilterConfiguration} object.
         */
        @NonNull
        public TsFilterConfiguration build() {
            return new TsFilterConfiguration(mSettings, mTpid);
        }
    }
}
