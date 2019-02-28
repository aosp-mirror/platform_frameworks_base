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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Structure of data source descriptor for sources using callback.
 *
 * Used by {@link MediaPlayer2#setDataSource}, {@link MediaPlayer2#setNextDataSource} and
 * {@link MediaPlayer2#setNextDataSources} to set data source for playback.
 *
 * <p>Users should use {@link Builder} to create {@link CallbackDataSourceDesc}.
 *
 */
public class CallbackDataSourceDesc extends DataSourceDesc {
    private DataSourceCallback mDataSourceCallback;

    private CallbackDataSourceDesc() {
    }

    /**
     * Return the DataSourceCallback of this data source.
     * @return the DataSourceCallback of this data source
     */
    public @NonNull DataSourceCallback getDataSourceCallback() {
        return mDataSourceCallback;
    }

    /**
     * Builder class for {@link CallbackDataSourceDesc} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link CallbackDataSourceDesc} to be used by a {@link MediaPlayer2} instance:
     *
     * <pre class="prettyprint">
     * CallbackDataSourceDesc newDSD = new CallbackDataSourceDesc.Builder()
     *         .setDataSource(media2DataSource)
     *         .setStartPosition(1000)
     *         .setEndPosition(15000)
     *         .build();
     * mediaplayer2.setDataSourceDesc(newDSD);
     * </pre>
     */
    public static class Builder extends BuilderBase<Builder> {
        private DataSourceCallback mDataSourceCallback;

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
            super();
        }

        /**
         * Constructs a new Builder from a given {@link CallbackDataSourceDesc} instance
         * @param dsd the {@link CallbackDataSourceDesc} object whose data will be reused
         * in the new Builder.
         */
        public Builder(@Nullable CallbackDataSourceDesc dsd) {
            super(dsd);
            if (dsd == null) {
                return;  // use default
            }
            mDataSourceCallback = dsd.mDataSourceCallback;
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link CallbackDataSourceDesc} object. <code>IllegalStateException</code> will be
         * thrown if there is conflict between fields.
         *
         * @return a new {@link CallbackDataSourceDesc} object
         */
        public @NonNull CallbackDataSourceDesc build() {
            if (mDataSourceCallback == null) {
                throw new IllegalStateException(
                        "DataSourceCallback should not be null");
            }

            CallbackDataSourceDesc dsd = new CallbackDataSourceDesc();
            super.build(dsd);
            dsd.mDataSourceCallback = mDataSourceCallback;

            return dsd;
        }

        /**
         * Sets the data source (DataSourceCallback) to use.
         *
         * @param dscb the DataSourceCallback for the media to play
         * @return the same Builder instance.
         * @throws NullPointerException if dscb is null.
         */
        public @NonNull Builder setDataSource(@NonNull DataSourceCallback dscb) {
            Media2Utils.checkArgument(dscb != null, "data source cannot be null.");
            mDataSourceCallback = dscb;
            return this;
        }
    }
}
