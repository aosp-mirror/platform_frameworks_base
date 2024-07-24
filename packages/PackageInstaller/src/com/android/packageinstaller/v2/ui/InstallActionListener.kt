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

package com.android.packageinstaller.v2.ui

import android.content.Intent

interface InstallActionListener {
    /**
     * Method to handle a positive response from the user.
     */
    fun onPositiveResponse(reasonCode: Int)

    /**
     * Method to dispatch intent for toggling "install from unknown sources" setting for a package.
     */
    fun sendUnknownAppsIntent(sourcePackageName: String)

    /**
     * Method to handle a negative response from the user.
     */
    fun onNegativeResponse(stageCode: Int)

    fun onNegativeResponse(resultCode: Int, data: Intent?)

    /**
     * Launch the intent to open the newly installed / updated app.
     */
    fun openInstalledApp(intent: Intent?)
}
