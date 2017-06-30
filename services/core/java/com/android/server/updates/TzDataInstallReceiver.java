/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.updates;

import com.android.timezone.distro.TimeZoneDistro;
import com.android.timezone.distro.installer.TimeZoneDistroInstaller;

import android.util.Slog;

import java.io.File;
import java.io.IOException;

/**
 * An install receiver responsible for installing timezone data updates.
 */
public class TzDataInstallReceiver extends ConfigUpdateInstallReceiver {

    private static final String TAG = "TZDataInstallReceiver";

    private static final File SYSTEM_TZ_DATA_FILE = new File("/system/usr/share/zoneinfo/tzdata");
    private static final File TZ_DATA_DIR = new File("/data/misc/zoneinfo");
    private static final String UPDATE_DIR_NAME = TZ_DATA_DIR.getPath() + "/updates/";
    private static final String UPDATE_METADATA_DIR_NAME = "metadata/";
    private static final String UPDATE_VERSION_FILE_NAME = "version";
    private static final String UPDATE_CONTENT_FILE_NAME = "tzdata_distro.zip";

    private final TimeZoneDistroInstaller installer;

    public TzDataInstallReceiver() {
        super(UPDATE_DIR_NAME, UPDATE_CONTENT_FILE_NAME, UPDATE_METADATA_DIR_NAME,
                UPDATE_VERSION_FILE_NAME);
        installer = new TimeZoneDistroInstaller(TAG, SYSTEM_TZ_DATA_FILE, TZ_DATA_DIR);
    }

    @Override
    protected void install(byte[] content, int version) throws IOException {
        TimeZoneDistro distro = new TimeZoneDistro(content);
        boolean valid = installer.install(distro);
        Slog.i(TAG, "Timezone data install valid for this device: " + valid);
        // Even if !valid, we call super.install(). Only in the event of an exception should we
        // not. If we didn't do this we could attempt to install repeatedly.
        super.install(content, version);
    }
}
