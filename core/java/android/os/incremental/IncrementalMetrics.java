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
}
