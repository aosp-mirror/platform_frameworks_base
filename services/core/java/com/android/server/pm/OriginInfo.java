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

import java.io.File;

final class OriginInfo {
    /**
     * Location where install is coming from, before it has been
     * copied/renamed into place. This could be a single monolithic APK
     * file, or a cluster directory. This location may be untrusted.
     */
    final File mFile;

    /**
     * Flag indicating that {@link #mFile} has already been staged, meaning downstream users
     * don't need to defensively copy the contents.
     */
    final boolean mStaged;

    /**
     * Flag indicating that {@link #mFile} is an already installed app that is being moved.
     */
    final boolean mExisting;

    final String mResolvedPath;
    final File mResolvedFile;

    static OriginInfo fromNothing() {
        return new OriginInfo(null, false, false);
    }

    static OriginInfo fromExistingFile(File file) {
        return new OriginInfo(file, false, true);
    }

    static OriginInfo fromStagedFile(File file) {
        return new OriginInfo(file, true, false);
    }

    private OriginInfo(File file, boolean staged, boolean existing) {
        mFile = file;
        mStaged = staged;
        mExisting = existing;

        if (file != null) {
            mResolvedPath = file.getAbsolutePath();
            mResolvedFile = file;
        } else {
            mResolvedPath = null;
            mResolvedFile = null;
        }
    }
}
