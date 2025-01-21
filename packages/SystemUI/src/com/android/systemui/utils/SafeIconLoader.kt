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

package com.android.systemui.utils

import android.app.IUriGrantsManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Application
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * Use to load an icon (from another app) safely. It will prevent cross user icon loading if there
 * are no permissions.
 */
class SafeIconLoader
@AssistedInject
constructor(
    @Assisted("serviceUid") private val serviceUid: Int,
    @Assisted private val packageName: String,
    @Assisted("userId") private val userId: Int,
    @Application private val applicationContext: Context,
    private val iUriGrantsManager: IUriGrantsManager,
) {

    private val serviceContext =
        applicationContext.createPackageContextAsUser(packageName, 0, UserHandle.of(userId))

    /**
     * Tries to load the icon. If it fails in any way (for example, cross user permissions), it will
     * return `null`. Prefer calling this in a background thread.
     */
    fun load(icon: Icon): Drawable? {
        return icon.loadDrawableCheckingUriGrant(
            serviceContext,
            iUriGrantsManager,
            serviceUid,
            packageName,
        )
    }

    @AssistedFactory
    interface Factory {

        fun create(
            @Assisted("serviceUid") serviceUid: Int,
            packageName: String,
            @Assisted("userId") userId: Int,
        ): SafeIconLoader
    }
}
