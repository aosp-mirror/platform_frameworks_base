package com.android.systemui.statusbar.notification.collection.provider

import android.util.ArraySet
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ListenerSet
import javax.inject.Inject

@SysUISingleton
class VisualStabilityProvider @Inject constructor() {
    /** All persistent and temporary listeners, in the order they were added */
    private val allListeners = ListenerSet<OnReorderingAllowedListener>()

    /** The subset of active listeners which are temporary (will be removed after called) */
    private val temporaryListeners = ArraySet<OnReorderingAllowedListener>()

    var isReorderingAllowed = true
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    notifyReorderingAllowed()
                }
            }
        }

    private fun notifyReorderingAllowed() {
        allListeners.forEach { listener ->
            if (temporaryListeners.remove(listener)) {
                allListeners.remove(listener)
            }
            listener.onReorderingAllowed()
        }
    }

    /** Add a listener which will be called until it is explicitly removed. */
    fun addPersistentReorderingAllowedListener(listener: OnReorderingAllowedListener) {
        temporaryListeners.remove(listener)
        allListeners.addIfAbsent(listener)
    }

    /** Add a listener which will be removed when it is called. */
    fun addTemporaryReorderingAllowedListener(listener: OnReorderingAllowedListener) {
        // Only add to the temporary set if it was added to the global set
        // to keep permanent listeners permanent
        if (allListeners.addIfAbsent(listener)) {
            temporaryListeners.add(listener)
        }
    }

    /** Remove a listener from receiving any callbacks, whether it is persistent or temporary. */
    fun removeReorderingAllowedListener(listener: OnReorderingAllowedListener) {
        temporaryListeners.remove(listener)
        allListeners.remove(listener)
    }
}

fun interface OnReorderingAllowedListener {
    fun onReorderingAllowed()
}
