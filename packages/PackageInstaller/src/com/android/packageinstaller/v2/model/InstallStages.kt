/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.model

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.graphics.drawable.Drawable

sealed class InstallStage(val stageCode: Int) {

    companion object {
        const val STAGE_DEFAULT = -1
        const val STAGE_ABORTED = 0
        const val STAGE_STAGING = 1
        const val STAGE_READY = 2
        const val STAGE_USER_ACTION_REQUIRED = 3
        const val STAGE_INSTALLING = 4
        const val STAGE_SUCCESS = 5
        const val STAGE_FAILED = 6
    }
}

class InstallStaging : InstallStage(STAGE_STAGING)

class InstallReady : InstallStage(STAGE_READY)

data class InstallUserActionRequired(
    val actionReason: Int,
    private val appSnippet: PackageUtil.AppSnippet? = null,
    val isAppUpdating: Boolean = false,
    /**
     * This holds either a package name or the app label of the install source.
     */
    val sourceApp: String? = null,
) : InstallStage(STAGE_USER_ACTION_REQUIRED) {

    val appIcon: Drawable?
        get() = appSnippet?.icon

    val appLabel: String?
        get() = appSnippet?.let { appSnippet.label as String? }

    companion object {
        const val USER_ACTION_REASON_UNKNOWN_SOURCE = 0
        const val USER_ACTION_REASON_ANONYMOUS_SOURCE = 1
        const val USER_ACTION_REASON_INSTALL_CONFIRMATION = 2
    }
}

data class InstallInstalling(private val appSnippet: PackageUtil.AppSnippet) :
    InstallStage(STAGE_INSTALLING) {

    val appIcon: Drawable?
        get() = appSnippet.icon

    val appLabel: String?
        get() = appSnippet.label as String?
}

data class InstallSuccess(
    private val appSnippet: PackageUtil.AppSnippet,
    val shouldReturnResult: Boolean = false,
    /**
     *
     * * If the caller is requesting a result back, this will hold an Intent with
     * [Intent.EXTRA_INSTALL_RESULT] set to [PackageManager.INSTALL_SUCCEEDED].
     *
     * * If the caller doesn't want the result back, this will hold an Intent that launches
     * the newly installed / updated app if a launchable activity exists.
     */
    val resultIntent: Intent? = null,
) : InstallStage(STAGE_SUCCESS) {

    val appIcon: Drawable?
        get() = appSnippet.icon

    val appLabel: String?
        get() = appSnippet.label as String?
}

data class InstallFailed(
    private val appSnippet: PackageUtil.AppSnippet? = null,
    val legacyCode: Int,
    val statusCode: Int,
    val message: String? = null,
    val shouldReturnResult: Boolean = false,
    /**
     * If the caller is requesting a result back, this will hold an Intent with
     * [Intent.EXTRA_INSTALL_RESULT] set to the [PackageInstaller.EXTRA_LEGACY_STATUS].
     */
    val resultIntent: Intent? = null
) : InstallStage(STAGE_FAILED) {

    val appIcon: Drawable?
        get() = appSnippet?.icon

    val appLabel: String?
        get() = appSnippet?.label as String?
}

data class InstallAborted(
    val abortReason: Int,
    /**
     * It will hold the restriction name, when the restriction was enforced by the system, and not
     * a device admin.
     */
    val message: String? = null,
    /**
     * * If abort reason is [ABORT_REASON_POLICY], then this will hold the Intent
     * to display a support dialog when a feature was disabled by an admin. It will be
     * `null` if the feature is disabled by the system. In this case, the restriction name
     * will be set in [message]
     * * If the abort reason is [ABORT_REASON_INTERNAL_ERROR], it **may** hold an
     * intent to be sent as a result to the calling activity.
     */
    val resultIntent: Intent? = null,
    val activityResultCode: Int = Activity.RESULT_CANCELED,
    val errorDialogType: Int? = DLG_NONE,
) : InstallStage(STAGE_ABORTED) {

    companion object {
        const val ABORT_REASON_INTERNAL_ERROR = 0
        const val ABORT_REASON_POLICY = 1
        const val ABORT_REASON_DONE = 2
        const val DLG_NONE = 0
        const val DLG_PACKAGE_ERROR = 1
    }
}
