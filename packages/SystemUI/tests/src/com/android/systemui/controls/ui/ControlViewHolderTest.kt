/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.ShapeDrawable
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.templates.ControlTemplate
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.res.R
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.utils.SafeIconLoader
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ControlViewHolderTest : SysuiTestCase() {

    private val clock = FakeSystemClock()

    private lateinit var cvh: ControlViewHolder
    private lateinit var baseLayout: ViewGroup
    private lateinit var safeIconLoader: SafeIconLoader

    @Before
    fun setUp() {
        TestableLooper.get(this).runWithLooper {
            baseLayout =
                LayoutInflater.from(mContext).inflate(R.layout.controls_base_item, null, false)
                    as ViewGroup

            safeIconLoader = mock(SafeIconLoader::class.java)
            `when`(safeIconLoader.load(any())).thenReturn(PLAIN_DRAWABLE)

            cvh =
                ControlViewHolder(
                    baseLayout,
                    mock(ControlsController::class.java),
                    FakeExecutor(clock),
                    FakeExecutor(clock),
                    mock(ControlActionCoordinator::class.java),
                    mock(ControlsMetricsLogger::class.java),
                    uid = 100,
                    0,
                    safeIconLoader,
                )

            val cws =
                ControlWithState(
                    ComponentName.createRelative("pkg", "cls"),
                    ControlInfo(
                        CONTROL_ID,
                        CONTROL_TITLE,
                        "subtitle",
                        DeviceTypes.TYPE_AIR_FRESHENER,
                    ),
                    Control.StatelessBuilder(CONTROL_ID, mock(PendingIntent::class.java)).build(),
                )

            cvh.bindData(cws, isLocked = false)
        }
    }

    @Test
    fun updateStatusRow_customIconWithTint_iconTintRemains() {
        val control =
            Control.StatelessBuilder(DEFAULT_CONTROL)
                .setCustomIcon(
                    Icon.createWithResource(mContext.resources, R.drawable.ic_emergency_star)
                        .setTint(TINT_COLOR)
                )
                .build()

        cvh.updateStatusRow(enabled = true, CONTROL_TITLE, DRAWABLE, COLOR, control)

        assertThat(cvh.icon.imageTintList).isEqualTo(ColorStateList.valueOf(TINT_COLOR))
    }

    @Test
    fun updateStatusRow_customIconWithTintList_iconTintListRemains() {
        val customIconTintList = ColorStateList.valueOf(TINT_COLOR)
        val control =
            Control.StatelessBuilder(CONTROL_ID, mock(PendingIntent::class.java))
                .setCustomIcon(
                    Icon.createWithResource(mContext.resources, R.drawable.ic_emergency_star)
                        .setTintList(customIconTintList)
                )
                .build()

        cvh.updateStatusRow(enabled = true, CONTROL_TITLE, DRAWABLE, COLOR, control)

        assertThat(cvh.icon.imageTintList).isEqualTo(customIconTintList)
    }

    @Test
    fun chevronIcon() {
        val control =
            Control.StatefulBuilder(CONTROL_ID, mock(PendingIntent::class.java))
                .setStatus(Control.STATUS_OK)
                .setControlTemplate(ControlTemplate.NO_TEMPLATE)
                .build()
        val cws =
            ControlWithState(
                ComponentName.createRelative("pkg", "cls"),
                ControlInfo(CONTROL_ID, CONTROL_TITLE, "subtitle", DeviceTypes.TYPE_AIR_FRESHENER),
                control,
            )
        cvh.bindData(cws, false)
        val chevronIcon = baseLayout.requireViewById<View>(R.id.chevron_icon)

        assertThat(chevronIcon.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun drawableLoadedSafely_showsPlainDrawableLoaded() {
        val control =
            Control.StatelessBuilder(DEFAULT_CONTROL)
                .setCustomIcon(
                    Icon.createWithResource(mContext.resources, R.drawable.ic_emergency_star)
                        .setTint(TINT_COLOR)
                )
                .build()

        cvh.updateStatusRow(enabled = true, CONTROL_TITLE, DRAWABLE, COLOR, control)

        assertThat(cvh.icon.drawable).isSameInstanceAs(PLAIN_DRAWABLE)
    }

    @Test
    fun drawableNotLoadedSafely_showsDefaultDrawable() {
        `when`(safeIconLoader.load(any())).thenReturn(null)

        val control =
            Control.StatelessBuilder(DEFAULT_CONTROL)
                .setCustomIcon(
                    Icon.createWithResource(mContext.resources, R.drawable.ic_emergency_star)
                        .setTint(TINT_COLOR)
                )
                .build()

        cvh.updateStatusRow(enabled = true, CONTROL_TITLE, DRAWABLE, COLOR, control)

        assertThat(cvh.icon.drawable).isSameInstanceAs(DRAWABLE)
    }
}

private const val CONTROL_ID = "CONTROL_ID"
private const val CONTROL_TITLE = "CONTROL_TITLE"
private const val TINT_COLOR = 0x00ff00 // Should be different from [COLOR]

private val DRAWABLE = GradientDrawable()
private val PLAIN_DRAWABLE = ShapeDrawable()
private val COLOR = ColorStateList.valueOf(0xffff00)
private val DEFAULT_CONTROL =
    Control.StatelessBuilder(CONTROL_ID, mock(PendingIntent::class.java)).build()
