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

package com.android.systemui.mediaprojection.appselector.data

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.test.filters.SmallTest
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.icons.IconFactory
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.system.PackageManagerWrapper
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class IconLoaderLibAppIconLoaderTest : SysuiTestCase() {

    private val iconFactory: IconFactory = mock()
    private val packageManagerWrapper: PackageManagerWrapper = mock()
    private val packageManager: PackageManager = mock()
    private val dispatcher = Dispatchers.Unconfined

    private val appIconLoader =
        IconLoaderLibAppIconLoader(
            backgroundDispatcher = dispatcher,
            context = context,
            packageManagerWrapper = packageManagerWrapper,
            packageManager = packageManager,
            iconFactoryProvider = { iconFactory }
        )

    @Test
    fun loadIcon_loadsIconUsingTheSameUserId() {
        val icon = createIcon()
        val component = ComponentName("com.test", "TestApplication")
        givenIcon(component, userId = 123, icon = icon)

        val loadedIcon = runBlocking { appIconLoader.loadIcon(userId = 123, component = component) }

        assertThat(loadedIcon).isEqualTo(icon)
    }

    private fun givenIcon(component: ComponentName, userId: Int, icon: FastBitmapDrawable) {
        val activityInfo = mock<ActivityInfo>()
        whenever(packageManagerWrapper.getActivityInfo(component, userId)).thenReturn(activityInfo)
        val rawIcon = mock<Drawable>()
        whenever(activityInfo.loadIcon(packageManager)).thenReturn(rawIcon)

        val bitmapInfo = mock<BitmapInfo>()
        whenever(iconFactory.createBadgedIconBitmap(eq(rawIcon), any())).thenReturn(bitmapInfo)
        whenever(bitmapInfo.newIcon(context)).thenReturn(icon)
    }

    private fun createIcon(): FastBitmapDrawable =
        FastBitmapDrawable(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
}
