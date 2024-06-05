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
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.temporarydisplay.chipbar.ChipbarInfo.Companion.DEFAULT_ICON_TINT
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
            MediaTttUtils.getIconInfoFromPackageName(
                context,
                appPackageName = null,
                isReceiver = false,
            ) {}

        assertThat(iconInfo.isAppIcon).isFalse()
        assertThat(iconInfo.contentDescription.loadContentDescription(context))
            .isEqualTo(context.getString(R.string.media_output_dialog_unknown_launch_app_name))
        assertThat(iconInfo.icon).isEqualTo(MediaTttIcon.Resource(R.drawable.ic_cast))
    }

    @Test
    fun getIconInfoFromPackageName_nullPackageName_isReceiver_returnsDefault() {
        val iconInfo =
            MediaTttUtils.getIconInfoFromPackageName(
                context,
                appPackageName = null,
                isReceiver = true,
            ) {}

        assertThat(iconInfo.isAppIcon).isFalse()
        assertThat(iconInfo.contentDescription.loadContentDescription(context))
            .isEqualTo(
                context.getString(R.string.media_transfer_receiver_content_description_unknown_app)
            )
        assertThat(iconInfo.icon).isEqualTo(MediaTttIcon.Resource(R.drawable.ic_cast))
    }

    @Test
    fun getIconInfoFromPackageName_nullPackageName_exceptionFnNotTriggered() {
        var exceptionTriggered = false

        MediaTttUtils.getIconInfoFromPackageName(
            context,
            appPackageName = null,
            isReceiver = false,
        ) {
            exceptionTriggered = true
        }

        assertThat(exceptionTriggered).isFalse()
    }

    @Test
    fun getIconInfoFromPackageName_invalidPackageName_returnsDefault() {
        val iconInfo =
            MediaTttUtils.getIconInfoFromPackageName(
                context,
                appPackageName = "fakePackageName",
                isReceiver = false,
            ) {}

        assertThat(iconInfo.isAppIcon).isFalse()
        assertThat(iconInfo.contentDescription.loadContentDescription(context))
            .isEqualTo(context.getString(R.string.media_output_dialog_unknown_launch_app_name))
        assertThat(iconInfo.icon).isEqualTo(MediaTttIcon.Resource(R.drawable.ic_cast))
    }

    @Test
    fun getIconInfoFromPackageName_invalidPackageName_isReceiver_returnsDefault() {
        val iconInfo =
            MediaTttUtils.getIconInfoFromPackageName(
                context,
                appPackageName = "fakePackageName",
                isReceiver = true,
            ) {}

        assertThat(iconInfo.isAppIcon).isFalse()
        assertThat(iconInfo.contentDescription.loadContentDescription(context))
            .isEqualTo(
                context.getString(R.string.media_transfer_receiver_content_description_unknown_app)
            )
        assertThat(iconInfo.icon).isEqualTo(MediaTttIcon.Resource(R.drawable.ic_cast))
        assertThat(iconInfo.tint).isEqualTo(DEFAULT_ICON_TINT)
    }

    @Test
    fun getIconInfoFromPackageName_invalidPackageName_exceptionFnTriggered() {
        var exceptionTriggered = false

        MediaTttUtils.getIconInfoFromPackageName(
            context,
            appPackageName = "fakePackageName",
            isReceiver = false
        ) {
            exceptionTriggered = true
        }

        assertThat(exceptionTriggered).isTrue()
    }

    @Test
    fun getIconInfoFromPackageName_invalidPackageName_isReceiver_exceptionFnTriggered() {
        var exceptionTriggered = false

        MediaTttUtils.getIconInfoFromPackageName(
            context,
            appPackageName = "fakePackageName",
            isReceiver = true
        ) {
            exceptionTriggered = true
        }

        assertThat(exceptionTriggered).isTrue()
    }

    @Test
    fun getIconInfoFromPackageName_validPackageName_returnsAppInfo() {
        val iconInfo =
            MediaTttUtils.getIconInfoFromPackageName(
                context,
                PACKAGE_NAME,
                isReceiver = false,
            ) {}

        assertThat(iconInfo.isAppIcon).isTrue()
        assertThat(iconInfo.icon).isEqualTo(MediaTttIcon.Loaded(appIconFromPackageName))
        assertThat(iconInfo.contentDescription.loadContentDescription(context)).isEqualTo(APP_NAME)
    }

    @Test
    fun getIconInfoFromPackageName_validPackageName_isReceiver_returnsAppInfo() {
        val iconInfo =
            MediaTttUtils.getIconInfoFromPackageName(
                context,
                PACKAGE_NAME,
                isReceiver = true,
            ) {}

        assertThat(iconInfo.isAppIcon).isTrue()
        assertThat(iconInfo.icon).isEqualTo(MediaTttIcon.Loaded(appIconFromPackageName))
        assertThat(iconInfo.contentDescription.loadContentDescription(context))
            .isEqualTo(
                context.getString(
                    R.string.media_transfer_receiver_content_description_with_app_name,
                    APP_NAME
                )
            )
    }

    @Test
    fun getIconInfoFromPackageName_validPackageName_exceptionFnNotTriggered() {
        var exceptionTriggered = false

        MediaTttUtils.getIconInfoFromPackageName(context, PACKAGE_NAME, isReceiver = false) {
            exceptionTriggered = true
        }

        assertThat(exceptionTriggered).isFalse()
    }

    @Test
    fun getIconInfoFromPackageName_validPackageName_isReceiver_exceptionFnNotTriggered() {
        var exceptionTriggered = false

        MediaTttUtils.getIconInfoFromPackageName(context, PACKAGE_NAME, isReceiver = true) {
            exceptionTriggered = true
        }

        assertThat(exceptionTriggered).isFalse()
    }

    @Test
    fun iconInfo_toTintedIcon_loaded() {
        val contentDescription = ContentDescription.Loaded("test")
        val drawable = context.getDrawable(R.drawable.ic_cake)!!
        val tint = R.color.GM2_blue_500

        val iconInfo =
            IconInfo(
                contentDescription,
                MediaTttIcon.Loaded(drawable),
                tint,
                isAppIcon = false,
            )

        val tinted = iconInfo.toTintedIcon()

        assertThat(tinted.icon).isEqualTo(Icon.Loaded(drawable, contentDescription))
        assertThat(tinted.tint).isEqualTo(tint)
    }

    @Test
    fun iconInfo_toTintedIcon_resource() {
        val contentDescription = ContentDescription.Loaded("test")
        val drawableRes = R.drawable.ic_cake
        val tint = R.color.GM2_blue_500

        val iconInfo =
            IconInfo(
                contentDescription,
                MediaTttIcon.Resource(drawableRes),
                tint,
                isAppIcon = false
            )

        val tinted = iconInfo.toTintedIcon()

        assertThat(tinted.icon).isEqualTo(Icon.Resource(drawableRes, contentDescription))
        assertThat(tinted.tint).isEqualTo(tint)
    }
}

private const val PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "Fake App Name"
