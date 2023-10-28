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
 * limitations under the License
 */
package com.android.systemui.statusbar.notification.row

import android.annotation.ColorInt
import android.graphics.Color
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.FakeShadowView
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.SourceType
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ActivatableNotificationViewTest : SysuiTestCase() {
    private val mContentView: View = mock()
    private lateinit var mView: ActivatableNotificationView

    @ColorInt
    private var mNormalColor = 0

    @Before
    fun setUp() {
        mView = object : ActivatableNotificationView(mContext, null) {

            init {
                onFinishInflate()
            }

            override fun getContentView(): View {
                return mContentView
            }

            override fun <T : View> findViewTraversal(id: Int): T? = when (id) {
                R.id.backgroundNormal -> mock<NotificationBackgroundView>()
                R.id.fake_shadow -> mock<FakeShadowView>()
                else -> null
            } as T?
        }

        mNormalColor = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorSurfaceContainerHigh)
    }

    @Test
    fun testBackgroundBehaviors() {
        // Color starts with the normal color
        mView.updateBackgroundColors()
        assertThat(mView.currentBackgroundTint).isEqualTo(mNormalColor)

        // Setting a tint changes the background to that color specifically
        mView.setTintColor(Color.BLUE)
        assertThat(mView.currentBackgroundTint).isEqualTo(Color.BLUE)

        // Setting an override tint blends with the previous tint
        mView.setOverrideTintColor(Color.RED, 0.5f)
        assertThat(mView.currentBackgroundTint)
            .isEqualTo(NotificationUtils.interpolateColors(Color.BLUE, Color.RED, 0.5f))

        // Updating the background colors resets tints, as those won't match the latest theme
        mView.updateBackgroundColors()
        assertThat(mView.currentBackgroundTint).isEqualTo(mNormalColor)
    }

    @Test
    fun roundnessShouldBeTheSame_after_onDensityOrFontScaleChanged() {
        val roundableState = mView.roundableState
        assertThat(mView.topRoundness).isEqualTo(0f)
        mView.requestTopRoundness(1f, SourceType.from(""))
        assertThat(mView.topRoundness).isEqualTo(1f)

        mView.onDensityOrFontScaleChanged()

        assertThat(mView.topRoundness).isEqualTo(1f)
        assertThat(mView.roundableState.hashCode()).isEqualTo(roundableState.hashCode())
    }
}