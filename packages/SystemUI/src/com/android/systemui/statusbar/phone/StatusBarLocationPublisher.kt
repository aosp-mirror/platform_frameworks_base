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

package com.android.systemui.statusbar.phone

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.CallbackController
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Publishes updates to the status bar's margins.
 *
 * While the status bar view consumes the entire width of the device, the status bar
 * contents are laid out with margins for rounded corners, padding from the absolute
 * edges, and potentially display cutouts in the corner.
 */
@SysUISingleton
class StatusBarLocationPublisher @Inject constructor()
: CallbackController<StatusBarMarginUpdatedListener> {
    private val listeners = mutableSetOf<WeakReference<StatusBarMarginUpdatedListener>>()

    var marginLeft: Int = 0
        private set
    var marginRight: Int = 0
        private set

    override fun addCallback(listener: StatusBarMarginUpdatedListener) {
        listeners.add(WeakReference(listener))
    }

    override fun removeCallback(listener: StatusBarMarginUpdatedListener) {
        var toRemove: WeakReference<StatusBarMarginUpdatedListener>? = null
        for (l in listeners) {
            if (l.get() == listener) {
                toRemove = l
            }
        }

        if (toRemove != null) {
            listeners.remove(toRemove)
        }
    }

    fun updateStatusBarMargin(left: Int, right: Int) {
        marginLeft = left
        marginRight = right

        notifyListeners()
    }

    private fun notifyListeners() {
        var listenerList: List<WeakReference<StatusBarMarginUpdatedListener>>
        synchronized(this) {
            listenerList = listeners.toList()
        }

        listenerList.forEach { wrapper ->
            if (wrapper.get() == null) {
                listeners.remove(wrapper)
            }

            wrapper.get()?.onStatusBarMarginUpdated(marginLeft, marginRight)
        }
    }
}

interface StatusBarMarginUpdatedListener {
    fun onStatusBarMarginUpdated(marginLeft: Int, marginRight: Int)
}