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
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.android.settingslib.spa.gallery"

    defaultConfig {
        applicationId = "com.android.settingslib.spa.gallery"
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets {
        sourceSets.getByName("main") {
            kotlin.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            manifest.srcFile("AndroidManifest.xml")
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":spa"))
}
