/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.internal.annotations.VisibleForTesting;

/**
 * @hide
 */
public abstract class DataSourceInstance implements AutoCloseable {
    private final DataSource mDataSource;
    private final int mInstanceIndex;

    public DataSourceInstance(DataSource dataSource, int instanceIndex) {
        this.mDataSource = dataSource;
        this.mInstanceIndex = instanceIndex;
    }

    /**
     * Executed when the tracing instance starts running.
     * <p>
     * NOTE: This callback executes on the Perfetto internal thread and is blocking.
     *       Anything that is run in this callback should execute quickly.
     *
     * @param args Start arguments.
     */
    protected void onStart(StartCallbackArguments args) {}

    /**
     * Executed when a flush is triggered.
     * <p>
     * NOTE: This callback executes on the Perfetto internal thread and is blocking.
     *       Anything that is run in this callback should execute quickly.
     * @param args Flush arguments.
     */
    protected void onFlush(FlushCallbackArguments args) {}

    /**
     * Executed when the tracing instance is stopped.
     * <p>
     * NOTE: This callback executes on the Perfetto internal thread and is blocking.
     *       Anything that is run in this callback should execute quickly.
     * @param args Stop arguments.
     */
    protected void onStop(StopCallbackArguments args) {}

    @Override
    public final void close() {
        this.release();
    }

    /**
     * Release the lock on the datasource once you are finished using it.
     * Only required to be called when instance was retrieved with
     * `DataSource#getDataSourceInstanceLocked`.
     */
    @VisibleForTesting
    public void release() {
        mDataSource.releaseDataSourceInstance(mInstanceIndex);
    }

    public final int getInstanceIndex() {
        return mInstanceIndex;
    }
}
