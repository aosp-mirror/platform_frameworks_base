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

package com.android.systemui.statusbar.phone.ui

import android.app.Flags
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter
import com.android.systemui.util.Assert
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class IconManagerTest : SysuiTestCase() {

    private lateinit var underTest: IconManager
    private lateinit var viewGroup: ViewGroup

    @Before
    fun setUp() {
        Assert.setTestThread(Thread.currentThread())
        viewGroup = LinearLayout(context)
        underTest =
            IconManager(
                viewGroup,
                StatusBarLocation.HOME,
                mock<WifiUiAdapter>(defaultAnswer = RETURNS_DEEP_STUBS),
                mock<MobileUiAdapter>(defaultAnswer = RETURNS_DEEP_STUBS),
                mock<MobileContextProvider>(defaultAnswer = RETURNS_DEEP_STUBS),
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_UI_ICONS)
    fun addIcon_shapeWrapContent_addsIconViewWithVariableWidth() {
        val sbIcon = newStatusBarIcon(StatusBarIcon.Shape.WRAP_CONTENT)

        underTest.addIcon(0, "slot", false, sbIcon)

        assertThat(viewGroup.childCount).isEqualTo(1)
        val iconView = viewGroup.getChildAt(0) as StatusBarIconView
        assertThat(iconView).isNotNull()

        assertThat(iconView.layoutParams.width).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(iconView.scaleType).isEqualTo(ImageView.ScaleType.CENTER)
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_UI_ICONS)
    fun addIcon_shapeFixedSpace_addsIconViewWithFixedWidth() {
        val sbIcon = newStatusBarIcon(StatusBarIcon.Shape.FIXED_SPACE)

        underTest.addIcon(0, "slot", false, sbIcon)

        assertThat(viewGroup.childCount).isEqualTo(1)
        val iconView = viewGroup.getChildAt(0) as StatusBarIconView
        assertThat(iconView).isNotNull()

        assertThat(iconView.layoutParams.width).isNotEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(iconView.layoutParams.width).isEqualTo(iconView.layoutParams.height)
        assertThat(iconView.scaleType).isEqualTo(ImageView.ScaleType.FIT_CENTER)
    }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI_ICONS)
    fun addIcon_iconsFlagOff_addsIconViewWithVariableWidth() {
        val sbIcon = newStatusBarIcon(StatusBarIcon.Shape.FIXED_SPACE)

        underTest.addIcon(0, "slot", false, sbIcon)

        assertThat(viewGroup.childCount).isEqualTo(1)
        val iconView = viewGroup.getChildAt(0) as StatusBarIconView
        assertThat(iconView).isNotNull()

        assertThat(iconView.layoutParams.width).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
        assertThat(iconView.scaleType).isEqualTo(ImageView.ScaleType.CENTER)
    }

    private fun newStatusBarIcon(shape: StatusBarIcon.Shape) =
        StatusBarIcon(
            UserHandle.CURRENT,
            context.packageName,
            Icon.createWithResource(context, android.R.drawable.ic_media_next),
            0,
            0,
            "",
            StatusBarIcon.Type.ResourceIcon,
            shape,
        )
}
