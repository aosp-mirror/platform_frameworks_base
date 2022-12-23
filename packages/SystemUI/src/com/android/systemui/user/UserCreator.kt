/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.user

import android.app.Dialog
import android.content.Context
import android.content.pm.UserInfo
import android.graphics.drawable.Drawable
import android.os.UserManager
import com.android.internal.util.UserIcons
import com.android.settingslib.users.UserCreatingDialog
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

/**
 * A class to do the user creation process. It shows a progress dialog, and manages the user
 * creation
 */
class UserCreator @Inject constructor(
    private val context: Context,
    private val userManager: UserManager,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: Executor
) {
    /**
     * Shows a progress dialog then starts the user creation process on the main thread.
     *
     * @param successCallback is called when the user creation is successful.
     * @param errorCallback is called when userManager.createUser returns null.
     * (Exceptions are not handled by this class)
     */
    fun createUser(
        userName: String?,
        userIcon: Drawable?,
        successCallback: Consumer<UserInfo?>,
       errorCallback: Runnable
    ) {
        val userCreationProgressDialog: Dialog = UserCreatingDialog(context)
        userCreationProgressDialog.show()

        // userManager.createUser will block the thread so post is needed for the dialog to show
        bgExecutor.execute {
            val user = userManager.createUser(userName, UserManager.USER_TYPE_FULL_SECONDARY, 0)
            mainExecutor.execute main@{
                if (user == null) {
                    // Couldn't create user for some reason
                    userCreationProgressDialog.dismiss()
                    errorCallback.run()
                    return@main
                }
                bgExecutor.execute {
                    var newUserIcon = userIcon
                    val res = context.resources
                    if (newUserIcon == null) {
                        newUserIcon = UserIcons.getDefaultUserIcon(res, user.id, false)
                    }
                    userManager.setUserIcon(
                        user.id, UserIcons.convertToBitmapAtUserIconSize(res, newUserIcon))
                }
                userCreationProgressDialog.dismiss()
                successCallback.accept(user)
            }
        }
    }
}
