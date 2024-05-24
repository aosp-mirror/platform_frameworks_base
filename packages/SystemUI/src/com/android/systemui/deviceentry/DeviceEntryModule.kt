package com.android.systemui.deviceentry

import com.android.systemui.deviceentry.data.repository.DeviceEntryRepositoryModule
import com.android.systemui.deviceentry.data.repository.FaceWakeUpTriggersConfigModule
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import dagger.Module
import dagger.multibindings.Multibinds

@Module(
    includes =
        [
            DeviceEntryRepositoryModule::class,
            FaceWakeUpTriggersConfigModule::class,
        ],
)
abstract class DeviceEntryModule {
    /**
     * A set of DeviceEntryIconTransitions. Ensures that this can be injected even if it's empty.
     */
    @Multibinds abstract fun deviceEntryIconTransitionSet(): Set<DeviceEntryIconTransition>
}
