/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.platform.test.ravenwood.ravenhelper.psi

import com.android.tools.lint.UastEnvironment

// PSI is a library to parse Java/Kotlin source files, which is part of JetBrains' IntelliJ/
// Android Studio, and other IDEs.
//
// PSI is normally used by IntelliJ's plugins, and as such, there isn't really a good documentation
// on how to use it from a standalone program. However, fortunately, Android Studio's Lint
// and Metalava both use PSI. Metalava reuses some of the APIs exposed by Lint. We also use the
// same Lint APIs used by Metalava here.
//
// Some code pointers around the relevant projects:
//
// - We stole code from Metalava, but the recent version of Metalava is too complicated,
//   and hard to understand. Older Metalava, such as this one:
//   https://android.git.corp.google.com/platform/tools/metalava/+/refs/heads/android13-dev
//   is easier to understand.
//
// - PSI is source code is available in IntelliJ's code base:
//   https://github.com/JetBrains/intellij-community.git
//
// - Lint is in Android studio.
//  https://android.googlesource.com/platform/tools/base/+/studio-master-dev/source.md


/**
 * Create [UastEnvironment] enough to parse Java source files.
 */
fun createUastEnvironment(): UastEnvironment {
    val config = UastEnvironment.Configuration.create(
        enableKotlinScripting = false,
        useFirUast = false,
    )

    config.javaLanguageLevel = com.intellij.pom.java.LanguageLevel.JDK_21

    // The following code exists in Metalava, but we don't seem to need it.
    // We may need to when we need to support kotlin.
//    config.kotlinLanguageLevel = kotlinLanguageLevel
//    config.addSourceRoots(listOf(File(root)))
//    config.addClasspathRoots(classpath.map { it.absoluteFile })
//    options.jdkHome?.let {
//        if (options.isJdkModular(it)) {
//            config.kotlinCompilerConfig.put(JVMConfigurationKeys.JDK_HOME, it)
//            config.kotlinCompilerConfig.put(JVMConfigurationKeys.NO_JDK, false)
//        }
//    }

    return UastEnvironment.create(config)
}
