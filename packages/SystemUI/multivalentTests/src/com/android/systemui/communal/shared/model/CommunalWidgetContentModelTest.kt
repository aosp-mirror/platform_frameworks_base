/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.shared.model

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Parcel
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalWidgetContentModelTest : SysuiTestCase() {
    @Test
    fun testParcelizeAvailableWidget() {
        val widgetToParcelize =
            CommunalWidgetContentModel.Available(
                appWidgetId = 1,
                providerInfo =
                    AppWidgetProviderInfo().apply { provider = ComponentName("pkg", "cls") },
                rank = 2,
                spanY = 3,
            )

        val parcel = Parcel.obtain()
        widgetToParcelize.writeToParcel(parcel, flags = 0)

        parcel.setDataPosition(0)

        // Only checking fields are equal and not complete equality because not all fields are
        // specified in the fake AppWidgetProviderInfo
        val widgetFromParcel =
            CommunalWidgetContentModel.createFromParcel(parcel)
                as CommunalWidgetContentModel.Available
        assertThat(widgetFromParcel.appWidgetId).isEqualTo(widgetToParcelize.appWidgetId)
        assertThat(widgetFromParcel.rank).isEqualTo(widgetToParcelize.rank)
        assertThat(widgetFromParcel.spanY).isEqualTo(widgetToParcelize.spanY)
        assertThat(widgetFromParcel.providerInfo.provider)
            .isEqualTo(widgetToParcelize.providerInfo.provider)
    }

    @Test
    fun testParcelizePendingWidget() {
        val widgetToParcelize =
            CommunalWidgetContentModel.Pending(
                appWidgetId = 2,
                rank = 3,
                componentName = ComponentName("pkg", "cls"),
                icon = null,
                user = UserHandle(0),
                spanY = 6,
            )

        val parcel = Parcel.obtain()
        widgetToParcelize.writeToParcel(parcel, flags = 0)

        parcel.setDataPosition(0)

        val widgetFromParcel = CommunalWidgetContentModel.createFromParcel(parcel)
        assertThat(widgetFromParcel).isEqualTo(widgetToParcelize)
    }
}
