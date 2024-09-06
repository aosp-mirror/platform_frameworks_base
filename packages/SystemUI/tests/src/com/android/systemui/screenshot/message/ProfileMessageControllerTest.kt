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

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.android.systemui.screenshot.data.model.ProfileType
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProfileMessageControllerTest {
    @Test
    fun personalScreenshot() = runTest {
        assertThat(
                getMessageController()
                    .onScreenshotTaken(UserHandle.of(profileTypeRepository.personalUser))
            )
            .isNull()
    }

    @Test
    fun communalScreenshot() = runTest {
        assertThat(
                getMessageController()
                    .onScreenshotTaken(UserHandle.of(profileTypeRepository.communalUser))
            )
            .isNull()
    }

    @Test
    fun noUserScreenshot() = runTest {
        assertThat(getMessageController().onScreenshotTaken(null)).isNull()
    }

    @Test
    fun alreadyDismissed() = runTest {
        val messageController = getMessageController()
        profileFirstRunSettings.onMessageDismissed(ProfileMessageController.FirstRunProfile.WORK)
        assertThat(
                messageController.onScreenshotTaken(UserHandle.of(profileTypeRepository.workUser))
            )
            .isNull()
    }

    @Test
    fun noFileManager() = runTest {
        val messageController = getMessageController(fileManagerComponent = null)
        val data =
            messageController.onScreenshotTaken(UserHandle.of(profileTypeRepository.workUser))
        assertThat(data?.profileType).isEqualTo(ProfileMessageController.FirstRunProfile.WORK)
        assertThat(data?.labeledIcon?.label).isEqualTo(DEFAULT_APP_NAME)
        assertThat(data?.labeledIcon?.badgedIcon).isNull()
    }

    @Test
    fun fileManagerNotFound() = runTest {
        val messageController =
            getMessageController(fileManagerComponent = ComponentName("Something", "Random"))
        val data =
            messageController.onScreenshotTaken(UserHandle.of(profileTypeRepository.privateUser))
        assertThat(data?.profileType).isEqualTo(ProfileMessageController.FirstRunProfile.PRIVATE)
        assertThat(data?.labeledIcon?.label).isEqualTo(DEFAULT_APP_NAME)
        assertThat(data?.labeledIcon?.badgedIcon).isNull()
    }

    @Test
    fun fileManagerFound() = runTest {
        val messageController = getMessageController()
        val data =
            messageController.onScreenshotTaken(UserHandle.of(profileTypeRepository.privateUser))
        assertThat(data?.profileType).isEqualTo(ProfileMessageController.FirstRunProfile.PRIVATE)
        assertThat(data?.labeledIcon?.label).isEqualTo(FILE_MANAGER_LABEL)
        assertThat(data?.labeledIcon?.badgedIcon).isEqualTo(drawable)
    }

    private val drawable =
        object : Drawable() {
            override fun draw(canvas: Canvas) {}

            override fun setAlpha(alpha: Int) {}

            override fun setColorFilter(colorFilter: ColorFilter?) {}

            override fun getOpacity(): Int = 0
        }

    private val packageLabelIconProvider =
        object : PackageLabelIconProvider {
            override suspend fun getPackageLabelIcon(
                componentName: ComponentName,
                userHandle: UserHandle
            ): LabeledIcon {
                if (componentName.equals(FILE_MANAGER_COMPONENT)) {
                    return LabeledIcon(FILE_MANAGER_LABEL, drawable)
                } else {
                    throw PackageManager.NameNotFoundException()
                }
            }
        }

    private class FakeProfileFirstRunResources(private val fileManager: ComponentName?) :
        ProfileFirstRunFileResources {
        override fun fileManagerComponentName(): ComponentName? {
            return fileManager
        }

        override fun defaultFileApp() = LabeledIcon(DEFAULT_APP_NAME, badgedIcon = null)
    }

    private val profileFirstRunSettings =
        object : ProfileFirstRunSettings {
            private val dismissed =
                mutableMapOf(
                    ProfileMessageController.FirstRunProfile.WORK to false,
                    ProfileMessageController.FirstRunProfile.PRIVATE to false,
                )

            override fun messageAlreadyDismissed(
                profileType: ProfileMessageController.FirstRunProfile
            ): Boolean {
                return dismissed.getOrDefault(profileType, false)
            }

            override fun onMessageDismissed(profileType: ProfileMessageController.FirstRunProfile) {
                dismissed[profileType] = true
            }
        }

    private val profileTypeRepository =
        object : ProfileTypeRepository {
            override suspend fun getProfileType(userId: Int): ProfileType {
                return when (userId) {
                    workUser -> ProfileType.WORK
                    privateUser -> ProfileType.PRIVATE
                    communalUser -> ProfileType.COMMUNAL
                    else -> ProfileType.NONE
                }
            }

            val personalUser = 0
            val workUser = 1
            val privateUser = 2
            val communalUser = 3
        }

    private fun getMessageController(
        fileManagerComponent: ComponentName? = FILE_MANAGER_COMPONENT
    ) =
        ProfileMessageController(
            packageLabelIconProvider,
            FakeProfileFirstRunResources(fileManagerComponent),
            profileFirstRunSettings,
            profileTypeRepository
        )

    companion object {
        val FILE_MANAGER_COMPONENT = ComponentName("package", "component")
        const val DEFAULT_APP_NAME = "default app"
        const val FILE_MANAGER_LABEL = "file manager"
    }
}
