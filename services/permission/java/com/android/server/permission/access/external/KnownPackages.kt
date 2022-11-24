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

package com.android.server.permission.access.external

class KnownPackages {
    companion object {
        const val PACKAGE_SYSTEM = 0
        const val PACKAGE_SETUP_WIZARD = 1
        const val PACKAGE_INSTALLER = 2
        const val PACKAGE_VERIFIER = 4
        const val PACKAGE_SYSTEM_TEXT_CLASSIFIER = 6
        const val PACKAGE_PERMISSION_CONTROLLER = 7
        const val PACKAGE_CONFIGURATOR = 10
        const val PACKAGE_INCIDENT_REPORT_APPROVER = 11
        const val PACKAGE_APP_PREDICTOR = 12
        const val PACKAGE_COMPANION = 15
        const val PACKAGE_RETAIL_DEMO = 16
        const val PACKAGE_RECENTS = 17
    }
}
