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

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    jacoco
}

val jetpackComposeVersion: String? by extra

android {
    namespace = "com.android.settingslib.spa"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        sourceSets.getByName("main") {
            kotlin.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            manifest.srcFile("AndroidManifest.xml")
        }
        sourceSets.getByName("androidTest") {
            kotlin.setSrcDirs(listOf("../tests/src"))
            res.setSrcDirs(listOf("../tests/res"))
            manifest.srcFile("../tests/AndroidManifest.xml")
        }
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        getByName("debug") {
            enableAndroidTestCoverage = true
        }
    }
}

dependencies {
    api(project(":SettingsLibColor"))
    api("androidx.appcompat:appcompat:1.7.0-alpha03")
    api("androidx.slice:slice-builders:1.1.0-alpha02")
    api("androidx.slice:slice-core:1.1.0-alpha02")
    api("androidx.slice:slice-view:1.1.0-alpha02")
    api("androidx.compose.material3:material3:1.2.0-beta02")
    api("androidx.compose.material:material-icons-extended:$jetpackComposeVersion")
    api("androidx.compose.runtime:runtime-livedata:$jetpackComposeVersion")
    api("androidx.compose.ui:ui-tooling-preview:$jetpackComposeVersion")
    api("androidx.lifecycle:lifecycle-livedata-ktx")
    api("androidx.lifecycle:lifecycle-runtime-compose")
    api("androidx.navigation:navigation-compose:2.7.6")
    api("com.github.PhilJay:MPAndroidChart:v3.1.0-alpha")
    api("com.google.android.material:material:1.7.0-alpha03")
    debugApi("androidx.compose.ui:ui-tooling:$jetpackComposeVersion")
    implementation("com.airbnb.android:lottie-compose:5.2.0")

    androidTestImplementation(project(":testutils"))
    androidTestImplementation(libs.dexmaker.mockito)
}

tasks.register<JacocoReport>("coverageReport") {
    group = "Reporting"
    description = "Generate Jacoco coverage reports after running tests."
    dependsOn("connectedDebugAndroidTest")
    sourceDirectories.setFrom(files("src"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            setExcludes(
                listOf(
                    "com/android/settingslib/spa/debug/**",

                    // Excludes files forked from AndroidX.
                    "com/android/settingslib/spa/widget/scaffold/CustomizedAppBar*",
                    "com/android/settingslib/spa/widget/scaffold/TopAppBarColors*",

                    // Excludes files forked from Accompanist.
                    "com/android/settingslib/spa/framework/compose/DrawablePainter*",

                    // Excludes inline functions, which is not covered in Jacoco reports.
                    "com/android/settingslib/spa/framework/util/Collections*",
                    "com/android/settingslib/spa/framework/util/Flows*",

                    // Excludes debug functions
                    "com/android/settingslib/spa/framework/compose/TimeMeasurer*",

                    // Excludes slice demo presenter & provider
                    "com/android/settingslib/spa/slice/presenter/Demo*",
                    "com/android/settingslib/spa/slice/provider/Demo*",
                )
            )
        }
    )
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/code_coverage/debugAndroidTest/connected"))
    )
}
