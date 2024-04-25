/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.systemui.statusbar.phone

import android.annotation.IntDef
import android.content.Context
import android.graphics.drawable.Icon
import android.os.UserHandle
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.CallIndicatorIconState
import com.android.systemui.statusbar.pipeline.icons.shared.model.ModernStatusBarViewCreator

/** Wraps [com.android.internal.statusbar.StatusBarIcon] so we can still have a uniform list */
open class StatusBarIconHolder private constructor() {
    @IntDef(TYPE_ICON, TYPE_MOBILE_NEW, TYPE_WIFI_NEW, TYPE_BINDABLE)
    @Retention(AnnotationRetention.SOURCE)
    internal annotation class IconType

    var icon: StatusBarIcon? = null

    @IconType
    open var type = TYPE_ICON
        internal set

    var tag = 0
        private set

    open var isVisible: Boolean
        get() =
            when (type) {
                TYPE_ICON -> icon!!.visible

                // The new pipeline controls visibilities via the view model and
                // view binder, so
                // this is effectively an unused return value.
                TYPE_BINDABLE,
                TYPE_MOBILE_NEW,
                TYPE_WIFI_NEW -> true
                else -> true
            }
        set(visible) {
            if (isVisible == visible) {
                return
            }
            when (type) {
                TYPE_ICON -> icon!!.visible = visible
                TYPE_BINDABLE,
                TYPE_MOBILE_NEW,
                TYPE_WIFI_NEW -> {}
            }
        }

    override fun toString(): String {
        return ("StatusBarIconHolder(type=${getTypeString(type)}" +
            " tag=$tag" +
            " visible=$isVisible)")
    }

    companion object {
        const val TYPE_ICON = 0

        /**
         * TODO (b/249790733): address this once the new pipeline is in place This type exists so
         * that the new pipeline (see [MobileIconViewModel]) can be used to inform the old view
         * system about changes to the data set (the list of mobile icons). The design of the new
         * pipeline should allow for removal of this icon holder type, and obsolete the need for
         * this entire class.
         */
        @Deprecated(
            """This field only exists so the new status bar pipeline can interface with the
      view holder system."""
        )
        const val TYPE_MOBILE_NEW = 3

        /**
         * TODO (b/238425913): address this once the new pipeline is in place This type exists so
         * that the new wifi pipeline can be used to inform the old view system about the existence
         * of the wifi icon. The design of the new pipeline should allow for removal of this icon
         * holder type, and obsolete the need for this entire class.
         */
        @Deprecated(
            """This field only exists so the new status bar pipeline can interface with the
      view holder system."""
        )
        const val TYPE_WIFI_NEW = 4

        /** Only applicable to [BindableIconHolder] */
        const val TYPE_BINDABLE = 5

        /** Returns a human-readable string representing the given type. */
        fun getTypeString(@IconType type: Int): String {
            return when (type) {
                TYPE_ICON -> "ICON"
                TYPE_MOBILE_NEW -> "MOBILE_NEW"
                TYPE_WIFI_NEW -> "WIFI_NEW"
                else -> "UNKNOWN"
            }
        }

        @JvmStatic
        fun fromIcon(icon: StatusBarIcon?): StatusBarIconHolder {
            val wrapper = StatusBarIconHolder()
            wrapper.icon = icon
            return wrapper
        }

        /** Creates a new holder with for the new wifi icon. */
        @JvmStatic
        fun forNewWifiIcon(): StatusBarIconHolder {
            val holder = StatusBarIconHolder()
            holder.type = TYPE_WIFI_NEW
            return holder
        }

        /**
         * ONLY for use with the new connectivity pipeline, where we only need a subscriptionID to
         * determine icon ordering and building the correct view model
         */
        @JvmStatic
        fun fromSubIdForModernMobileIcon(subId: Int): StatusBarIconHolder {
            val holder = StatusBarIconHolder()
            holder.type = TYPE_MOBILE_NEW
            holder.tag = subId
            return holder
        }

        /** Creates a new StatusBarIconHolder from a CallIndicatorIconState. */
        @JvmStatic
        fun fromCallIndicatorState(
            context: Context,
            state: CallIndicatorIconState,
        ): StatusBarIconHolder {
            val holder = StatusBarIconHolder()
            val resId = if (state.isNoCalling) state.noCallingResId else state.callStrengthResId
            val contentDescription =
                if (state.isNoCalling) state.noCallingDescription else state.callStrengthDescription
            holder.icon =
                StatusBarIcon(
                    UserHandle.SYSTEM,
                    context.packageName,
                    Icon.createWithResource(context, resId),
                    0,
                    0,
                    contentDescription,
                )
            holder.tag = state.subId
            return holder
        }
    }

    /**
     * Subclass of StatusBarIconHolder that is responsible only for the registration of an icon into
     * the [StatusBarIconList]. A bindable icon takes care of its own display, including hiding
     * itself under the correct conditions.
     *
     * StatusBarIconController will register all available bindable icons on init (see
     * [BindableIconsRepository]), and will ignore any call to setIcon for these.
     *
     * @property initializer a view creator that can bind the relevant view models to the created
     *   view.
     * @property slot the name of the slot that this holder is used for.
     */
    class BindableIconHolder(val initializer: ModernStatusBarViewCreator, val slot: String) :
        StatusBarIconHolder() {
        override var type: Int = TYPE_BINDABLE

        /** This is unused, as bindable icons use their own view binders to control visibility */
        override var isVisible: Boolean = true

        override fun toString(): String {
            return ("StatusBarIconHolder(type=BINDABLE, slot=$slot)")
        }
    }
}
