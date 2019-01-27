/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.rollback;

import android.content.rollback.PackageRollbackInfo;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Information about a rollback available for a set of atomically installed
 * packages.
 */
class RollbackData {
    /**
     * A unique identifier for this rollback.
     */
    public final int rollbackId;

    /**
     * The per-package rollback information.
     */
    public final List<PackageRollbackInfo> packages = new ArrayList<>();

    /**
     * The directory where the rollback data is stored.
     */
    public final File backupDir;

    /**
     * The time when the upgrade occurred, for purposes of expiring
     * rollback data.
     */
    public Instant timestamp;

    /**
     * Whether this Rollback is currently in progress. This field is true from the point
     * we commit a {@code PackageInstaller} session containing these packages to the point the
     * {@code PackageInstaller} calls into the {@code onFinished} callback.
     */
    // NOTE: All accesses to this field are from the RollbackManager handler thread.
    public boolean inProgress = false;

    RollbackData(int rollbackId, File backupDir) {
        this.rollbackId = rollbackId;
        this.backupDir = backupDir;
    }
}
