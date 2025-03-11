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

package android.appwidget

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppWidgetEventsTest {
    @Test
    fun createWidgetInteractionEvent() {
        val appWidgetId = 1
        val durationMs = 1000L
        val position = Rect(1, 2, 3, 4)
        val clicked = intArrayOf(1, 2, 3)
        val scrolled = intArrayOf(4, 5, 6)
        val bundle = AppWidgetManager.createWidgetInteractionEvent(
            appWidgetId,
            durationMs,
            position,
            clicked,
            scrolled
        )

        assertThat(bundle.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID)).isEqualTo(appWidgetId)
        assertThat(bundle.getLong(AppWidgetManager.EXTRA_EVENT_DURATION_MS)).isEqualTo(durationMs)
        assertThat(bundle.getIntArray(AppWidgetManager.EXTRA_EVENT_POSITION_RECT))
            .asList().containsExactly(position.left, position.top, position.right, position.bottom)
        assertThat(bundle.getIntArray(AppWidgetManager.EXTRA_EVENT_CLICKED_VIEWS))
            .asList().containsExactly(clicked[0], clicked[1], clicked[2])
        assertThat(bundle.getIntArray(AppWidgetManager.EXTRA_EVENT_SCROLLED_VIEWS))
            .asList().containsExactly(scrolled[0], scrolled[1], scrolled[2])
    }
}
