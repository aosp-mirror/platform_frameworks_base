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

package com.android.server.wm;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@IntDef({
        WindowTracingLogFrequency.FRAME,
        WindowTracingLogFrequency.TRANSACTION,
        WindowTracingLogFrequency.SINGLE_DUMP,
})
@Retention(RetentionPolicy.SOURCE)
@interface WindowTracingLogFrequency {
    /**
     * Trace state snapshots when a frame is committed.
     */
    int FRAME = 0;
    /**
     * Trace state snapshots when a transaction is committed.
     */
    int TRANSACTION = 1;
    /**
     * Trace single state snapshots when the Perfetto data source is started.
     */
    int SINGLE_DUMP = 2;
}
