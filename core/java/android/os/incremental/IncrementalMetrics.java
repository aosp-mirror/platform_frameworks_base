/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.os.incremental;

import android.annotation.NonNull;
import android.os.PersistableBundle;

/**
 * Provides methods to access metrics about an app installed via Incremental
 * @hide
 */
public class IncrementalMetrics {
    @NonNull private final PersistableBundle mData;

    public IncrementalMetrics(@NonNull PersistableBundle data) {
        mData = data;
    }

    /**
     * @return Milliseconds between now and when the oldest pending read happened
     */
    public long getMillisSinceOldestPendingRead() {
        return mData.getLong(IIncrementalService.METRICS_MILLIS_SINCE_OLDEST_PENDING_READ, -1);
    }

    /**
     * @return Whether read logs are enabled
     */
    public boolean getReadLogsEnabled() {
        return mData.getBoolean(IIncrementalService.METRICS_READ_LOGS_ENABLED, false);
    }

    /**
     * @return storage health status code. @see android.os.incremental.IStorageHealthListener
     */
    public int getStorageHealthStatusCode() {
        return mData.getInt(IIncrementalService.METRICS_STORAGE_HEALTH_STATUS_CODE, -1);
    }

    /**
     * @return data loader status code. @see android.content.pm.IDataLoaderStatusListener
     */
    public int getDataLoaderStatusCode() {
        return mData.getInt(IIncrementalService.METRICS_DATA_LOADER_STATUS_CODE, -1);
    }

    /**
     * @return duration since last data loader binding attempt
     */
    public long getMillisSinceLastDataLoaderBind() {
        return mData.getLong(IIncrementalService.METRICS_MILLIS_SINCE_LAST_DATA_LOADER_BIND, -1);
    }

    /**
     * @return delay in milliseconds to retry data loader binding
     */
    public long getDataLoaderBindDelayMillis() {
        return mData.getLong(IIncrementalService.METRICS_DATA_LOADER_BIND_DELAY_MILLIS, -1);
    }

    /**
     * @return total count of delayed reads caused by pending reads
     */
    public int getTotalDelayedReads() {
        return mData.getInt(IIncrementalService.METRICS_TOTAL_DELAYED_READS, -1);
    }

    /**
     * @return total count of failed reads
     */
    public int getTotalFailedReads() {
        return mData.getInt(IIncrementalService.METRICS_TOTAL_FAILED_READS, -1);
    }

    /**
     * @return total duration in milliseconds of delayed reads
     */
    public long getTotalDelayedReadsDurationMillis() {
        return mData.getLong(IIncrementalService.METRICS_TOTAL_DELAYED_READS_MILLIS, -1);
    }

    /**
     * @return the uid of the last read error
     */
    public int getLastReadErrorUid() {
        return mData.getInt(IIncrementalService.METRICS_LAST_READ_ERROR_UID, -1);
    }

    /**
     * @return duration in milliseconds since the last read error
     */
    public long getMillisSinceLastReadError() {
        return mData.getLong(IIncrementalService.METRICS_MILLIS_SINCE_LAST_READ_ERROR, -1);
    }

    /**
     * @return the error number of the last read error
     */
    public int getLastReadErrorNumber() {
        return mData.getInt(IIncrementalService.METRICS_LAST_READ_ERROR_NUMBER, -1);
    }
}
