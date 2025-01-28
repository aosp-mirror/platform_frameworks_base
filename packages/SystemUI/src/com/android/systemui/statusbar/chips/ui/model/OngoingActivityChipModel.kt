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

package com.android.systemui.statusbar.chips.ui.model

import android.view.View
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays

/** Model representing the display of an ongoing activity as a chip in the status bar. */
sealed class OngoingActivityChipModel {
    /** Condensed name representing the model, used for logs. */
    abstract val logName: String

    /**
     * This chip shouldn't be shown.
     *
     * @property shouldAnimate true if the transition from [Shown] to [Hidden] should be animated,
     *   and false if that transition should *not* be animated (i.e. the chip view should
     *   immediately disappear).
     */
    data class Hidden(val shouldAnimate: Boolean = true) : OngoingActivityChipModel() {
        override val logName = "Hidden(anim=$shouldAnimate)"
    }

    /** This chip should be shown with the given information. */
    sealed class Shown(
        /** The icon to show on the chip. If null, no icon will be shown. */
        open val icon: ChipIcon?,
        /** What colors to use for the chip. */
        open val colors: ColorsModel,
        /**
         * Listener method to invoke when this chip is clicked. If null, the chip won't be
         * clickable. Will be deprecated after [StatusBarChipsModernization] is enabled.
         */
        open val onClickListenerLegacy: View.OnClickListener?,
        /** Data class that determines how clicks on the chip should be handled. */
        open val clickBehavior: ClickBehavior,
    ) : OngoingActivityChipModel() {

        /** This chip shows only an icon and nothing else. */
        data class IconOnly(
            override val icon: ChipIcon,
            override val colors: ColorsModel,
            override val onClickListenerLegacy: View.OnClickListener?,
            override val clickBehavior: ClickBehavior,
        ) : Shown(icon, colors, onClickListenerLegacy, clickBehavior) {
            override val logName = "Shown.Icon"
        }

        /** The chip shows a timer, counting up from [startTimeMs]. */
        data class Timer(
            override val icon: ChipIcon,
            override val colors: ColorsModel,
            /**
             * The time this event started, used to show the timer.
             *
             * This time should be relative to
             * [com.android.systemui.util.time.SystemClock.elapsedRealtime], *not*
             * [com.android.systemui.util.time.SystemClock.currentTimeMillis] because the
             * [ChipChronometer] is based off of elapsed realtime. See
             * [android.widget.Chronometer.setBase].
             */
            val startTimeMs: Long,
            override val onClickListenerLegacy: View.OnClickListener?,
            override val clickBehavior: ClickBehavior,
        ) : Shown(icon, colors, onClickListenerLegacy, clickBehavior) {
            override val logName = "Shown.Timer"
        }

        /**
         * The chip shows the time delta between now and [time] in a short format, e.g. "15min" or
         * "1hr ago".
         */
        data class ShortTimeDelta(
            override val icon: ChipIcon,
            override val colors: ColorsModel,
            /** The time of the event that this chip represents. */
            val time: Long,
            override val onClickListenerLegacy: View.OnClickListener?,
            override val clickBehavior: ClickBehavior,
        ) : Shown(icon, colors, onClickListenerLegacy, clickBehavior) {
            init {
                StatusBarNotifChips.assertInNewMode()
            }

            override val logName = "Shown.ShortTimeDelta"
        }

        /**
         * This chip shows a countdown using [secondsUntilStarted]. Used to inform users that an
         * event is about to start. Typically, a [Countdown] chip will turn into a [Timer] chip.
         */
        data class Countdown(
            override val colors: ColorsModel,
            /** The number of seconds until an event is started. */
            val secondsUntilStarted: Long,
        ) :
            Shown(
                icon = null,
                colors,
                onClickListenerLegacy = null,
                clickBehavior = ClickBehavior.None,
            ) {
            override val logName = "Shown.Countdown"
        }

        /** This chip shows the specified [text] in the chip. */
        data class Text(
            override val icon: ChipIcon,
            override val colors: ColorsModel,
            // TODO(b/361346412): Enforce a max length requirement?
            val text: String,
            override val onClickListenerLegacy: View.OnClickListener? = null,
            override val clickBehavior: ClickBehavior,
        ) : Shown(icon, colors, onClickListenerLegacy, clickBehavior) {
            override val logName = "Shown.Text"
        }
    }

    /** Represents an icon to show on the chip. */
    sealed interface ChipIcon {
        /**
         * The icon is a custom icon, which is set on [impl]. The icon was likely created by an
         * external app.
         */
        data class StatusBarView(val impl: StatusBarIconView) : ChipIcon {
            init {
                StatusBarConnectedDisplays.assertInLegacyMode()
            }
        }

        /**
         * The icon is a custom icon, which is set on a notification, and can be looked up using the
         * provided [notificationKey]. The icon was likely created by an external app.
         */
        data class StatusBarNotificationIcon(val notificationKey: String) : ChipIcon {
            init {
                StatusBarConnectedDisplays.assertInNewMode()
            }
        }

        /**
         * This icon is a single color and it came from basic resource or drawable icon that System
         * UI created internally.
         */
        data class SingleColorIcon(val impl: Icon) : ChipIcon
    }

    /** Defines the behavior of the chip when it is clicked. */
    sealed interface ClickBehavior {
        /** No specific click behavior. */
        data object None : ClickBehavior

        /** The chip expands into a dialog or activity on click. */
        data class ExpandAction(val onClick: (Expandable) -> Unit) : ClickBehavior

        /** Clicking the chip will show the heads up notification associated with the chip. */
        data class ShowHeadsUpNotification(val onClick: () -> Unit) : ClickBehavior
    }
}
