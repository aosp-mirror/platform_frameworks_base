/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.providers.settings;

import static com.android.providers.settings.SettingsProvider.SETTINGS_PROVIDER_JOBS_NS;
import static com.android.providers.settings.SettingsProvider.TABLE_CONFIG;
import static com.android.providers.settings.SettingsProvider.TABLE_GLOBAL;
import static com.android.providers.settings.SettingsProvider.TABLE_SECURE;
import static com.android.providers.settings.SettingsProvider.TABLE_SSAID;
import static com.android.providers.settings.SettingsProvider.TABLE_SYSTEM;
import static com.android.providers.settings.SettingsProvider.WRITE_FALLBACK_SETTINGS_FILES_JOB_ID;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.ArrayList;
import java.util.List;

/**
 * JobService to make a copy of a list of files, given their paths.
 */
public class WriteFallbackSettingsFilesJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!SETTINGS_PROVIDER_JOBS_NS.equals(params.getJobNamespace())
                || params.getJobId() != WRITE_FALLBACK_SETTINGS_FILES_JOB_ID) {
            return false;
        }
        final List<String> settingsFiles = new ArrayList<>();
        settingsFiles.add(params.getExtras().getString(TABLE_GLOBAL, ""));
        settingsFiles.add(params.getExtras().getString(TABLE_SYSTEM, ""));
        settingsFiles.add(params.getExtras().getString(TABLE_SECURE, ""));
        settingsFiles.add(params.getExtras().getString(TABLE_SSAID, ""));
        settingsFiles.add(params.getExtras().getString(TABLE_CONFIG, ""));
        SettingsProvider.writeFallBackSettingsFiles(settingsFiles);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

}
