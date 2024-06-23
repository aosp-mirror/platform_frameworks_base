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

package com.android.server.pm;

import android.annotation.NonNull;

import java.io.File;

final class CleanUpArgs {
    @NonNull
    private final String mPackageName;
    @NonNull
    private final File mCodeFile;
    @NonNull
    private final String[] mInstructionSets;

    /**
     * Create args that describe an existing installed package. Typically used
     * when cleaning up old installs.
     */
    CleanUpArgs(@NonNull String packageName, @NonNull String codePath,
                @NonNull String[] instructionSets) {
        mPackageName = packageName;
        mCodeFile = new File(codePath);
        mInstructionSets = instructionSets;
    }

    @NonNull
    String getPackageName() {
        return mPackageName;
    }

    @NonNull
    File getCodeFile() {
        return mCodeFile;
    }

    @NonNull
    /** @see PackageSetting#getPath() */
    String getCodePath() {
        return mCodeFile.getAbsolutePath();
    }

    @NonNull
    String[] getInstructionSets() {
        return mInstructionSets;
    }
}
