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

package com.android.internal.protolog;

import android.annotation.NonNull;

public interface ProtoLogDataSourceBuilder {
    /**
     * Builder method for the DataSource the PerfettoProtoLogImpl is going to us.
     * @param onStart The onStart callback that should be used by the created datasource.
     * @param onFlush The onFlush callback that should be used by the created datasource.
     * @param onStop The onStop callback that should be used by the created datasource.
     * @return A new DataSource that uses the provided callbacks.
     */
    @NonNull
    ProtoLogDataSource build(
            @NonNull ProtoLogDataSource.Instance.TracingInstanceStartCallback onStart,
            @NonNull Runnable onFlush,
            @NonNull ProtoLogDataSource.Instance.TracingInstanceStopCallback onStop);
}
