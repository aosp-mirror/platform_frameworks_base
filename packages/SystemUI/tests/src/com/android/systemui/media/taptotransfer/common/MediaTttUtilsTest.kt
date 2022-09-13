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

package com.android.systemui.media.taptotransfer.common

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.internal.widget.CachingIconView
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
class MediaTttUtilsTest : SysuiTestCase() {

    private lateinit var appIconFromPackageName: Drawable
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var applicationInfo: ApplicationInfo
    @Mock private lateinit var logger: MediaTttLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Set up our package manager to give valid information for [PACKAGE_NAME] only
        appIconFromPackageName = context.getDrawable(R.drawable.ic_cake)!!
        whenever(packageManager.getApplicationIcon(PACKAGE_NAME)).thenReturn(appIconFromPackageName)
        whenever(applicationInfo.loadLabel(packageManager)).thenReturn(APP_NAME)
        whenever(
                packageManager.getApplicationInfo(any(), any<PackageManager.ApplicationInfoFlags>())
            )
            .thenThrow(PackageManager.NameNotFoundException())
        whenever(
                packageManager.getApplicationInfo(
                    Mockito.eq(PACKAGE_NAME),
                    any<PackageManager.ApplicationInfoFlags>()
                )
            )
            .thenReturn(applicationInfo)
        context.setMockPackageManager(packageManager)
    }

    @Test
    fun getIconInfoFromPackageName_nullPackageName_returnsDefault() {
        val iconInfo =
            MediaTttUtils.getIconInfoFromPackageName(context, appPackageName = null, logger)

        assertThat(iconInfo.isAppIcon).isFalse()
        assertThat(iconInfo.contentDescription)
            .isEqualTo(context.getString(R.string.media_output_dialog_unknown_launch_app_name))
    }

    @Test
    fun getIconInfoFromPackageName_invalidPackageName_returnsDefault() {
        val iconInfo = MediaTttUtils.getIconInfoFromPackageName(context, "fakePackageName", logger)

        assertThat(iconInfo.isAppIcon).isFalse()
        assertThat(iconInfo.contentDescription)
            .isEqualTo(context.getString(R.string.media_output_dialog_unknown_launch_app_name))
    }

    @Test
    fun getIconInfoFromPackageName_validPackageName_returnsAppInfo() {
        val iconInfo = MediaTttUtils.getIconInfoFromPackageName(context, PACKAGE_NAME, logger)

        assertThat(iconInfo.isAppIcon).isTrue()
        assertThat(iconInfo.drawable).isEqualTo(appIconFromPackageName)
        assertThat(iconInfo.contentDescription).isEqualTo(APP_NAME)
    }

    @Test
    fun setIcon_viewHasIconAndContentDescription() {
        val view = CachingIconView(context)
        val icon = context.getDrawable(R.drawable.ic_celebration)!!
        val contentDescription = "Happy birthday!"

        MediaTttUtils.setIcon(view, icon, contentDescription)

        assertThat(view.drawable).isEqualTo(icon)
        assertThat(view.contentDescription).isEqualTo(contentDescription)
    }

    @Test
    fun setIcon_iconSizeNull_viewSizeDoesNotChange() {
        val view = CachingIconView(context)
        val size = 456
        view.layoutParams = FrameLayout.LayoutParams(size, size)

        MediaTttUtils.setIcon(view, context.getDrawable(R.drawable.ic_cake)!!, "desc")

        assertThat(view.layoutParams.width).isEqualTo(size)
        assertThat(view.layoutParams.height).isEqualTo(size)
    }

    @Test
    fun setIcon_iconSizeProvided_viewSizeUpdates() {
        val view = CachingIconView(context)
        val size = 456
        view.layoutParams = FrameLayout.LayoutParams(size, size)

        val newSize = 40
        MediaTttUtils.setIcon(
            view,
            context.getDrawable(R.drawable.ic_cake)!!,
            "desc",
            iconSize = newSize
        )

        assertThat(view.layoutParams.width).isEqualTo(newSize)
        assertThat(view.layoutParams.height).isEqualTo(newSize)
    }
}

private const val PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "Fake App Name"
