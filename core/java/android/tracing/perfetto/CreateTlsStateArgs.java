/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.tracing.perfetto;

import android.annotation.Nullable;

/**
 * @hide
 * @param <DataSourceInstanceType> The type of datasource instance this state applied to.
 */
public class CreateTlsStateArgs<DataSourceInstanceType extends DataSourceInstance> {
    private final DataSource<DataSourceInstanceType, Object, Object> mDataSource;
    private final int mInstanceIndex;

    CreateTlsStateArgs(DataSource dataSource, int instanceIndex) {
        this.mDataSource = dataSource;
        this.mInstanceIndex = instanceIndex;
    }

    /**
     * Gets the datasource instance for this state with a lock.
     * releaseDataSourceInstanceLocked must be called before this can be called again.
     * @return The data source instance for this state.
     *         Null if the datasource instance no longer exists.
     */
    public @Nullable DataSourceInstanceType getDataSourceInstanceLocked() {
        return mDataSource.getDataSourceInstanceLocked(mInstanceIndex);
    }
}
