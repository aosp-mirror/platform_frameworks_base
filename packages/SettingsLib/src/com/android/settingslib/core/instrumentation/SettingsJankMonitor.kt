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
package com.android.settingslib.core.instrumentation

import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.jank.InteractionJankMonitor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Helper class for Settings library to trace jank.
 */
object SettingsJankMonitor {
    private val jankMonitor = InteractionJankMonitor.getInstance()
    private val scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Switch toggle animation duration is 250ms, and there is also a ripple effect animation when
    // clicks, which duration is variable. Use 300ms here to cover.
    @VisibleForTesting
    const val MONITORED_ANIMATION_DURATION_MS = 300L

    /**
     * Detects the jank when click on a SwitchPreference.
     *
     * @param recyclerView the recyclerView contains the preference
     * @param preference the clicked preference
     */
    @JvmStatic
    fun detectSwitchPreferenceClickJank(recyclerView: RecyclerView, preference: SwitchPreference) {
        val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
        val adapterPosition = adapter.getPreferenceAdapterPosition(preference)
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(adapterPosition) ?: return
        detectToggleJank(preference.key, viewHolder.itemView)
    }

    /**
     * Detects the animation jank on the given view.
     *
     * @param tag the tag for jank monitor
     * @param view the instrumented view
     */
    @JvmStatic
    fun detectToggleJank(tag: String?, view: View) {
        val builder = InteractionJankMonitor.Configuration.Builder.withView(
            InteractionJankMonitor.CUJ_SETTINGS_TOGGLE,
            view
        )
        if (tag != null) {
            builder.setTag(tag)
        }
        if (jankMonitor.begin(builder)) {
            scheduledExecutorService.schedule({
                jankMonitor.end(InteractionJankMonitor.CUJ_SETTINGS_TOGGLE)
            }, MONITORED_ANIMATION_DURATION_MS, TimeUnit.MILLISECONDS)
        }
    }
}