package com.android.systemui.statusbar.notification.collection.provider

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ListenerSet
import javax.inject.Inject

@SysUISingleton
class VisualStabilityProvider @Inject constructor() {
    private val listeners = ListenerSet<OnReorderingAllowedListener>()

    var isReorderingAllowed = true
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    listeners.forEach(OnReorderingAllowedListener::onReorderingAllowed)
                }
            }
        }

    fun addPersistentReorderingAllowedListener(listener: OnReorderingAllowedListener) =
        listeners.addIfAbsent(listener)
}

fun interface OnReorderingAllowedListener {
    fun onReorderingAllowed()
}
