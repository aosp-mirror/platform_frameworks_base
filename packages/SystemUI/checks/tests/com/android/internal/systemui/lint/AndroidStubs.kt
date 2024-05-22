/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.LibraryReferenceTestFile
import java.io.File

internal val libraryNames =
    arrayOf(
        "androidx.annotation_annotation.jar",
        "dagger2.jar",
        "framework.jar",
        "kotlinx-coroutines-core.jar",
    )

/*
 * This file contains stubs of framework APIs and System UI classes for testing purposes only. The
 * stubs are not used in the lint detectors themselves.
 */
internal val androidStubs =
    libraryNames.map { LibraryReferenceTestFile(File(it).canonicalFile) }.toTypedArray()
