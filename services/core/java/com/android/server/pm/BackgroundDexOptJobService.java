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

package com.android.server.pm;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * JobService to run background dex optimization. This is a thin wrapper and most logic exits in
 * {@link BackgroundDexOptService}.
 */
public final class BackgroundDexOptJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        return BackgroundDexOptService.getService().onStartJob(this, params);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return BackgroundDexOptService.getService().onStopJob(this, params);
    }
}
