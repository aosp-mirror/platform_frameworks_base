package com.android.systemui.deviceentry

import com.android.systemui.deviceentry.data.repository.DeviceEntryHapticsRepositoryModule
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepositoryModule
import dagger.Module

@Module(
    includes =
        [
            DeviceEntryRepositoryModule::class,
            DeviceEntryHapticsRepositoryModule::class,
        ],
)
object DeviceEntryModule
