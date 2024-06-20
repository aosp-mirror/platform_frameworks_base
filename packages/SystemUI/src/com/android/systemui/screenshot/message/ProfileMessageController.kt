/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.message

import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.screenshot.data.model.ProfileType
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import javax.inject.Inject

/**
 * Handles work profile and private profile first run, determining whether a first run UI should be
 * shown and populating that UI if needed.
 */
class ProfileMessageController
@Inject
constructor(
    private val packageLabelIconProvider: PackageLabelIconProvider,
    private val fileResources: ProfileFirstRunFileResources,
    private val firstRunSettings: ProfileFirstRunSettings,
    private val profileTypes: ProfileTypeRepository,
) {

    /**
     * @return a populated ProfileFirstRunData object if a profile first run message should be
     *   shown, otherwise null.
     */
    suspend fun onScreenshotTaken(userHandle: UserHandle?): ProfileFirstRunData? {
        if (userHandle == null) return null
        val profileType =
            when (profileTypes.getProfileType(userHandle.identifier)) {
                ProfileType.WORK -> FirstRunProfile.WORK
                ProfileType.PRIVATE -> FirstRunProfile.PRIVATE
                else -> return null
            }

        if (firstRunSettings.messageAlreadyDismissed(profileType)) {
            return null
        }

        val fileApp =
            runCatching {
                    fileResources.fileManagerComponentName()?.let { fileManager ->
                        packageLabelIconProvider.getPackageLabelIcon(fileManager, userHandle)
                    }
                }
                .getOrNull() ?: fileResources.defaultFileApp()

        return ProfileFirstRunData(fileApp, profileType)
    }

    /**
     * Use the provided ProfileFirstRunData to populate the profile first run UI in the given view.
     */
    fun bindView(view: ViewGroup, data: ProfileFirstRunData, animateOut: () -> Unit) {
        if (data.labeledIcon.badgedIcon != null) {
            // Replace the default icon if one is provided.
            val imageView = view.requireViewById<ImageView>(R.id.screenshot_message_icon)
            imageView.setImageDrawable(data.labeledIcon.badgedIcon)
        }
        val messageContent = view.requireViewById<TextView>(R.id.screenshot_message_content)
        messageContent.text =
            view.context.getString(messageTemplate(data.profileType), data.labeledIcon.label)
        view.requireViewById<View>(R.id.message_dismiss_button).setOnClickListener {
            animateOut()
            firstRunSettings.onMessageDismissed(data.profileType)
        }
    }

    private fun messageTemplate(profile: FirstRunProfile): Int {
        return when (profile) {
            FirstRunProfile.WORK -> R.string.screenshot_work_profile_notification
            FirstRunProfile.PRIVATE -> R.string.screenshot_private_profile_notification
        }
    }

    data class ProfileFirstRunData(
        val labeledIcon: LabeledIcon,
        val profileType: FirstRunProfile,
    )

    enum class FirstRunProfile {
        WORK,
        PRIVATE
    }

    companion object {
        const val TAG = "PrivateProfileMessageCtrl"
    }
}
