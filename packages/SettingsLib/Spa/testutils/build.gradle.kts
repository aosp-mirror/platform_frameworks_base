/*
 * Copyright 2022 The Android Open Source Project
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

plugins {
    id(libs.plugins.android.library.get().pluginId)
    id(libs.plugins.kotlin.android.get().pluginId)
}

val jetpackComposeVersion: String? by extra

android {
    namespace = "com.android.settingslib.spa.testutils"

    sourceSets {
        sourceSets.getByName("main") {
            java.setSrcDirs(listOf("src"))
            manifest.srcFile("AndroidManifest.xml")
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":spa"))

    api("androidx.arch.core:core-testing:2.2.0-alpha01")
    api("androidx.compose.ui:ui-test-junit4:$jetpackComposeVersion")
    api(libs.truth)
    api(libs.dexmaker.mockito)
    debugApi("androidx.compose.ui:ui-test-manifest:$jetpackComposeVersion")
}
