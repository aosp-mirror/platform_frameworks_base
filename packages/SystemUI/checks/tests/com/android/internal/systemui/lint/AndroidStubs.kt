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

import com.android.annotations.NonNull
import com.android.tools.lint.checks.infrastructure.LintDetectorTest.java
import com.android.tools.lint.checks.infrastructure.TestFiles.LibraryReferenceTestFile
import java.io.File
import org.intellij.lang.annotations.Language

@Suppress("UnstableApiUsage")
@NonNull
private fun indentedJava(@NonNull @Language("JAVA") source: String) = java(source).indented()

/*
 * This file contains stubs of framework APIs and System UI classes for testing purposes only. The
 * stubs are not used in the lint detectors themselves.
 */
internal val androidStubs =
    arrayOf(
        LibraryReferenceTestFile(File("framework.jar").canonicalFile),
        LibraryReferenceTestFile(File("androidx.annotation_annotation.jar").canonicalFile),
        indentedJava(
            """
package com.android.systemui.settings;
import android.content.pm.UserInfo;

public interface UserTracker {
    int getUserId();
    UserInfo getUserInfo();
}
"""
        ),
    )
