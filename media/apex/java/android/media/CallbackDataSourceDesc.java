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
import android.annotation.TestApi;

/**
 * Structure of data source descriptor for sources using callback.
 *
 * Used by {@link MediaPlayer2#setDataSource}, {@link MediaPlayer2#setNextDataSource} and
 * {@link MediaPlayer2#setNextDataSources} to set data source for playback.
 *
 * <p>Users should use {@link Builder} to create {@link CallbackDataSourceDesc}.
 * @hide
 */
@TestApi
public class CallbackDataSourceDesc extends DataSourceDesc {
    private DataSourceCallback mDataSourceCallback;

    CallbackDataSourceDesc(String mediaId, long startPositionMs, long endPositionMs,
            DataSourceCallback dataSourceCallback) {
        super(mediaId, startPositionMs, endPositionMs);
        mDataSourceCallback = dataSourceCallback;
    }

    /**
     * Return the DataSourceCallback of this data source.
     * @return the DataSourceCallback of this data source
     */
    public @NonNull DataSourceCallback getDataSourceCallback() {
        return mDataSourceCallback;
    }
}
