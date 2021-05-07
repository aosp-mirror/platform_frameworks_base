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

package com.android.systemui.statusbar.events

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import com.android.settingslib.graph.ThemedBatteryDrawable
import com.android.systemui.R
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyItem

interface StatusEvent {
    val priority: Int
    // Whether or not to force the status bar open and show a dot
    val forceVisible: Boolean
    // Whether or not to show an animation for this event
    val showAnimation: Boolean
    val viewCreator: (context: Context) -> View

    // Update this event with values from another event.
    fun updateFromEvent(other: StatusEvent?) {
        // no op by default
    }

    // Whether or not this event should update its value from the provided. False by default
    fun shouldUpdateFromEvent(other: StatusEvent?): Boolean {
        return false
    }
}

class BatteryEvent : StatusEvent {
    override val priority = 50
    override val forceVisible = false
    override val showAnimation = true

    override val viewCreator: (context: Context) -> View = { context ->
        val iv = ImageView(context)
        iv.setImageDrawable(ThemedBatteryDrawable(context, Color.WHITE))
        iv.setBackgroundDrawable(ColorDrawable(Color.GREEN))
        iv
    }

    override fun toString(): String {
        return javaClass.simpleName
    }
}
class PrivacyEvent(override val showAnimation: Boolean = true) : StatusEvent {
    override val priority = 100
    override val forceVisible = true
    var privacyItems: List<PrivacyItem> = listOf()
    private var privacyChip: OngoingPrivacyChip? = null

    override val viewCreator: (context: Context) -> View = { context ->
        val v = LayoutInflater.from(context)
                .inflate(R.layout.ongoing_privacy_chip, null) as OngoingPrivacyChip
        v.privacyList = privacyItems
        privacyChip = v
        v
    }

    override fun toString(): String {
        return javaClass.simpleName
    }

    override fun shouldUpdateFromEvent(other: StatusEvent?): Boolean {
        return other is PrivacyEvent && other.privacyItems != privacyItems
    }

    override fun updateFromEvent(other: StatusEvent?) {
        if (other !is PrivacyEvent) {
            return
        }

        privacyItems = other.privacyItems
        privacyChip?.privacyList = other.privacyItems
    }
}
