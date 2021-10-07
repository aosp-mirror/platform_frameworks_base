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

package com.android.server.pm.dex;

import java.util.List;

/**
 * Holds package information relevant to ART use cases.
 */
public class ArtPackageInfo {
    private final String mPackageName;
    private final List<String> mInstructionSets;
    private final List<String> mCodePaths;
    // TODO: This should be computed on the fly in PackageDexOptimizer / DexManager, but the
    // logic is too complicated to do it in a single re-factoring.
    private final String mOatDir;

    public ArtPackageInfo(
            String packageName,
            List<String> instructionSets,
            List<String> codePaths,
            String oatDir) {
        mPackageName = packageName;
        mInstructionSets = instructionSets;
        mCodePaths = codePaths;
        mOatDir = oatDir;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public List<String> getInstructionSets() {
        return mInstructionSets;
    }

    public List<String> getCodePaths() {
        return mCodePaths;
    }

    public String getOatDir() {
        return mOatDir;
    }
}
